package io.github.gshahrza.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * Tracing is off in tests by default; @AutoConfigureTracing turns it on. Exporting is disabled,
 * so no Grafana stack is needed: we check what the application itself produces.
 */
@AutoConfigureTracing
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "OTEL_EXPORT=false",
        "server.port=18087",   // fixed: the app calls itself at services.base-url, built from server.port
        "payment.failure-rate=0"})
class ObservabilityTest {

    int port = 18087;

    RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    ResponseEntity<Map<String, Object>> checkout(String sku, int quantity) {
        return http().post().uri("/api/checkout").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("customer", "Aynur", "sku", sku, "quantity", quantity))
                .retrieve().toEntity(new ParameterizedTypeReference<>() { });
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> hops(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("hops");
    }

    Map<String, Object> metric(String name, String tag) {
        return http().get().uri("/actuator/metrics/{name}?tag={tag}", name, tag)
                .retrieve().body(new ParameterizedTypeReference<>() { });
    }

    @Test
    void oneTraceSpansAllServicesCalledOverHttp() {
        var response = checkout("BOOK", 1);
        var body = response.getBody();

        assertThat(body.get("status")).isEqualTo("success");
        String traceId = (String) body.get("traceId");
        assertThat(traceId).hasSize(32);
        assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo(traceId);
        assertThat(hops(body)).extracting(h -> h.get("service")).containsExactly("checkout", "inventory", "payment");
        // the traceparent header carried the trace to the other services; each has its own span
        assertThat(hops(body)).allSatisfy(h -> assertThat(h.get("traceId")).isEqualTo(traceId));
        assertThat(hops(body)).extracting(h -> h.get("spanId")).doesNotHaveDuplicates();
    }

    @Test
    void outOfStockStopsBeforePayment() {
        var body = checkout("LAPTOP", 10_000).getBody();

        assertThat(body.get("status")).isEqualTo("out_of_stock");
        assertThat(hops(body)).extracting(h -> h.get("service")).containsExactly("checkout", "inventory");
    }

    @Test
    void businessAndTechnicalMetricsAreRecorded() {
        checkout("PHONE", 1);

        assertThat(metric("orders.placed", "result:success")).isNotNull();
        assertThat(metric("checkout.process", "sku:PHONE")).isNotNull();       // from the Observation API
        assertThat(metric("inventory.reserve", "error:none")).isNotNull();      // from @Observed
        assertThat(metric("http.client.requests", "uri:/api/payments")).isNotNull();
        assertThat(metric("inventory.stock", "sku:PHONE")).isNotNull();         // gauge
    }

    @Test
    void customHealthIndicatorIsPartOfHealth() {
        Map<String, Object> health = http().get().uri("/actuator/health")
                .retrieve().body(new ParameterizedTypeReference<>() { });

        assertThat(health.get("status")).isEqualTo("UP");
        assertThat(health.get("components")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKey("paymentGatewayHealth");
    }
}
