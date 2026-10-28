#!/usr/bin/env bash
# Starts one no-Spring VMS (product-plain|cart-plain) of work/vmodb-marketplace as a plain JVM,
# with the same JVM flags as the other launchers. The classpath includes the product/cart jar
# from ~/.m2, so run `mvn install` after changing product or cart. Classpath cached as in
# start-ours.sh.
# usage: start-plain.sh <product|cart>
set -euo pipefail
m="$1"
B="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(dirname "$B")"
L="$B/run/plain"
mkdir -p "$L"
JAVA="${JAVA:-/opt/homebrew/opt/openjdk@21/bin/java}"
FLAGS=(--enable-preview --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/jdk.internal.util=ALL-UNNAMED)
CPF="$B/run/$m-plain.cp"
[ -f "$CPF" ] || (cd "$REPO/$m-plain" && mvn -q dependency:build-classpath -Dmdep.outputFile="$CPF")
CP="$REPO/$m-plain/target/classes:$(cat "$CPF")"
cd "$REPO"
nohup "$JAVA" "${FLAGS[@]}" -cp "$CP" "dk.ku.di.dms.vms.marketplace.$m.PlainMain" > "$L/$m.log" 2>&1 &
echo $! > "$L/$m.pid"
echo "$m pid $!"
