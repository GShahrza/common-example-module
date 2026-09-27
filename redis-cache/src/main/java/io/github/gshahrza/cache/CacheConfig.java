package io.github.gshahrza.cache;

import io.github.gshahrza.cache.product.Product;
import io.github.gshahrza.cache.rates.Rates;
import io.github.gshahrza.cache.report.Report;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.json.JsonMapper;

@Configuration
class CacheConfig {

    /** Key prefix for everything this service writes: many services often share one Redis. */
    static final String PREFIX = "shop:";

    @ConfigurationProperties("cache")
    record CacheTtl(Map<String, Duration> ttl) {
    }

    /**
     * One configuration per cache: its own TTL and a JSON serializer that knows the exact type.
     * JSON instead of JDK serialization: readable in redis-cli, no Serializable needed, and other
     * languages can read it. An explicit type instead of "@class" type info in every value:
     * records are final classes, which Jackson's default typing does not cover.
     */
    @Bean
    RedisCacheManagerBuilderCustomizer cacheConfigurations(CacheTtl ttl, JsonMapper jsonMapper,
                                                           RedisConnectionFactory connectionFactory) {
        var types = jsonMapper.getTypeFactory();
        Map<String, JavaType> caches = Map.of(
                "products", types.constructType(Product.class),
                "productsByCategory", types.constructCollectionType(List.class, Product.class),
                "rates", types.constructType(Rates.class),
                "dailyReport", types.constructType(Report.class));
        return builder -> {
            /*
             * The cache writer talks to Redis. Two settings differ from the defaults:
             * - enableLocking: needed for @Cacheable(sync = true). Since Spring Data Redis 4 the writer
             *   does the synchronisation, and the default writer does not lock. The lock is a key in
             *   Redis, so it works across application instances too. Cost: every cache write takes
             *   the lock, one extra round trip.
             * - immediateWrites: with Lettuce, puts and evictions are sent asynchronously by default,
             *   so right after an @CacheEvict the old entry may still be readable for a moment.
             */
            builder.cacheWriter(RedisCacheWriter.create(connectionFactory, writer -> writer
                    .enableLocking()
                    .immediateWrites()
                    .collectStatistics()));
            caches.forEach((name, type) -> builder.withCacheConfiguration(name,
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(ttl.ttl().getOrDefault(name, Duration.ofMinutes(5)))
                        .computePrefixWith(cache -> PREFIX + cache + ":")          // shop:products:42
                        .disableCachingNullValues()
                        .serializeValuesWith(SerializationPair.fromSerializer(new JacksonJsonRedisSerializer<>(jsonMapper, type)))));
        };
    }
}
