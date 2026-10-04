package dk.ku.di.dms.vms.marketplace.cart.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dk.ku.di.dms.vms.modb.api.annotations.VmsTable;
import dk.ku.di.dms.vms.modb.api.interfaces.IEntity;

import javax.persistence.Column;
import javax.persistence.Id;
import javax.persistence.IdClass;
import java.io.Serializable;

// See the matching comment on Product.java: IEntity.getId() throws by default, and this class
// does not override it, so Jackson's getter auto-detection must be told to skip it.
@JsonIgnoreProperties({"id"})
@VmsTable(name="product_replicas")
@IdClass(ProductReplica.ProductId.class)
public final class ProductReplica implements IEntity<ProductReplica.ProductId> {

    public static class ProductId implements Serializable {
        public int seller_id;
        public int product_id;

        public ProductId(){}

        public ProductId(int seller_id, int product_id) {
            this.seller_id = seller_id;
            this.product_id = product_id;
        }
    }

    public ProductId getProductId() {
        return new ProductId( this.seller_id, this.product_id );
    }

    @Id
    public int seller_id;

    @Id
    public int product_id;

    @Column
    public String name;

    @Column
    public float price;

    @Column
    public String status;

    @Column
    public String version;

    public ProductReplica(){}

    public ProductReplica(int seller_id, int product_id, String name, float price, String status, String version) {
        this.seller_id = seller_id;
        this.product_id = product_id;
        this.name = name;
        this.price = price;
        this.status = status;
        this.version = version;
    }

    @Override
    public String toString() {
        return "{"
                + "\"seller_id\":\"" + seller_id + "\""
                + ", \"product_id\":\"" + product_id + "\""
                + ", \"name\":\"" + name + "\""
                + ", \"price\":\"" + price + "\""
                + ", \"status\":\"" + status + "\""
                + ", \"version\":\"" + version + "\""
                + "}";
    }

}
