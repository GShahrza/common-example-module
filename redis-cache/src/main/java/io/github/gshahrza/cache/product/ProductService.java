package io.github.gshahrza.cache.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

/**
 * Cache-aside with annotations. The annotations work through a proxy: calling one of these
 * methods from another method of this class skips the cache (self-invocation).
 */
@Service
public class ProductService {

    private final ProductRepository repository;

    ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    /**
     * Read: look in Redis first (key shop:products:{id}); on a miss call the method and store
     * the result. Optional is unwrapped: the Product is cached, an empty result is not
     * (otherwise a product created later would stay "not found" until the TTL ends).
     */
    @Cacheable(cacheNames = "products", key = "#id", unless = "#result == null")
    public Optional<Product> find(long id) {
        return repository.findById(id);
    }

    @Cacheable(cacheNames = "productsByCategory", key = "#category")
    public List<Product> byCategory(String category) {
        return repository.findByCategory(category);
    }

    /**
     * Write: update the database, then put the new value into the cache (@CachePut always runs
     * the method). The list of the product's category is evicted, not patched: recomputing it is
     * easier and safer. Only that one key is removed; allEntries = true would also throw away
     * the lists of all other categories.
     */
    @Caching(
            put = @CachePut(cacheNames = "products", key = "#result.id"),
            evict = @CacheEvict(cacheNames = "productsByCategory", key = "#product.category"))
    public Product changePrice(Product product, BigDecimal price) {
        return repository.save(new Product(product.id(), product.name(), product.category(), price, Instant.now()));
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = "products", key = "#product.id"),
            @CacheEvict(cacheNames = "productsByCategory", key = "#product.category")})
    public boolean delete(Product product) {
        return repository.delete(product.id());
    }
}
