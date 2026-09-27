package io.github.gshahrza.observability.payment;

import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Custom health check shown under /actuator/health → components.paymentGateway.
 * DOWN after 5 failures in a row; Kubernetes readiness probes or a load balancer can act on it.
 */
@Component
public class PaymentGatewayHealth implements HealthIndicator {

    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    void recordFailure() {
        consecutiveFailures.incrementAndGet();
    }

    void recordSuccess() {
        consecutiveFailures.set(0);
    }

    @Override
    public Health health() {
        int failures = consecutiveFailures.get();
        return (failures >= 5 ? Health.down() : Health.up())
                .withDetail("consecutiveFailures", failures)
                .build();
    }
}
