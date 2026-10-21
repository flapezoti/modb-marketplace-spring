package dk.ku.di.dms.vms.marketplace.common.catalog;

import java.util.Optional;

/**
 * Product's own catalog storage, as a hexagonal port: no VMODB (or any other infrastructure)
 * types in this contract, so any adapter -- VMODB, Redis, a plain HTTP handler, a test -- can
 * satisfy it the same way. ProductService depends on this, not on VMODB's own repository
 * interface.
 */
public interface ProductCatalogPort {

    Optional<ProductSnapshot> find(int sellerId, int productId);

    void save(ProductSnapshot product);

}
