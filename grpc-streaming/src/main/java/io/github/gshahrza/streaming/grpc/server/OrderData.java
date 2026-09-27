package io.github.gshahrza.streaming.grpc.server;

import io.github.gshahrza.streaming.grpc.StreamingProperties;
import io.github.gshahrza.streaming.grpc.proto.Order;
import com.google.protobuf.Timestamp;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Fake database, read page by page with a delay per page. */
@Component
public class OrderData {

    public static final long MAX_ID = 1_000_000;
    private static final String[] CUSTOMERS = {"Aysel", "Murad", "Leyla", "Kamran", "Nigar", "Tural", "Sevda", "Elvin"};
    private static final Order.Status[] STATUSES = {
            Order.Status.NEW, Order.Status.PAID, Order.Status.SHIPPED, Order.Status.DELIVERED};
    private static final long EPOCH_SECONDS = 1_767_225_600L; // 2026-01-01T00:00:00Z

    private final StreamingProperties properties;

    public OrderData(StreamingProperties properties) {
        this.properties = properties;
    }

    public Stream<List<Order>> pages(int count) {
        int pageSize = properties.orderPageSize();
        int pages = (count + pageSize - 1) / pageSize;
        return IntStream.range(0, pages).mapToObj(page -> {
            sleep();
            long from = (long) page * pageSize + 1;
            long to = Math.min(from + pageSize - 1, count);
            return LongStream.rangeClosed(from, to).mapToObj(OrderData::order).toList();
        });
    }

    public static Order order(long id) {
        return Order.newBuilder()
                .setId(id)
                .setCustomer(CUSTOMERS[(int) (id % CUSTOMERS.length)])
                .setAmount(BigDecimal.valueOf(1000 + (id * 7919) % 90000, 2).toPlainString())
                .setStatus(STATUSES[(int) (id % STATUSES.length)])
                .setCreatedAt(Timestamp.newBuilder().setSeconds(EPOCH_SECONDS + id * 37))
                .build();
    }

    private void sleep() {
        try {
            Thread.sleep(properties.orderPageDelay());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
