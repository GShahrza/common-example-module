package io.github.gshahrza.observability.payment;

import io.github.gshahrza.observability.Hop;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Plays the "payment service": variable latency and a configurable share of failures. */
@RestController
class PaymentController {

    record PaymentRequest(String customer, BigDecimal amount, boolean slow) {
    }

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final Tracer tracer;
    private final PaymentGatewayHealth health;
    private final double failureRate;

    PaymentController(Tracer tracer, PaymentGatewayHealth health, @Value("${payment.failure-rate}") double failureRate) {
        this.tracer = tracer;
        this.health = health;
        this.failureRate = failureRate;
    }

    @PostMapping("/api/payments")
    ResponseEntity<Hop> pay(@RequestBody PaymentRequest request) throws InterruptedException {
        long start = System.nanoTime();
        // A custom attribute on the current span: searchable in Tempo, never used as a metric tag
        var span = tracer.currentSpan();
        if (span != null) {
            span.tag("payment.customer", request.customer());
        }
        Thread.sleep(request.slow() ? 1500 : ThreadLocalRandom.current().nextLong(20, 150));

        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            health.recordFailure();
            log.error("Payment gateway error for customer {} amount {}", request.customer(), request.amount());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Hop.of(tracer, "payment", start, "gateway error"));
        }
        health.recordSuccess();
        log.info("Charged {} to {}", request.amount(), request.customer());
        return ResponseEntity.ok(Hop.of(tracer, "payment", start, "paid"));
    }
}
