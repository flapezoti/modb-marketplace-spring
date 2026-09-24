package dk.ku.di.dms.vms.marketplace.product;

import dk.ku.di.dms.vms.modb.common.transaction.ITransactionManager;
import dk.ku.di.dms.vms.sdk.embed.client.DefaultHttpHandler;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import vmodb.spring.VmodbBootstrap;
import vmodb.spring.VmodbProperties;

/**
 * Product VMS bootstrapped as a Spring Boot application using vmodb-spring-starter.
 *
 * The vmsApplication() @Bean method is declared here, not in the starter, because
 * VmsApplication.build() keeps only the @Microservice classes in its direct caller's package
 * (VMODB's ConfigUtils.getCallerPackage()), so the call must be made from this package.
 */
@SpringBootApplication
public class Main {

    // Exposed for in-process test verification only; populated from the Spring context after startup.
    public static ConfigurableApplicationContext CONTEXT;
    public static VmsApplication VMS;
    public static ITransactionManager TRANSACTION_MANAGER;
    public static IProductRepository REPOSITORY;

    public static void main(String[] args) {
        // Not named "application.yml" so this config does not collide with the cart module's on a
        // shared classpath, e.g. when both VMSes run in one JVM for testing.
        System.setProperty("spring.config.name", "application-product");
        CONTEXT = SpringApplication.run(Main.class, args != null ? args : new String[0]);
        VMS = CONTEXT.getBean(VmsApplication.class);
        TRANSACTION_MANAGER = CONTEXT.getBean(ITransactionManager.class);
        REPOSITORY = (IProductRepository) VMS.getRepositoryProxy("products");
    }

    @Bean
    VmsApplication vmsApplication(VmodbProperties props) throws Exception {
        return VmsApplication.build(VmodbBootstrap.buildOptions(props), (transactionManager, repoLookup) ->
                new ProductHttpHandler(transactionManager, (IProductRepository) repoLookup.apply("products")));
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
