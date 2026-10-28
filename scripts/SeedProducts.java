import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Step 5 of the performance benchmark plan (Claude outputs/vmodb-performance-benchmark-plan.md):
 * seeds Product's table with the same data set on every variant (original, ours with Spring, ours
 * without Spring), then reads every row back by key to verify it.
 *
 * Data set follows the official driver's modb_local.json and its synthetic generator
 * (BaseDataGenerator.GenerateProduct): numProdPerSeller=10, composite key (seller_id 1..S,
 * product_id 1..10 within each seller), initial version "0", status "approved". Only Product is
 * seeded -- Cart's replica is not, per the paper's §5 data-population procedure.
 *
 * Every stored string is <= 32 chars, since VMODB silently truncates longer ones. Every name is
 * unique ("p-<seller>-<product>"), so the read-back also catches two keys colliding on
 * hashCode() (VMODB identifies keys by hash only): a collision would return the other row.
 *
 * Must run AFTER product starts but BEFORE the coordinator connects: a VMS stops answering HTTP
 * once a coordinator session is active.
 *
 * Run with: java SeedProducts.java [productBaseUrl] [numSellers] [productsPerSeller] [full|lite]
 *   defaults: http://localhost:8081 1000 10 full
 *
 * "full" sends the original implementation's 10-field Product (as the official generator does);
 * "lite" sends only the 6 fields this repo's Product has (seller_id, product_id, name, price,
 * status, version) -- its VMODB serdes (Jackson) rejects unknown fields. Keys, names, prices,
 * status and version are identical in both.
 */
public class SeedProducts {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final Pattern SELLER_PATTERN = Pattern.compile("\"seller_id\":\"?(\\d+)");
    private static final Pattern PRODUCT_PATTERN = Pattern.compile("\"product_id\":\"?(\\d+)");
    private static final Pattern NAME_PATTERN = Pattern.compile("\"name\":\"([^\"]*)\"");

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8081";
        int numSellers = args.length > 1 ? Integer.parseInt(args[1]) : 1000;
        int perSeller = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        boolean full = args.length <= 3 || !args[3].equals("lite");

        long start = System.nanoTime();
        for (int s = 1; s <= numSellers; s++) {
            for (int p = 1; p <= perSeller; p++) {
                HttpResponse<String> resp = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/product"))
                        .timeout(Duration.ofSeconds(5))
                        .POST(HttpRequest.BodyPublishers.ofString(productJson(s, p, full)))
                        .build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new IllegalStateException("POST " + s + "/" + p + " -> HTTP " + resp.statusCode() + ": " + resp.body());
                }
            }
        }
        System.out.printf("Seeded %d products (%s schema) in %.1fs%n", numSellers * perSeller,
                full ? "full" : "lite", (System.nanoTime() - start) / 1e9);

        int ok = 0, missing = 0, wrong = 0;
        for (int s = 1; s <= numSellers; s++) {
            for (int p = 1; p <= perSeller; p++) {
                HttpResponse<String> resp = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/product/" + s + "/" + p))
                        .timeout(Duration.ofSeconds(5))
                        .header("Accept", "application/json")
                        .GET()
                        .build(), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    if (missing++ < 5) System.out.println("MISSING " + s + "/" + p + " -> HTTP " + resp.statusCode());
                    continue;
                }
                String body = resp.body();
                boolean matches = s == extractInt(SELLER_PATTERN, body)
                        && p == extractInt(PRODUCT_PATTERN, body)
                        && name(s, p).equals(extract(NAME_PATTERN, body));
                if (matches) {
                    ok++;
                } else if (wrong++ < 5) {
                    System.out.println("WRONG ROW for " + s + "/" + p + ": " + body);
                }
            }
        }
        System.out.printf("Verified: %d ok, %d missing, %d wrong row (of %d)%n", ok, missing, wrong, numSellers * perSeller);
        if (ok != numSellers * perSeller) System.exit(1);
    }

    private static String name(int s, int p) {
        return "p-" + s + "-" + p;
    }

    private static String productJson(int s, int p, boolean full) {
        float price = 10 + ((s * 10 + p) % 90);
        return "{\"seller_id\":" + s
                + ",\"product_id\":" + p
                + ",\"name\":\"" + name(s, p) + "\""
                + (full ? ",\"sku\":\"" + String.format("SKU%013d", s * 100 + p) + "\""
                        + ",\"category\":\"bench\""
                        + ",\"description\":\"seeded for benchmark\"" : "")
                + ",\"price\":" + price
                + (full ? ",\"freight_value\":5.0" : "")
                + ",\"status\":\"approved\""
                + ",\"version\":\"0\"}";
    }

    private static int extractInt(Pattern pattern, String body) {
        String v = extract(pattern, body);
        return v == null ? -1 : Integer.parseInt(v);
    }

    private static String extract(Pattern pattern, String body) {
        Matcher m = pattern.matcher(body);
        return m.find() ? m.group(1) : null;
    }
}
