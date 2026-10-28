package marketplace.coordinator;

import dk.ku.di.dms.vms.coordinator.Coordinator;
import dk.ku.di.dms.vms.coordinator.options.CoordinatorOptions;
import dk.ku.di.dms.vms.coordinator.transaction.TransactionBootstrap;
import dk.ku.di.dms.vms.coordinator.transaction.TransactionDAG;
import dk.ku.di.dms.vms.modb.common.schema.network.node.IdentifiableNode;
import dk.ku.di.dms.vms.modb.common.schema.network.node.ServerNode;
import dk.ku.di.dms.vms.modb.common.serdes.VmsSerdesProxyBuilder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Starts VMODB's coordinator as its own standalone, long-running process -- the same role
 * Kafka/Redis play for the Redis/Kafka-backed modules: an external dependency product/cart
 * connect to, with its own lifecycle, started independently of them.
 *
 * Builds the identical update_product/update_price DAG the test module's CoordinatorTestSupport
 * already builds inline for the embedded-in-test scenario (same VMS names, same queue names, same
 * default ports) -- this is that same setup, run as its own process instead of being torn down
 * at the end of a test. Depends on nothing from product/cart/common/ports: the DAG below is
 * built entirely from strings, not Java types, which is what makes this module possible at all.
 */
public final class CoordinatorMain {

    public static void main(String[] args) throws Exception {
        int tcpPort = Integer.getInteger("coordinator.tcp.port", 8091);
        String productHost = System.getProperty("product.host", "localhost");
        String cartHost = System.getProperty("cart.host", "localhost");
        int productPort = Integer.getInteger("product.vms.port", 18081);
        int cartPort = Integer.getInteger("cart.vms.port", 18080);
        int batchWindowMs = Integer.getInteger("coordinator.batch.window.ms", 500);
        int networkThreadPoolSize = Integer.getInteger("coordinator.network.thread.pool.size", 4);

        ServerNode serverIdentifier = new ServerNode("localhost", tcpPort);
        Map<Integer, ServerNode> serverMap = new HashMap<>(1);
        serverMap.put(serverIdentifier.hashCode(), serverIdentifier);

        TransactionDAG updateProductDag = TransactionBootstrap.name("update_product")
                .input("a", "product", "update_product")
                .terminal("b", "cart", "a")
                .build();

        TransactionDAG updatePriceDag = TransactionBootstrap.name("update_price")
                .input("a", "product", "update_price")
                .terminal("b", "cart", "a")
                .build();

        Map<String, TransactionDAG> transactionMap = new HashMap<>();
        transactionMap.put(updateProductDag.name, updateProductDag);
        transactionMap.put(updatePriceDag.name, updatePriceDag);

        IdentifiableNode productAddress = new IdentifiableNode("product", productHost, productPort);
        IdentifiableNode cartAddress = new IdentifiableNode("cart", cartHost, cartPort);

        Map<String, IdentifiableNode> starterVMSs = new HashMap<>(2);
        starterVMSs.put(productAddress.identifier, productAddress);
        starterVMSs.put(cartAddress.identifier, cartAddress);

        // -Dcoordinator.config=<file>: build through the same Coordinator.build(Properties, ...)
        // overload the original implementation's proxy uses, so a benchmark can run both
        // coordinators from identical settings by construction. The file's tcp_port overrides
        // coordinator.tcp.port. Without it, the system-property defaults below apply, as before.
        String configFile = System.getProperty("coordinator.config");
        Coordinator coordinator;
        if (configFile != null) {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(Path.of(configFile))) {
                properties.load(in);
            }
            tcpPort = Integer.parseInt(properties.getProperty("tcp_port"));
            System.out.println("Coordinator settings loaded from " + configFile + ": " + properties);
            coordinator = Coordinator.build(properties, starterVMSs, transactionMap, CoordinatorProxyHttpHandler::new);
        } else {
            coordinator = Coordinator.build(
                    serverMap,
                    starterVMSs,
                    transactionMap,
                    serverIdentifier,
                    new CoordinatorOptions()
                            .withBatchWindow(batchWindowMs)
                            .withNetworkThreadPoolSize(networkThreadPoolSize)
                            .withNetworkBufferSize(0)
                            .withLogging(false),
                    1,
                    1, CoordinatorProxyHttpHandler::new,
                    VmsSerdesProxyBuilder.build()
            );
        }

        System.out.println("Coordinator listening on port " + tcpPort + "; waiting for product ("
                + productHost + ":" + productPort + ") and cart (" + cartHost + ":" + cartPort + ") to connect...");

        // Coordinator is a Runnable; running it directly on the main thread is what keeps this
        // process alive -- there is nothing else for main() to do once it's started.
        coordinator.run();
    }

}
