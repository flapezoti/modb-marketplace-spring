import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Step 7 of the performance benchmark plan (Claude outputs/vmodb-performance-benchmark-plan.md):
 * one driver, run unchanged against the original implementation's proxy and against
 * coordinator-launcher (with either the Spring or the no-Spring product/cart behind it).
 *
 * Mirrors the official driver's CONTINUOUS mode for vMODB (sources/OnlineMarketplaceBenchmark:
 * WorkloadManager.RunContinuous, DefaultSellerWorker.SendUpdatePriceRequest): CONCURRENCY
 * threads start together on a barrier, and each loops sending one PATCH update_price input to
 * <baseUrl>/product, counting a 200 as submitted and immediately sending the next. Each picks a
 * uniformly random seller (1..numSellers), then a uniformly random product of that seller
 * (1..perSeller): the paper's §5 "pick a seller, then one of its products", Uniform. Every update
 * carries version "0", which price updates never change, so every one passes the version check.
 *
 * Methodology from both papers (Online Marketplace §7.2.1, vMODB §7.1): the run is EPOCHS epochs
 * of EPOCH_SECONDS each, and the first WARMUP_EPOCHS are discarded. Throughput comes from the
 * coordinator's committed-TID counter, sampled at every epoch boundary (ingestion is
 * fire-and-forget, so a 200 only means the input was queued). The coordinator commits in batches
 * (batch_window_ms), so each epoch's count is quantized to whole batches. Polling needs
 * "Accept: application/octet-stream" (an 8-byte little-endian long); any other Accept is a 404.
 *
 * Per-epoch numbers are printed so steady state can be checked rather than assumed. The backlog
 * (submitted - committed) shows which side is the bottleneck: if it stays near 0, the system kept
 * up with the driver, so the number measures the driver's send rate, not the system's capacity.
 * The driver's own CPU use is reported too, since it shares the machine with the system under test.
 *
 * Optionally (-Dbench.pids=product:<pid>,cart:<pid>,...) it also reports each listed process's CPU
 * time per committed transaction over the measured epochs, sampled with ps at exactly the same
 * epoch boundaries as the TID counters (ProcessHandle.Info#totalCpuDuration is empty for other
 * processes on macOS). On a single machine whose CPU saturates before the system under test
 * does, this resource cost per transaction is what can still separate the variants.
 *
 * After the run it waits for the backlog to drain, so a run where submitted inputs never commit is
 * a failure, not a low number. The last line is a machine-readable RESULT line.
 *
 * Must run after the coordinator has connected to both VMSes (and after seeding, see
 * SeedProducts). All workload parameters are required arguments: they are Step 8 decisions.
 *
 * Run with: java BenchmarkDriver.java <baseUrl> <concurrency> <epochs> <epochSeconds> <warmupEpochs> [numSellers] [perSeller]
 *   e.g.    java BenchmarkDriver.java http://localhost:8091 16 6 10 2
 *   numSellers/perSeller default to Step 5's data set: 1000 10
 */
public class BenchmarkDriver {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final long DRAIN_TIMEOUT_MS = 600_000;

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: java BenchmarkDriver.java <baseUrl> <concurrency> <epochs> <epochSeconds> <warmupEpochs> [numSellers] [perSeller]");
            System.exit(2);
        }
        String base = args[0];
        int concurrency = Integer.parseInt(args[1]);
        int epochs = Integer.parseInt(args[2]);
        int epochSeconds = Integer.parseInt(args[3]);
        int warmupEpochs = Integer.parseInt(args[4]);
        int numSellers = args.length > 5 ? Integer.parseInt(args[5]) : 1000;
        int perSeller = args.length > 6 ? Integer.parseInt(args[6]) : 10;
        if (warmupEpochs >= epochs) throw new IllegalArgumentException("warmupEpochs must be < epochs");

        AtomicLong sent = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicLong instanceIds = new AtomicLong();
        long[] committedAt = new long[epochs + 1];
        long[] submittedAt = new long[epochs + 1];
        long[] sentAt = new long[epochs + 1];
        long[] nanosAt = new long[epochs + 1];

        // same as the official driver: all workers start together, released by the main thread
        CyclicBarrier barrier = new CyclicBarrier(concurrency + 1);
        long[] deadline = new long[1];

        Thread[] workers = new Thread[concurrency];
        for (int i = 0; i < concurrency; i++) {
            workers[i] = Thread.ofPlatform().start(() -> {
                try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
                ThreadLocalRandom random = ThreadLocalRandom.current();
                while (System.nanoTime() < deadline[0]) {
                    int sellerId = random.nextInt(1, numSellers + 1);
                    int productId = random.nextInt(1, perSeller + 1);
                    float price = random.nextInt(10, 100);
                    String body = "{\"sellerId\":" + sellerId
                            + ",\"productId\":" + productId
                            + ",\"price\":" + price
                            + ",\"version\":\"0\""
                            + ",\"instanceId\":\"" + instanceIds.incrementAndGet() + "\"}";
                    try {
                        HttpResponse<Void> resp = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/product"))
                                .timeout(Duration.ofSeconds(5))
                                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                                .build(), HttpResponse.BodyHandlers.discarding());
                        if (resp.statusCode() == 200) sent.incrementAndGet(); else failed.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    }
                }
            });
        }

        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpuStart = os.getProcessCpuTime();

        committedAt[0] = tid(base, "committed");
        submittedAt[0] = tid(base, "submitted");
        long start = System.nanoTime();
        nanosAt[0] = start;
        deadline[0] = start + (long) epochs * epochSeconds * 1_000_000_000L;
        barrier.await();

        String[] pidSpecs = System.getProperty("bench.pids", "").isEmpty() ? new String[0] : System.getProperty("bench.pids").split(",");
        double[] cpuAtWarmupEnd = new double[pidSpecs.length];
        double[] cpuAtEnd = new double[pidSpecs.length];
        if (warmupEpochs == 0) for (int p = 0; p < pidSpecs.length; p++) cpuAtWarmupEnd[p] = cpuSeconds(pidSpecs[p]);

        // sample the counters at every epoch boundary
        for (int e = 1; e <= epochs; e++) {
            long boundary = start + (long) e * epochSeconds * 1_000_000_000L;
            long wait = boundary - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            nanosAt[e] = System.nanoTime();
            sentAt[e] = sent.get();
            committedAt[e] = tid(base, "committed");
            submittedAt[e] = tid(base, "submitted");
            if (e == warmupEpochs) for (int p = 0; p < pidSpecs.length; p++) cpuAtWarmupEnd[p] = cpuSeconds(pidSpecs[p]);
            if (e == epochs) for (int p = 0; p < pidSpecs.length; p++) cpuAtEnd[p] = cpuSeconds(pidSpecs[p]);
        }
        for (Thread worker : workers) worker.join();
        double driverCores = (os.getProcessCpuTime() - cpuStart) / (double) (nanosAt[epochs] - start);

        System.out.printf("target=%s concurrency=%d epochs=%dx%ds warmup=%d keys=%dx%d cpus=%d%n", base, concurrency,
                epochs, epochSeconds, warmupEpochs, numSellers, perSeller, os.getAvailableProcessors());
        for (int e = 1; e <= epochs; e++) {
            double secs = (nanosAt[e] - nanosAt[e - 1]) / 1e9;
            System.out.printf("epoch %d%s: committed_tps=%.1f accepted_rps=%.1f backlog=%d%n", e, e <= warmupEpochs ? " (warm-up)" : "",
                    (committedAt[e] - committedAt[e - 1]) / secs, (sentAt[e] - sentAt[e - 1]) / secs,
                    submittedAt[e] - committedAt[e]);
        }
        double measuredSecs = (nanosAt[epochs] - nanosAt[warmupEpochs]) / 1e9;
        double committedTps = (committedAt[epochs] - committedAt[warmupEpochs]) / measuredSecs;
        double acceptedRps = (sentAt[epochs] - sentAt[warmupEpochs]) / measuredSecs;
        long backlog = submittedAt[epochs] - committedAt[epochs];
        System.out.printf("HTTP: %d accepted, %d failed; driver used %.2f cores%n", sent.get(), failed.get(), driverCores);
        System.out.printf("THROUGHPUT (epochs %d-%d) committed_tps=%.1f accepted_rps=%.1f backlog_at_end=%d%n",
                warmupEpochs + 1, epochs, committedTps, acceptedRps, backlog);
        long measuredCommits = committedAt[epochs] - committedAt[warmupEpochs];
        StringBuilder cpuColumns = new StringBuilder();
        for (int p = 0; p < pidSpecs.length; p++) {
            String name = pidSpecs[p].split(":")[0];
            double cpu = cpuAtEnd[p] - cpuAtWarmupEnd[p];
            double usPerTx = measuredCommits > 0 ? cpu * 1e6 / measuredCommits : Double.NaN;
            System.out.printf("CPU %s: %.2f cpu-s over epochs %d-%d (%.2f cores), %.2f cpu-us per committed tx%n",
                    name, cpu, warmupEpochs + 1, epochs, cpu / measuredSecs, usPerTx);
            cpuColumns.append(String.format(",%.3f", usPerTx));
        }

        // inputs accepted over HTTP are submitted asynchronously; wait until every accepted one
        // has a TID and every TID has committed
        long target = submittedAt[0] + sent.get();
        long drainStart = System.nanoTime();
        long committed = committedAt[epochs], submitted = submittedAt[epochs];
        while ((submitted < target || committed < submitted) && (System.nanoTime() - drainStart) / 1_000_000 < DRAIN_TIMEOUT_MS) {
            Thread.sleep(100);
            submitted = tid(base, "submitted");
            committed = tid(base, "committed");
        }
        boolean drained = submitted >= target && committed >= submitted;
        double drainSecs = (System.nanoTime() - drainStart) / 1e9;
        System.out.printf("DRAIN %s: submitted=%d (expected %d), committed=%d, after %.2fs%n",
                drained ? "ok" : "TIMED OUT", submitted, target, committed, drainSecs);
        System.out.printf("RESULT,%d,%.1f,%.1f,%d,%.2f,%d,%s,%.2f%n", concurrency, committedTps, acceptedRps, backlog,
                driverCores, failed.get(), drained ? "drained" : "not_drained", drainSecs);
        // cpu-us per committed tx, one column per -Dbench.pids entry, in the order given
        if (pidSpecs.length > 0) System.out.println("CPU_RESULT" + cpuColumns);
        if (!drained || failed.get() > 0) System.exit(1);
    }

    /** Total CPU seconds of "name:pid" so far, from ps's cumulative TIME ([[h:]m:]s.cc). */
    private static double cpuSeconds(String pidSpec) throws Exception {
        String pid = pidSpec.split(":")[1];
        Process ps = new ProcessBuilder("ps", "-o", "time=", "-p", pid).redirectErrorStream(true).start();
        String out = new String(ps.getInputStream().readAllBytes()).trim();
        if (ps.waitFor() != 0 || out.isEmpty()) throw new IllegalStateException("ps failed for " + pidSpec + ": " + out);
        String[] parts = out.split(":");
        double secs = 0;
        for (String part : parts) secs = secs * 60 + Double.parseDouble(part);
        return secs;
    }

    private static long tid(String base, String which) throws Exception {
        HttpResponse<byte[]> resp = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/status/" + which))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/octet-stream")
                .GET()
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200 || resp.body().length != 8) {
            throw new IllegalStateException("GET /status/" + which + " -> HTTP " + resp.statusCode() + ", " + resp.body().length + " bytes");
        }
        return ByteBuffer.wrap(resp.body()).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

}
