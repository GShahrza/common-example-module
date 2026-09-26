package io.github.gshahrza.streaming.mvc.order;

import java.math.BigDecimal;
import java.time.Instant;

public record Order(long id, String customer, BigDecimal amount, String status, Instant createdAt) {
}
