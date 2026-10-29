#!/usr/bin/env bash
# Startup memory footprint (decision M2, 2026-10-29): for each rep and variant, start product and
# cart on clean state, wait until both are idle, take one memory sample WITH a forced GC (so live
# heap is measured), stop. No seeding, no coordinator, no load: this isolates what each variant
# costs just by being started, and keeps forced GCs out of the load runs (sweep.sh), where they
# would lower later RSS readings. Variant order rotated per rep, as in sweep.sh.
# usage: footprint.sh <csvFile> <reps>
set -uo pipefail
if [ -z "${CAFFEINATED:-}" ] && command -v caffeinate >/dev/null; then
  CAFFEINATED=1 exec caffeinate -dimsu "$0" "$@"
fi
source "$(dirname "$0")/lib.sh"
MEM_CSV="$1"; reps="$2"; conc="-"
VARIANTS=(original spring plain)
for rep in $(seq 1 "$reps"); do
  for j in 0 1 2; do
    v=${VARIANTS[$(( (rep - 1 + j) % 3 ))]}
    variant_setup "$v" || exit 2
    echo "=== $(date +%H:%M:%S) rep=$rep variant=$v"
    check_ports_and_clean || exit 1
    if start_vmses; then
      wait_idle
      echo "--- product+cart idle: $idle"
      mem_sample footprint gc
    fi
    stop_all
  done
done
echo "=== $(date +%H:%M:%S) footprint done"
