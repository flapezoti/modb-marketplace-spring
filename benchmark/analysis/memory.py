"""Aggregates a memory CSV (harness/lib.sh mem_sample; from sweep.sh as <sweep>-memory.csv, or
from footprint.sh) into mean and a 95% Student-t confidence interval per (point, concurrency,
process, variant), in MB. Repetitions are separate JVM invocations (Georges et al., OOPSLA 2007).
Points: footprint (footprint.sh: started, idle, after a forced GC), started / seeded (load runs,
no forced GC), after_load (load runs, after the drain; the only load-run point with a forced GC,
so the only one with live heap).
usage: python3 memory.py <memory csv>"""
import csv
import statistics
import sys
from collections import defaultdict

T95 = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571, 6: 2.447, 7: 2.365, 8: 2.306, 9: 2.262, 10: 2.228}
POINTS = ["footprint", "started", "seeded", "after_load"]
METRICS = [("rss_kb", "RSS MB", 1024), ("heap_used_kb", "heap used MB", 1024),
           ("heap_live_kb", "live heap MB", 1024), ("metaspace_used_kb", "metaspace MB", 1024),
           ("classes_loaded", "classes", 1)]


def ci(values):
    mean = statistics.mean(values)
    if len(values) < 2:
        return mean, float("nan")
    return mean, T95.get(len(values) - 1, 1.96) * statistics.stdev(values) / len(values) ** 0.5


def conc_key(c):
    return -1 if c == "-" else int(c)


rows = defaultdict(list)
with open(sys.argv[1]) as f:
    for r in csv.DictReader(f):
        rows[(r["point"], r["concurrency"], r["process"], r["variant"])].append(r)

print(f"{'point':10} {'conc':>4} {'process':11} {'variant':9} {'n':>2}" + "".join(f" {label:>18}" for _, label, _ in METRICS))
last = None
for key in sorted(rows, key=lambda k: (POINTS.index(k[0]), conc_key(k[1]), k[2], k[3])):
    if last is not None and key[:3] != last:
        print()
    last = key[:3]
    rs = rows[key]
    line = f"{key[0]:10} {key[1]:>4} {key[2]:11} {key[3]:9} {len(rs):>2}"
    for col, _, div in METRICS:
        values = [float(r[col]) / div for r in rs if r[col] != ""]
        if not values:
            line += f" {'-':>18}"
            continue
        mean, half = ci(values)
        digits = 0 if div == 1 else 1
        line += f" {format(mean, f'.{digits}f') + ' ± ' + format(half, f'.{digits}f'):>18}"
    print(line)
