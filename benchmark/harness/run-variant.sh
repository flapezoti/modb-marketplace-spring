#!/usr/bin/env bash
# One full benchmark cycle for one variant, always in the same order:
#   clear VMODB data dirs -> start product+cart -> seed (+verify) -> start coordinator/proxy ->
#   wait for handshake -> wait for CPU idle -> BenchmarkDriver -> stop everything.
# Restarting every process and clearing state each run follows both papers (OM §7.2.1, vMODB §7.1).
# usage: run-variant.sh <original|spring|plain> <concurrency> <epochs> <epochSeconds> <warmupEpochs> [csvFile] [rep]
# The original needs host port 8080: stop the client-project-game-room-manager-1 container first.
set -uo pipefail
v="$1"; conc="$2"; epochs="$3"; epochSecs="$4"; warmup="$5"; csv="${6:-}"; rep="${7:-0}"
source "$(dirname "$0")/lib.sh"
variant_setup "$v" || exit 2
MEM_CSV=""; [ -n "$csv" ] && MEM_CSV="${csv%.csv}-memory.csv"

check_ports_and_clean || exit 1
rm -f "$L/driver.started"
start_vmses || { stop_all; exit 1; }
echo "--- product properties: $(grep -A1 '^Properties:' "$L/product.log" | tail -1)"
echo "--- cart properties:    $(grep -A1 '^Properties:' "$L/cart.log" | tail -1)"
# no forced GC before the load: it would lower every later RSS reading (see mem_sample in lib.sh)
mem_sample started nogc

"$JAVA" "$S/driver/SeedProducts.java" "http://localhost:$PP" 1000 10 $SCHEMA || { stop_all; exit 1; }

if [ "$v" = original ]; then
  "$S/launch/start-original.sh" proxy
else
  # cd first, as its own command: "cd X && nohup java ... &" would background the whole list as a
  # subshell, and $! would be that subshell's pid, not java's
  cd "$R/coordinator-launcher"
  nohup "$JAVA" "${FLAGS[@]}" -Dcoordinator.config=benchmark-coordinator.properties \
      -jar target/coordinator-launcher-1.0-SNAPSHOT.jar > "$L/coordinator.log" 2>&1 &
  echo $! > "$L/coordinator.pid"
  cd - >/dev/null
fi
for _ in $(seq 1 60); do grep -q "Setting up consumption from producer" "$L/cart.log" && break; sleep 0.5; done
grep -q "Setting up consumption from producer" "$L/cart.log" || { echo "handshake never completed"; stop_all; exit 1; }
sleep 1

wait_idle
echo "--- product+cart idle before submission: $idle"
mem_sample seeded nogc

out="$L/driver.out"
"$JAVA" "$S/driver/BenchmarkDriver.java" http://localhost:8091 "$conc" "$epochs" "$epochSecs" "$warmup" | tee "$out"
rc=${PIPESTATUS[0]}
# after the measured window and the drain, so this forced GC cannot touch the CPU measurement
mem_sample after_load gc
errors=$(grep -iE 'exception|severe' "$L"/*.log | grep -v AsynchronousClose | grep -vc "driver.out")
echo "--- .llog files written (logging=true): $(ls ~/vms/*.llog 2>/dev/null | wc -l | tr -d ' ')"
echo "--- log errors: $errors"
if [ -n "$csv" ]; then
  [ -f "$csv" ] || echo "variant,rep,concurrency,committed_tps,accepted_rps,backlog_at_end,driver_cores,http_failed,drain,drain_secs,idle_before,log_errors,exit,lat_avg_ms,lat_p50_ms,lat_p75_ms,lat_p90_ms,lat_p99_ms,lat_n" > "$csv"
  line=$(grep '^RESULT,' "$out" | sed 's/^RESULT,//')
  [ -n "$line" ] || line="$conc,,,,,,,"
  lat=$(grep '^LATENCY_RESULT,' "$out" | sed 's/^LATENCY_RESULT//')
  [ -n "$lat" ] || lat=",,,,,,"
  echo "$v,$rep,$line,$idle,$errors,$rc$lat" >> "$csv"
fi
stop_all
exit $rc
