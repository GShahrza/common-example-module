package io.github.gshahrza.cache.report;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * An expensive computation that everyone requests at once (e.g. the home page right after the
 * cache expired). Without sync, 100 concurrent misses start 100 computations: a cache stampede.
 */
@Service
public class ReportService {

    private final AtomicLong computations = new AtomicLong();

    /** sync = true: only one caller computes, the others wait for its result (per application instance). */
    @Cacheable(cacheNames = "dailyReport", key = "'today'", sync = true)
    public Report daily() throws InterruptedException {
        computations.incrementAndGet();
        Thread.sleep(1000);
        return new Report(1284, new BigDecimal("48210.50"), Instant.now());
    }

    @CacheEvict(cacheNames = "dailyReport", allEntries = true)
    public void evict() {
    }

    public long computations() {
        return computations.get();
    }
}
