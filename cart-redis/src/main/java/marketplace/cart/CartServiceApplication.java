package marketplace.cart;

import com.fasterxml.jackson.databind.ObjectMapper;
import marketplace.ports.catalog.ProductReplicaCatalogPort;
import marketplace.ports.policy.PriceUpdatePolicy;
import marketplace.ports.policy.VersionMatchPriceUpdatePolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The Redis/Kafka-backed counterpart to the VMODB-backed `cart` module's Main -- see
 * product-redis's ProductServiceApplication for the full rationale.
 */
@SpringBootApplication
public class CartServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CartServiceApplication.class, args);
    }

    @Bean
    PriceUpdatePolicy priceUpdatePolicy() {
        return new VersionMatchPriceUpdatePolicy();
    }

    @Bean
    ProductReplicaCatalogPort productReplicaCatalog(StringRedisTemplate redis, ObjectMapper mapper) {
        return new RedisProductReplicaCatalogAdapter(redis, mapper);
    }
}
