package io.github.gshahrza.r2dbc;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gshahrza.r2dbc.order.OrderService;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.test.StepVerifier;

/** A real PostgreSQL in Docker; Flyway creates the schema and the 100 000 orders. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
class R2dbcTest {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String hostPort = postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + postgres.getDatabaseName();
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + hostPort);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("blocking.datasource.jdbc-url", postgres::getJdbcUrl);
        for (String prefix : new String[] {"spring.r2dbc.", "blocking.datasource."}) {
            registry.add(prefix + "username", postgres::getUsername);
            registry.add(prefix + "password", postgres::getPassword);
        }
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    WebTestClient web;
    @Autowired
    OrderService service;

    @Test
    void repositoryReadsOneRowAndAPage() {
        web.get().uri("/api/orders/1").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.customer").isEqualTo("rashad").jsonPath("$.productId").isEqualTo(2);

        web.get().uri("/api/orders?customer=aynur&size=5").exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).hasSize(5);
    }

    @Test
    void databaseClientRunsHandWrittenAggregates() {
        web.get().uri("/api/stats/customers").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(6).jsonPath("$[0].orders").isNumber();
    }

    @Test
    void failedTransactionLeavesNoTrace() {
        int before = service.stock(3).block();

        web.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("customer", "nigar", "productId", 3, "quantity", before + 1))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(service.stock(3).block()).isEqualTo(before);

        web.post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("customer", "nigar", "productId", 3, "quantity", 1))
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.amount").isEqualTo(2400.0);
        assertThat(service.stock(3).block()).isEqualTo(before - 1);
    }

    @Test
    void streamIsNdjsonAndStartsBeforeTheQueryIsFinished() {
        var lines = web.get().uri("/api/orders/stream?limit=100000").accept(MediaType.APPLICATION_NDJSON).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(new ParameterizedTypeReference<Map<String, Object>>() { })
                .getResponseBody();

        StepVerifier.create(lines.take(3))
                .expectNextMatches(l -> l.get("id").equals(1) && l.get("product").equals("Telefon"))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void theDatabaseSendsOnlyWhatTheSubscriberAsksFor() {
        long before = service.rowsFetched();

        StepVerifier.create(service.stream(100_000), 0)   // request nothing yet
                .thenRequest(10).expectNextCount(10)
                .thenRequest(90).expectNextCount(90)
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        // Out of 100 000 rows only the requested ones went through (plus at most one fetch of 250)
        assertThat(service.rowsFetched() - before).isLessThan(500);
    }

    record Result(String mode, int requests, long totalMillis, long slowestMillis, java.util.List<String> serverThreads) {
    }

    Result compare(String mode) {
        return web.get().uri("/api/compare?mode={m}&requests=40", mode).exchange()
                .expectStatus().isOk().expectBody(Result.class).returnResult().getResponseBody();
    }

    @Test
    void blockingJdbcOnTheEventLoopIsMuchSlowerThanR2dbc() {
        Result r2dbc = compare("r2dbc");
        Result blocking = compare("jdbc-on-event-loop");
        Result offloaded = compare("jdbc-offloaded");

        // 40 × 200 ms: R2DBC waits on 20 connections (~400 ms); blocking JDBC on 4 event-loop
        // threads (~2000 ms); offloaded JDBC is fast too, but needs one thread per waiting query
        assertThat(blocking.totalMillis()).isGreaterThan(2 * r2dbc.totalMillis());
        assertThat(blocking.serverThreads()).allMatch(t -> t.contains("-http-"));   // event-loop threads (reactor-http-* / webflux-http-*)
        assertThat(offloaded.serverThreads()).allMatch(t -> t.startsWith("boundedElastic")).hasSizeGreaterThan(10);
        assertThat(r2dbc.serverThreads()).hasSizeLessThanOrEqualTo(4);
    }
}
