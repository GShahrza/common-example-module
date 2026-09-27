package io.github.gshahrza.cache.rates;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Data from a slow external API that changes, but not every second. The TTL (cache.ttl.rates)
 * decides how old the rates may be: the cache is the answer to "how often do we call the API".
 */
@Service
public class RateService {

    private final AtomicLong fetches = new AtomicLong();

    @Cacheable(cacheNames = "rates", key = "'AZN'")
    public Rates current() throws InterruptedException {
        fetches.incrementAndGet();
        Thread.sleep(500);   // the external API
        return new Rates("AZN", Map.of("USD", jitter("1.70"), "EUR", jitter("1.85"), "TRY", jitter("0.05")), Instant.now());
    }

    public long fetches() {
        return fetches.get();
    }

    private static BigDecimal jitter(String value) {
        double factor = 1 + ThreadLocalRandom.current().nextDouble(-0.01, 0.01);
        return new BigDecimal(value).multiply(BigDecimal.valueOf(factor)).setScale(4, RoundingMode.HALF_UP);
    }
}
