package dk.ku.di.dms.vms.marketplace.product;

import dk.ku.di.dms.vms.marketplace.common.catalog.ProductCatalogPort;
import dk.ku.di.dms.vms.marketplace.common.catalog.ProductSnapshot;

import java.util.Optional;

/**
 * The VMODB adapter for {@link ProductCatalogPort}: all VMODB-specific storage access (the
 * repository type, VMODB's annotated {@link Product} entity) lives here, mapped to and from the
 * plain {@link ProductSnapshot}. ProductService itself never sees {@link IProductRepository} or
 * {@link Product}.
 */
public final class VmodbProductCatalogAdapter implements ProductCatalogPort {

    private final IProductRepository repository;

    public VmodbProductCatalogAdapter(IProductRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ProductSnapshot> find(int sellerId, int productId) {
        Product product = this.repository.lookupByKey(new Product.ProductId(sellerId, productId));
        if (product == null) {
            return Optional.empty();
        }
        return Optional.of(new ProductSnapshot(product.seller_id, product.product_id, product.name,
                product.price, product.status, product.version));
    }

    @Override
    public void save(ProductSnapshot snapshot) {
        this.repository.upsert(new Product(snapshot.sellerId(), snapshot.productId(), snapshot.name(),
                snapshot.price(), snapshot.status(), snapshot.version()));
    }

}
