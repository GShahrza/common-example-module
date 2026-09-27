package io.github.gshahrza.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.gshahrza.cache.product.Product;
import io.github.gshahrza.cache.product.ProductRepository;
import io.github.gshahrza.cache.product.ProductService;
import io.github.gshahrza.cache.product.ProductViews;
import io.github.gshahrza.cache.rates.RateService;
import io.github.gshahrza.cache.report.ReportService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A real Redis in Docker (Testcontainers); @ServiceConnection points spring.data.redis at it. */
@Testcontainers
@SpringBootTest(properties = "cache.ttl.rates=1s")
class RedisCacheTest {

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:8-alpine").withExposedPorts(6379);

    @Autowired
    ProductService products;
    @Autowired
    ProductRepository repository;
    @Autowired
    RateService rates;
    @Autowired
    ReportService reports;
    @Autowired
    ProductViews views;
    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void emptyRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void secondReadComesFromRedisAsJsonWithTtl() {
        long before = repository.calls();

        Product first = products.find(1).orElseThrow();
        Product second = products.find(1).orElseThrow();

        assertThat(second).isEqualTo(first);
        assertThat(repository.calls() - before).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get("shop:products:1")).contains("\"name\":\"" + first.name() + "\"");
        assertThat(redisTemplate.getExpire("shop:products:1")).isBetween(1L, 600L);
    }

    @Test
    void priceChangeUpdatesTheEntryAndEvictsLists() {
        products.find(2);
        products.byCategory("laptops");
        products.byCategory("phones");

        products.changePrice(products.find(2).orElseThrow(), new BigDecimal("99.90"));

        assertThat(redisTemplate.hasKey("shop:productsByCategory:laptops")).isFalse();   // product 2 is a laptop
        assertThat(redisTemplate.hasKey("shop:productsByCategory:phones")).isTrue();     // other lists stay
        long before = repository.calls();
        assertThat(products.find(2).orElseThrow().price()).isEqualByComparingTo("99.90");
        assertThat(repository.calls()).isEqualTo(before);   // served by the @CachePut value
    }

    @Test
    void missingProductIsNotCached() {
        long before = repository.calls();

        assertThat(products.find(9999)).isEmpty();
        assertThat(products.find(9999)).isEmpty();

        assertThat(repository.calls() - before).isEqualTo(2);
        assertThat(redisTemplate.hasKey("shop:products:9999")).isFalse();
    }

    @Test
    void deleteEvicts() {
        assertThat(products.delete(products.find(3).orElseThrow())).isTrue();

        assertThat(redisTemplate.hasKey("shop:products:3")).isFalse();
        assertThat(products.find(3)).isEmpty();
    }

    @Test
    void entriesExpireAfterTheirTtl() throws Exception {
        long before = rates.fetches();
        rates.current();
        rates.current();
        assertThat(rates.fetches() - before).isEqualTo(1);

        Thread.sleep(1300);   // cache.ttl.rates=1s in this test
        rates.current();

        assertThat(rates.fetches() - before).isEqualTo(2);
    }

    @Test
    void syncCacheableComputesOnceUnderConcurrentMisses() throws Exception {
        reports.evict();
        long before = reports.computations();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                calls.add(executor.submit(reports::daily));
            }
            for (Future<?> call : calls) {
                call.get();
            }
        }

        assertThat(reports.computations() - before).isEqualTo(1);
    }

    @Test
    void sortedSetKeepsTheMostViewedProducts() {
        for (int i = 0; i < 3; i++) {
            views.viewed(7);
        }
        views.viewed(5);

        assertThat(views.top(2)).containsExactly(new ProductViews.Top(7, 3), new ProductViews.Top(5, 1));
    }
}
