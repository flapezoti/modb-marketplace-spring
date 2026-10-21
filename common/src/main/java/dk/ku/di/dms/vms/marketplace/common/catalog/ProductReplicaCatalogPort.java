package dk.ku.di.dms.vms.marketplace.common.catalog;

import java.util.Optional;

/**
 * Cart's replica storage, as a port -- see {@link ProductCatalogPort} for the full rationale.
 * Kept as its own interface rather than reusing ProductCatalogPort: Product's catalog and Cart's
 * replica are different aggregates owned by different services, even though they share a data
 * shape today.
 */
public interface ProductReplicaCatalogPort {

    Optional<ProductSnapshot> find(int sellerId, int productId);

    void save(ProductSnapshot product);

}
