#!/usr/bin/env bash
# Launch the XMage Web Proxy server.
# Usage: ./run-web-proxy.sh [--port PORT]
#
# Requires the XMage server to already be running (start-server.sh).
set -e
cd "$(dirname "$0")/.."

# ── JDK 21 module flags (same as start-server.sh / run-bot-game.sh) ──
# JBoss Remoting uses Java serialization, which needs reflective access
# to java.io private methods. Without these flags, you get:
#   "Wrong java version - check your client running scripts and params."
JAVA_OPENS=(
  --add-opens java.base/java.lang=ALL-UNNAMED
  --add-opens java.base/java.util=ALL-UNNAMED
  --add-opens java.base/java.io=ALL-UNNAMED
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED
  --add-opens java.base/java.text=ALL-UNNAMED
  --add-opens java.base/java.net=ALL-UNNAMED
  --add-opens java.base/java.security=ALL-UNNAMED
  --add-opens java.base/sun.security.action=ALL-UNNAMED
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED
  --add-opens java.base/sun.reflect=ALL-UNNAMED
  --add-opens java.base/jdk.internal.access=ALL-UNNAMED
  --add-opens java.base/jdk.internal.ref=ALL-UNNAMED
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
  --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED
)

# ── Classpath ──────────────────────────────────────────────────────────
# Build classpath from Maven dependencies
CP_FILE=$(mktemp)
mvn -q -pl Mage.WebProxy dependency:build-classpath -Dmdep.outputFile="$CP_FILE" -DskipTests 2>/dev/null
CP="Mage.WebProxy/target/classes:$(cat "$CP_FILE"):Mage.Server/release/plugins/*:Mage.Server/release/lib/*"
rm -f "$CP_FILE"

exec java -Xmx512m \
  "${JAVA_OPENS[@]}" \
  -cp "$CP" \
  mage.webproxy.WebProxyServer \
  "$@"