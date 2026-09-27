package io.github.gshahrza.cache;

import io.github.gshahrza.cache.product.Product;
import io.github.gshahrza.cache.product.ProductRepository;
import io.github.gshahrza.cache.product.ProductService;
import io.github.gshahrza.cache.product.ProductViews;
import io.github.gshahrza.cache.rates.RateService;
import io.github.gshahrza.cache.rates.Rates;
import io.github.gshahrza.cache.report.ReportService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CacheController {

    /** "source" shows where the answer came from: cache (no database call) or db. */
    record Timed<T>(T data, String source, long millis) {
    }

    record PriceChange(BigDecimal price) {
    }

    record KeyInfo(String key, String type, long ttlSeconds) {
    }

    private final ProductService products;
    private final ProductRepository repository;
    private final ProductViews views;
    private final RateService rates;
    private final ReportService reports;
    private final StringRedisTemplate redis;

    CacheController(ProductService products, ProductRepository repository, ProductViews views,
                    RateService rates, ReportService reports, StringRedisTemplate redis) {
        this.products = products;
        this.repository = repository;
        this.views = views;
        this.rates = rates;
        this.reports = reports;
        this.redis = redis;
    }

    private interface Call<T> {
        T get() throws Exception;
    }

    private <T> Timed<T> timed(Call<T> call) throws Exception {
        long before = repository.calls() + rates.fetches() + reports.computations();
        long start = System.nanoTime();
        T data = call.get();
        long millis = (System.nanoTime() - start) / 1_000_000;
        boolean loaded = repository.calls() + rates.fetches() + reports.computations() > before;
        return new Timed<>(data, loaded ? "db" : "cache", millis);
    }

    @GetMapping("/api/products/{id}")
    ResponseEntity<Timed<Product>> product(@PathVariable long id) throws Exception {
        Timed<java.util.Optional<Product>> result = timed(() -> products.find(id));
        if (result.data().isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        views.viewed(id);
        return ResponseEntity.ok(new Timed<>(result.data().get(), result.source(), result.millis()));
    }

    @GetMapping("/api/products")
    Timed<List<Product>> byCategory(@RequestParam String category) throws Exception {
        return timed(() -> products.byCategory(category));
    }

    @PutMapping("/api/products/{id}/price")
    ResponseEntity<Product> changePrice(@PathVariable long id, @RequestBody PriceChange change) {
        return products.find(id)
                .map(p -> ResponseEntity.ok(products.changePrice(p, change.price())))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/products/{id}")
    ResponseEntity<Void> delete(@PathVariable long id) {
        return products.find(id)
                .map(p -> products.delete(p) ? ResponseEntity.noContent().<Void>build() : ResponseEntity.notFound().<Void>build())
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/products/top")
    List<ProductViews.Top> top() {
        return views.top(5);
    }

    @GetMapping("/api/rates")
    Timed<Rates> rates() throws Exception {
        return timed(rates::current);
    }

    /** Evicts the report, then asks for it from n threads at the same moment. */
    @PostMapping("/api/reports/daily/stampede")
    Map<String, Long> stampede(@RequestParam(defaultValue = "50") int n) throws Exception {
        reports.evict();
        long before = reports.computations();
        long start = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < Math.clamp(n, 1, 500); i++) {
                calls.add(executor.submit(reports::daily));
            }
            for (Future<?> call : calls) {
                call.get();
            }
        }
        return Map.of("requests", (long) n, "computations", reports.computations() - before,
                "millis", (System.nanoTime() - start) / 1_000_000);
    }

    /** What is in Redis right now. SCAN, not KEYS: KEYS blocks Redis while it walks all keys. */
    @GetMapping("/api/cache/keys")
    List<KeyInfo> keys() {
        List<KeyInfo> keys = new ArrayList<>();
        try (var cursor = redis.scan(ScanOptions.scanOptions().match(CacheConfig.PREFIX + "*").count(100).build())) {
            cursor.forEachRemaining(key -> {
                Long ttl = redis.getExpire(key);
                keys.add(new KeyInfo(key, String.valueOf(redis.type(key)).toLowerCase(), ttl == null ? -2 : ttl));
            });
        }
        keys.sort(java.util.Comparator.comparing(KeyInfo::key));
        return keys;
    }

    @GetMapping("/api/cache/keys/value")
    ResponseEntity<String> value(@RequestParam String key) {
        if (!key.startsWith(CacheConfig.PREFIX)) {
            return ResponseEntity.badRequest().build();
        }
        String value = "zset".equalsIgnoreCase(String.valueOf(redis.type(key))) ? String.valueOf(views.top(10)) : redis.opsForValue().get(key);
        return value == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(value);
    }

    @GetMapping("/api/stats")
    Map<String, Long> stats() {
        return Map.of("dbCalls", repository.calls(), "rateApiCalls", rates.fetches(),
                "reportComputations", reports.computations());
    }
}
