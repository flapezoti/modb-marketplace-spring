#!/usr/bin/env bash
# Starts one module of work/modb-original-marketplace (a sibling repo of this one) in the background.
# usage: start-original.sh <product|cart|proxy>
set -euo pipefail
m="$1"
B="$(cd "$(dirname "$0")/.." && pwd)"
ORIGINAL="${ORIGINAL_REPO:-$(dirname "$(dirname "$B")")/modb-original-marketplace}"
L="$B/run/original"
mkdir -p "$L"
JAVA="${JAVA:-/opt/homebrew/opt/openjdk@21/bin/java}"
FLAGS=(--enable-preview --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/jdk.internal.util=ALL-UNNAMED)
cd "$ORIGINAL"
nohup "$JAVA" "${FLAGS[@]}" -jar "$m/target/$m-1.0-SNAPSHOT-jar-with-dependencies.jar" > "$L/$m.log" 2>&1 &
echo $! > "$L/$m.pid"
echo "$m pid $!"
