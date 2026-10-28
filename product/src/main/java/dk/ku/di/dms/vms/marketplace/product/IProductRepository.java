package dk.ku.di.dms.vms.marketplace.product;

import dk.ku.di.dms.vms.modb.api.interfaces.IRepository;
import org.springframework.data.jpa.repository.Query;

public interface IProductRepository extends IRepository<Product.ProductId, Product> {

    // Spring Data's own @Query, not VMODB's -- recognized via VmodbBootstrap.repository(prepared,
    // table, repositoryInterface) in Main.java, parsed and executed by VMODB's own query engine.
    // See vmodb-spring-starter's VmodbBootstrap for the one real limitation: placeholders bind to
    // this method's arguments by position, not by name.
    @Query("select price from products where seller_id = :sellerId and product_id = :productId")
    float getPriceOnly(int sellerId, int productId);
}
