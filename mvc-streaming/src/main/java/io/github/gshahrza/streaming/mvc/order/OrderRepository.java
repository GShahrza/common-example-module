package io.github.gshahrza.streaming.mvc.order;

import io.github.gshahrza.streaming.mvc.config.Sleeper;
import io.github.gshahrza.streaming.mvc.config.StreamingProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;

/**
 * Fake database. Rows are read page by page with a delay per page, like a real query with a cursor.
 * With Spring Data JPA the equivalent is a repository method returning {@code Stream<Order>} inside a
 * read-only transaction.
 */
@Repository
public class OrderRepository {

    private static final String[] CUSTOMERS = {"Aysel", "Murad", "Leyla", "Kamran", "Nigar", "Tural", "Sevda", "Elvin"};
    private static final String[] STATUSES = {"NEW", "PAID", "SHIPPED", "DELIVERED"};
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    private final StreamingProperties properties;

    public OrderRepository(StreamingProperties properties) {
        this.properties = properties;
    }

    /** Lazy: a page is only "queried" when the consumer gets to it, and the rest is skipped if it stops early. */
    public Stream<List<Order>> streamPages(int count) {
        int pageSize = properties.orderPageSize();
        int pages = (count + pageSize - 1) / pageSize;
        return IntStream.range(0, pages).mapToObj(page -> {
            Sleeper.sleep(properties.orderPageDelay());
            long from = (long) page * pageSize + 1;
            long to = Math.min(from + pageSize - 1, count);
            return LongStream.rangeClosed(from, to).mapToObj(OrderRepository::order).toList();
        });
    }

    public Stream<Order> stream(int count) {
        return streamPages(count).flatMap(List::stream);
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
