package io.github.gshahrza.cache.product;

import java.util.List;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis is more than a cache: a sorted set keeps "most viewed products" with an atomic
 * increment, shared by all application instances. No database table, no locking.
 */
@Component
public class ProductViews {

    static final String KEY = "shop:product-views";

    public record Top(long productId, long views) {
    }

    private final StringRedisTemplate redis;

    ProductViews(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** ZINCRBY shop:product-views 1 {id} */
    public void viewed(long productId) {
        redis.opsForZSet().incrementScore(KEY, String.valueOf(productId), 1);
    }

    /** ZREVRANGE shop:product-views 0 {n-1} WITHSCORES */
    public List<Top> top(int n) {
        var result = redis.opsForZSet().reverseRangeWithScores(KEY, 0, n - 1);
        return result == null ? List.of() : result.stream()
                .map(t -> new Top(Long.parseLong(Objects.requireNonNull(t.getValue())), Math.round(Objects.requireNonNull(t.getScore()))))
                .toList();
    }
}
