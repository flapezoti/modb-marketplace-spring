package dk.ku.di.dms.vms.marketplace;

import dk.ku.di.dms.vms.coordinator.Coordinator;
import dk.ku.di.dms.vms.coordinator.options.CoordinatorOptions;
import dk.ku.di.dms.vms.coordinator.transaction.TransactionBootstrap;
import dk.ku.di.dms.vms.coordinator.transaction.TransactionDAG;
import dk.ku.di.dms.vms.coordinator.transaction.TransactionInput;
import dk.ku.di.dms.vms.modb.common.schema.network.node.IdentifiableNode;
import dk.ku.di.dms.vms.modb.common.schema.network.node.ServerNode;
import dk.ku.di.dms.vms.modb.common.serdes.IVmsSerdesProxy;
import dk.ku.di.dms.vms.modb.common.serdes.VmsSerdesProxyBuilder;
import dk.ku.di.dms.vms.web_common.IHttpHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static dk.ku.di.dms.vms.marketplace.common.Constants.*;

/** Shared coordinator bootstrap for tests that need the Product/Cart update_product + update_price DAGs. */
final class CoordinatorTestSupport {

    private CoordinatorTestSupport() {}

    static Coordinator buildAndConnect(Properties properties) throws Exception {
        Coordinator coordinator = build(properties);
        Thread coordinatorThread = new Thread(coordinator);
        coordinatorThread.start();

        int maxSleep = 6;
        do {
            Thread.sleep(2000);
            if (coordinator.getConnectedVMSs().size() == 2) break;
            maxSleep--;
        } while (maxSleep > 0);
        if (coordinator.getConnectedVMSs().size() < 2) {
            throw new RuntimeException("VMSs did not connect to coordinator on time");
        }
        return coordinator;
    }

    static <T> void submit(Coordinator coordinator, String queue, T payload, Class<T> clazz) {
        IVmsSerdesProxy serdes = VmsSerdesProxyBuilder.build();
        String json = serdes.serialize(payload, clazz);
        TransactionInput.Event event = new TransactionInput.Event(queue, json);
        coordinator.queueTransactionInput(new TransactionInput(queue, event));
    }

    private static Coordinator build(Properties properties) throws IOException {
        int tcpPort = Integer.parseInt(properties.getProperty("tcp_port"));
        ServerNode serverIdentifier = new ServerNode("localhost", tcpPort);

        Map<Integer, ServerNode> serverMap = new HashMap<>(1);
        serverMap.put(serverIdentifier.hashCode(), serverIdentifier);

        TransactionDAG updateProductDag = TransactionBootstrap.name(UPDATE_PRODUCT)
                .input("a", "product", UPDATE_PRODUCT)
                .terminal("b", "cart", "a")
                .build();

        TransactionDAG updatePriceDag = TransactionBootstrap.name(UPDATE_PRICE)
                .input("a", "product", UPDATE_PRICE)
                .terminal("b", "cart", "a")
                .build();

        Map<String, TransactionDAG> transactionMap = new HashMap<>();
        transactionMap.put(updateProductDag.name, updateProductDag);
        transactionMap.put(updatePriceDag.name, updatePriceDag);

        String productHost = properties.getProperty("product_host");
        String cartHost = properties.getProperty("cart_host");

        IdentifiableNode productAddress = new IdentifiableNode("product", productHost, PRODUCT_VMS_PORT);
        IdentifiableNode cartAddress = new IdentifiableNode("cart", cartHost, CART_VMS_PORT);

        Map<String, IdentifiableNode> starterVMSs = new HashMap<>(2);
        starterVMSs.put(productAddress.identifier, productAddress);
        starterVMSs.put(cartAddress.identifier, cartAddress);

        int networkBufferSize = Integer.parseInt(properties.getProperty("network_buffer_size"));
        int batchSendRate = Integer.parseInt(properties.getProperty("batch_window_ms"));
        int groupPoolSize = Integer.parseInt(properties.getProperty("network_thread_pool_size"));

        return Coordinator.build(
                serverMap,
                starterVMSs,
                transactionMap,
                serverIdentifier,
                new CoordinatorOptions()
                        .withBatchWindow(batchSendRate)
                        .withNetworkThreadPoolSize(groupPoolSize)
                        .withNetworkBufferSize(networkBufferSize)
                        .withLogging(false),
                1,
                1, _ -> IHttpHandler.DEFAULT,
                VmsSerdesProxyBuilder.build()
        );
    }
}
