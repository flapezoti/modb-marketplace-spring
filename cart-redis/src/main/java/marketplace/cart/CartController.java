package marketplace.cart;

import marketplace.ports.catalog.ProductReplicaCatalogPort;
import marketplace.ports.catalog.ProductSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/replicas")
public class CartController {

    private final ProductReplicaCatalogPort productReplicaCatalog;

    public CartController(ProductReplicaCatalogPort productReplicaCatalog) {
        this.productReplicaCatalog = productReplicaCatalog;
    }

    @GetMapping("/{sellerId}/{productId}")
    public ResponseEntity<ProductSnapshot> get(@PathVariable int sellerId, @PathVariable int productId) {
        return this.productReplicaCatalog.find(sellerId, productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
