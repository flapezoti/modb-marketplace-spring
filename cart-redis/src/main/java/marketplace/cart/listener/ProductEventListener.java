package marketplace.cart.listener;

import marketplace.cart.event.PriceUpdatedEvent;
import marketplace.cart.event.ProductUpdatedEvent;
import marketplace.ports.catalog.ProductReplicaCatalogPort;
import marketplace.ports.catalog.ProductSnapshot;
import marketplace.ports.policy.PriceUpdatePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static marketplace.cart.KafkaTopics.PRICE_UPDATED;
import static marketplace.cart.KafkaTopics.PRODUCT_UPDATED;

/**
 * Storage access goes through {@link ProductReplicaCatalogPort}, and the price-update decision
 * through {@link PriceUpdatePolicy} -- the same two ports the VMODB-backed `cart` module's
 * CartService uses. This class's job is purely the Kafka-adapter side: the
 * {@code @KafkaListener} methods that tell Spring Kafka how to dispatch these events.
 */
@Component
public class ProductEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProductEventListener.class);

    private final ProductReplicaCatalogPort productReplicaCatalog;
    private final PriceUpdatePolicy priceUpdatePolicy;

    public ProductEventListener(ProductReplicaCatalogPort productReplicaCatalog, PriceUpdatePolicy priceUpdatePolicy) {
        this.productReplicaCatalog = productReplicaCatalog;
        this.priceUpdatePolicy = priceUpdatePolicy;
    }

    @KafkaListener(topics = PRODUCT_UPDATED, groupId = "cart-service")
    public void onProductUpdated(ProductUpdatedEvent event) {
        LOGGER.info("Received ProductUpdatedEvent for {}-{} version {}", event.getSellerId(), event.getProductId(), event.getVersion());
        this.productReplicaCatalog.save(new ProductSnapshot(event.getSellerId(), event.getProductId(),
                event.getName(), event.getPrice(), event.getStatus(), event.getVersion()));
    }

    @KafkaListener(topics = PRICE_UPDATED, groupId = "cart-service")
    public void onPriceUpdated(PriceUpdatedEvent event) {
        LOGGER.info("Received PriceUpdatedEvent for {}-{} version {}", event.getSellerId(), event.getProductId(), event.getVersion());
        Optional<ProductSnapshot> current = this.productReplicaCatalog.find(event.getSellerId(), event.getProductId());
        if (current.isEmpty()) {
            LOGGER.info("No replica yet for {}-{}, ignoring price update", event.getSellerId(), event.getProductId());
            return;
        }
        ProductSnapshot replica = current.get();
        Optional<Float> accepted = this.priceUpdatePolicy.evaluate(replica.version(), event.getVersion(), event.getPrice());
        if (accepted.isPresent()) {
            this.productReplicaCatalog.save(new ProductSnapshot(replica.sellerId(), replica.productId(), replica.name(),
                    accepted.get(), replica.status(), replica.version()));
        } else {
            LOGGER.warn("Ignoring price update with conflicting version: {} != {}", replica.version(), event.getVersion());
        }
    }
}
