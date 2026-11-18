#!/usr/bin/env bash
# Prepares a fresh UCloud Terminal Ubuntu job (Ubuntu 24.04, passwordless sudo) to run the
# benchmark. Run it at the start of every job: UCloud keeps only /work, so installed packages are
# gone when a job ends.
#
# <root> is a folder under /work holding the four repos side by side, at the versions being
# measured: vmodb-fork, vmodb-spring-starter, vmodb-marketplace, modb-original-marketplace.
# Maven's repository is kept in <root>/.m2 (symlinked from ~/.m2), so later jobs reuse the
# downloads; the build still runs every time, so the jars match the checked-out sources.
#
# Two-job layout (DRIVER_HOST in run-variant.sh): the platform job starts the driver on the driver
# job over ssh. Each job creates an ssh key, publishes its PUBLIC half in <root>/.bench-keys/,
# and authorizes every key published there, so set up the platform job before the driver job (or
# re-run the driver's setup). Only public keys are shared; keys live in ~/.ssh, so they disappear
# when a job stops and the next job makes new ones.
#
# usage: ucloud-setup.sh <root> [--no-build]
#   --no-build: packages and keys only (the driver job, which needs only JDK 21)
set -euo pipefail
[ "$(uname -s)" = Linux ] || { echo "ucloud-setup.sh is for the UCloud Linux jobs only"; exit 2; }
ROOT="$(cd "$1" && pwd)"

sudo apt-get update -y
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y \
    openjdk-21-jdk-headless maven iproute2 procps python3 git tmux iperf3
java -version 2>&1 | head -1

mkdir -p ~/.ssh "$ROOT/.bench-keys"; chmod 700 ~/.ssh
[ -f ~/.ssh/id_ed25519 ] || ssh-keygen -q -t ed25519 -N "" -C "bench-$(hostname)" -f ~/.ssh/id_ed25519
cp ~/.ssh/id_ed25519.pub "$ROOT/.bench-keys/$(hostname).pub"
touch ~/.ssh/authorized_keys; chmod 600 ~/.ssh/authorized_keys
for key in "$ROOT"/.bench-keys/*.pub; do
  grep -qxF "$(cat "$key")" ~/.ssh/authorized_keys || cat "$key" >> ~/.ssh/authorized_keys
done
echo "ssh keys authorized here: $(ls "$ROOT/.bench-keys" | tr '\n' ' ')"

if [ "${2:-}" = --no-build ]; then exit 0; fi

mkdir -p "$ROOT/.m2"
if [ -e ~/.m2 ] && [ ! -L ~/.m2 ]; then
  echo "~/.m2 already exists and is not a symlink; leaving it (downloads will not persist)"
else
  ln -sfn "$ROOT/.m2" ~/.m2
fi

export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
for repo in vmodb-fork vmodb-spring-starter vmodb-marketplace modb-original-marketplace; do
  [ -d "$ROOT/$repo" ] || { echo "missing $ROOT/$repo"; exit 1; }
done
# same order and module lists as the repos' READMEs; tests skipped, they ran on the Mac
(cd "$ROOT/vmodb-fork" && mvn -q -pl modb-api,modb-common,sdk-embed,sdk-core,web_common,coordinator -am \
    clean install -DskipTests=true)
(cd "$ROOT/vmodb-spring-starter" && mvn -q clean install -DskipTests=true)
(cd "$ROOT/vmodb-marketplace" && mvn -q clean install -DskipTests=true)
(cd "$ROOT/modb-original-marketplace" && mvn -q -pl product,cart,proxy,common -am clean package -DskipTests=true)
# cached classpaths hold absolute paths from wherever they were made
rm -f "$ROOT/vmodb-marketplace/benchmark/run/"*.cp
echo "setup done: $(date -u +%FT%TZ)"
