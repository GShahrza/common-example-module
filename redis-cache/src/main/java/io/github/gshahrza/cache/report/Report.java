package io.github.gshahrza.cache.report;

import java.math.BigDecimal;
import java.time.Instant;

public record Report(long orders, BigDecimal revenue, Instant computedAt) {
}
