package io.github.gshahrza.observability.checkout;

import io.github.gshahrza.observability.Hop;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class CheckoutService {

    public record CheckoutRequest(String customer, String sku, Integer quantity, Boolean slowPayment) {

        public CheckoutRequest {
            customer = customer == null || customer.isBlank() ? "anonymous" : customer;
            sku = sku == null ? "BOOK" : sku;
            quantity = quantity == null || quantity < 1 ? 1 : quantity;
            slowPayment = Boolean.TRUE.equals(slowPayment);
        }
    }

    public record CheckoutResult(String status, String traceId, List<Hop> hops) {
    }

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final Map<String, BigDecimal> PRICES = Map.of(
            "BOOK", new BigDecimal("25"), "LAPTOP", new BigDecimal("2400"), "PHONE", new BigDecimal("900"));

    private final RestClient http;
    private final ObservationRegistry observations;
    private final MeterRegistry meters;
    private final Tracer tracer;
    private final DistributionSummary orderAmount;

    /**
     * RestClient.Builder from Spring Boot is already instrumented: every call gets a client span
     * and a "traceparent" header, so the called service continues the same trace.
     */
    CheckoutService(RestClient.Builder builder, @Value("${services.base-url}") String baseUrl,
                    ObservationRegistry observations, MeterRegistry meters, Tracer tracer) {
        this.http = builder.baseUrl(baseUrl)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })   // read error bodies too
                .build();
        this.observations = observations;
        this.meters = meters;
        this.tracer = tracer;
        this.orderAmount = DistributionSummary.builder("orders.amount")
                .description("Order value in AZN")
                .baseUnit("AZN")
                .register(meters);
    }

    public CheckoutResult checkout(CheckoutRequest request) {
        // Observation API: one call produces a timer (checkout.process) AND a span (checkout)
        return Observation.createNotStarted("checkout.process", observations)
                .contextualName("checkout")
                .lowCardinalityKeyValue("sku", request.sku())              // few values: fine as a metric tag
                .highCardinalityKeyValue("customer", request.customer())   // many values: only on the span
                .observe(() -> process(request));
    }

    private CheckoutResult process(CheckoutRequest request) {
        List<Hop> hops = new ArrayList<>();
        long start = System.nanoTime();
        log.info("Checkout started: customer={} sku={} quantity={}", request.customer(), request.sku(), request.quantity());

        Hop inventory = http.post().uri("/api/inventory/{sku}/reserve?quantity={q}", request.sku(), request.quantity())
                .retrieve().body(Hop.class);
        hops.add(inventory);
        if (!"reserved".equals(inventory.result())) {
            return finish("out_of_stock", hops, start);
        }

        BigDecimal amount = PRICES.get(request.sku()).multiply(BigDecimal.valueOf(request.quantity()));
        Hop payment = http.post().uri("/api/payments")
                .body(Map.of("customer", request.customer(), "amount", amount, "slow", request.slowPayment()))
                .retrieve().body(Hop.class);
        hops.add(payment);
        if (!"paid".equals(payment.result())) {
            return finish("payment_failed", hops, start);
        }
        orderAmount.record(amount.doubleValue());
        return finish("success", hops, start);
    }

    private CheckoutResult finish(String status, List<Hop> hops, long start) {
        // A business metric: how many orders, by outcome. The tag has 3 fixed values.
        Counter.builder("orders.placed").description("Checkouts by result").tag("result", status)
                .register(meters).increment();
        log.info("Checkout finished: {}", status);
        List<Hop> all = new ArrayList<>();
        all.add(Hop.of(tracer, "checkout", start, status));
        all.addAll(hops);
        return new CheckoutResult(status, all.getFirst().traceId(), all);
    }
}
