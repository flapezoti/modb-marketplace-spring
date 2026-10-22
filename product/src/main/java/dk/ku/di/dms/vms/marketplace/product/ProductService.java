package dk.ku.di.dms.vms.marketplace.product;

import marketplace.ports.catalog.ProductCatalogPort;
import marketplace.ports.catalog.ProductSnapshot;
import dk.ku.di.dms.vms.marketplace.common.events.PriceUpdated;
import dk.ku.di.dms.vms.marketplace.common.events.ProductUpdated;
import dk.ku.di.dms.vms.marketplace.common.inputs.PriceUpdate;
import dk.ku.di.dms.vms.marketplace.common.inputs.UpdateProduct;
import marketplace.ports.policy.PriceUpdatePolicy;
import dk.ku.di.dms.vms.modb.api.annotations.*;

import java.util.Optional;

import static dk.ku.di.dms.vms.marketplace.common.Constants.*;
import static dk.ku.di.dms.vms.modb.api.enums.TransactionTypeEnum.RW;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

/**
 * @Microservice is the only VMODB-specific surface on this class that does any real work beyond
 * its own methods' annotations: the actual price-update business rule lives in
 * {@link PriceUpdatePolicy}, and storage access goes through the {@link ProductCatalogPort} --
 * both plain interfaces with no VMODB (or Spring) types in their contracts. This class's job is
 * purely the VMODB-adapter side: event construction and the annotations that tell VMODB's
 * coordinator how to dispatch these methods.
 *
 * The constructor still takes {@link IProductRepository} directly, not {@link ProductCatalogPort}
 * -- this is VMODB's own constraint, not a design choice: VMODB decides which tables need a
 * repository proxy built by scanning the @Microservice class's own constructor parameter types,
 * with no other mechanism to ask for one. Hiding IProductRepository behind the port entirely (as
 * a first attempt did) told VMODB this VMS needs no repositories at all, and it silently built
 * none -- breaking even the unrelated productRepository bean ProductQueryController needs. So
 * the repository type is wrapped into {@link VmodbProductCatalogAdapter} right here, in the
 * constructor, and never appears in any method body below.
 */
@Microservice("product")
public final class ProductService {

    private static final System.Logger LOGGER = System.getLogger(ProductService.class.getName());

    private final ProductCatalogPort productCatalog;
    private final PriceUpdatePolicy priceUpdatePolicy;

    public ProductService(IProductRepository productRepository, PriceUpdatePolicy priceUpdatePolicy) {
        this.productCatalog = new VmodbProductCatalogAdapter(productRepository);
        this.priceUpdatePolicy = priceUpdatePolicy;
    }

    @Inbound(values = {UPDATE_PRODUCT})
    @Outbound(PRODUCT_UPDATED)
    @Transactional(type=RW)
    @PartitionBy(clazz = UpdateProduct.class, method = "getId")
    public ProductUpdated updateProduct(UpdateProduct updateProduct) {
        LOGGER.log(DEBUG, "APP: Product received a product update event with version: "+updateProduct.version);

        this.productCatalog.save(new ProductSnapshot(updateProduct.seller_id, updateProduct.product_id,
                updateProduct.name, updateProduct.price, updateProduct.status, updateProduct.version));

        return new ProductUpdated(updateProduct.seller_id, updateProduct.product_id, updateProduct.name,
                updateProduct.price, updateProduct.status, updateProduct.version);
    }

    @Inbound(values = {UPDATE_PRICE})
    @Outbound(PRICE_UPDATED)
    @Transactional(type=RW)
    @PartitionBy(clazz = PriceUpdate.class, method = "getId")
    public PriceUpdated updateProductPrice(PriceUpdate priceUpdate) {
        LOGGER.log(DEBUG, "APP: Product received an update price event with version: "+priceUpdate.instanceId);

        ProductSnapshot product = this.productCatalog.find(priceUpdate.sellerId, priceUpdate.productId)
                .orElseThrow(() -> new RuntimeException("Product not found "+priceUpdate.sellerId+"-"+priceUpdate.productId));

        Optional<Float> accepted = this.priceUpdatePolicy.evaluate(product.version(), priceUpdate.version, priceUpdate.price);
        if (accepted.isPresent()) {
            this.productCatalog.save(new ProductSnapshot(product.sellerId(), product.productId(), product.name(),
                    accepted.get(), product.status(), product.version()));
        } else {
            LOGGER.log(WARNING, "APP: Product received an update price event with conflicting version: "+product.version()+" != "+priceUpdate.version);
        }

        // must send regardless: Cart's replica needs to see every price update, even conflicting ones,
        // so it can apply the same version check and stay consistent with Product
        return new PriceUpdated(priceUpdate.sellerId, priceUpdate.productId, priceUpdate.price, priceUpdate.version, priceUpdate.instanceId);
    }

}
