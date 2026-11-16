import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * End-to-end latency, as vMODB §7.1 defines it: "the interval between a transaction being emitted
 * and its commit being received by the client". A 200 does not say which TID a request got, and
 * the coordinator's commit stream (Accept: text/event-stream) never delivers events: ModbHttpServer
 * never adds a subscriber to SSE_CLIENTS, in the original VMODB as in the fork. So a poller thread
 * reads the committed TID every POLL_MS on its own connection and records when it changes.
 *
 * Each request is matched to its TID by acceptance order. The coordinator queues an input before
 * it answers 200, and TIDs are handed out in queue order, so the k-th accepted request has TID
 * (submitted TID at start + k). The drain check below confirms that count. The request's commit
 * time is the first poll that saw a committed TID at or above its TID. This holds even when a
 * backlog makes requests wait several batches; matching to "the next batch after acceptance", as
 * the official driver does (Modb/BatchTrackingUtils), would under-count there.
 * - Bias: the commit is seen up to POLL_MS plus one poll round trip late. That is small next to
 *   batch_window_ms (500 ms).
 * - Ordering: concurrent requests may queue in a slightly different order than their 200s arrive.
 *   That can move a request near a batch edge by one batch. A request whose 200 was lost (the rare
 *   client-side failures, see reportFailure) still takes a TID, which shifts later requests by one.
 * - Scope: latency is reported for requests sent during the measured epochs.
 *
 * After the run it waits for the backlog to drain, so a run where submitted inputs never commit is
 * a failure, not a low number. The machine-readable lines are RESULT and LATENCY_RESULT.
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
    // the commit poller's own client, so its requests never queue behind the workers' connections
    private static final HttpClient POLL_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final long DRAIN_TIMEOUT_MS = 600_000;
    private static final long POLL_MS = 5;

    /** Growable long array: per-request records without boxing. */
    private static final class LongList {
        long[] a = new long[1 << 16];
        int n;
        void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }
    }

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
        long[] measureFrom = new long[1];

        // per worker: acceptance index k and send time of each request sent in the measured epochs
        LongList[] acceptIdx = new LongList[concurrency];
        LongList[] sendNanos = new LongList[concurrency];
        Thread[] workers = new Thread[concurrency];
        for (int i = 0; i < concurrency; i++) {
            LongList myIdx = acceptIdx[i] = new LongList();
            LongList mySend = sendNanos[i] = new LongList();
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
                    long sendStart = System.nanoTime();
                    try {
                        HttpResponse<Void> resp = CLIENT.send(HttpRequest.newBuilder(URI.create(base + "/product"))
                                .timeout(Duration.ofSeconds(5))
                                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                                .build(), HttpResponse.BodyHandlers.discarding());
                        if (resp.statusCode() == 200) {
                            long k = sent.incrementAndGet();
                            if (sendStart >= measureFrom[0]) { myIdx.add(k); mySend.add(sendStart); }
                        } else {
                            reportFailure(failed, sendStart, "HTTP " + resp.statusCode(), body);
                        }
                    } catch (Exception e) {
                        reportFailure(failed, sendStart, e.toString(), body);
                    }
                }
            });
        }

        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpuStart = os.getProcessCpuTime();

        committedAt[0] = tid(base, "committed");
        submittedAt[0] = tid(base, "submitted");

        // commit poller: (time seen, committed TID) at every change, until stopped after the drain
        LongList seenNanos = new LongList();
        LongList seenTid = new LongList();
        AtomicBoolean stopPolling = new AtomicBoolean();
        AtomicLong pollErrors = new AtomicLong();
        AtomicLong latestSeen = new AtomicLong(Long.MIN_VALUE);
        Thread poller = Thread.ofPlatform().start(() -> {
            long last = Long.MIN_VALUE;
            while (!stopPolling.get()) {
                try {
                    long c = tid(POLL_CLIENT, base, "committed");
                    long now = System.nanoTime();
                    if (c != last) { seenNanos.add(now); seenTid.add(c); last = c; latestSeen.set(c); }
                } catch (Exception e) {
                    pollErrors.incrementAndGet();
                }
                try { Thread.sleep(POLL_MS); } catch (InterruptedException e) { return; }
            }
        });

        long start = System.nanoTime();
        nanosAt[0] = start;
        deadline[0] = start + (long) epochs * epochSeconds * 1_000_000_000L;
        measureFrom[0] = start + (long) warmupEpochs * epochSeconds * 1_000_000_000L;
        barrier.await();

        // sample the counters at every epoch boundary
        for (int e = 1; e <= epochs; e++) {
            long boundary = start + (long) e * epochSeconds * 1_000_000_000L;
            long wait = boundary - System.nanoTime();
            if (wait > 0) Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
            nanosAt[e] = System.nanoTime();
            sentAt[e] = sent.get();
            committedAt[e] = tid(base, "committed");
            submittedAt[e] = tid(base, "submitted");
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

        // let the poller see the final commits, then stop it
        long pollWaitStart = System.nanoTime();
        while (drained && latestSeen.get() < committed && System.nanoTime() - pollWaitStart < 5_000_000_000L) Thread.sleep(POLL_MS);
        stopPolling.set(true);
        poller.join(); // after this, the poller's lists are safe to read here
        reportLatency(submittedAt[0], acceptIdx, sendNanos, seenNanos, seenTid, pollErrors.get(), warmupEpochs, epochs);
        if (!drained || failed.get() > 0) System.exit(1);
    }

    /**
     * Latency of each request sent in the measured epochs: from its send time to the first poll that
     * saw a committed TID >= its TID (submitted TID at start + acceptance index). Prints a summary
     * and LATENCY_RESULT,avg,p50,p75,p90,p99,n in ms (the percentiles the vMODB TPC-C experiment
     * code reports, ExperimentUtils).
     */
    private static void reportLatency(long tidAtStart, LongList[] acceptIdx, LongList[] sendNanos, LongList seenNanos,
                                      LongList seenTid, long pollErrors, int warmupEpochs, int epochs) {
        int total = 0;
        for (LongList l : acceptIdx) total += l.n;
        double[] ms = new double[total];
        int n = 0, unmatched = 0;
        for (int w = 0; w < acceptIdx.length; w++) {
            for (int j = 0; j < acceptIdx[w].n; j++) {
                long tid = tidAtStart + acceptIdx[w].a[j];
                // first change whose committed TID reaches tid (TIDs only grow)
                int lo = 0, hi = seenTid.n;
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (seenTid.a[mid] >= tid) hi = mid; else lo = mid + 1;
                }
                if (lo == seenTid.n) { unmatched++; continue; }
                ms[n++] = (seenNanos.a[lo] - sendNanos[w].a[j]) / 1e6;
            }
        }
        if (n == 0) {
            System.out.printf("LATENCY: no matched requests (unmatched=%d, poll errors=%d)%n", unmatched, pollErrors);
            System.out.println("LATENCY_RESULT,,,,,,0");
            return;
        }
        double[] sorted = Arrays.copyOf(ms, n);
        Arrays.sort(sorted);
        double avg = Arrays.stream(sorted).average().orElse(Double.NaN);
        System.out.printf("LATENCY (requests sent in epochs %d-%d, n=%d): avg=%.1f p50=%.1f p75=%.1f p90=%.1f p99=%.1f max=%.1f ms"
                        + " (poll every %d ms; unmatched=%d, poll errors=%d)%n",
                warmupEpochs + 1, epochs, n, avg, pct(sorted, 0.50), pct(sorted, 0.75), pct(sorted, 0.90), pct(sorted, 0.99),
                sorted[n - 1], POLL_MS, unmatched, pollErrors);
        System.out.printf("LATENCY_RESULT,%.2f,%.2f,%.2f,%.2f,%.2f,%d%n", avg, pct(sorted, 0.50), pct(sorted, 0.75),
                pct(sorted, 0.90), pct(sorted, 0.99), n);
    }

    /** Nearest-rank percentile of a sorted array. */
    private static double pct(double[] sorted, double p) {
        return sorted[Math.max(0, (int) Math.ceil(p * sorted.length) - 1)];
    }

    // Failures are rare (a few per ~1M requests), so each one is printed: what failed, after how
    // long (a 5 s wait is the request timeout, not an error response), and which input it carried,
    // so it can be matched against the drain count (a lost response still commits).
    private static final int MAX_FAILURES_PRINTED = 20;

    private static void reportFailure(AtomicLong failed, long sendStart, String what, String body) {
        long n = failed.incrementAndGet();
        if (n <= MAX_FAILURES_PRINTED) {
            System.out.printf("FAILURE #%d at %d (epoch ms) after %d ms on %s: %s; request %s%n", n,
                    System.currentTimeMillis(), (System.nanoTime() - sendStart) / 1_000_000,
                    Thread.currentThread().getName(), what, body);
        }
    }

    private static long tid(String base, String which) throws Exception {
        return tid(CLIENT, base, which);
    }

    private static long tid(HttpClient client, String base, String which) throws Exception {
        HttpResponse<byte[]> resp = client.send(HttpRequest.newBuilder(URI.create(base + "/status/" + which))
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
