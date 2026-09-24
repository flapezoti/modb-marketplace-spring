package dk.ku.di.dms.vms.marketplace.cart;

import dk.ku.di.dms.vms.marketplace.cart.entities.ProductReplica;
import dk.ku.di.dms.vms.marketplace.cart.repositories.IProductReplicaRepository;
import dk.ku.di.dms.vms.marketplace.common.events.PriceUpdated;
import dk.ku.di.dms.vms.marketplace.common.events.ProductUpdated;
import dk.ku.di.dms.vms.modb.api.annotations.Inbound;
import dk.ku.di.dms.vms.modb.api.annotations.Microservice;
import dk.ku.di.dms.vms.modb.api.annotations.PartitionBy;
import dk.ku.di.dms.vms.modb.api.annotations.Transactional;

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
 */
@Microservice("cart")
public final class CartService {

    private static final System.Logger LOGGER = System.getLogger(CartService.class.getName());

    private final IProductReplicaRepository productReplicaRepository;

    public CartService(IProductReplicaRepository productReplicaRepository) {
        this.productReplicaRepository = productReplicaRepository;
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
        // same version check as Product's own guard: keeps the replica from ever
        // applying a stale/out-of-order price update, even if delivery were to repeat one
        if (product.version.contentEquals(priceUpdated.version)) {
            product.price = priceUpdated.price;
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
