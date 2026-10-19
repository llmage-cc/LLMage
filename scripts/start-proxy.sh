#!/usr/bin/env bash
# Start the XMage Web Proxy (serves React frontend + WebSocket bridge to XMage server)
# Usage: ./start-proxy.sh [--port 8090]
#
# Runs from the pre-built JAR + dependencies in Mage.WebProxy/target/.
# Frontend must be built first: cd Mage.WebProxy/web/frontend/react && npm run build
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# JDK 21 module flags for JBoss Remoting (same as start-server.sh / start-client.sh)
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

# Resolve the versioned proxy JAR name (changes with pom version).
PROXY_JAR=$(ls Mage.WebProxy/target/mage-web-proxy-*.jar 2>/dev/null | head -1)
if [ -z "$PROXY_JAR" ]; then
    echo "ERROR: mage-web-proxy JAR not found. Run: mvn -pl Mage.WebProxy install -DskipTests" >&2
    exit 1
fi

# Optional hosted-deployment flags (both unset = local dev, unchanged):
#   XMAGE_PROXY_PINNED_TARGET=host:port  → pin every browser session to that
#       XMage target (client-supplied server/port in the connect message are
#       ignored). Required together with the toggle below.
#   XMAGE_PROXY_HIDE_SERVER_TARGET=true  → the connect form hides its Server/Port
#       fields (served via GET /config). Without the pin above the proxy refuses
#       to start, by design.
EXTRA_PROPS=()
if [ -n "${XMAGE_PROXY_PINNED_TARGET:-}" ]; then
    EXTRA_PROPS+=("-Dwebproxy.pinnedXmageTarget=$XMAGE_PROXY_PINNED_TARGET")
fi
if [ -n "${XMAGE_PROXY_HIDE_SERVER_TARGET:-}" ]; then
    EXTRA_PROPS+=("-Dwebproxy.hideServerTarget=$XMAGE_PROXY_HIDE_SERVER_TARGET")
fi
# Model-endpoint host allowlist (fetch_models, seat endpoints, and the
# client-key /llm-relay). Default in code: localhost,127.0.0.1::1 (local
# Ollama). Set for hosted/browser-key deployments, e.g.
#   XMAGE_ALLOWED_MODEL_HOSTS=opencode.ai,openrouter.ai
if [ -n "${XMAGE_ALLOWED_MODEL_HOSTS:-}" ]; then
    EXTRA_PROPS+=("-Dwebproxy.allowedModelHosts=$XMAGE_ALLOWED_MODEL_HOSTS")
fi

# XMage-server host allowlist (SSRF guard on the connect form's target).
# Default in code: localhost,127.0.0.1::1. Set when the proxy must reach a
# remote XMage server, e.g. testing against the official server:
#   XMAGE_ALLOWED_XMAGE_HOSTS=beta.xmage.today
if [ -n "${XMAGE_ALLOWED_XMAGE_HOSTS:-}" ]; then
    EXTRA_PROPS+=("-Dwebproxy.allowedXmageHosts=$XMAGE_ALLOWED_XMAGE_HOSTS")
fi

# Origin allowlist for the WebSocket upgrade (CSWSH guard). Default in code:
# localhost/127.0.0.1 only. Set when the proxy is bound to a non-localhost
# interface (--bind), e.g. a LAN or VPN address:
#   XMAGE_PROXY_ALLOWED_ORIGINS=http://192.168.1.10:8090
if [ -n "${XMAGE_PROXY_ALLOWED_ORIGINS:-}" ]; then
    EXTRA_PROPS+=("-Dwebproxy.allowedOrigins=$XMAGE_PROXY_ALLOWED_ORIGINS")
fi

# Prefer IPv4 for this JVM: the XMage server binds IPv4 by default, while
# "localhost" resolves to ::1 first on dual-stack hosts — the proxy then fails
# the XMage handshake with "Server is likely offline" even though the server is
# up. (Same reason the web client's Server field needs 127.0.0.1, not localhost.)
exec java "${JAVA_OPENS[@]}" "${EXTRA_PROPS[@]}" -Djava.net.preferIPv4Stack=true -jar "$PROXY_JAR" "$@"
