package io.github.gshahrza.streaming.webflux.order;

import io.github.gshahrza.streaming.webflux.StreamingProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Fake reactive database (think R2DBC or reactive MongoDB). Pages are "queried" only when the
 * subscriber asks for more rows: this is backpressure. A client that reads slowly or stops early
 * never causes the remaining pages to be loaded.
 */
@Repository
public class OrderRepository {

    private static final String[] CUSTOMERS = {"Aysel", "Murad", "Leyla", "Kamran", "Nigar", "Tural", "Sevda", "Elvin"};
    private static final String[] STATUSES = {"NEW", "PAID", "SHIPPED", "DELIVERED"};
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private final StreamingProperties properties;
    private final AtomicLong pagesQueried = new AtomicLong();

    public OrderRepository(StreamingProperties properties) {
        this.properties = properties;
    }

    public Flux<Order> findAll(int count) {
        int pageSize = properties.orderPageSize();
        int pages = (count + pageSize - 1) / pageSize;
        return Flux.range(0, pages)
                // concatMap: one page at a time, in order; the next page starts only when rows are requested
                .concatMap(page -> Mono.delay(properties.orderPageDelay())
                        .doOnNext(t -> pagesQueried.incrementAndGet())
                        .thenMany(Flux.fromIterable(page(page, pageSize, count))));
    }

    public long pagesQueried() {
        return pagesQueried.get();
    }

    private static List<Order> page(int page, int pageSize, int count) {
        long from = (long) page * pageSize + 1;
        long to = Math.min(from + pageSize - 1, count);
        return LongStream.rangeClosed(from, to).mapToObj(OrderRepository::order).toList();
    }

    private static Order order(long id) {
        return new Order(
                id,
                CUSTOMERS[(int) (id % CUSTOMERS.length)],
                BigDecimal.valueOf(1000 + (id * 7919) % 90000, 2),
                STATUSES[(int) (id % STATUSES.length)],
                EPOCH.plusSeconds(id * 37));
    }
}
