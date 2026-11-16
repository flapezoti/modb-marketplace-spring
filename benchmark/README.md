# Performance benchmark harness

Measures what Spring costs a VMODB application, on the Price Update transaction only, by running
three variants through the same driver:

| variant    | what runs                                                                   | ports (product/cart) |
|------------|-----------------------------------------------------------------------------|----------------------|
| `original` | `work/modb-original-marketplace` (original app code, on the forked runtime) + its proxy | 8081 / 8080 |
| `spring`   | this repo's `product`/`cart`, as shipped (Spring Boot, Tomcat on)           | 18081 / 18080        |
| `plain`    | this repo's `product-plain`/`cart-plain` (same services, no Spring on the classpath) | 18081 / 18080 |

All three use port 8091 for the coordinator or proxy that the driver talks to. The full design
(decisions, sources, open questions) is in
`Claude outputs/vmodb-performance-benchmark-plan.md` in the project folder; this file only says
how to run it.

## Prerequisites

- JDK 21: Homebrew's `/opt/homebrew/opt/openjdk@21/bin/java` if present, else `java` on the
  PATH; override with `JAVA=...`.
- `mvn install` in this repo (the start scripts take the dependency classpath from `~/.m2`) and
  `mvn package` in `../modb-original-marketplace`, both on JDK 21.
- `python3` for the analysis scripts.
- Ports 8080, 8081, 8091, 18080, 18081 free (checked with `ss` on Linux, `lsof` on macOS).
  **Each run deletes `~/vms`, `~/vms_product` and `~/vms_cart`** (VMODB's data directories),
  following both papers' clean-state-per-run rule.

Runs on macOS and Linux. On a UCloud job, see "Running on UCloud" below.

## Layout

```
benchmark/
  harness/    run-variant.sh, sweep.sh   - what you run
              footprint.sh, lib.sh   - startup-memory runs; shared helpers
              ucloud-setup.sh, env-record.sh   - prepare a UCloud job; record the environment
  launch/     start-original.sh, start-ours.sh, start-plain.sh   - start one process (used by run-variant.sh)
  driver/     BenchmarkDriver.java, SeedProducts.java   - load generator and data seeder (single-file Java)
  analysis/   aggregate.py   - turns a sweep CSV into the results table
              memory.py   - memory table from the *-memory.csv files
  results/    one folder per date
  run/        runtime state: process logs, pids, cached classpaths (gitignored)
```

- `harness/run-variant.sh <original|spring|plain> <concurrency> <epochs> <epochSecs> <warmupEpochs> [csv] [rep]`:
  one full cycle (clean data, start VMSes, seed 10k products with `driver/SeedProducts.java`,
  start coordinator/proxy, wait for idle, run `driver/BenchmarkDriver.java`, stop everything)
  and append one CSV row.
- `harness/sweep.sh <csv> <reps> <level...>`: `reps` × levels × 3 variants of `run-variant.sh`
  with 6 × 10 s epochs, 2 warm-up; variant order rotated per block; per-run logs go to `logs/`
  next to the CSV. Example: `harness/sweep.sh results/<date>/sweep/sweep.csv 5 1 4 8 16 32`.
- `analysis/aggregate.py <csv>`: mean ± 95% t-interval per (variant, concurrency) for committed
  tx/s and latency (avg, p50, p99); lists excluded runs. October CSVs, which have CPU µs per
  committed transaction per process instead of latency, still aggregate.

### Metrics (decision of 2026-11-16)

The metrics are throughput and end-to-end latency, the two both papers report. Memory per process
is still sampled (`*-memory.csv`, `footprint.sh`). CPU per transaction, the October sweeps' main
metric, is no longer measured.

- **Throughput:** committed TIDs per second over the measured epochs, from the coordinator's
  counter.
- **Latency:** vMODB §7.1, "from a transaction being emitted to its commit being received by the
  client". The driver polls the committed TID every 5 ms on its own connection. Each request's
  TID is the submitted TID at the start plus its acceptance order, and its latency ends at the
  first poll that saw that TID committed.
  - Reported for requests sent in the measured epochs: avg, p50, p75, p90 and p99 in ms.
  - Matching by TID stays correct when a backlog makes requests wait several batches.
  - The official driver's commit stream (`Accept: text/event-stream`) cannot be used: VMODB's
    `ModbHttpServer` never registers a subscriber in `SSE_CLIENTS`, in the original as in the fork.
- **Idle check before the load:** on Linux it reads `/proc`, because Linux `ps` only counts whole
  seconds.
- **Driver load:** `driver_cores` is the driver's own CPU use, for OM §7.2.1's "resource usage
  ... under 80%" check.

## Running on UCloud

Plan and decisions: Section 0 of the plan, 2026-11-11. Stage 1 runs everything on one job.

1. Start a **Terminal Ubuntu** job on `cpu-amd-zen5` with **Enable SSH server** ticked, and the
   folder that holds the four repos added: `vmodb-fork`, `vmodb-spring-starter`,
   `vmodb-marketplace` and `modb-original-marketplace`, at the versions being measured.
2. Run `harness/ucloud-setup.sh /work/<folder>`. It installs JDK 21, Maven, iproute2, python3,
   tmux and iperf3, keeps Maven's repository in `/work/<folder>/.m2`, and builds all four repos.
   Run it in every new job, because UCloud keeps only `/work`.
3. Run `harness/env-record.sh results/<date>/environment-<job>.txt`. It records the CPU, cgroup
   limits, the JVM's default heap and CPU count, and the repo versions. The write-up has to state
   the hardware, since the paper's `u1-standard` no longer exists.
4. Run inside `tmux`, so a dropped SSH session does not stop a sweep. Keep results under `/work`,
   and stop the job when done: it is charged for its vCPUs while it runs, idle or not.

## Results

`results/2026-10-28/`:
- `pilot/`: the 6 × 10 s pilot at concurrency 1, 32, 64 that showed one machine saturates at
  ~25–27k tx/s for every variant (why CPU per transaction became the main metric).
- `checks/`: short pre-sweep checks: `check.csv`, single-trial per-process CPU (`cpu-*.txt`)
  and the idle-check diagnostics (`diag-*.txt`).
- `sweep/`: the full sweep (`sweep-2026-10-28.csv`, `sweep.log`, per-run `logs/`;
  5 reps × concurrency 1,4,8,16,32 × 3 variants).

Files from before 2026-10-28's move into this repo show the session scratchpad paths the
harness ran from at the time, not these paths.

- `repro-before/`: 9 runs (3 per variant, 4 threads) on vmodb-fork `a7c0efd`, the first with the
  driver printing each failed request.
- `diagnostics/`: the logs that identified the HTTP failures (see its README).

## Findings (sweep of 2026-10-28)

The sweep ran on the runtime of that afternoon: vmodb-fork `32337f2`, before the fork commits of
2026-10-28 evening. Every run drained and logged no errors.

**HTTP failures are a driver artifact, so all 75 runs are kept.** 31 runs had 1–5 failed requests
out of ~0.6–1.0M. The diagnostics show the driver's Java HttpClient closing the connection after
VMODB had already committed the request and written exactly one correct reply. The error is
`header parser received no bytes`, within a few ms. `analysis/aggregate.py` keeps these runs and
reports their count per cell.

**On one machine, throughput is machine-bound.** All variants saturate at ~22k tx/s with backlog
~0, so throughput mostly measures the shared machine. CPU per committed transaction, per process,
is the metric that can separate the variants.

**No Spring cost is measurable.** Spring minus plain, total CPU µs per committed transaction
(product + cart + coordinator), mean difference ± 95% Welch interval, n = 5 per cell:

| concurrency | Spring − plain | relative | Spring − original |
|---|---|---|---|
| 1  | +6.7 ± 15.1 | +2.8% ± 6.3% | +0.1% ± 6.2% |
| 4  | +3.7 ± 12.7 | +1.5% ± 5.2% | −0.3% ± 5.0% |
| 8  | +5.1 ± 7.3  | +2.3% ± 3.3% | +0.1% ± 2.6% |
| 16 | +3.2 ± 7.2  | +1.5% ± 3.3% | +0.1% ± 5.0% |
| 32 | −0.5 ± 9.6  | −0.2% ± 4.5% | −2.8% ± 5.6% |

Every interval includes 0, so the defensible claim is an upper bound. At 95% confidence, Spring
adds at most ~4–9% CPU per transaction, depending on concurrency. The point estimates are
positive (+1.5–2.8%) at four of five levels: a hint of a small cost this design cannot resolve.
Throughput differences also all include 0.
