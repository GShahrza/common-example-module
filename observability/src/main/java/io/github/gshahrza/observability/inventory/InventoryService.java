package io.github.gshahrza.observability.inventory;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.annotation.Observed;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final Map<String, AtomicInteger> stock = new ConcurrentHashMap<>();

    InventoryService(MeterRegistry registry) {
        for (var sku : Map.of("BOOK", 1000, "LAPTOP", 50, "PHONE", 200).entrySet()) {
            AtomicInteger level = new AtomicInteger(sku.getValue());
            stock.put(sku.getKey(), level);
            // Gauge: a value that goes up and down; Micrometer reads it when metrics are scraped
            Gauge.builder("inventory.stock", level, AtomicInteger::get)
                    .description("Items in stock")
                    .tag("sku", sku.getKey())
                    .register(registry);
        }
    }

    /**
     * {@code @Observed}: one annotation gives a timer metric (inventory.reserve) and a child span
     * in the trace. Low-cardinality tags only: never put ids or user input into metric tags.
     */
    @Observed(name = "inventory.reserve", contextualName = "reserve-stock")
    public boolean reserve(String sku, int quantity) {
        AtomicInteger level = stock.get(sku);
        if (level == null) {
            throw new IllegalArgumentException("Unknown sku " + sku);
        }
        int left = level.addAndGet(-quantity);
        if (left < 0) {
            level.addAndGet(quantity);
            log.warn("Out of stock: sku={} requested={}", sku, quantity);
            return false;
        }
        log.info("Reserved {} x {}, {} left", quantity, sku, left);
        return true;
    }

    public Map<String, Integer> levels() {
        Map<String, Integer> result = new java.util.TreeMap<>();
        stock.forEach((sku, level) -> result.put(sku, level.get()));
        return result;
    }
}
