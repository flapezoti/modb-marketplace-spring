package dk.ku.di.dms.vms.marketplace.product;

import dk.ku.di.dms.vms.marketplace.common.events.PriceUpdated;
import dk.ku.di.dms.vms.marketplace.common.events.ProductUpdated;
import dk.ku.di.dms.vms.marketplace.common.inputs.PriceUpdate;
import dk.ku.di.dms.vms.marketplace.common.inputs.UpdateProduct;
import dk.ku.di.dms.vms.marketplace.common.policy.PriceUpdatePolicy;
import dk.ku.di.dms.vms.modb.api.annotations.*;

import java.util.Optional;

import static dk.ku.di.dms.vms.marketplace.common.Constants.*;
import static dk.ku.di.dms.vms.modb.api.enums.TransactionTypeEnum.RW;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

/**
 * @Microservice is the only VMODB-specific surface on this class that does any real work beyond
 * its own methods' annotations: the actual price-update business rule lives in
 * {@link PriceUpdatePolicy}, a plain interface with no VMODB (or Spring) types in its contract.
 * This class's job is purely the VMODB-adapter side: repository I/O, event construction, and the
 * annotations that tell VMODB's coordinator how to dispatch these methods.
 */
@Microservice("product")
public final class ProductService {

    private static final System.Logger LOGGER = System.getLogger(ProductService.class.getName());

    private final IProductRepository productRepository;
    private final PriceUpdatePolicy priceUpdatePolicy;

    public ProductService(IProductRepository productRepository, PriceUpdatePolicy priceUpdatePolicy) {
        this.productRepository = productRepository;
        this.priceUpdatePolicy = priceUpdatePolicy;
    }

    @Inbound(values = {UPDATE_PRODUCT})
    @Outbound(PRODUCT_UPDATED)
    @Transactional(type=RW)
    @PartitionBy(clazz = UpdateProduct.class, method = "getId")
    public ProductUpdated updateProduct(UpdateProduct updateProduct) {
        LOGGER.log(DEBUG, "APP: Product received a product update event with version: "+updateProduct.version);

        Product product = new Product(updateProduct.seller_id, updateProduct.product_id, updateProduct.name,
                updateProduct.price, updateProduct.status, updateProduct.version);
        this.productRepository.upsert(product);

        return new ProductUpdated(updateProduct.seller_id, updateProduct.product_id, updateProduct.name,
                updateProduct.price, updateProduct.status, updateProduct.version);
    }

    @Inbound(values = {UPDATE_PRICE})
    @Outbound(PRICE_UPDATED)
    @Transactional(type=RW)
    @PartitionBy(clazz = PriceUpdate.class, method = "getId")
    public PriceUpdated updateProductPrice(PriceUpdate priceUpdate) {
        LOGGER.log(DEBUG, "APP: Product received an update price event with version: "+priceUpdate.instanceId);

        Product product = this.productRepository.lookupByKey(new Product.ProductId(priceUpdate.sellerId, priceUpdate.productId));
        if (product == null) {
            throw new RuntimeException("Product not found "+priceUpdate.sellerId+"-"+priceUpdate.productId);
        }

        Optional<Float> accepted = this.priceUpdatePolicy.evaluate(product.version, priceUpdate.version, priceUpdate.price);
        if (accepted.isPresent()) {
            product.price = accepted.get();
            this.productRepository.update(product);
        } else {
            LOGGER.log(WARNING, "APP: Product received an update price event with conflicting version: "+product.version+" != "+priceUpdate.version);
        }

        // must send regardless: Cart's replica needs to see every price update, even conflicting ones,
        // so it can apply the same version check and stay consistent with Product
        return new PriceUpdated(priceUpdate.sellerId, priceUpdate.productId, priceUpdate.price, priceUpdate.version, priceUpdate.instanceId);
    }

}
