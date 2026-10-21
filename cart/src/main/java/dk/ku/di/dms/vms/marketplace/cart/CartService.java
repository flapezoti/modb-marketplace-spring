package dk.ku.di.dms.vms.marketplace.cart;

import dk.ku.di.dms.vms.marketplace.cart.repositories.IProductReplicaRepository;
import dk.ku.di.dms.vms.marketplace.common.catalog.ProductReplicaCatalogPort;
import dk.ku.di.dms.vms.marketplace.common.catalog.ProductSnapshot;
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
 * ProductService uses; storage access goes through the {@link ProductReplicaCatalogPort}. This
 * class's job is the VMODB-adapter side (VMODB's dispatch annotations) only.
 *
 * The constructor still takes {@link IProductReplicaRepository} directly -- see the matching
 * comment on product's ProductService for why: VMODB decides which tables need a repository
 * proxy built by scanning this class's own constructor, with no other mechanism to ask for one.
 * The repository is wrapped into {@link VmodbProductReplicaCatalogAdapter} right here, and never
 * appears in any method body below.
 */
@Microservice("cart")
public final class CartService {

    private static final System.Logger LOGGER = System.getLogger(CartService.class.getName());

    private final ProductReplicaCatalogPort productReplicaCatalog;
    private final PriceUpdatePolicy priceUpdatePolicy;

    public CartService(IProductReplicaRepository productReplicaRepository, PriceUpdatePolicy priceUpdatePolicy) {
        this.productReplicaCatalog = new VmodbProductReplicaCatalogAdapter(productReplicaRepository);
        this.priceUpdatePolicy = priceUpdatePolicy;
    }

    @Inbound(values = {PRICE_UPDATED})
    @Transactional(type=RW)
    public void updateProductPrice(PriceUpdated priceUpdated) {
        LOGGER.log(DEBUG, "APP: Cart received a price update event with version: "+priceUpdated.instanceId);
        Optional<ProductSnapshot> current = this.productReplicaCatalog.find(priceUpdated.sellerId, priceUpdated.productId);
        if (current.isEmpty()) {
            LOGGER.log(DEBUG, "Cart has no product replica with seller ID "+priceUpdated.sellerId+" : product ID "+priceUpdated.productId);
            return;
        }
        ProductSnapshot product = current.get();
        Optional<Float> accepted = this.priceUpdatePolicy.evaluate(product.version(), priceUpdated.version, priceUpdated.price);
        if (accepted.isPresent()) {
            this.productReplicaCatalog.save(new ProductSnapshot(product.sellerId(), product.productId(), product.name(),
                    accepted.get(), product.status(), product.version()));
        }
    }

    @Inbound(values = {PRODUCT_UPDATED})
    @Transactional(type=W)
    @PartitionBy(clazz = ProductUpdated.class, method = "getId")
    public void processProductUpdate(ProductUpdated productUpdated) {
        LOGGER.log(DEBUG, "APP: Cart received a product update event with version: "+productUpdated.version);
        this.productReplicaCatalog.save(new ProductSnapshot(productUpdated.seller_id, productUpdated.product_id,
                productUpdated.name, productUpdated.price, productUpdated.status, productUpdated.version));
    }

}
