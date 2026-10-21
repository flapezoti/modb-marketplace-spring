package dk.ku.di.dms.vms.marketplace.cart;

import dk.ku.di.dms.vms.marketplace.cart.entities.ProductReplica;
import dk.ku.di.dms.vms.marketplace.cart.repositories.IProductReplicaRepository;
import dk.ku.di.dms.vms.marketplace.common.catalog.ProductReplicaCatalogPort;
import dk.ku.di.dms.vms.marketplace.common.catalog.ProductSnapshot;

import java.util.Optional;

/**
 * The VMODB adapter for {@link ProductReplicaCatalogPort} -- see the matching
 * {@code VmodbProductCatalogAdapter} in the product module for the full rationale. CartService
 * itself never sees {@link IProductReplicaRepository} or {@link ProductReplica}.
 */
public final class VmodbProductReplicaCatalogAdapter implements ProductReplicaCatalogPort {

    private final IProductReplicaRepository repository;

    public VmodbProductReplicaCatalogAdapter(IProductReplicaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<ProductSnapshot> find(int sellerId, int productId) {
        ProductReplica replica = this.repository.lookupByKey(new ProductReplica.ProductId(sellerId, productId));
        if (replica == null) {
            return Optional.empty();
        }
        return Optional.of(new ProductSnapshot(replica.seller_id, replica.product_id, replica.name,
                replica.price, replica.status, replica.version));
    }

    @Override
    public void save(ProductSnapshot snapshot) {
        this.repository.upsert(new ProductReplica(snapshot.sellerId(), snapshot.productId(), snapshot.name(),
                snapshot.price(), snapshot.status(), snapshot.version()));
    }

}
