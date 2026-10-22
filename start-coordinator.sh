#!/bin/sh
# Starts VMODB's coordinator as its own standalone process -- the external dependency
# product/cart connect to, the same role docker-compose.yml's Kafka/Redis play for the
# product-redis/cart-redis modules. Run this first, then start product's and cart's Main
# classes separately; it listens and waits, the same way a broker does.
#
# Usage: ./start-coordinator.sh
# Override defaults with JVM system properties, e.g.:
#   COORD_OPTS="-Dcoordinator.tcp.port=9091" ./start-coordinator.sh

set -e
cd "$(dirname "$0")/coordinator-launcher"

JAR="target/coordinator-launcher-1.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
    echo "Building coordinator-launcher..."
    mvn -q clean package -DskipTests
fi

exec java --enable-preview \
    --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    ${COORD_OPTS} \
    -jar "$JAR"
