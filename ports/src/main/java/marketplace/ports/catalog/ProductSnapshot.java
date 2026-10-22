package marketplace.ports.catalog;

/**
 * A plain, framework-free representation of a product's catalog data -- no VMODB, Kafka, Redis,
 * or Spring types anywhere here.
 */
public record ProductSnapshot(int sellerId, int productId, String name, float price, String status, String version) {
}
