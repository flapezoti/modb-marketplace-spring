"""Aggregates sweep CSV rows into mean and a 95% Student-t confidence interval per (variant,
concurrency), following Georges et al. (OOPSLA 2007): repetitions are separate JVM invocations,
and with few of them the t distribution (not the normal) gives the interval.
Metrics: committed tx/s and end-to-end latency (avg, p50, p99 in ms), the two metrics both papers
report (decision of 2026-11-16). Sweeps from October 2026 have CPU microseconds per committed tx
per process instead of latency; the table shows whichever metric columns the CSV has.

Runs with a few failed HTTP requests are kept (decision of 2026-10-28): those failures are the
driver's HttpClient losing a connection after the server had already committed the request
("header parser received no bytes"), 1-5 per ~1M requests in every variant, so they are a
measurement artifact, not a system failure; dropping those runs would select runs by outcome.
Their count is reported per cell. Runs that did not drain or logged errors are still excluded.
usage: python3 aggregate.py <csv>"""
import csv
import statistics
import sys
from collections import defaultdict

# two-sided 95% t critical values by degrees of freedom
T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228}
ALL_METRICS = [("committed_tps", "tx/s", 0),
               ("lat_avg_ms", "latency avg ms", 1), ("lat_p50_ms", "latency p50 ms", 1),
               ("lat_p99_ms", "latency p99 ms", 1),
               ("product_cpu_us_tx", "product us/tx", 2), ("cart_cpu_us_tx", "cart us/tx", 2),
               ("coordinator_cpu_us_tx", "coord us/tx", 2)]


def ci(values):
    mean = statistics.mean(values)
    if len(values) < 2:
        return mean, float("nan")
    return mean, T95.get(len(values) - 1, 1.96) * statistics.stdev(values) / len(values) ** 0.5


rows = defaultdict(list)
bad = []
with open(sys.argv[1]) as f:
    reader = csv.DictReader(f)
    METRICS = [m for m in ALL_METRICS if m[0] in reader.fieldnames]
    for r in reader:
        ok = r["drain"] == "drained" and r["log_errors"] == "0" and r["committed_tps"] != ""
        (rows[(r["variant"], int(r["concurrency"]))] if ok else bad).append(r)

header = f"{'variant':9} {'conc':>4} {'n':>2}" + "".join(f" {label:>22}" for _, label, _ in METRICS) + f" {'backlog':>8} {'idle':>5} {'http_failed':>11}"
print(header)
last_c = None
for (v, c) in sorted(rows, key=lambda k: (k[1], k[0])):
    if last_c is not None and c != last_c:
        print()
    last_c = c
    rs = rows[(v, c)]
    line = f"{v:9} {c:>4} {len(rs):>2}"
    for key, _, digits in METRICS:
        values = [float(r[key]) for r in rs if r[key] != ""]
        if not values:
            line += f" {'-':>22}"
            continue
        mean, half = ci(values)
        line += f" {format(mean, f'.{digits}f') + ' ± ' + format(half, f'.{digits}f'):>22}"
    backlog = statistics.mean(float(r["backlog_at_end"]) for r in rs)
    idle = sum(r["idle_before"] == "yes" for r in rs)
    failed = sum(int(r["http_failed"]) for r in rs)
    print(line + f" {backlog:>8.0f} {idle:>2}/{len(rs)} {failed:>11}")

if bad:
    print(f"\n{len(bad)} run(s) excluded (failed checks):")
    for r in bad:
        print("  ", dict(r))
