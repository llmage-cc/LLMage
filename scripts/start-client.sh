#!/usr/bin/env bash
# Start the XMage client
# Usage: ./start-client.sh [server-host] [server-port]
#
# Runs from pre-built JARs in Mage.Client/release/lib/.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

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
  --add-opens java.desktop/java.awt=ALL-UNNAMED
  --add-opens java.desktop/java.awt.event=ALL-UNNAMED
  --add-opens java.desktop/javax.swing=ALL-UNNAMED
  --add-opens java.desktop/javax.swing.plaf.basic=ALL-UNNAMED
  --add-opens java.desktop/javax.swing.text=ALL-UNNAMED
  --add-opens java.desktop/com.sun.java.swing=ALL-UNNAMED
  --add-opens java.desktop/java.beans=ALL-UNNAMED
  --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED
)

exec java -Xmx4g \
  "${JAVA_OPENS[@]}" \
  -cp "Mage.Client/release/lib/*" \
  mage.client.MageFrame "$@"
