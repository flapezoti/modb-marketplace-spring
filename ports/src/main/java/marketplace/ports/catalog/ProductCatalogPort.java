package marketplace.ports.catalog;

import java.util.Optional;

/**
 * Product's own catalog storage, as a hexagonal port: no VMODB, Kafka, Redis, or Spring types in
 * this contract, so any adapter can satisfy it the same way.
 */
public interface ProductCatalogPort {

    Optional<ProductSnapshot> find(int sellerId, int productId);

    void save(ProductSnapshot product);

}
