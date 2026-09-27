package io.github.gshahrza.streaming.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.gshahrza.streaming.grpc.proto.ChatRequest;
import io.github.gshahrza.streaming.grpc.proto.ChatResponse;
import io.github.gshahrza.streaming.grpc.proto.GetOrderRequest;
import io.github.gshahrza.streaming.grpc.proto.ListOrdersRequest;
import io.github.gshahrza.streaming.grpc.proto.Order;
import io.github.gshahrza.streaming.grpc.proto.OrderServiceGrpc;
import io.github.gshahrza.streaming.grpc.proto.UploadSummary;
import io.github.gshahrza.streaming.grpc.server.OrderData;
import io.github.gshahrza.streaming.grpc.server.OrderGrpcService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The gRPC service alone, over an in-process transport (no network, no Spring). */
class OrderGrpcServiceTest {

    Server server;
    ManagedChannel channel;
    OrderServiceGrpc.OrderServiceBlockingStub blocking;
    OrderServiceGrpc.OrderServiceStub async;

    @BeforeEach
    void start() throws Exception {
        StreamingProperties props = new StreamingProperties(Duration.ofMillis(2), Duration.ofMillis(50), 100);
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(new OrderGrpcService(new OrderData(props), props))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).build();
        blocking = OrderServiceGrpc.newBlockingStub(channel);
        async = OrderServiceGrpc.newStub(channel);
    }

    @AfterEach
    void stop() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void unaryReturnsOneOrderOrAStatusCode() {
        Order order = blocking.getOrder(GetOrderRequest.newBuilder().setId(7).build());
        assertThat(order.getId()).isEqualTo(7);
        assertThat(order.getCustomer()).isEqualTo("Elvin");
        assertThat(order.getStatus()).isEqualTo(Order.Status.DELIVERED);

        assertThatThrownBy(() -> blocking.getOrder(GetOrderRequest.newBuilder().setId(9_999_999).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND));
        assertThatThrownBy(() -> blocking.getOrder(GetOrderRequest.newBuilder().setId(0).build()))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
    }

    @Test
    void serverStreamingSendsAllOrdersInOrder() {
        Iterator<Order> orders = blocking.listOrders(ListOrdersRequest.newBuilder().setCount(250).build());
        List<Long> ids = new ArrayList<>();
        orders.forEachRemaining(o -> ids.add(o.getId()));
        assertThat(ids).hasSize(250).startsWith(1L, 2L, 3L).endsWith(250L);
    }

    @Test
    void deadlineStopsASlowStream() {
        // 100 pages x 50 ms would take 5 s; the client only waits 200 ms
        Iterator<Order> orders = blocking.withDeadlineAfter(200, TimeUnit.MILLISECONDS)
                .listOrders(ListOrdersRequest.newBuilder().setCount(10_000).build());
        assertThatThrownBy(() -> orders.forEachRemaining(o -> { }))
                .isInstanceOfSatisfying(StatusRuntimeException.class,
                        e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED));
    }

    @Test
    void clientStreamingGetsOneSummaryAtTheEnd() throws Exception {
        CompletableFuture<UploadSummary> result = new CompletableFuture<>();
        StreamObserver<Order> upload = async.uploadOrders(observer(result));
        for (long id = 1; id <= 1000; id++) {
            upload.onNext(OrderData.order(id));
        }
        assertThat(result).isNotDone(); // nothing comes back before the client completes
        upload.onCompleted();

        UploadSummary summary = result.get(5, TimeUnit.SECONDS);
        assertThat(summary.getReceived()).isEqualTo(1000);
        assertThat(summary.getTotalAmount()).isNotBlank();
    }

    @Test
    void bidirectionalStreamAnswersEveryQuestionInOrder() throws Exception {
        List<ChatResponse> responses = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> done = new CompletableFuture<>();
        StreamObserver<ChatRequest> chat = async.chat(new StreamObserver<>() {
            public void onNext(ChatResponse r) { responses.add(r); }
            public void onError(Throwable t) { done.completeExceptionally(t); }
            public void onCompleted() { done.complete(null); }
        });
        chat.onNext(ChatRequest.newBuilder().setQuestion("first").build());
        chat.onNext(ChatRequest.newBuilder().setQuestion("second").build());
        chat.onCompleted();
        done.get(10, TimeUnit.SECONDS);

        List<String> finished = responses.stream().filter(ChatResponse::getDone).map(ChatResponse::getQuestion).toList();
        assertThat(finished).containsExactly("first", "second");
        String firstAnswer = responses.stream().filter(r -> r.getQuestion().equals("first") && !r.getDone())
                .map(ChatResponse::getToken).reduce("", String::concat);
        assertThat(firstAnswer).startsWith("Answer to \"first\": gRPC keeps one HTTP/2 connection open");
    }

    static <T> StreamObserver<T> observer(CompletableFuture<T> result) {
        return new StreamObserver<>() {
            public void onNext(T value) { result.complete(value); }
            public void onError(Throwable t) { result.completeExceptionally(t); }
            public void onCompleted() { }
        };
    }
}
