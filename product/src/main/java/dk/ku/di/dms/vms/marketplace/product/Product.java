package dk.ku.di.dms.vms.marketplace.product;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dk.ku.di.dms.vms.modb.api.annotations.VmsTable;
import dk.ku.di.dms.vms.modb.api.interfaces.IEntity;

import javax.persistence.Column;
import javax.persistence.Id;
import javax.persistence.IdClass;
import java.io.Serializable;

// IEntity.getId() has a default implementation that throws UnsupportedOperationException, and
// this class does not override it. Jackson's default bean introspection auto-detects that
// public getter-shaped method as an "id" property and calls it when serializing to JSON
// (confirmed: JsonMappingException wrapping the UnsupportedOperationException, through reference
// chain Product["id"]). Ignored here rather than overriding getId(), which nothing in this
// module needs.
@JsonIgnoreProperties({"id"})
@VmsTable(name="products")
@IdClass(Product.ProductId.class)
public final class Product implements IEntity<Product.ProductId> {

    public static class ProductId implements Serializable {
        public int seller_id;
        public int product_id;

        @SuppressWarnings("unused")
        public ProductId(){}

        public ProductId(int seller_id, int product_id) {
            this.seller_id = seller_id;
            this.product_id = product_id;
        }
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

    public Product(){}

    public Product(int seller_id, int product_id, String name, float price, String status, String version) {
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
                + ",\"product_id\":\"" + product_id + "\""
                + ",\"name\":\"" + name + "\""
                + ",\"price\":\"" + price + "\""
                + ",\"status\":\"" + status + "\""
                + ",\"version\":\"" + version + "\""
                + "}";
    }

}
