package io.github.gshahrza.resilience;

import io.github.gshahrza.resilience.RatesClient.PartnerUnavailableException;
import io.github.gshahrza.resilience.RatesClient.Rates;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;

@RestController
class RatesController {

    record Result(Rates rates, long millis, String circuit) {
    }

    private final RatesClient client;
    private final CircuitBreakerRegistry circuitBreakers;

    RatesController(RatesClient client, CircuitBreakerRegistry circuitBreakers) {
        this.client = client;
        this.circuitBreakers = circuitBreakers;
    }

    @GetMapping("/api/rates")
    Result rates() {
        RatesClient.ATTEMPTS.get().set(0);
        long start = System.nanoTime();
        try {
            Rates rates = client.fetch();
            return new Result(rates, (System.nanoTime() - start) / 1_000_000, state());
        } finally {
            RatesClient.ATTEMPTS.remove();
        }
    }

    @GetMapping("/api/circuit")
    Map<String, Object> circuit() {
        var cb = circuitBreakers.circuitBreaker("partner");
        var metrics = cb.getMetrics();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", cb.getState().name());
        result.put("failureRate", metrics.getFailureRate());
        result.put("slowCallRate", metrics.getSlowCallRate());
        result.put("bufferedCalls", metrics.getNumberOfBufferedCalls());
        result.put("failedCalls", metrics.getNumberOfFailedCalls());
        result.put("notPermittedCalls", metrics.getNumberOfNotPermittedCalls());
        return result;
    }

    /** For the demo: start again with a closed circuit. */
    @PostMapping("/api/circuit/reset")
    Map<String, Object> reset() {
        circuitBreakers.circuitBreaker("partner").reset();
        return circuit();
    }

    @ExceptionHandler(PartnerUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(PartnerUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Partner unavailable and no cached rates yet", "reason", e.getMessage()));
    }

    @ExceptionHandler(HttpClientErrorException.class)
    ResponseEntity<Map<String, String>> badRequest(HttpClientErrorException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Partner rejected our request (" + e.getStatusCode().value() + ")",
                        "reason", e.getResponseBodyAsString()));
    }

    private String state() {
        return circuitBreakers.circuitBreaker("partner").getState().name();
    }
}
