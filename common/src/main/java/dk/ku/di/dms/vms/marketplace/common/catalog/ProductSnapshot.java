package dk.ku.di.dms.vms.marketplace.common.catalog;

/**
 * A plain, framework-free representation of a product's catalog data -- no VMODB (or any other
 * infrastructure) types anywhere here. Both Product's own catalog and Cart's replica of it share
 * this shape today; each still gets its own port ({@link ProductCatalogPort}/
 * {@link ProductReplicaCatalogPort}), since they are different aggregates owned by different
 * services, even though their current data happens to coincide.
 */
public record ProductSnapshot(int sellerId, int productId, String name, float price, String status, String version) {
}
