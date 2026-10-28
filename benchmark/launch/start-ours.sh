#!/usr/bin/env bash
# Starts one VMS of work/vmodb-marketplace as a single plain JVM (no Maven parent process),
# with the same JVM flags as the original's launcher. Extra args are passed to the app
# (e.g. Spring property overrides such as --vmodb.logging=true).
# The dependency classpath is resolved once from ~/.m2 (so run `mvn install` first) and cached
# in run/<module>.cp; delete that file after changing dependencies.
# usage: start-ours.sh <product|cart> [app args...]
set -euo pipefail
m="$1"; shift
B="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(dirname "$B")"
L="$B/run/ours"
mkdir -p "$L"
JAVA="${JAVA:-/opt/homebrew/opt/openjdk@21/bin/java}"
FLAGS=(--enable-preview --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/jdk.internal.util=ALL-UNNAMED)
CPF="$B/run/$m.cp"
[ -f "$CPF" ] || (cd "$REPO/$m" && mvn -q dependency:build-classpath -Dmdep.outputFile="$CPF")
CP="$REPO/$m/target/classes:$(cat "$CPF")"
cd "$REPO"
nohup "$JAVA" "${FLAGS[@]}" -cp "$CP" "dk.ku.di.dms.vms.marketplace.$m.Main" "$@" > "$L/$m.log" 2>&1 &
echo $! > "$L/$m.pid"
echo "$m pid $!"
