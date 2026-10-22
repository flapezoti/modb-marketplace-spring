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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static dk.ku.di.dms.vms.marketplace.common.Constants.*;
import static java.lang.Thread.sleep;

/**
 * Does NOT test "which price wins" among concurrent price updates -- there is no well-defined
 * correct winner for genuinely concurrent submissions, and asserting one would be testing a
 * coincidence, not a guarantee. What VMODB's coordinator actually guarantees is that every
 * participating VMS sees the *same* order, so Product's own stored price and Cart's replica of
 * it can never disagree, no matter which update happened to "win" the race.
 *
 * Each trial fires CONCURRENCY price updates for the same product, same version, at once (via a
 * CountDownLatch releasing all threads together, not a tight sequential loop), waits for them to
 * settle, then asserts Product's price equals Cart's replica's price. Repeated across TRIALS
 * rounds on the same product (price updates never change the stored version, so the race can be
 * rerun without reseeding) to make a single lucky/unlucky interleaving less likely to be the
 * whole story.
 */
public class ConcurrentPriceUpdateConsistencyTest {

    private static final int CONCURRENCY = 5;
    private static final int TRIALS = 10;
    private static final int SETTLE_WAIT_MS = 2000;

    @Test
    public final void concurrentPriceUpdatesNeverDisagreeBetweenProductAndCart() throws Exception {
        dk.ku.di.dms.vms.marketplace.product.Main.main(null);
        dk.ku.di.dms.vms.marketplace.cart.Main.main(null);

        Properties properties = ConfigUtils.loadProperties();
        Coordinator coordinator = CoordinatorTestSupport.buildAndConnect(properties);

        // seed the product once; price updates never change the stored version, so every trial
        // below can race against this same "version 1" state without needing to reseed it
        CoordinatorTestSupport.submit(coordinator, UPDATE_PRODUCT,
                new UpdateProduct(1, 1, "Widget", 10.0F, "active", "1"), UpdateProduct.class);
        sleep(SETTLE_WAIT_MS);
        Assert.assertEquals(10.0F, getProduct().price, 0.01);
        Assert.assertEquals(10.0F, getReplica().price, 0.01);

        int agreements = 0;
        for (int trial = 0; trial < TRIALS; trial++) {
            fireConcurrentPriceUpdates(coordinator, trial);
            sleep(SETTLE_WAIT_MS);

            float productPrice = getProduct().price;
            float replicaPrice = getReplica().price;
            boolean agree = Float.compare(productPrice, replicaPrice) == 0;
            System.out.println("Trial " + trial + ": product=" + productPrice + " replica=" + replicaPrice
                    + " -> " + (agree ? "AGREE" : "DISAGREE"));
            if (agree) agreements++;
        }

        System.out.println("VMODB: " + agreements + "/" + TRIALS + " trials agreed between Product and Cart.");

        // VMODB's coordinator-enforced ordering means this must be every single trial, not a
        // majority -- any disagreement here is a real regression, not expected flakiness.
        Assert.assertEquals(TRIALS, agreements);
    }

    private static void fireConcurrentPriceUpdates(Coordinator coordinator, int trial) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < CONCURRENCY; i++) {
            // price/instanceId encode (trial, i) only to make submissions distinguishable in
            // logs -- nothing below depends on which one actually wins
            float price = 1000.0F + (trial * 100) + i;
            String instanceId = "t" + trial + "-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                CoordinatorTestSupport.submit(coordinator, UPDATE_PRICE,
                        new PriceUpdate(1, 1, price, "1", instanceId), PriceUpdate.class);
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
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
