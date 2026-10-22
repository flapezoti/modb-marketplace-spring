package marketplace.product;

import marketplace.product.dto.UpdatePriceRequest;
import marketplace.product.dto.UpdateProductRequest;
import marketplace.product.event.PriceUpdatedEvent;
import marketplace.product.event.ProductUpdatedEvent;
import marketplace.ports.catalog.ProductCatalogPort;
import marketplace.ports.catalog.ProductSnapshot;
import marketplace.ports.policy.PriceUpdatePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * All storage access goes through {@link ProductCatalogPort}, and the price-update decision
 * through {@link PriceUpdatePolicy} -- the same two ports the VMODB-backed `product` module's
 * ProductService uses. This class's job is purely the Redis/Kafka-adapter side: translating HTTP
 * requests into port calls, and publishing the resulting events.
 */
@RestController
@RequestMapping("/products")
public class ProductController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProductController.class);

    private final ProductCatalogPort productCatalog;
    private final PriceUpdatePolicy priceUpdatePolicy;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ProductController(ProductCatalogPort productCatalog, PriceUpdatePolicy priceUpdatePolicy,
                             KafkaTemplate<String, Object> kafkaTemplate) {
        this.productCatalog = productCatalog;
        this.priceUpdatePolicy = priceUpdatePolicy;
        this.kafkaTemplate = kafkaTemplate;
    }

    @PostMapping
    public ResponseEntity<ProductSnapshot> upsert(@RequestBody UpdateProductRequest req) {
        ProductSnapshot product = new ProductSnapshot(req.getSellerId(), req.getProductId(), req.getName(),
                req.getPrice(), req.getStatus(), req.getVersion());
        this.productCatalog.save(product);

        ProductUpdatedEvent event = new ProductUpdatedEvent(product.sellerId(), product.productId(),
                product.name(), product.price(), product.status(), product.version());
        // Deliberately sent without a partition key, so ordering across partitions is not
        // guaranteed; a keyed send would use KafkaTemplate.send(topic, key, value).
        this.kafkaTemplate.send(KafkaTopics.PRODUCT_UPDATED, event);
        LOGGER.info("Published ProductUpdatedEvent for {}-{} version {}", product.sellerId(), product.productId(), product.version());

        return ResponseEntity.ok(product);
    }

    @PatchMapping("/{sellerId}/{productId}/price")
    public ResponseEntity<ProductSnapshot> updatePrice(@PathVariable int sellerId, @PathVariable int productId,
                                               @RequestBody UpdatePriceRequest req) {
        ProductSnapshot current = this.productCatalog.find(sellerId, productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found " + sellerId + "-" + productId));

        Optional<Float> accepted = this.priceUpdatePolicy.evaluate(current.version(), req.getVersion(), req.getPrice());
        ProductSnapshot result = current;
        if (accepted.isPresent()) {
            result = new ProductSnapshot(current.sellerId(), current.productId(), current.name(),
                    accepted.get(), current.status(), current.version());
            this.productCatalog.save(result);
        } else {
            LOGGER.warn("Received an update price event with conflicting version: {} != {}", current.version(), req.getVersion());
        }

        // must send regardless: Cart's replica needs to see every price update, even
        // conflicting ones, so it can apply the same version check and stay consistent
        PriceUpdatedEvent event = new PriceUpdatedEvent(sellerId, productId, req.getPrice(), req.getVersion(), req.getInstanceId());
        this.kafkaTemplate.send(KafkaTopics.PRICE_UPDATED, event);
        LOGGER.info("Published PriceUpdatedEvent for {}-{} version {}", sellerId, productId, req.getVersion());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{sellerId}/{productId}")
    public ResponseEntity<ProductSnapshot> get(@PathVariable int sellerId, @PathVariable int productId) {
        return this.productCatalog.find(sellerId, productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
