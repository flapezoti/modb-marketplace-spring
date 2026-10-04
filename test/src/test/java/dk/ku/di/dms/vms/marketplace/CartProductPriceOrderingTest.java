package dk.ku.di.dms.vms.marketplace;

import dk.ku.di.dms.vms.coordinator.Coordinator;
import dk.ku.di.dms.vms.marketplace.cart.entities.ProductReplica;
import dk.ku.di.dms.vms.marketplace.common.inputs.PriceUpdate;
import dk.ku.di.dms.vms.marketplace.common.inputs.UpdateProduct;
import dk.ku.di.dms.vms.marketplace.product.Product;
import dk.ku.di.dms.vms.modb.common.utils.ConfigUtils;
import org.junit.Assert;
import org.junit.Test;

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
 * for this reason. SpringQueryApiTest covers the same scenario through the Spring MVC query
 * endpoints instead, which do not have this limitation.
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
        Coordinator coordinator = CoordinatorTestSupport.buildAndConnect(properties);

        // step 1: create the product, which must also create Cart's replica
        CoordinatorTestSupport.submit(coordinator, UPDATE_PRODUCT,
                new UpdateProduct(1, 1, "Widget", 10.0F, "active", "1"), UpdateProduct.class);
        sleep(STEP_WAIT_MS);

        Assert.assertEquals(10.0F, getProduct().price, 0.01);
        Assert.assertEquals(10.0F, getReplica().price, 0.01);
        Assert.assertEquals("1", getReplica().version);

        // step 2: matching-version price update must be applied on both Product and Cart
        CoordinatorTestSupport.submit(coordinator, UPDATE_PRICE,
                new PriceUpdate(1, 1, 12.5F, "1", "p1"), PriceUpdate.class);
        sleep(STEP_WAIT_MS);

        Assert.assertEquals(12.5F, getProduct().price, 0.01);
        Assert.assertEquals(12.5F, getReplica().price, 0.01);

        // step 3: stale/conflicting-version price update must be rejected on both sides,
        // even though Product still emits the event and Cart still receives it
        CoordinatorTestSupport.submit(coordinator, UPDATE_PRICE,
                new PriceUpdate(1, 1, 999.0F, "99", "p2"), PriceUpdate.class);
        sleep(STEP_WAIT_MS);

        Assert.assertEquals(12.5F, getProduct().price, 0.01);
        Assert.assertEquals(12.5F, getReplica().price, 0.01);

        Assert.assertEquals(3, coordinator.getLastTidCommitted());
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

}
