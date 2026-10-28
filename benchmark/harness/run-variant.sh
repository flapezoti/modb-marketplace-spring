#!/usr/bin/env bash
# One full benchmark cycle for one variant, always in the same order:
#   clear VMODB data dirs -> start product+cart -> seed (+verify) -> start coordinator/proxy ->
#   wait for handshake -> wait for CPU idle -> BenchmarkDriver -> stop everything.
# Restarting every process and clearing state each run follows both papers (OM §7.2.1, vMODB §7.1).
# usage: run-variant.sh <original|spring|plain> <concurrency> <epochs> <epochSeconds> <warmupEpochs> [csvFile] [rep]
# The original needs host port 8080: stop the client-project-game-room-manager-1 container first.
set -uo pipefail
v="$1"; conc="$2"; epochs="$3"; epochSecs="$4"; warmup="$5"; csv="${6:-}"; rep="${7:-0}"
S="$(cd "$(dirname "$0")/.." && pwd)"
R="$(dirname "$S")"
JAVA="${JAVA:-/opt/homebrew/opt/openjdk@21/bin/java}"
FLAGS=(--enable-preview --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/jdk.internal.util=ALL-UNNAMED)

case "$v" in
  original) L="$S/run/original"; PP=8081; CP=8080; SCHEMA=full ;;
  spring)   L="$S/run/ours";     PP=18081; CP=18080; SCHEMA=lite ;;
  plain)    L="$S/run/plain";    PP=18081; CP=18080; SCHEMA=lite ;;
  *) echo "unknown variant $v"; exit 2 ;;
esac
mkdir -p "$L"

stop_all() {
  local pids="" pid
  for p in coordinator proxy cart product; do
    if [ -f "$L/$p.pid" ]; then pid="$(cat "$L/$p.pid")"; pids="$pids $pid"; kill "$pid" 2>/dev/null; rm -f "$L/$p.pid"; fi
  done
  # wait until every process has really exited, so the next run starts on free ports
  for pid in $pids; do
    for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill -0 "$pid" 2>/dev/null && { echo "pid $pid ignored SIGTERM, killing"; kill -9 "$pid"; }
  done
  return 0
}
wait_listen() { # port, seconds
  for _ in $(seq 1 $(( $2 * 2 ))); do
    lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1 && return 0; sleep 0.5
  done
  echo "port $1 never listened"; return 1
}

for port in $PP $CP 8091; do
  if lsof -nP -iTCP:$port -sTCP:LISTEN >/dev/null 2>&1; then echo "port $port busy"; exit 1; fi
done
rm -rf ~/vms ~/vms_product ~/vms_cart
rm -f "$L/driver.started"

case "$v" in
  original) "$S/launch/start-original.sh" product; "$S/launch/start-original.sh" cart ;;
  # decision 2: the original's own app.properties values (product max_sleep=100, cart 250)
  spring)   "$S/launch/start-ours.sh" product --vmodb.logging=true --vmodb.checkpointing=true --vmodb.max-sleep=100
            "$S/launch/start-ours.sh" cart --vmodb.logging=true --vmodb.checkpointing=true --vmodb.max-sleep=250 ;;
  plain)    "$S/launch/start-plain.sh" product; "$S/launch/start-plain.sh" cart ;;
esac
wait_listen $PP 60 && wait_listen $CP 60 || { stop_all; exit 1; }
echo "--- product properties: $(grep -A1 '^Properties:' "$L/product.log" | tail -1)"
echo "--- cart properties:    $(grep -A1 '^Properties:' "$L/cart.log" | tail -1)"

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

COORD=coordinator; [ "$v" = original ] && COORD=proxy
cpu_secs() { ps -o time= -p "$1" | awk -F: '{s=0; for(i=1;i<=NF;i++) s=s*60+$i; print s}'; }

# OM §7.2.1: "after data population, we ensure CPU usage returns to idle before initializing
# transaction submission". Whole-machine idle can never be reached here: the coordinator/proxy
# busy-polls one core by design (max_sleep=0, the original's own setting). So idle = product and
# cart each using < 0.05 CPU-s over a 1 s window (seeding work and its JIT done), for at most 30s.
idle=no
for _ in $(seq 1 30); do
  p0=$(cpu_secs "$(cat "$L/product.pid")"); c0=$(cpu_secs "$(cat "$L/cart.pid")"); sleep 1
  p1=$(cpu_secs "$(cat "$L/product.pid")"); c1=$(cpu_secs "$(cat "$L/cart.pid")")
  if awk -v a="$p0" -v b="$p1" -v c="$c0" -v d="$c1" 'BEGIN{exit !((b-a) < 0.05 && (d-c) < 0.05)}'; then idle=yes; break; fi
done
echo "--- product+cart idle before submission: $idle"

out="$L/driver.out"
PIDS="product:$(cat "$L/product.pid"),cart:$(cat "$L/cart.pid"),coordinator:$(cat "$L/$COORD.pid")"
"$JAVA" -Dbench.pids="$PIDS" "$S/driver/BenchmarkDriver.java" http://localhost:8091 "$conc" "$epochs" "$epochSecs" "$warmup" | tee "$out"
rc=${PIPESTATUS[0]}
errors=$(grep -iE 'exception|severe' "$L"/*.log | grep -v AsynchronousClose | grep -vc "driver.out")
echo "--- .llog files written (logging=true): $(ls ~/vms/*.llog 2>/dev/null | wc -l | tr -d ' ')"
echo "--- log errors: $errors"
if [ -n "$csv" ]; then
  [ -f "$csv" ] || echo "variant,rep,concurrency,committed_tps,accepted_rps,backlog_at_end,driver_cores,http_failed,drain,drain_secs,idle_before,log_errors,exit,product_cpu_us_tx,cart_cpu_us_tx,coordinator_cpu_us_tx" > "$csv"
  line=$(grep '^RESULT,' "$out" | sed 's/^RESULT,//')
  [ -n "$line" ] || line="$conc,,,,,,,"
  cpu=$(grep '^CPU_RESULT,' "$out" | sed 's/^CPU_RESULT//')
  [ -n "$cpu" ] || cpu=",,,"
  echo "$v,$rep,$line,$idle,$errors,$rc$cpu" >> "$csv"
fi
stop_all
exit $rc
