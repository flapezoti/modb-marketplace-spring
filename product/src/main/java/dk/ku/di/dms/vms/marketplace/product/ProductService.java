package dk.ku.di.dms.vms.marketplace.product;

import dk.ku.di.dms.vms.marketplace.common.events.PriceUpdated;
import dk.ku.di.dms.vms.marketplace.common.events.ProductUpdated;
import dk.ku.di.dms.vms.marketplace.common.inputs.PriceUpdate;
import dk.ku.di.dms.vms.marketplace.common.inputs.UpdateProduct;
import dk.ku.di.dms.vms.modb.api.annotations.*;

import static dk.ku.di.dms.vms.marketplace.common.Constants.*;
import static dk.ku.di.dms.vms.modb.api.enums.TransactionTypeEnum.RW;
import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.WARNING;

@Microservice("product")
public final class ProductService {

    private static final System.Logger LOGGER = System.getLogger(ProductService.class.getName());

    private final IProductRepository productRepository;

    public ProductService(IProductRepository productRepository) {
        this.productRepository = productRepository;
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

        // version check guards against applying a stale/out-of-order price update
        if (product.version.contentEquals(priceUpdate.version)) {
            product.price = priceUpdate.price;
            this.productRepository.update(product);
        } else {
            LOGGER.log(WARNING, "APP: Product received an update price event with conflicting version: "+product.version+" != "+priceUpdate.version);
        }

        // must send regardless: Cart's replica needs to see every price update, even conflicting ones,
        // so it can apply the same version check and stay consistent with Product
        return new PriceUpdated(priceUpdate.sellerId, priceUpdate.productId, priceUpdate.price, priceUpdate.version, priceUpdate.instanceId);
    }

}
