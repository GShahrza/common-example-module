package io.github.gshahrza.cache.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Repository;

/** Stands in for a slow database: every call takes 300 ms and is counted. */
@Repository
public class ProductRepository {

    private static final List<String> CATEGORIES = List.of("books", "phones", "laptops");

    private final Map<Long, Product> rows = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();

    ProductRepository() {
        for (long id = 1; id <= 30; id++) {
            String category = CATEGORIES.get((int) (id % 3));
            rows.put(id, new Product(id, category.substring(0, category.length() - 1) + " #" + id, category,
                    BigDecimal.valueOf(10 + id * 7), Instant.now()));
        }
    }

    Optional<Product> findById(long id) {
        slowQuery();
        return Optional.ofNullable(rows.get(id));
    }

    List<Product> findByCategory(String category) {
        slowQuery();
        return rows.values().stream().filter(p -> p.category().equals(category))
                .sorted(Comparator.comparing(Product::id)).toList();
    }

    Product save(Product product) {
        slowQuery();
        rows.put(product.id(), product);
        return product;
    }

    boolean delete(long id) {
        slowQuery();
        return rows.remove(id) != null;
    }

    public long calls() {
        return calls.get();
    }

    private void slowQuery() {
        calls.incrementAndGet();
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
