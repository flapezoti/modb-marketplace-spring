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

- JDK 21 (default path `/opt/homebrew/opt/openjdk@21/bin/java`; override with `JAVA=...`).
- `mvn install` in this repo (the start scripts take the dependency classpath from `~/.m2`) and
  `mvn package` in `../modb-original-marketplace`, both on JDK 21.
- Ports 8080, 8081, 8091, 18080, 18081 free. **Each run deletes `~/vms`, `~/vms_product` and
  `~/vms_cart`** (VMODB's data directories), following both papers' clean-state-per-run rule.

## Layout

```
benchmark/
  harness/    run-variant.sh, sweep.sh   - what you run
  launch/     start-original.sh, start-ours.sh, start-plain.sh   - start one process (used by run-variant.sh)
  driver/     BenchmarkDriver.java, SeedProducts.java   - load generator and data seeder (single-file Java)
  analysis/   aggregate.py   - turns a sweep CSV into the results table
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
  tx/s and CPU µs per committed transaction for product, cart, coordinator; lists excluded runs.

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

**Status: paused.** The sweep failed a check: 31 of 75 runs had 1–5 requests (out of ~0.6–1.0M)
time out after 5 s, in all three variants, so `aggregate.py` excludes them. The suspected cause
is a write-buffer race in VMODB's `ModbHttpServer`. Supporting evidence: in a re-check after the
move (1 run per variant, 4 threads), each run had 1 timeout and a drain line of
`submitted = expected + 1`, i.e. the server committed the "failed" request and only its response
was lost. Whether to keep the runs, reproduce the
timeouts, or fix the fork and re-run is still open (see the plan's Section 0 log).
