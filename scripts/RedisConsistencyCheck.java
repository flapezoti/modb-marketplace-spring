import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Kafka/Redis-side counterpart to vmodb-marketplace/test's ConcurrentPriceUpdateConsistencyTest
 * -- same experiment (fire CONCURRENCY price updates for the same product at once, check whether
 * Product's own stored price and Cart's replica of it agree afterward), run externally over real
 * HTTP against already-running product-redis/cart-redis, instead of in-process like the VMODB
 * version has to be.
 *
 * Assumes infrastructure is already up: `docker-compose up` (Redis/Kafka) and both
 * product-redis/cart-redis already running on their configured ports. No external dependency
 * beyond the JDK -- plain java.net.http.HttpClient, no JSON library, since the response shape is
 * fixed and simple enough to pull the one field needed (price) out with a regex.
 *
 * Run with: java RedisConsistencyCheck.java
 */
public class RedisConsistencyCheck {

    private static final String PRODUCT_BASE = "http://localhost:58081";
    private static final String CART_BASE = "http://localhost:58080";
    private static final int CONCURRENCY = 5;
    private static final int TRIALS = 10;
    private static final int SETTLE_WAIT_MS = 3000;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Pattern PRICE_PATTERN = Pattern.compile("\"price\":(-?[0-9.]+)");

    public static void main(String[] args) throws Exception {
        // seed the product once; price updates never change the stored version, so every trial
        // below can race against this same "version 1" state without needing to reseed it
        post(PRODUCT_BASE + "/products",
                "{\"sellerId\":1,\"productId\":1,\"name\":\"Widget\",\"price\":10.0,\"status\":\"active\",\"version\":\"1\"}");
        Thread.sleep(SETTLE_WAIT_MS);

        float seedProduct = getPrice(PRODUCT_BASE + "/products/1/1");
        float seedReplica = getPrice(CART_BASE + "/replicas/1/1");
        System.out.println("Seed: product=" + seedProduct + " replica=" + seedReplica);

        int agreements = 0;
        for (int trial = 0; trial < TRIALS; trial++) {
            fireConcurrentPriceUpdates(trial);
            Thread.sleep(SETTLE_WAIT_MS);

            float productPrice = getPrice(PRODUCT_BASE + "/products/1/1");
            float replicaPrice = getPrice(CART_BASE + "/replicas/1/1");
            boolean agree = Float.compare(productPrice, replicaPrice) == 0;
            System.out.println("Trial " + trial + ": product=" + productPrice + " replica=" + replicaPrice
                    + " -> " + (agree ? "AGREE" : "DISAGREE"));
            if (agree) agreements++;
        }

        System.out.println("Kafka/Redis: " + agreements + "/" + TRIALS + " trials agreed between Product and Cart.");
    }

    private static void fireConcurrentPriceUpdates(int trial) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < CONCURRENCY; i++) {
            float price = 1000.0F + (trial * 100) + i;
            String instanceId = "t" + trial + "-" + i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                try {
                    patch(PRODUCT_BASE + "/products/1/1/price",
                            "{\"price\":" + price + ",\"version\":\"1\",\"instanceId\":\"" + instanceId + "\"}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    private static float getPrice(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        Matcher m = PRICE_PATTERN.matcher(resp.body());
        if (!m.find()) {
            throw new IllegalStateException("No price field in response from " + url + ": " + resp.body());
        }
        return Float.parseFloat(m.group(1));
    }

    private static void post(String url, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
    }

    private static void patch(String url, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                .build();
        CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
    }

}
