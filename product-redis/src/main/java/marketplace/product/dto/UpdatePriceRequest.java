package marketplace.product.dto;

public class UpdatePriceRequest {
    private float price;
    private String version;
    private String instanceId;

    public float getPrice() { return price; }
    public void setPrice(float price) { this.price = price; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
}
