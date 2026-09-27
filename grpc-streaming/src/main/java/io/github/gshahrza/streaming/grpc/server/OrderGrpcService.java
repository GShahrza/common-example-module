package io.github.gshahrza.streaming.grpc.server;

import io.github.gshahrza.streaming.grpc.StreamingProperties;
import io.github.gshahrza.streaming.grpc.proto.ChatRequest;
import io.github.gshahrza.streaming.grpc.proto.ChatResponse;
import io.github.gshahrza.streaming.grpc.proto.GetOrderRequest;
import io.github.gshahrza.streaming.grpc.proto.ListOrdersRequest;
import io.github.gshahrza.streaming.grpc.proto.Order;
import io.github.gshahrza.streaming.grpc.proto.OrderServiceGrpc;
import io.github.gshahrza.streaming.grpc.proto.UploadSummary;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The gRPC server. Spring Boot finds this bean (it is a {@code BindableService}) and registers it
 * on the gRPC server (port 9090). Each method gets a {@link StreamObserver} to send responses.
 */
@Service
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(OrderGrpcService.class);

    private final OrderData data;
    private final StreamingProperties properties;
    private final ExecutorService producers = Executors.newVirtualThreadPerTaskExecutor();

    public OrderGrpcService(OrderData data, StreamingProperties properties) {
        this.data = data;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- 1. unary

    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<Order> responseObserver) {
        if (request.getId() < 1) {
            // gRPC has its own status codes instead of HTTP 4xx/5xx
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("id must be positive").asRuntimeException());
            return;
        }
        if (request.getId() > OrderData.MAX_ID) {
            responseObserver.onError(Status.NOT_FOUND.withDescription("Order " + request.getId() + " not found").asRuntimeException());
            return;
        }
        responseObserver.onNext(OrderData.order(request.getId()));
        responseObserver.onCompleted();
    }

    // ---------------------------------------------------------------- 2. server streaming

    @Override
    public void listOrders(ListOrdersRequest request, StreamObserver<Order> responseObserver) {
        if (request.getCount() < 1 || request.getCount() > 1_000_000) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("count must be 1..1000000").asRuntimeException());
            return;
        }
        ServerCallStreamObserver<Order> observer = (ServerCallStreamObserver<Order>) responseObserver;
        FlowControl flow = new FlowControl(observer);
        observer.setOnCancelHandler(() -> log.info("Client cancelled ListOrders"));

        // Produce on our own thread: gRPC delivers callbacks (like onReady) one at a time per call,
        // so blocking inside this method would also block the onReady notification we wait for.
        producers.execute(() -> {
            try {
                for (List<Order> page : (Iterable<List<Order>>) data.pages(request.getCount())::iterator) {
                    for (Order order : page) {
                        if (!flow.awaitReady()) {
                            return; // client cancelled or its deadline expired
                        }
                        observer.onNext(order);
                    }
                }
                observer.onCompleted();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                if (!observer.isCancelled()) {
                    observer.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
                }
            }
        });
    }

    // ---------------------------------------------------------------- 3. client streaming

    @Override
    public StreamObserver<Order> uploadOrders(StreamObserver<UploadSummary> responseObserver) {
        long start = System.nanoTime();
        // The server returns an observer; gRPC calls it for every message the client sends
        return new StreamObserver<>() {
            private int received;
            private BigDecimal total = BigDecimal.ZERO;

            @Override
            public void onNext(Order order) {
                received++;
                total = total.add(new BigDecimal(order.getAmount()));
            }

            @Override
            public void onError(Throwable t) {
                log.info("Upload aborted by client after {} orders: {}", received, Status.fromThrowable(t).getCode());
            }

            @Override
            public void onCompleted() {
                // Only when the client says "that was all" does the single response go back
                responseObserver.onNext(UploadSummary.newBuilder()
                        .setReceived(received)
                        .setTotalAmount(total.toPlainString())
                        .setElapsedMs((System.nanoTime() - start) / 1_000_000)
                        .build());
                responseObserver.onCompleted();
            }
        };
    }

    // ---------------------------------------------------------------- 4. bidirectional streaming

    @Override
    public StreamObserver<ChatRequest> chat(StreamObserver<ChatResponse> responseObserver) {
        ServerCallStreamObserver<ChatResponse> observer = (ServerCallStreamObserver<ChatResponse>) responseObserver;
        // StreamObserver is not thread-safe: one thread per call writes all answers, in order
        ExecutorService writer = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        observer.setOnCancelHandler(writer::shutdownNow);

        return new StreamObserver<>() {
            @Override
            public void onNext(ChatRequest request) {
                // The client may send the next question while we are still answering: it is queued
                writer.execute(() -> answer(request.getQuestion(), observer));
            }

            @Override
            public void onError(Throwable t) {
                writer.shutdownNow();
            }

            @Override
            public void onCompleted() {
                // Client has no more questions: finish after the queued answers
                writer.execute(() -> {
                    if (!observer.isCancelled()) {
                        observer.onCompleted();
                    }
                });
                writer.shutdown();
            }
        };
    }

    private void answer(String question, ServerCallStreamObserver<ChatResponse> observer) {
        String answer = "Answer to \"" + question + "\": gRPC keeps one HTTP/2 connection open and both sides "
                + "can send messages on it at any time.";
        String[] words = answer.split(" ");
        try {
            for (int i = 0; i < words.length && !observer.isCancelled(); i++) {
                observer.onNext(ChatResponse.newBuilder()
                        .setQuestion(question)
                        .setToken(i == 0 ? words[i] : " " + words[i])
                        .build());
                Thread.sleep(properties.chatTokenDelay());
            }
            if (!observer.isCancelled()) {
                observer.onNext(ChatResponse.newBuilder().setQuestion(question).setDone(true).build());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
