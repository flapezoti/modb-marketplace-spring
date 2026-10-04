package dk.ku.di.dms.vms.marketplace.product;

import dk.ku.di.dms.vms.modb.common.transaction.ITransactionManager;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only product query API, over Spring MVC's own web server (not VMODB's raw HTTP handler --
 * see ProductHttpHandler below in Main.java, used only for seeding). Backed entirely by the
 * productRepository/productService/VmsApplication beans declared in Main, proving they are
 * usable as ordinary Spring dependencies.
 *
 * This also keeps working while a coordinator session is active, unlike a query through VMODB's
 * own HTTP handler.
 */
@RestController
public class ProductQueryController {

    private final IProductRepository repository;
    private final ProductService service;
    private final VmsApplication vms;
    private final ITransactionManager transactionManager;

    public ProductQueryController(IProductRepository repository, ProductService service,
                                  VmsApplication vms, ITransactionManager transactionManager) {
        this.repository = repository;
        this.service = service;
        this.vms = vms;
        this.transactionManager = transactionManager;
    }

    @GetMapping("/products/{sellerId}/{productId}")
    public ResponseEntity<Product> get(@PathVariable int sellerId, @PathVariable int productId) {
        long lastTid = vms.lastTidFinished();
        transactionManager.beginTransaction(lastTid, 0, lastTid, true);
        Product product = repository.lookupByKey(new Product.ProductId(sellerId, productId));
        return product != null ? ResponseEntity.ok(product) : ResponseEntity.notFound().build();
    }

    /**
     * Confirms the @Microservice bean itself is reachable through Spring injection. Only reports
     * on it -- never calls its @Inbound/@Outbound methods, which must run through VMODB's own
     * coordinator-driven scheduler, not as an ordinary method call.
     */
    @GetMapping("/system/info")
    public Map<String, Object> info() {
        return Map.of(
                "vmsServiceClass", service.getClass().getName(),
                "lastTidFinished", vms.lastTidFinished()
        );
    }
}
