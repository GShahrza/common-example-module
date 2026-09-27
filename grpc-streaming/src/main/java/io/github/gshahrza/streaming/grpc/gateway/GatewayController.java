package io.github.gshahrza.streaming.grpc.gateway;

import io.github.gshahrza.streaming.grpc.proto.ChatRequest;
import io.github.gshahrza.streaming.grpc.proto.ChatResponse;
import io.github.gshahrza.streaming.grpc.proto.GetOrderRequest;
import io.github.gshahrza.streaming.grpc.proto.ListOrdersRequest;
import io.github.gshahrza.streaming.grpc.proto.Order;
import io.github.gshahrza.streaming.grpc.proto.OrderServiceGrpc;
import io.github.gshahrza.streaming.grpc.proto.UploadSummary;
import io.github.gshahrza.streaming.grpc.server.OrderData;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Browsers cannot speak native gRPC (they cannot control HTTP/2 frames and trailers). A common setup is
 * a gateway: the browser talks HTTP/JSON/SSE to it, and the gateway talks gRPC to the services.
 */
@RestController
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final OrderServiceGrpc.OrderServiceBlockingStub blockingStub;
    private final OrderServiceGrpc.OrderServiceStub asyncStub;
    private final JsonMapper jsonMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public GatewayController(OrderServiceGrpc.OrderServiceBlockingStub blockingStub,
                             OrderServiceGrpc.OrderServiceStub asyncStub, JsonMapper jsonMapper) {
        this.blockingStub = blockingStub;
        this.asyncStub = asyncStub;
        this.jsonMapper = jsonMapper;
    }

    /** 1. Unary. A deadline is part of every gRPC call: after 2s the call fails with DEADLINE_EXCEEDED. */
    @GetMapping("/api/orders/{id}")
    public OrderDto get(@PathVariable long id) {
        Order order = blockingStub.withDeadlineAfter(2, TimeUnit.SECONDS)
                .getOrder(GetOrderRequest.newBuilder().setId(id).build());
        return OrderDto.from(order);
    }

    /** 2. Server streaming, forwarded to the browser as NDJSON. */
    @GetMapping("/api/orders/stream")
    public ResponseEntity<ResponseBodyEmitter> stream(@RequestParam(defaultValue = "1000") int count) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(Duration.ofMinutes(5).toMillis());
        // A cancellable gRPC context: cancelling it cancels the call on the server as well
        Context.CancellableContext call = Context.current().withCancellation();
        emitter.onCompletion(() -> call.cancel(null));
        emitter.onTimeout(() -> call.cancel(null));
        emitter.onError(e -> call.cancel(e));

        executor.execute(() -> call.run(() -> {
            try {
                Iterator<Order> orders = blockingStub.withDeadlineAfter(5, TimeUnit.MINUTES)
                        .listOrders(ListOrdersRequest.newBuilder().setCount(count).build());
                List<String> batch = new ArrayList<>();
                while (orders.hasNext()) {
                    batch.add(jsonMapper.writeValueAsString(OrderDto.from(orders.next())));
                    if (batch.size() == 100 || !orders.hasNext()) {
                        emitter.send(String.join("\n", batch) + "\n");
                        batch.clear();
                    }
                }
                emitter.complete();
            } catch (IOException e) {
                log.debug("Browser disconnected: {}", e.getMessage());
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() != Status.Code.CANCELLED) {
                    emitter.completeWithError(e);
                }
            }
        }));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_NDJSON).body(emitter);
    }

    /** 3. Client streaming: the gateway sends {@code count} orders one by one, the service answers once. */
    @PostMapping("/api/orders/upload")
    public Map<String, Object> upload(@RequestParam(defaultValue = "10000") int count) throws Exception {
        CompletableFuture<UploadSummary> result = new CompletableFuture<>();
        StreamObserver<Order> requests = asyncStub.withDeadlineAfter(1, TimeUnit.MINUTES)
                .uploadOrders(new StreamObserver<>() {
                    @Override
                    public void onNext(UploadSummary summary) {
                        result.complete(summary);
                    }

                    @Override
                    public void onError(Throwable t) {
                        result.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        for (long id = 1; id <= count; id++) {
            requests.onNext(OrderData.order(id));
        }
        requests.onCompleted(); // "no more messages" -> the server sends its single response
        UploadSummary summary = result.get(1, TimeUnit.MINUTES);
        return Map.of("received", summary.getReceived(), "totalAmount", summary.getTotalAmount(),
                "serverElapsedMs", summary.getElapsedMs());
    }

    /**
     * 4. Bidirectional streaming, forwarded to the browser as SSE. Questions are sent 300 ms apart
     * while answers are already streaming back on the same gRPC call.
     */
    @GetMapping(path = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestParam(name = "q") List<String> questions) {
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(2).toMillis());
        StreamObserver<ChatRequest> requests = asyncStub.chat(new StreamObserver<>() {
            @Override
            public void onNext(ChatResponse response) {
                try {
                    emitter.send(SseEmitter.event()
                            .name(response.getDone() ? "done" : "token")
                            .data(Map.of("question", response.getQuestion(), "token", response.getToken()),
                                    MediaType.APPLICATION_JSON));
                } catch (IOException e) {
                    throw Status.CANCELLED.withDescription("browser disconnected").asRuntimeException();
                }
            }

            @Override
            public void onError(Throwable t) {
                emitter.completeWithError(t);
            }

            @Override
            public void onCompleted() {
                try {
                    emitter.send(SseEmitter.event().name("end").data(Map.of("questions", questions.size()),
                            MediaType.APPLICATION_JSON));
                    emitter.complete();
                } catch (IOException e) {
                    log.debug("Browser disconnected: {}", e.getMessage());
                }
            }
        });
        // Browser gone -> cancel the gRPC call, the server stops answering
        emitter.onCompletion(() -> requests.onError(Status.CANCELLED.asRuntimeException()));

        executor.execute(() -> {
            try {
                for (String question : questions) {
                    requests.onNext(ChatRequest.newBuilder().setQuestion(question).build());
                    Thread.sleep(300);
                }
                requests.onCompleted();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IllegalStateException e) {
                log.debug("Chat call already finished: {}", e.getMessage());
            }
        });
        return emitter;
    }

    /** Size of the same orders as protobuf (binary) and as JSON. */
    @GetMapping("/api/size")
    public Map<String, Object> size(@RequestParam(defaultValue = "1000") int count) {
        long protobuf = 0;
        long json = 0;
        for (long id = 1; id <= count; id++) {
            Order order = OrderData.order(id);
            protobuf += order.getSerializedSize();
            json += jsonMapper.writeValueAsBytes(OrderDto.from(order)).length;
        }
        return Map.of("count", count, "protobufBytes", protobuf, "jsonBytes", json,
                "ratio", Math.round(json * 100.0 / protobuf) / 100.0);
    }

    /** gRPC status codes mapped back to HTTP for the browser. */
    @ExceptionHandler(StatusRuntimeException.class)
    public ResponseEntity<Map<String, String>> grpcError(StatusRuntimeException e) {
        HttpStatus http = switch (e.getStatus().getCode()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(http).body(Map.of(
                "grpcStatus", e.getStatus().getCode().name(),
                "message", String.valueOf(e.getStatus().getDescription())));
    }
}
