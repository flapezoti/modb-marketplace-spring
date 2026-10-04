package dk.ku.di.dms.vms.marketplace.cart;

import dk.ku.di.dms.vms.marketplace.cart.entities.ProductReplica;
import dk.ku.di.dms.vms.marketplace.cart.repositories.IProductReplicaRepository;
import dk.ku.di.dms.vms.modb.common.transaction.ITransactionManager;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only replica query API, over Spring MVC's own web server (not VMODB's raw HTTP handler --
 * see CartHttpHandler in Main.java). Backed entirely by the productReplicaRepository/cartService/
 * VmsApplication beans declared in Main.
 *
 * Keeps working while a coordinator session is active, unlike a query through VMODB's own HTTP
 * handler.
 */
@RestController
public class ReplicaQueryController {

    private final IProductReplicaRepository repository;
    private final CartService service;
    private final VmsApplication vms;
    private final ITransactionManager transactionManager;

    public ReplicaQueryController(IProductReplicaRepository repository, CartService service,
                                  VmsApplication vms, ITransactionManager transactionManager) {
        this.repository = repository;
        this.service = service;
        this.vms = vms;
        this.transactionManager = transactionManager;
    }

    @GetMapping("/replicas/{sellerId}/{productId}")
    public ResponseEntity<ProductReplica> get(@PathVariable int sellerId, @PathVariable int productId) {
        long lastTid = vms.lastTidFinished();
        transactionManager.beginTransaction(lastTid, 0, lastTid, true);
        ProductReplica replica = repository.lookupByKey(new ProductReplica.ProductId(sellerId, productId));
        return replica != null ? ResponseEntity.ok(replica) : ResponseEntity.notFound().build();
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
