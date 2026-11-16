#!/usr/bin/env bash
# Records the environment a set of runs used, next to its results: the hardware cannot match the
# papers' (OM §7.2.1: UCloud u1-standard, Xeon Gold 6130; that product is no longer offered), so
# the write-up has to state what it ran on instead. Includes the JVM's default heap and CPU count
# as it sees them, since the heap is left at its default (decision of 2026-10-29, kept 2026-11-11).
# usage: env-record.sh <outFile>
set -uo pipefail
B="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(dirname "$(dirname "$B")")"
JAVA="${JAVA:-$(command -v /opt/homebrew/opt/openjdk@21/bin/java || command -v java)}"
{
  echo "## recorded $(date -u +%FT%TZ) on $(hostname)"
  echo "## os"; uname -a; [ -f /etc/os-release ] && grep -E '^(PRETTY_NAME|VERSION_ID)=' /etc/os-release
  echo "## cpu"
  if command -v lscpu >/dev/null; then lscpu; else sysctl -n machdep.cpu.brand_string hw.ncpu 2>/dev/null; fi
  echo "nproc: $(getconf _NPROCESSORS_ONLN)"
  echo "## cgroup limits (cpu quota/period, memory bytes)"
  cat /sys/fs/cgroup/cpu.max /sys/fs/cgroup/memory.max 2>/dev/null || echo "n/a"
  echo "## memory"
  if command -v free >/dev/null; then free -m; else sysctl -n hw.memsize 2>/dev/null; fi
  echo "## java"
  "$JAVA" -version 2>&1
  "$JAVA" -XX:+PrintFlagsFinal -version 2>/dev/null | grep -E ' (MaxHeapSize|InitialHeapSize|ActiveProcessorCount|UseG1GC|ParallelGCThreads) '
  echo "## repos (HEAD, dirty files)"
  for repo in vmodb-fork vmodb-spring-starter vmodb-marketplace modb-original-marketplace; do
    [ -d "$ROOT/$repo/.git" ] || { echo "$repo: not found"; continue; }
    echo "$repo: $(git -C "$ROOT/$repo" rev-parse --short HEAD) ($(git -C "$ROOT/$repo" branch --show-current)), $(git -C "$ROOT/$repo" status --porcelain | grep -vc '^??') modified"
  done
  echo "## disk holding ~ (VMODB data dirs)"
  df -h ~ | tail -1
} > "$1" 2>&1
echo "environment written to $1"
