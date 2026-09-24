package dk.ku.di.dms.vms.marketplace.cart;

import dk.ku.di.dms.vms.marketplace.cart.entities.ProductReplica;
import dk.ku.di.dms.vms.marketplace.cart.repositories.IProductReplicaRepository;
import dk.ku.di.dms.vms.modb.common.transaction.ITransactionManager;
import dk.ku.di.dms.vms.sdk.embed.client.DefaultHttpHandler;
import dk.ku.di.dms.vms.sdk.embed.client.VmsApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import vmodb.spring.VmodbBootstrap;
import vmodb.spring.VmodbProperties;

/** Cart VMS bootstrapped as a Spring Boot application using vmodb-spring-starter. */
@SpringBootApplication
public class Main {

    public static ConfigurableApplicationContext CONTEXT;
    public static VmsApplication VMS;
    public static ITransactionManager TRANSACTION_MANAGER;
    public static IProductReplicaRepository REPOSITORY;

    public static void main(String[] args) {
        // Not named "application.yml" so this config does not collide with the product module's on a shared classpath.
        System.setProperty("spring.config.name", "application-cart");
        CONTEXT = SpringApplication.run(Main.class, args != null ? args : new String[0]);
        VMS = CONTEXT.getBean(VmsApplication.class);
        TRANSACTION_MANAGER = CONTEXT.getBean(ITransactionManager.class);
        REPOSITORY = (IProductReplicaRepository) VMS.getRepositoryProxy("product_replicas");
    }

    @Bean
    VmsApplication vmsApplication(VmodbProperties props) throws Exception {
        return VmsApplication.build(VmodbBootstrap.buildOptions(props), (transactionManager, repoLookup) ->
                new CartHttpHandler(transactionManager, (IProductReplicaRepository) repoLookup.apply("product_replicas")));
    }

    /** Lets a test/client read the product_replicas table directly, to verify what the coordinator delivered. */
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
