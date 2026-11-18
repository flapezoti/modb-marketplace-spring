#!/usr/bin/env bash
# Step 9 sweep: REPS repetitions x concurrency LEVELS x 3 variants, each run a fresh set of
# processes (run-variant.sh). Variant order is rotated per (rep, level), so no variant always runs
# first/last in a block: environment drift then spreads across variants instead of biasing one.
# Per-run logs are kept in logs/ next to the CSV.
# usage: sweep.sh <csvFile> <reps> <level...>
# With DRIVER_HOST set (see run-variant.sh), every run's driver runs on that machine; the sweep
# checks it is reachable first, and records both machines' environments next to the CSV.
set -uo pipefail
# a macOS idle sleep mid-run freezes every process and breaks that run, so keep the machine awake
if [ -z "${CAFFEINATED:-}" ] && command -v caffeinate >/dev/null; then
  CAFFEINATED=1 exec caffeinate -dimsu "$0" "$@"
fi
S="$(cd "$(dirname "$0")" && pwd)"
csv="$1"; reps="$2"; shift 2; levels="$*"
RD="$(cd "$(dirname "$csv")" && pwd)"; mkdir -p "$RD/logs"
if [ -n "${DRIVER_HOST:-}" ]; then
  ssh -n -o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10 "$DRIVER_HOST" true \
    || { echo "cannot ssh to driver host '$DRIVER_HOST' (run ucloud-setup.sh on it after this job's)"; exit 1; }
  "$S/env-record.sh" "$RD/environment-platform.txt"
  ssh -n -o BatchMode=yes "$DRIVER_HOST" "$(printf '%q ' "$S/env-record.sh" "$RD/environment-driver.txt")"
else
  "$S/env-record.sh" "$RD/environment.txt"
fi
VARIANTS=(original spring plain)
k=0
for rep in $(seq 1 "$reps"); do
  for c in $levels; do
    for j in 0 1 2; do
      v=${VARIANTS[$(( (k + j) % 3 ))]}
      for attempt in 1 2; do
        echo "=== $(date +%H:%M:%S) rep=$rep concurrency=$c variant=$v attempt=$attempt"
        before=0; [ -f "$csv" ] && before=$(wc -l < "$csv")
        "$S/run-variant.sh" "$v" "$c" 6 10 2 "$csv" "$rep" > "$RD/last-run.log" 2>&1
        cp "$RD/last-run.log" "$RD/logs/rep${rep}-c${c}-${v}-a${attempt}.log"
        grep -E "THROUGHPUT|LATENCY |DRAIN|FAILURE|log errors|idle before" "$RD/last-run.log"
        after=0; [ -f "$csv" ] && after=$(wc -l < "$csv")
        # no CSV row = the run never reached the driver (e.g. a port still busy); retry once
        [ "$after" -gt "$before" ] && break
        echo "!!! no result row; last lines:"; tail -5 "$RD/last-run.log"; sleep 10
      done
    done
    k=$((k + 1))
  done
done
echo "=== $(date +%H:%M:%S) sweep done"
