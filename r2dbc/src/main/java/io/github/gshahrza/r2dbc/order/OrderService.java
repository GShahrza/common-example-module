package io.github.gshahrza.r2dbc.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class OrderService {

    public record CustomerStats(String customer, long orders, BigDecimal total) {
    }

    public record OrderLine(long id, String customer, String product, int quantity, BigDecimal amount, String status) {
    }

    public static class OutOfStockException extends RuntimeException {
        OutOfStockException(String message) {
            super(message);
        }
    }

    private final OrderRepository orders;
    private final DatabaseClient db;
    /** How many rows the database has actually sent to us: shows backpressure at work. */
    private final AtomicLong rowsFetched = new AtomicLong();

    OrderService(OrderRepository orders, DatabaseClient db) {
        this.orders = orders;
        this.db = db;
    }

    /**
     * Streams orders straight from a PostgreSQL cursor. fetchSize(250): the driver asks the
     * database for 250 rows at a time, and only when the subscriber requests more. A client
     * that reads slowly or stops reading therefore also slows or stops the database side.
     */
    public Flux<OrderLine> stream(int limit) {
        return db.sql("""
                        SELECT o.id, o.customer, p.name AS product, o.quantity, o.amount, o.status
                        FROM orders o JOIN product p ON p.id = o.product_id
                        ORDER BY o.id LIMIT :limit""")
                .filter(statement -> statement.fetchSize(250))
                .bind("limit", limit)
                .map((row, meta) -> new OrderLine(row.get("id", Long.class), row.get("customer", String.class),
                        row.get("product", String.class), row.get("quantity", Integer.class),
                        row.get("amount", BigDecimal.class), row.get("status", String.class)))
                .all()
                .doOnNext(line -> rowsFetched.incrementAndGet());
    }

    public long rowsFetched() {
        return rowsFetched.get();
    }

    /** DatabaseClient: plain SQL when a repository method is not enough (aggregates, joins). */
    public Flux<CustomerStats> statsByCustomer() {
        return db.sql("SELECT customer, COUNT(*) AS orders, SUM(amount) AS total FROM orders GROUP BY customer ORDER BY total DESC")
                .map(row -> new CustomerStats(row.get("customer", String.class), row.get("orders", Long.class),
                        row.get("total", BigDecimal.class)))
                .all();
    }

    /**
     * A reactive transaction: decrease the stock and insert the order, or neither. @Transactional
     * works on Mono/Flux methods too (R2dbcTransactionManager); the transaction lives in the
     * Reactor context, not in a ThreadLocal, because the work can jump between threads.
     * The manager is named because the comparison's JDBC pool brings a second (JDBC) one.
     */
    @Transactional(transactionManager = "connectionFactoryTransactionManager")
    public Mono<Order> place(String customer, long productId, int quantity) {
        return db.sql("UPDATE product SET stock = stock - :q WHERE id = :id AND stock >= :q RETURNING price")
                .bind("q", quantity).bind("id", productId)
                .map(row -> row.get("price", BigDecimal.class))
                .one()
                .switchIfEmpty(Mono.error(() -> new OutOfStockException("Not enough stock for product " + productId)))
                .flatMap(price -> orders.save(new Order(null, customer, productId, quantity,
                        price.multiply(BigDecimal.valueOf(quantity)), "NEW", Instant.now())));
    }

    public Mono<Integer> stock(long productId) {
        return db.sql("SELECT stock FROM product WHERE id = :id").bind("id", productId)
                .map(row -> row.get("stock", Integer.class)).one();
    }
}
