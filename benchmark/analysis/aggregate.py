"""Aggregates sweep CSV rows into mean and a 95% Student-t confidence interval per (variant,
concurrency), following Georges et al. (OOPSLA 2007): repetitions are separate JVM invocations,
and with few of them the t distribution (not the normal) gives the interval.
Metrics: committed tx/s, and CPU microseconds per committed tx for product, cart, coordinator.
usage: python3 aggregate.py <csv>"""
import csv
import statistics
import sys
from collections import defaultdict

# two-sided 95% t critical values by degrees of freedom
T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228}
METRICS = [("committed_tps", "tx/s", 0), ("product_cpu_us_tx", "product us/tx", 2),
           ("cart_cpu_us_tx", "cart us/tx", 2), ("coordinator_cpu_us_tx", "coord us/tx", 2)]


def ci(values):
    mean = statistics.mean(values)
    if len(values) < 2:
        return mean, float("nan")
    return mean, T95.get(len(values) - 1, 1.96) * statistics.stdev(values) / len(values) ** 0.5


rows = defaultdict(list)
bad = []
with open(sys.argv[1]) as f:
    for r in csv.DictReader(f):
        ok = r["exit"] == "0" and r["drain"] == "drained" and r["http_failed"] == "0" and r["log_errors"] == "0"
        (rows[(r["variant"], int(r["concurrency"]))] if ok else bad).append(r)

header = f"{'variant':9} {'conc':>4} {'n':>2}" + "".join(f" {label:>22}" for _, label, _ in METRICS) + f" {'backlog':>8} {'idle':>5}"
print(header)
last_c = None
for (v, c) in sorted(rows, key=lambda k: (k[1], k[0])):
    if last_c is not None and c != last_c:
        print()
    last_c = c
    rs = rows[(v, c)]
    line = f"{v:9} {c:>4} {len(rs):>2}"
    for key, _, digits in METRICS:
        mean, half = ci([float(r[key]) for r in rs])
        line += f" {format(mean, f'.{digits}f') + ' ± ' + format(half, f'.{digits}f'):>22}"
    backlog = statistics.mean(float(r["backlog_at_end"]) for r in rs)
    idle = sum(r["idle_before"] == "yes" for r in rs)
    print(line + f" {backlog:>8.0f} {idle:>2}/{len(rs)}")

if bad:
    print(f"\n{len(bad)} run(s) excluded (failed checks):")
    for r in bad:
        print("  ", dict(r))
