package dk.ku.di.dms.vms.marketplace;

import dk.ku.di.dms.vms.coordinator.Coordinator;
import dk.ku.di.dms.vms.coordinator.options.CoordinatorOptions;
import dk.ku.di.dms.vms.coordinator.transaction.TransactionBootstrap;
import dk.ku.di.dms.vms.coordinator.transaction.TransactionDAG;
import dk.ku.di.dms.vms.coordinator.transaction.TransactionInput;
import dk.ku.di.dms.vms.marketplace.cart.entities.ProductReplica;
import dk.ku.di.dms.vms.marketplace.common.inputs.PriceUpdate;
import dk.ku.di.dms.vms.marketplace.common.inputs.UpdateProduct;
import dk.ku.di.dms.vms.marketplace.product.Product;
import dk.ku.di.dms.vms.modb.common.schema.network.node.IdentifiableNode;
import dk.ku.di.dms.vms.modb.common.schema.network.node.ServerNode;
import dk.ku.di.dms.vms.modb.common.serdes.IVmsSerdesProxy;
import dk.ku.di.dms.vms.modb.common.serdes.VmsSerdesProxyBuilder;
import dk.ku.di.dms.vms.modb.common.utils.ConfigUtils;
import dk.ku.di.dms.vms.web_common.IHttpHandler;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static dk.ku.di.dms.vms.marketplace.common.Constants.*;
import static java.lang.Thread.sleep;

/**
 * End-to-end test of price-update propagation from Product to Cart: Cart's product_replicas
 * table is kept consistent by the TID order in which the coordinator delivers events, with no
 * ordering key, partition assignment or client-side retry logic.
 *
 * State is verified by reading Product's and Cart's repositories directly, in-process,
 * rather than over HTTP: querying a VMS over plain HTTP while it has an active coordinator
 * session returns an empty reply (confirmed with both curl and Java's HttpClient). See
 * Main.TRANSACTION_MANAGER / Main.REPOSITORY in both the product and cart modules, exposed
 * for this reason.
 *
 * Three steps, each waited out to its own batch commit before the next is sent,
 * because product's own price-update handler requires the product to already exist:
 *   1. UPDATE_PRODUCT (seller=1,product=1,price=10.0,version="1") creates the product
 *      AND (via PRODUCT_UPDATED) creates Cart's replica.
 *   2. UPDATE_PRICE with version="1" (matching) is accepted on both sides -> price 12.5.
 *   3. UPDATE_PRICE with version="99" (stale/conflicting) is REJECTED on both sides,
 *      even though Product still emits PriceUpdated for it -- proving the version guard,
 *      not just delivery order, is what Cart's replica actually depends on.
 */
public class CartProductPriceOrderingTest {

    private static final int STEP_WAIT_MS = 3000;

    @Test
    public final void testPriceUpdatePropagatesInOrder() throws Exception {
        dk.ku.di.dms.vms.marketplace.product.Main.main(null);
        dk.ku.di.dms.vms.marketplace.cart.Main.main(null);

        Properties properties = ConfigUtils.loadProperties();
        Coordinator coordinator = buildCoordinator(properties);

        Thread coordinatorThread = new Thread(coordinator);
        coordinatorThread.start();

        int maxSleep = 6;
        do {
            sleep(2000);
            if (coordinator.getConnectedVMSs().size() == 2) break;
            maxSleep--;
        } while (maxSleep > 0);
        if (coordinator.getConnectedVMSs().size() < 2) {
            throw new RuntimeException("VMSs did not connect to coordinator on time");
        }

        // step 1: create the product, which must also create Cart's replica
        submit(coordinator, UPDATE_PRODUCT,
                new UpdateProduct(1, 1, "Widget", 10.0F, "active", "1"), UpdateProduct.class);
        sleep(STEP_WAIT_MS);

        Assert.assertEquals(10.0F, getProduct().price, 0.01);
        Assert.assertEquals(10.0F, getReplica().price, 0.01);
        Assert.assertEquals("1", getReplica().version);

        // step 2: matching-version price update must be applied on both Product and Cart
        submit(coordinator, UPDATE_PRICE,
                new PriceUpdate(1, 1, 12.5F, "1", "p1"), PriceUpdate.class);
        sleep(STEP_WAIT_MS);

        Assert.assertEquals(12.5F, getProduct().price, 0.01);
        Assert.assertEquals(12.5F, getReplica().price, 0.01);

        // step 3: stale/conflicting-version price update must be rejected on both sides,
        // even though Product still emits the event and Cart still receives it
        submit(coordinator, UPDATE_PRICE,
                new PriceUpdate(1, 1, 999.0F, "99", "p2"), PriceUpdate.class);
        sleep(STEP_WAIT_MS);

        Assert.assertEquals(12.5F, getProduct().price, 0.01);
        Assert.assertEquals(12.5F, getReplica().price, 0.01);

        Assert.assertEquals(3, coordinator.getLastTidCommitted());
    }

    private static <T> void submit(Coordinator coordinator, String queue, T payload, Class<T> clazz) {
        IVmsSerdesProxy serdes = VmsSerdesProxyBuilder.build();
        String json = serdes.serialize(payload, clazz);
        TransactionInput.Event event = new TransactionInput.Event(queue, json);
        coordinator.queueTransactionInput(new TransactionInput(queue, event));
    }

    // reading with tid=0 would open a snapshot from BEFORE any coordinator transaction ran;
    // VMS.lastTidFinished() gives the tid of the latest committed transaction instead
    private static Product getProduct() {
        long lastTid = dk.ku.di.dms.vms.marketplace.product.Main.VMS.lastTidFinished();
        dk.ku.di.dms.vms.marketplace.product.Main.TRANSACTION_MANAGER.beginTransaction(lastTid, 0, lastTid, true);
        return dk.ku.di.dms.vms.marketplace.product.Main.REPOSITORY.lookupByKey(new Product.ProductId(1, 1));
    }

    private static ProductReplica getReplica() {
        long lastTid = dk.ku.di.dms.vms.marketplace.cart.Main.VMS.lastTidFinished();
        dk.ku.di.dms.vms.marketplace.cart.Main.TRANSACTION_MANAGER.beginTransaction(lastTid, 0, lastTid, true);
        return dk.ku.di.dms.vms.marketplace.cart.Main.REPOSITORY.lookupByKey(new ProductReplica.ProductId(1, 1));
    }

    private Coordinator buildCoordinator(Properties properties) throws IOException {
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
