package dk.ku.di.dms.vms.marketplace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ku.di.dms.vms.coordinator.Coordinator;
import dk.ku.di.dms.vms.marketplace.common.inputs.PriceUpdate;
import dk.ku.di.dms.vms.marketplace.common.inputs.UpdateProduct;
import dk.ku.di.dms.vms.modb.common.utils.ConfigUtils;
import org.junit.Assert;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Properties;

import static dk.ku.di.dms.vms.marketplace.common.Constants.*;
import static java.lang.Thread.sleep;

/**
 * Proves the productRepository/productService (and cart equivalents) beans declared in Main are
 * real, usable Spring dependencies: ProductQueryController and ReplicaQueryController are
 * ordinary @RestController beans backed entirely by them, exercised here over real HTTP while a
 * coordinator session is active -- the exact scenario in which VMODB's own raw HTTP handler
 * returns an empty reply (see CartProductPriceOrderingTest's Javadoc). The Spring MVC endpoints
 * do not go through VMODB's handler at all, so they keep working.
 *
 * Runs the same three-step scenario as CartProductPriceOrderingTest, verified through
 * GET /products/{sellerId}/{productId} and GET /replicas/{sellerId}/{productId} instead of
 * direct in-process repository access, plus one check that the @Microservice beans
 * (ProductService/CartService) are reachable via GET /system/info.
 */
public class SpringQueryApiTest {

    private static final int STEP_WAIT_MS = 3000;
    private static final int PRODUCT_HTTP_PORT = 48081;
    private static final int CART_HTTP_PORT = 48080;

    @Test
    public final void queryApiReflectsStateWhileCoordinatorIsActive() throws Exception {
        dk.ku.di.dms.vms.marketplace.product.Main.main(null);
        dk.ku.di.dms.vms.marketplace.cart.Main.main(null);

        Properties properties = ConfigUtils.loadProperties();
        Coordinator coordinator = CoordinatorTestSupport.buildAndConnect(properties);

        // step 1: create the product, which must also create Cart's replica
        CoordinatorTestSupport.submit(coordinator, UPDATE_PRODUCT,
                new UpdateProduct(1, 1, "Widget", 10.0F, "active", "1"), UpdateProduct.class);
        sleep(STEP_WAIT_MS);

        Assert.assertEquals(10.0F, getJson(productUrl(1, 1)).get("price").floatValue(), 0.01);
        Assert.assertEquals(10.0F, getJson(replicaUrl(1, 1)).get("price").floatValue(), 0.01);
        Assert.assertEquals("1", getJson(replicaUrl(1, 1)).get("version").asText());

        // step 2: matching-version price update must be applied on both Product and Cart
        CoordinatorTestSupport.submit(coordinator, UPDATE_PRICE,
                new PriceUpdate(1, 1, 12.5F, "1", "p1"), PriceUpdate.class);
        sleep(STEP_WAIT_MS);

        Assert.assertEquals(12.5F, getJson(productUrl(1, 1)).get("price").floatValue(), 0.01);
        Assert.assertEquals(12.5F, getJson(replicaUrl(1, 1)).get("price").floatValue(), 0.01);

        // step 3: stale/conflicting-version price update must be rejected on both sides
        CoordinatorTestSupport.submit(coordinator, UPDATE_PRICE,
                new PriceUpdate(1, 1, 999.0F, "99", "p2"), PriceUpdate.class);
        sleep(STEP_WAIT_MS);

        Assert.assertEquals(12.5F, getJson(productUrl(1, 1)).get("price").floatValue(), 0.01);
        Assert.assertEquals(12.5F, getJson(replicaUrl(1, 1)).get("price").floatValue(), 0.01);

        // the @Microservice beans (not just the repositories) are reachable through Spring DI
        JsonNode productInfo = getJson("http://localhost:" + PRODUCT_HTTP_PORT + "/system/info");
        Assert.assertEquals("dk.ku.di.dms.vms.marketplace.product.ProductService",
                productInfo.get("vmsServiceClass").asText());

        JsonNode cartInfo = getJson("http://localhost:" + CART_HTTP_PORT + "/system/info");
        Assert.assertEquals("dk.ku.di.dms.vms.marketplace.cart.CartService",
                cartInfo.get("vmsServiceClass").asText());
    }

    private static String productUrl(int sellerId, int productId) {
        return "http://localhost:" + PRODUCT_HTTP_PORT + "/products/" + sellerId + "/" + productId;
    }

    private static String replicaUrl(int sellerId, int productId) {
        return "http://localhost:" + CART_HTTP_PORT + "/replicas/" + sellerId + "/" + productId;
    }

    private static JsonNode getJson(String url) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AssertionError("GET " + url + " -> " + response.statusCode() + ": " + response.body());
            }
            return new ObjectMapper().readTree(response.body());
        }
    }

}
