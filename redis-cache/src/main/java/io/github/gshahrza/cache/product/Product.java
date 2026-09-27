package io.github.gshahrza.cache.product;

import java.math.BigDecimal;
import java.time.Instant;

public record Product(long id, String name, String category, BigDecimal price, Instant updatedAt) {
}
