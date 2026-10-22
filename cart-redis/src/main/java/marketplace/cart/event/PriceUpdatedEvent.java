package marketplace.cart.event;

public class PriceUpdatedEvent {

    private int sellerId;
    private int productId;
    private float price;
    private String version;
    private String instanceId;

    public PriceUpdatedEvent() {}

    public int getSellerId() { return sellerId; }
    public void setSellerId(int sellerId) { this.sellerId = sellerId; }
    public int getProductId() { return productId; }
    public void setProductId(int productId) { this.productId = productId; }
    public float getPrice() { return price; }
    public void setPrice(float price) { this.price = price; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
}
