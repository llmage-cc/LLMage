#!/usr/bin/env bash
# Launch a headless LLM-vs-LLM bot game and wait for it to finish.
# Usage: ./run-bot-game.sh [--bo3] [--timeout-minutes N] [--deck1 PATH] [--deck2 PATH]
#
# The server host defaults to the literal 127.0.0.1 when no --host is given —
# NOT the runner's own "localhost" default. See the argument note at the bottom.
#
# Requires the LLMage (XMage) server to already be running (start-server.sh).
# Exit code: 0 = game completed, 1 = connection/startup failure, 2 = timeout
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# ── JDK 21 module flags (same as start-server.sh) ─────────────────────
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
  --add-opens java.desktop/java.beans=ALL-UNNAMED
  --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED
)

# ── Classpath ──────────────────────────────────────────────────────────
# Same libs as the server, plus the console JAR we just built.
# Resolve the versioned console JAR name (changes with pom version).
CONSOLE_JAR=$(ls Mage.Server.Console/target/mage-server-console-*.jar 2>/dev/null | head -1)
if [ -z "$CONSOLE_JAR" ]; then
    echo "ERROR: mage-server-console JAR not found. Run: mvn -pl Mage.Server.Console install -DskipTests" >&2
    exit 1
fi
CP="${CONSOLE_JAR}:Mage.Server/release/lib/*:Mage.Server/release/plugins/*"

# ── Argument assembly ──────────────────────────────────────────────────
# User args are forwarded VERBATIM; an explicit --host always wins. When the
# caller gave none (and did not ask for --help), default the host to the
# literal 127.0.0.1.
#
# Why not let the runner's own default ("localhost") stand: Mage.Common's
# Connection.getURI() substitutes a "localhost" host with the FIRST
# non-loopback IPv4 address of this machine (getLocalAddress() walks the
# network interfaces in enumeration order). On a docker-heavy host a bridge
# gateway such as 172.27.0.1 commonly enumerates before eth0, so "localhost"
# silently dials an address the server is not listening on and the connect
# fails with CannotConnectException. The literal 127.0.0.1 never hits that
# substitution. See
# (the engine-side fix is deliberately NOT part of this change).
ARGS=("$@")
has_host=0
has_help=0
for arg in "${ARGS[@]}"; do
  case "$arg" in
    # Both spellings count as "the caller chose a host" so an explicit choice
    # is never overridden. (BotGameRunner only parses the `--host VALUE` form;
    # `--host=VALUE` is forwarded verbatim and ignored by the runner, exactly
    # as before this change.)
    --host|--host=*) has_host=1 ;;
    --help|-h) has_help=1 ;;
  esac
done
if [ "$has_host" -eq 0 ] && [ "$has_help" -eq 0 ]; then
  ARGS+=(--host 127.0.0.1)
fi

exec java -Xmx512m \
  -Djava.net.preferIPv4Stack=true \
  "${JAVA_OPENS[@]}" \
  -cp "$CP" \
  mage.server.console.BotGameRunner \
  "${ARGS[@]}"