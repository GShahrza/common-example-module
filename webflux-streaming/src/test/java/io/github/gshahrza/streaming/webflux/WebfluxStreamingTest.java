package io.github.gshahrza.streaming.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gshahrza.streaming.webflux.compare.CallResult;
import io.github.gshahrza.streaming.webflux.compare.LoadResult;
import io.github.gshahrza.streaming.webflux.dashboard.DashboardResponse;
import io.github.gshahrza.streaming.webflux.order.Order;
import io.github.gshahrza.streaming.webflux.order.OrderRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebfluxStreamingTest {

    /** Netty event-loop threads, e.g. reactor-http-epoll-1 (named webflux-http-* inside a test context). */
    static final String EVENT_LOOP = ".+-http-(epoll|nio|kqueue)-\\d+";

    @LocalServerPort
    int port;

    @Autowired
    OrderRepository repository;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(60))
                .build();
    }

    LoadResult load(String mode, int requests) {
        return client.get().uri("/api/compare/load?mode={m}&requests={r}&delayMs=200", mode, requests)
                .exchange().expectStatus().isOk()
                .expectBody(LoadResult.class).returnResult().getResponseBody();
    }

    @Test
    void blockingTheEventLoopIsManyTimesSlowerThanReactiveCode() {
        // Reactor Netty uses max(cores, 4) event-loop threads; 10 requests per thread make blocking queue up
        int eventLoops = Math.max(Runtime.getRuntime().availableProcessors(), 4);
        int requests = Math.min(eventLoops * 10, 500);

        LoadResult reactive = load("reactive", requests);
        LoadResult blocking = load("blocking", requests);

        assertThat(reactive.totalMs()).isLessThan(2_000);
        assertThat(blocking.totalMs()).isGreaterThan(reactive.totalMs() * 3);
        assertThat(blocking.handlerThreads()).allMatch(t -> t.matches(EVENT_LOOP));
    }

    @Test
    void offloadedBlockingCodeRunsOnBoundedElastic() {
        CallResult result = client.get().uri("/api/compare/offloaded?delayMs=10").exchange()
                .expectBody(CallResult.class).returnResult().getResponseBody();
        assertThat(result.handlerThread()).matches(EVENT_LOOP);
        assertThat(result.completionThread()).startsWith("boundedElastic");
    }

    @Test
    void zipCallsServicesInParallel() {
        DashboardResponse sequential = client.get().uri("/api/dashboard/1/sequential").exchange()
                .expectBody(DashboardResponse.class).returnResult().getResponseBody();
        DashboardResponse parallel = client.get().uri("/api/dashboard/1/parallel").exchange()
                .expectBody(DashboardResponse.class).returnResult().getResponseBody();

        assertThat(sequential.elapsedMs()).isGreaterThanOrEqualTo(1_200);
        assertThat(parallel.elapsedMs()).isBetween(500L, 900L);
        assertThat(parallel.dashboard()).isEqualTo(sequential.dashboard());
    }

    @Test
    void chatIsAStreamOfServerSentEvents() {
        var events = client.get().uri("/api/chat/stream?prompt=hi")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange().expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<Map<String, Object>>>() { })
                .getResponseBody();

        StepVerifier.create(events.collectList())
                .assertNext(list -> {
                    assertThat(list.getFirst().event()).isEqualTo("token");
                    assertThat(list.getFirst().data()).containsEntry("text", "You");
                    assertThat(list.getLast().event()).isEqualTo("done");
                    assertThat(list.getLast().data()).containsEntry("tokens", list.size() - 1);
                })
                .verifyComplete();
    }

    @Test
    void ndjsonLinesArriveWhileTheStreamIsStillRunning() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        long start = System.nanoTime();
        HttpResponse<java.util.stream.Stream<String>> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders?count=1000"))
                        .header("Accept", "application/x-ndjson").build(),
                HttpResponse.BodyHandlers.ofLines());
        List<String> lines = new ArrayList<>();
        long[] first = {-1};
        response.body().forEach(line -> {
            if (first[0] < 0) {
                first[0] = (System.nanoTime() - start) / 1_000_000;
            }
            lines.add(line);
        });
        long total = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/x-ndjson");
        assertThat(lines).hasSize(1000);
        assertThat(first[0]).isLessThan(total - 500); // 10 pages x 100 ms
    }

    @Test
    void sameFluxAsJsonIsOneArray() {
        List<Order> orders = client.get().uri("/api/orders?count=250")
                .accept(MediaType.APPLICATION_JSON)
                .exchange().expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBodyList(Order.class).returnResult().getResponseBody();
        assertThat(orders).hasSize(250);
        assertThat(orders.getFirst().id()).isEqualTo(1);
    }

    @Test
    void backpressureQueriesOnlyThePagesThatAreRequested() {
        long before = repository.pagesQueried();

        // Ask for 10 rows only, then cancel: exactly one page of 100 rows is queried
        StepVerifier.create(repository.findAll(100_000), 10)
                .expectNextCount(10)
                .thenCancel()
                .verify();

        assertThat(repository.pagesQueried() - before).isEqualTo(1);
    }

    @Test
    void clientCancellationStopsTheServerStream() {
        Map<String, Long> before = stats();

        client.get().uri("/api/orders?count=100000")
                .accept(MediaType.APPLICATION_NDJSON)
                .exchange()
                .returnResult(Order.class)
                .getResponseBody()
                .take(150) // the client stops reading after 150 rows and closes the connection
                .blockLast(Duration.ofSeconds(10));

        StepVerifier.create(reactor.core.publisher.Mono.defer(() -> reactor.core.publisher.Mono.just(stats()))
                        .repeatWhenEmpty(r -> r)
                        .filter(s -> s.get("cancelledStreams") > before.get("cancelledStreams"))
                        .repeatWhenEmpty(20, r -> r.delayElements(Duration.ofMillis(100))))
                .assertNext(after -> assertThat(after.get("pagesQueried") - before.get("pagesQueried"))
                        .isLessThan(10)) // far fewer than the 1000 pages of the full stream
                .verifyComplete();
    }

    Map<String, Long> stats() {
        return client.get().uri("/api/orders/stats").exchange()
                .expectBody(new ParameterizedTypeReference<Map<String, Long>>() { })
                .returnResult().getResponseBody();
    }
}
