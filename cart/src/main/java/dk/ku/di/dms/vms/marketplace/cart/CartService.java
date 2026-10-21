package dk.ku.di.dms.vms.marketplace.cart;

import dk.ku.di.dms.vms.marketplace.cart.entities.ProductReplica;
import dk.ku.di.dms.vms.marketplace.cart.repositories.IProductReplicaRepository;
import dk.ku.di.dms.vms.marketplace.common.events.PriceUpdated;
import dk.ku.di.dms.vms.marketplace.common.events.ProductUpdated;
import dk.ku.di.dms.vms.marketplace.common.policy.PriceUpdatePolicy;
import dk.ku.di.dms.vms.modb.api.annotations.Inbound;
import dk.ku.di.dms.vms.modb.api.annotations.Microservice;
import dk.ku.di.dms.vms.modb.api.annotations.PartitionBy;
import dk.ku.di.dms.vms.modb.api.annotations.Transactional;

import java.util.Optional;

import static dk.ku.di.dms.vms.marketplace.common.Constants.PRICE_UPDATED;
import static dk.ku.di.dms.vms.marketplace.common.Constants.PRODUCT_UPDATED;
import static dk.ku.di.dms.vms.modb.api.enums.TransactionTypeEnum.RW;
import static dk.ku.di.dms.vms.modb.api.enums.TransactionTypeEnum.W;
import static java.lang.System.Logger.Level.DEBUG;

/**
 * Owns a read replica of Product's catalog data, kept fresh purely by consuming
 * Product's own outbound events. vMODB's coordinator delivers PRODUCT_UPDATED and
 * PRICE_UPDATED to this VMS in the same TID order Product emitted them, so the replica does
 * not observe price updates out of order.
 *
 * updateProductPrice's actual business rule is the same {@link PriceUpdatePolicy} Product's own
 * ProductService uses -- this class's job is the VMODB-adapter side (repository I/O, VMODB's
 * dispatch annotations) only.
 */
@Microservice("cart")
public final class CartService {

    private static final System.Logger LOGGER = System.getLogger(CartService.class.getName());

    private final IProductReplicaRepository productReplicaRepository;
    private final PriceUpdatePolicy priceUpdatePolicy;

    public CartService(IProductReplicaRepository productReplicaRepository, PriceUpdatePolicy priceUpdatePolicy) {
        this.productReplicaRepository = productReplicaRepository;
        this.priceUpdatePolicy = priceUpdatePolicy;
    }

    @Inbound(values = {PRICE_UPDATED})
    @Transactional(type=RW)
    public void updateProductPrice(PriceUpdated priceUpdated) {
        LOGGER.log(DEBUG, "APP: Cart received a price update event with version: "+priceUpdated.instanceId);
        ProductReplica product = this.productReplicaRepository.lookupByKey(
                new ProductReplica.ProductId(priceUpdated.sellerId, priceUpdated.productId));
        if (product == null) {
            LOGGER.log(DEBUG, "Cart has no product replica with seller ID "+priceUpdated.sellerId+" : product ID "+priceUpdated.productId);
            return;
        }
        Optional<Float> accepted = this.priceUpdatePolicy.evaluate(product.version, priceUpdated.version, priceUpdated.price);
        if (accepted.isPresent()) {
            product.price = accepted.get();
            this.productReplicaRepository.update(product);
        }
    }

    @Inbound(values = {PRODUCT_UPDATED})
    @Transactional(type=W)
    @PartitionBy(clazz = ProductUpdated.class, method = "getId")
    public void processProductUpdate(ProductUpdated productUpdated) {
        LOGGER.log(DEBUG, "APP: Cart received a product update event with version: "+productUpdated.version);
        ProductReplica product = new ProductReplica(productUpdated.seller_id, productUpdated.product_id,
                productUpdated.name, productUpdated.price, productUpdated.status, productUpdated.version);
        this.productReplicaRepository.upsert(product);
    }

}
