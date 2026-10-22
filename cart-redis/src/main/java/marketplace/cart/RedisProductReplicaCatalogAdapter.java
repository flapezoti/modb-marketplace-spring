package marketplace.cart;

import com.fasterxml.jackson.databind.ObjectMapper;
import marketplace.ports.catalog.ProductReplicaCatalogPort;
import marketplace.ports.catalog.ProductSnapshot;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

/**
 * The Redis adapter for {@link ProductReplicaCatalogPort} -- see the matching
 * {@code RedisProductCatalogAdapter} in product-redis for the full rationale.
 */
public final class RedisProductReplicaCatalogAdapter implements ProductReplicaCatalogPort {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisProductReplicaCatalogAdapter(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    private static String key(int sellerId, int productId) {
        return "product_replica:%d:%d".formatted(sellerId, productId);
    }

    @Override
    public Optional<ProductSnapshot> find(int sellerId, int productId) {
        String json = this.redis.opsForValue().get(key(sellerId, productId));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(this.mapper.readValue(json, ProductSnapshot.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize product replica", e);
        }
    }

    @Override
    public void save(ProductSnapshot snapshot) {
        try {
            String json = this.mapper.writeValueAsString(snapshot);
            this.redis.opsForValue().set(key(snapshot.sellerId(), snapshot.productId()), json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize product replica", e);
        }
    }

}
