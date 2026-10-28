package dk.ku.di.dms.vms.marketplace.cart;

import dk.ku.di.dms.vms.marketplace.cart.entities.ProductReplica;
import dk.ku.di.dms.vms.marketplace.cart.repositories.IProductReplicaRepository;
import dk.ku.di.dms.vms.modb.common.transaction.ITransactionManager;
import dk.ku.di.dms.vms.modb.common.utils.ConfigUtils;
import dk.ku.di.dms.vms.sdk.embed.client.DefaultHttpHandler;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplication;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplicationOptions;
import dk.ku.di.dms.vms.sdk.embed.client.VmsPreparedApplication;
import marketplace.ports.policy.VersionMatchPriceUpdatePolicy;

import java.util.Map;
import java.util.Properties;

/**
 * Cart VMS without Spring -- see product-plain's PlainMain for the full rationale. app.properties
 * is a verbatim copy of the original cart module's.
 */
public final class PlainMain {

    // same port as the Spring variant (application-cart.yml), which coordinator-launcher's
    // defaults point at
    private static final int VMS_PORT = 18080;

    public static void main(String[] ignoredArgs) throws Exception {
        Properties properties = ConfigUtils.loadProperties();
        VmsApplicationOptions options = VmsApplicationOptions.build(properties, "0.0.0.0", VMS_PORT, new String[]{
                "dk.ku.di.dms.vms.marketplace.cart",
                "dk.ku.di.dms.vms.marketplace.common"
        });

        VmsPreparedApplication prepared = VmsApplication.prepare(options);
        IProductReplicaRepository repository = (IProductReplicaRepository) prepared.getRepositoryProxy("product_replicas");
        CartService cartService = new CartService(repository, new VersionMatchPriceUpdatePolicy());

        VmsApplication vms = prepared.complete(Map.of(CartService.class.getName(), cartService),
                (transactionManager, repoLookup) ->
                        new CartHttpHandler(transactionManager, (IProductReplicaRepository) repoLookup.apply("product_replicas")));
        vms.start();
    }

    /** Same read handler as cart's Main.java (private there, so repeated here). */
    private static class CartHttpHandler extends DefaultHttpHandler {
        private final IProductReplicaRepository repository;

        public CartHttpHandler(ITransactionManager transactionManager,
                               IProductReplicaRepository repository){
            super(transactionManager);
            this.repository = repository;
        }

        @Override
        public Object getAsJson(String uri) {
            String[] split = uri.split("/");
            int sellerId = Integer.parseInt(split[split.length - 2]);
            int productId = Integer.parseInt(split[split.length - 1]);
            this.transactionManager.beginTransaction(0, 0, 0, true);
            return this.repository.lookupByKey(new ProductReplica.ProductId(sellerId, productId));
        }
    }

}
