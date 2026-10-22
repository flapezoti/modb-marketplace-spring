package dk.ku.di.dms.vms.marketplace.product;

import marketplace.ports.policy.PriceUpdatePolicy;
import marketplace.ports.policy.VersionMatchPriceUpdatePolicy;
import dk.ku.di.dms.vms.modb.common.transaction.ITransactionManager;
import dk.ku.di.dms.vms.sdk.embed.client.DefaultHttpHandler;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplication;
import dk.ku.di.dms.vms.sdk.embed.client.VmsPreparedApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import vmodb.spring.VmodbBootstrap;
import vmodb.spring.VmodbProperties;

import java.util.Map;

/**
 * Product VMS bootstrapped as a Spring Boot application using vmodb-spring-starter, with
 * ProductService constructed by Spring itself (real @Autowired, full bean lifecycle) rather than
 * by VMODB's own reflection, via VmsApplication.prepare(...)/VmsPreparedApplication#complete(...).
 *
 * preparedVms() is declared here, not in the starter, because VmsApplication.prepare(...) keeps
 * only the @Microservice classes in its direct caller's package (VMODB's
 * ConfigUtils.getCallerPackage()), so the call must be made from this package.
 *
 * ProductQueryController and both test classes depend on IProductRepository/ProductService/
 * VmsApplication purely by type, not on how these beans are constructed.
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
        REPOSITORY = CONTEXT.getBean(IProductRepository.class);
    }

    @Bean
    VmsPreparedApplication preparedVms(VmodbProperties props) throws Exception {
        return VmsApplication.prepare(VmodbBootstrap.buildOptions(props));
    }

    // Kept as its own bean: ProductQueryController still depends on the raw VMODB repository
    // directly, for its own read API, unrelated to ProductService's internal storage access.
    @Bean
    IProductRepository productRepository(VmsPreparedApplication prepared) {
        return VmodbBootstrap.repository(prepared, "products");
    }

    // The actual price-update business rule, framework-free -- see PriceUpdatePolicy. Declared
    // as a @Bean rather than annotated itself, so it stays usable outside Spring too.
    @Bean
    PriceUpdatePolicy priceUpdatePolicy() {
        return new VersionMatchPriceUpdatePolicy();
    }

    // A genuine Spring bean: constructed by Spring's own dependency resolution, not by VMODB's
    // reflection. ProductService itself carries no Spring annotation at all -- it stays exactly
    // as portable/testable as before; only this factory method knows Spring exists. It still
    // takes IProductRepository (see ProductService's own constructor comment for why) and wraps
    // it into VmodbProductCatalogAdapter internally -- the repository type stops there.
    @Bean
    ProductService productService(IProductRepository productRepository, PriceUpdatePolicy priceUpdatePolicy) {
        return new ProductService(productRepository, priceUpdatePolicy);
    }

    @Bean
    VmsApplication vmsApplication(VmsPreparedApplication prepared, ProductService productService) throws Exception {
        return prepared.complete(Map.of(ProductService.class.getName(), productService),
                (transactionManager, repoLookup) ->
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
