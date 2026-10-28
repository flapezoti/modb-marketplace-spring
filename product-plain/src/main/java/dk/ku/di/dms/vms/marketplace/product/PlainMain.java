package dk.ku.di.dms.vms.marketplace.product;

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
 * Product VMS without Spring: the same deep-DI wiring product's Main.java declares as @Beans
 * (prepare, repository proxy, policy, ProductService, complete), done by hand in main(). This
 * is what isolates Spring's own cost in the performance benchmark: the transaction hot path
 * runs the exact same ProductService class either way.
 *
 * Lives in the product module's own package (split across two jars, fine on a plain classpath)
 * because VmsApplication.prepare(...) keeps only the @Microservice classes in its direct
 * caller's package.
 *
 * Settings come from app.properties via VMODB's own ConfigUtils, exactly as the original
 * implementation's Main does; the file is a verbatim copy of the original product module's.
 */
public final class PlainMain {

    // same port as the Spring variant (application-product.yml), which coordinator-launcher's
    // defaults point at
    private static final int VMS_PORT = 18081;

    public static void main(String[] ignoredArgs) throws Exception {
        Properties properties = ConfigUtils.loadProperties();
        VmsApplicationOptions options = VmsApplicationOptions.build(properties, "0.0.0.0", VMS_PORT, new String[]{
                "dk.ku.di.dms.vms.marketplace.product",
                "dk.ku.di.dms.vms.marketplace.common"
        });

        VmsPreparedApplication prepared = VmsApplication.prepare(options);
        IProductRepository repository = (IProductRepository) prepared.getRepositoryProxy("products");
        ProductService productService = new ProductService(repository, new VersionMatchPriceUpdatePolicy());

        VmsApplication vms = prepared.complete(Map.of(ProductService.class.getName(), productService),
                (transactionManager, repoLookup) ->
                        new ProductHttpHandler(transactionManager, (IProductRepository) repoLookup.apply("products")));
        vms.start();
    }

    /** Same seeding/read handler as product's Main.java (private there, so repeated here). */
    private static class ProductHttpHandler extends DefaultHttpHandler {
        private final IProductRepository repository;

        public ProductHttpHandler(ITransactionManager transactionManager,
                                  IProductRepository repository){
            super(transactionManager);
            this.repository = repository;
        }

        @Override
        public void post(String uri, String payload) {
            Product product = SERDES.deserialize(payload, Product.class);
            this.transactionManager.beginTransaction(0, 0, 0, false);
            this.repository.upsert(product);
        }

        @Override
        public Object getAsJson(String uri) {
            String[] split = uri.split("/");
            int sellerId = Integer.parseInt(split[split.length - 2]);
            int productId = Integer.parseInt(split[split.length - 1]);
            this.transactionManager.beginTransaction(0, 0, 0, true);
            return this.repository.lookupByKey(new Product.ProductId(sellerId, productId));
        }

    }

}
