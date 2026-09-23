package dk.ku.di.dms.vms.marketplace.product;

import dk.ku.di.dms.vms.marketplace.common.Constants;
import dk.ku.di.dms.vms.modb.common.transaction.ITransactionManager;
import dk.ku.di.dms.vms.modb.common.utils.ConfigUtils;
import dk.ku.di.dms.vms.sdk.embed.client.DefaultHttpHandler;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplication;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplicationOptions;

import java.util.Properties;

public final class Main {

    /**
     * Exposed for in-process test verification only: querying a VMS over plain HTTP while it
     * has an active coordinator session returns an empty reply (confirmed with curl and Java's
     * HttpClient alike -- a previously undocumented vMODB constraint, distinct from the ones
     * already catalogued in the parent project's vmodb-framework-bugs memory). Reading directly
     * through the repository, inside a transaction on the calling thread exactly like
     * ProductHttpHandler.getAsJson does, sidesteps that entirely. VMS itself is exposed too,
     * so a reader can pass VMS.lastTidFinished() -- reading with tid=0 while real coordinator
     * transactions have already advanced past it yields a pre-commit (empty) snapshot.
     */
    public static VmsApplication VMS;
    public static ITransactionManager TRANSACTION_MANAGER;
    public static IProductRepository REPOSITORY;

    public static void main(String[] ignoredArgs) throws Exception {
        Properties properties = ConfigUtils.loadProperties();
        VMS = buildVms(properties);
        VMS.start();
    }

    public static VmsApplication buildVms(Properties properties) throws Exception {
        VmsApplicationOptions options = VmsApplicationOptions.build(
                properties,
                "0.0.0.0",
                Constants.PRODUCT_VMS_PORT, new String[]{
                        "dk.ku.di.dms.vms.marketplace.product",
                        "dk.ku.di.dms.vms.marketplace.common"
                });
        return VmsApplication.build(options, (x,y) -> {
            TRANSACTION_MANAGER = x;
            REPOSITORY = (IProductRepository) y.apply("products");
            return new ProductHttpHandler(x, REPOSITORY);
        });
    }

    /** Lets a test/client seed or read Product rows directly (outside the coordinator's transaction flow). */
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
