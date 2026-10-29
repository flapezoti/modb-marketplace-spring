# Shared helpers for run-variant.sh and footprint.sh (sourced, not run). Callers set v (variant)
# and, for mem_sample, rep, conc and MEM_CSV, then call variant_setup.
S="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
R="$(dirname "$S")"
JAVA="${JAVA:-/opt/homebrew/opt/openjdk@21/bin/java}"
JDK_BIN="$(dirname "$JAVA")"
FLAGS=(--enable-preview --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/jdk.internal.util=ALL-UNNAMED)

variant_setup() { # <variant>: sets L (run dir), PP/CP (product/cart ports), SCHEMA, COORD
  case "$1" in
    original) L="$S/run/original"; PP=8081; CP=8080; SCHEMA=full; COORD=proxy ;;
    spring)   L="$S/run/ours";     PP=18081; CP=18080; SCHEMA=lite; COORD=coordinator ;;
    plain)    L="$S/run/plain";    PP=18081; CP=18080; SCHEMA=lite; COORD=coordinator ;;
    *) echo "unknown variant $1"; return 2 ;;
  esac
  mkdir -p "$L"
}

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

# Fresh state for every run (both papers: OM §7.2.1, vMODB §7.1). Fails if a port is still taken.
check_ports_and_clean() {
  for port in $PP $CP 8091; do
    if lsof -nP -iTCP:$port -sTCP:LISTEN >/dev/null 2>&1; then echo "port $port busy"; return 1; fi
  done
  rm -rf ~/vms ~/vms_product ~/vms_cart
}

# product + cart only; the coordinator/proxy is started by run-variant.sh after seeding.
start_vmses() {
  case "$v" in
    original) "$S/launch/start-original.sh" product; "$S/launch/start-original.sh" cart ;;
    # decision 2: the original's own app.properties values (product max_sleep=100, cart 250)
    spring)   "$S/launch/start-ours.sh" product --vmodb.logging=true --vmodb.checkpointing=true --vmodb.max-sleep=100
              "$S/launch/start-ours.sh" cart --vmodb.logging=true --vmodb.checkpointing=true --vmodb.max-sleep=250 ;;
    plain)    "$S/launch/start-plain.sh" product; "$S/launch/start-plain.sh" cart ;;
  esac
  wait_listen $PP 60 && wait_listen $CP 60
}

cpu_secs() { ps -o time= -p "$1" | awk -F: '{s=0; for(i=1;i<=NF;i++) s=s*60+$i; print s}'; }

# OM §7.2.1: "after data population, we ensure CPU usage returns to idle before initializing
# transaction submission". Whole-machine idle can never be reached here: the coordinator/proxy
# busy-polls one core by design (max_sleep=0, the original's own setting). So idle = product and
# cart each using < 0.05 CPU-s over a 1 s window (startup/seeding work and its JIT done), for at
# most 30 s. Sets idle=yes|no.
wait_idle() {
  local p0 c0 p1 c1
  idle=no
  for _ in $(seq 1 30); do
    p0=$(cpu_secs "$(cat "$L/product.pid")"); c0=$(cpu_secs "$(cat "$L/cart.pid")"); sleep 1
    p1=$(cpu_secs "$(cat "$L/product.pid")"); c1=$(cpu_secs "$(cat "$L/cart.pid")")
    if awk -v a="$p0" -v b="$p1" -v c="$c0" -v d="$c1" 'BEGIN{exit !((b-a) < 0.05 && (d-c) < 0.05)}'; then idle=yes; return 0; fi
  done
}

# One memory sample of every running process of this run, appended to MEM_CSV:
#   rss_kb             resident memory as the OS sees it: heap, VMODB's off-heap/mapped tables,
#                      metaspace, thread stacks, JIT code. Read first, before any forced GC.
#   heap_used_kb       heap in use as found, garbage included (jcmd GC.heap_info)
#   heap_live_kb       heap in use right after a full GC (jcmd GC.run), only when <gc> is "gc":
#                      what the application keeps live. Empty otherwise.
#   metaspace_used_kb  class metadata (GC.heap_info); classes_loaded (jstat -class). Neither needs a GC.
# GC.heap_info reads current values without collecting; jstat's heap and metaspace columns only
# refresh at GC time, so they read stale (checked 2026-10-29: 38.5 vs 44.0 MB heap, 10.0 vs 15.8 MB
# metaspace on the same idle process).
# VMODB keeps its tables outside the Java heap (arena segments / mapped files, StorageUtils), so
# heap alone would miss them; RSS covers them. A forced GC makes the JVM return freed memory to the
# OS and so lowers every later RSS reading: the load runs force one only after the load, and the
# startup live heap comes from footprint.sh instead (decision M2, 2026-10-29). JVM heap sizing is
# left at the defaults (decision of 2026-10-29).
mem_sample() { # <point> <gc|nogc>
  [ -n "$MEM_CSV" ] || return 0
  [ -f "$MEM_CSV" ] || echo "variant,rep,concurrency,point,process,rss_kb,heap_used_kb,heap_live_kb,metaspace_used_kb,classes_loaded" > "$MEM_CSV"
  local name pid rss info used live mu cls
  for name in product cart $COORD; do
    [ -f "$L/$name.pid" ] || continue
    pid="$(cat "$L/$name.pid")"
    rss=$(ps -o rss= -p "$pid" | tr -d ' ')
    info=$(heap_info "$pid"); used="${info%,*}"; mu="${info#*,}"
    live=""
    if [ "$2" = gc ]; then
      "$JDK_BIN/jcmd" "$pid" GC.run >/dev/null 2>&1
      info=$(heap_info "$pid"); live="${info%,*}"; mu="${info#*,}"
    fi
    cls=$("$JDK_BIN/jstat" -class "$pid" 2>/dev/null | awk 'NR==2{print $1}')
    echo "$v,$rep,$conc,$1,$name,$rss,$used,$live,$mu,$cls" >> "$MEM_CSV"
    echo "--- memory $1 $name: rss=${rss}KB heap_used=${used}KB heap_live=${live:--}KB metaspace=${mu}KB classes=$cls"
  done
}

heap_info() { # <pid>: "<heap used KB>,<metaspace used KB>" from jcmd GC.heap_info
  "$JDK_BIN/jcmd" "$1" GC.heap_info 2>/dev/null | awk '
    /heap/ && /used/ && heap == "" { if (match($0, /used [0-9]+K/)) heap = substr($0, RSTART+5, RLENGTH-6) }
    /^ *Metaspace/ { if (match($0, /used [0-9]+K/)) meta = substr($0, RSTART+5, RLENGTH-6) }
    END { printf "%s,%s", heap, meta }'
}
