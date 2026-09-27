package io.github.gshahrza.cache.rates;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record Rates(String base, Map<String, BigDecimal> rates, Instant fetchedAt) {
}
