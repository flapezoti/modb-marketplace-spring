package marketplace.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import marketplace.ports.catalog.ProductCatalogPort;
import marketplace.ports.policy.PriceUpdatePolicy;
import marketplace.ports.policy.VersionMatchPriceUpdatePolicy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The Redis/Kafka-backed counterpart to the VMODB-backed `product` module's Main: same two
 * ports ({@link ProductCatalogPort}, {@link PriceUpdatePolicy}), different adapter wired
 * underneath. Swapping between the two VMS implementations is choosing which of these two
 * modules' Spring Boot application to run -- the port contracts and the business rule itself
 * (VersionMatchPriceUpdatePolicy, identical to the one `product` uses) are what actually stay
 * the same.
 */
@SpringBootApplication
public class ProductServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }

    // Declared as @Bean methods rather than annotating the classes themselves, so the port and
    // its implementations stay usable outside Spring too -- the same discipline applied on the
    // VMODB side.
    @Bean
    PriceUpdatePolicy priceUpdatePolicy() {
        return new VersionMatchPriceUpdatePolicy();
    }

    @Bean
    ProductCatalogPort productCatalog(StringRedisTemplate redis, ObjectMapper mapper) {
        return new RedisProductCatalogAdapter(redis, mapper);
    }
}
