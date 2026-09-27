package io.github.gshahrza.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/** The app calls its own partner endpoint over HTTP, so it runs on a fixed port. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18089",
        "partner.read-timeout=300ms",
        "resilience4j.circuitbreaker.instances.partner.wait-duration-in-open-state=500ms",
        "resilience4j.circuitbreaker.instances.partner.slow-call-duration-threshold=250ms",
        "resilience4j.ratelimiter.instances.partner.limit-for-period=1000"})
class ResilienceTest {

    @Autowired
    CircuitBreakerRegistry circuitBreakers;
    @Autowired
    PartnerSimulator partner;

    final RestClient http = RestClient.builder().baseUrl("http://localhost:18089")
            .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
            .build();

    CircuitBreaker circuit() {
        return circuitBreakers.circuitBreaker("partner");
    }

    void mode(String mode) {
        http.put().uri("/partner/mode").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("mode", mode)).retrieve().toBodilessEntity();
    }

    ResponseEntity<Map<String, Object>> call() {
        return http.get().uri("/api/rates").retrieve().toEntity(new ParameterizedTypeReference<>() { });
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> rates(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("rates");
    }

    @BeforeEach
    void healthyStart() {
        circuit().reset();
        mode("OK");
        assertThat(rates(call()).get("source")).isEqualTo("partner");   // also fills the "last good" value
        circuit().reset();
    }

    @Test
    void healthyPartnerIsCalledOnce() {
        long before = partner.calls();

        Map<String, Object> rates = rates(call());

        assertThat(rates.get("stale")).isEqualTo(false);
        assertThat(rates.get("attempts")).isEqualTo(1);
        assertThat(partner.calls() - before).isEqualTo(1);
    }

    @Test
    void transientErrorsAreRetried() {
        mode("FLAKY");   // 503, 503, 200

        Map<String, Object> rates = rates(call());

        assertThat(rates.get("source")).isEqualTo("partner");
        assertThat(rates.get("attempts")).isEqualTo(3);
    }

    @Test
    void clientErrorIsNeitherRetriedNorCountedAgainstThePartner() {
        mode("BAD_REQUEST");
        long before = partner.calls();

        var response = call();

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(partner.calls() - before).isEqualTo(1);
        assertThat(circuit().getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void timeoutIsRetriedThenTheLastGoodValueIsServed() {
        mode("SLOW");

        Map<String, Object> rates = rates(call());

        assertThat(rates.get("stale")).isEqualTo(true);
        assertThat(rates.get("attempts")).isEqualTo(3);
        assertThat((String) rates.get("reason")).startsWith("ResourceAccessException");
    }

    @Test
    void failuresOpenTheCircuitWhichThenFailsFastWithoutCallingThePartner() {
        mode("ERROR");
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            call();
            return circuit().getState() == CircuitBreaker.State.OPEN;
        });
        long before = partner.calls();

        var response = call();

        Map<String, Object> rates = rates(response);
        assertThat(rates.get("stale")).isEqualTo(true);
        assertThat(rates.get("attempts")).isEqualTo(0);
        assertThat((String) rates.get("reason")).startsWith("CallNotPermittedException");
        assertThat(((Number) response.getBody().get("millis")).longValue()).isLessThan(100);
        assertThat(partner.calls()).isEqualTo(before);
    }

    @Test
    void circuitClosesAgainWhenThePartnerRecovers() {
        mode("ERROR");
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            call();
            return circuit().getState() == CircuitBreaker.State.OPEN;
        });

        mode("OK");
        await().atMost(Duration.ofSeconds(5)).until(() -> circuit().getState() == CircuitBreaker.State.HALF_OPEN);
        for (int i = 0; i < 3; i++) {   // permitted-number-of-calls-in-half-open-state
            assertThat(rates(call()).get("source")).isEqualTo("partner");
        }

        assertThat(circuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
