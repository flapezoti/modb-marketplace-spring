package dk.ku.di.dms.vms.marketplace.common.inputs;

import dk.ku.di.dms.vms.modb.api.annotations.Event;

@Event
public final class UpdateProduct {

    public int seller_id;

    public int product_id;

    public String name;

    public float price;

    public String status;

    public String version;

    public UpdateProduct(){}

    public UpdateProduct(int seller_id, int product_id, String name, float price, String status, String version) {
        this.seller_id = seller_id;
        this.product_id = product_id;
        this.name = name;
        this.price = price;
        this.status = status;
        this.version = version;
    }

    public ProductId getId(){
        return new ProductId(this.seller_id, this.product_id);
    }

    public record ProductId(int sellerId, int productId){}

}
