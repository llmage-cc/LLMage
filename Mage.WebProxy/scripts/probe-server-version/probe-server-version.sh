#!/usr/bin/env bash
# probe-server-version.sh — connect to an XMage server, print its MageVersion,
# disconnect. Read-only: no tables, no chat, no games. Used by the
# this fork branch to verify wire compatibility with official servers

#
# Usage: bash probe-server-version.sh <host> [port]
#
# Build deps: mage-common from the local m2 repo (built by `mvn install`).
# The probe class is compiled on the fly into a scratch dir.
set -euo pipefail

HOST="${1:?usage: probe-server-version.sh <host> [port]}"
PORT="${2:-17171}"

M2="${M2_REPO:-$HOME/.m2/repository}"
# Artifact version = the root pom's <mage-version> (single source of truth).
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
VERSION="$(awk -F'[<>]' '/<mage-version>/{print $3; exit}' "$REPO/pom.xml" 2>/dev/null || true)"
[ -n "$VERSION" ] || { echo "FAIL: cannot read <mage-version> from $REPO/pom.xml" >&2; exit 1; }
CP="$M2/org/mage/mage-common/$VERSION/mage-common-$VERSION.jar"
CP="$CP:$M2/org/mage/mage/$VERSION/mage-$VERSION.jar"
CP="$CP:$M2/org/jboss/remoting/jboss-remoting/2.5.4.SP5/jboss-remoting-2.5.4.SP5.jar"
CP="$CP:$M2/jboss/jboss-serialization/4.2.2.GA/jboss-serialization-4.2.2.GA.jar"
CP="$CP:$M2/org/jboss/jboss-common-core/2.5.0.Final/jboss-common-core-2.5.0.Final.jar"
CP="$CP:$M2/org/jboss/logging/jboss-logging-spi/2.1.2.GA/jboss-logging-spi-2.1.2.GA.jar"
CP="$CP:$M2/log4j/log4j/1.2.17/log4j-1.2.17.jar"
CP="$CP:$M2/com/google/guava/guava/33.4.8-jre/guava-33.4.8-jre.jar"
CP="$CP:$M2/concurrent/concurrent/1.3.4/concurrent-1.3.4.jar"

SCRATCH="$(mktemp -d)"
trap 'rm -rf "$SCRATCH"' EXIT
cp "$(dirname "$0")/ServerProbe.java" "$SCRATCH/"
javac -cp "$CP" -d "$SCRATCH" "$SCRATCH/ServerProbe.java"

java -Dlog4j.configuration="$(dirname "$0")/log4j.properties" \
     -Djava.net.preferIPv4Stack=true \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.util=ALL-UNNAMED \
     --add-opens java.base/java.io=ALL-UNNAMED \
     --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
     --add-opens java.base/java.text=ALL-UNNAMED \
     --add-opens java.base/java.net=ALL-UNNAMED \
     --add-opens java.base/java.security=ALL-UNNAMED \
     --add-opens java.base/sun.security.action=ALL-UNNAMED \
     --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
     -cp "$SCRATCH:$CP" ServerProbe "$HOST" "$PORT" 2>&1 | grep -v "log4j:WARN"