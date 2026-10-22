package marketplace.cart.event;

/** Cart's own copy of Product's event contract -- deliberately not a shared library. */
public class ProductUpdatedEvent {

    private int sellerId;
    private int productId;
    private String name;
    private float price;
    private String status;
    private String version;

    public ProductUpdatedEvent() {}

    public int getSellerId() { return sellerId; }
    public void setSellerId(int sellerId) { this.sellerId = sellerId; }
    public int getProductId() { return productId; }
    public void setProductId(int productId) { this.productId = productId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public float getPrice() { return price; }
    public void setPrice(float price) { this.price = price; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
}
