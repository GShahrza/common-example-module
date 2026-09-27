package io.github.gshahrza.resilience;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * The protected call. Resilience4j nests the annotations in a fixed order, outermost first:
 *
 *   Retry → CircuitBreaker → RateLimiter → Bulkhead → HTTP call (with a read timeout)
 *
 * So every retry attempt goes through the circuit breaker and is counted there, and an open
 * circuit or a full rate limiter rejects an attempt before any network call is made.
 */
@Component
public class RatesClient {

    public record Rates(Map<String, Object> rates, boolean stale, String source, int attempts,
                        String reason, Instant fetchedAt) {
    }

    /** Counts attempts of the current request; Retry calls the method again on the same thread. */
    static final ThreadLocal<AtomicInteger> ATTEMPTS = ThreadLocal.withInitial(AtomicInteger::new);

    private final RestClient http;
    private final AtomicReference<Rates> lastGood = new AtomicReference<>();

    RatesClient(RestClient.Builder builder, @Value("${partner.base-url}") String baseUrl,
                @Value("${partner.read-timeout}") Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(500));
        factory.setReadTimeout(readTimeout);
        this.http = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Retry(name = "partner", fallbackMethod = "fallback")
    @CircuitBreaker(name = "partner")
    @RateLimiter(name = "partner")
    @Bulkhead(name = "partner")
    public Rates fetch() {
        int attempt = ATTEMPTS.get().incrementAndGet();
        Map<String, Object> body = http.get().uri("/partner/rates").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        Rates rates = new Rates(body, false, "partner", attempt, null, Instant.now());
        lastGood.set(rates);
        return rates;
    }

    /**
     * Graceful degradation: when the partner cannot answer, return the last good rates marked as
     * stale, instead of an error page. The Throwable says why (timeout, open circuit, rate limit...).
     */
    Rates fallback(Throwable error) {
        if (error instanceof HttpClientErrorException clientError) {
            throw clientError;   // a 4xx is our bug: hiding it behind old data would only delay the fix
        }
        Rates last = lastGood.get();
        String reason = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (last == null) {
            throw new PartnerUnavailableException(reason, error);
        }
        return new Rates(last.rates(), true, "fallback (last good value)", ATTEMPTS.get().get(), reason, last.fetchedAt());
    }

    public static class PartnerUnavailableException extends RuntimeException {
        PartnerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
