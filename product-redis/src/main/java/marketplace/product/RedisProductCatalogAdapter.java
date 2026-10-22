package marketplace.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import marketplace.ports.catalog.ProductCatalogPort;
import marketplace.ports.catalog.ProductSnapshot;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

/**
 * The Redis adapter for {@link ProductCatalogPort}: stores one JSON-serialized
 * {@link ProductSnapshot} per key, directly -- no separate Redis-specific entity class, since
 * the snapshot is already a plain, serializable shape.
 */
public final class RedisProductCatalogAdapter implements ProductCatalogPort {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisProductCatalogAdapter(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    private static String key(int sellerId, int productId) {
        return "product:%d:%d".formatted(sellerId, productId);
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
            throw new RuntimeException("Failed to deserialize product", e);
        }
    }

    @Override
    public void save(ProductSnapshot snapshot) {
        try {
            String json = this.mapper.writeValueAsString(snapshot);
            this.redis.opsForValue().set(key(snapshot.sellerId(), snapshot.productId()), json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize product", e);
        }
    }

}
