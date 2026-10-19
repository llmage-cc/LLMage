#!/usr/bin/env bash
# Isolated stack for the web-UI AI-seat-name live verification.
#
# Usage:  PLUGIN=fixed|base bash scripts/ainames-launch-stack.sh
#   fixed (default) = the fixed build's mage-player-llm jar
#   base            = a pre-fix release's mage-player-llm jar
#
# Stage: /tmp/ainames-stage — reuses a clean-deploy release (server
# libs + proxy jar + frontend) and only swaps the LLM plugin jar.
# The cards DB comes from /tmp/ainames-db/cards.h2.mv.db: a pre-scanned copy
# (92k cards, 587 expansions) whose BUILD rows already match the core jars'
# Build-Time (2026-09-28 22:27), so neither RepositoryUtil nor the proxy's
# CardsDbGuard drops the CARD table on boot.
# Ports: server 127.0.0.1:17241 (secondary 17243), proxy 127.0.0.1:8141,
#        strict mock 127.0.0.1:18241.
set -uo pipefail

PLUGIN="${PLUGIN:-fixed}"
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
BASE_REPO="${BASE_REPO:-$REPO}"
# Artifact names carry the Maven project version — read the single source of
# truth (<mage-version> in the root pom) instead of hardcoding it. The stage is
# assembled from BASE_REPO, so derive from its pom, falling back to this checkout.
VERSION="$(awk -F'[<>]' '/<mage-version>/{print $3; exit}' "$BASE_REPO/pom.xml" 2>/dev/null || true)"
VERSION="${VERSION:-$(awk -F'[<>]' '/<mage-version>/{print $3; exit}' "$REPO/pom.xml" 2>/dev/null || true)}"
[ -n "$VERSION" ] || { echo "FAIL: cannot read <mage-version> from $BASE_REPO/pom.xml" >&2; exit 1; }
STAGE=/tmp/ainames-stage
HOST=127.0.0.1
SERVER_PORT=17241
PROXY_PORT=8141
MOCK_PORT=18241
DB_SRC=/tmp/ainames-db/cards.h2.mv.db
H2="$HOME/.m2/repository/com/h2database/h2/1.4.197/h2-1.4.197.jar"

if [ "$PLUGIN" = "fixed" ]; then
    PLUGIN_JAR="$REPO/Mage.Server.Plugins/Mage.Player.LLM/target/mage-player-llm.jar"
else
    PLUGIN_JAR="$BASE_REPO/Mage.Server/release/plugins/mage-player-llm-$VERSION.jar"
fi
[ -r "$PLUGIN_JAR" ] || { echo "FAIL: plugin jar not found: $PLUGIN_JAR" >&2; exit 1; }
[ -r "$DB_SRC" ] || { echo "FAIL: staged cards DB not found: $DB_SRC" >&2; \
    echo "  create it: cp <repo>/db/cards.h2.mv.db $DB_SRC" >&2; exit 1; }
[ -r "$H2" ] || { echo "FAIL: h2 jar not found: $H2" >&2; exit 1; }

echo "=== STAGE ASSEMBLY ($PLUGIN) ==="
for p in $SERVER_PORT $PROXY_PORT $MOCK_PORT; do
    if ss -tln | grep -q ":$p "; then
        echo "FAIL: port $p in use" >&2; exit 1
    fi
done

rm -rf "$STAGE"
mkdir -p "$STAGE/server" "$STAGE/server/db" "$STAGE/webproxy/db" "$STAGE/gamelogs"

# ── server: clean release + swapped LLM plugin jar ──
cp -r "$BASE_REPO/Mage.Server/release/." "$STAGE/server/"
cp "$PLUGIN_JAR" "$STAGE/server/lib/mage-player-llm-$VERSION.jar"
cp "$PLUGIN_JAR" "$STAGE/server/plugins/mage-player-llm-$VERSION.jar"

# plugins/ must hold UNVERSIONED symlinks: PluginUtil resolves
# CWD-relative plugins/<jar> by the config.xml jar names (bootstrap-plugins.sh
# is the repo-side equivalent; without it every plugin fails to load).
(
  cd "$STAGE/server" && mkdir -p plugins
  for jar in lib/mage-player-*.jar lib/mage-game-*.jar lib/mage-tournament-*.jar \
             lib/mage-deck-*.jar plugins/mage-player-*.jar; do
      [ -e "$jar" ] || continue
      base="$(basename "$jar")"
      name="$(printf '%s' "$base" | sed -E 's/-[0-9][0-9.]*\.jar$//; s/\.jar$//')"
      rm -f "plugins/$name.jar"
      ln -s "../$jar" "plugins/$name.jar"
  done
)
dangling=0
for link in "$STAGE"/server/plugins/*.jar; do
    [ -L "$link" ] || continue
    [ -e "$link" ] || { echo "DANGLING: $link -> $(readlink "$link")" >&2; dangling=$((dangling+1)); }
done
[ "$dangling" = 0 ] || { echo "FAIL: $dangling dangling plugin symlinks" >&2; exit 1; }
echo "plugins/: $(ls "$STAGE"/server/plugins/*.jar | wc -l) links materialized, all resolve"

# ── server config: dev config (unversioned jar names) + stage ports ──
cp "$REPO/Mage.Server/config/config.xml" "$STAGE/server/config/config.xml"
sed -i -e "s/port=\"17171\"/port=\"$SERVER_PORT\"/" \
       -e "s/secondaryBindPort=\"17179\"/secondaryBindPort=\"17243\"/" \
       -e "s/serverAddress=\"127.0.0.1\"/serverAddress=\"$HOST\"/" \
    "$STAGE/server/config/config.xml"
grep -q "port=\"$SERVER_PORT\"" "$STAGE/server/config/config.xml" \
    || { echo "FAIL: server port sed did not apply" >&2; exit 1; }

# ── cards DB: pre-scanned copy for BOTH server and proxy ──
cp "$DB_SRC" "$STAGE/server/db/cards.h2.mv.db"
cp "$DB_SRC" "$STAGE/webproxy/db/cards.h2.mv.db"
for d in server webproxy; do
    COUNT="$(java -cp "$H2" org.h2.tools.Shell \
        -url "jdbc:h2:file:$STAGE/$d/db/cards.h2;AUTO_SERVER=TRUE" \
        -user '' -password '' -sql 'SELECT COUNT(*) AS cards FROM CARD;' 2>/dev/null \
        | sed -n '2p' | tr -d ' \r')"
    case "$COUNT" in
        ''|*[!0-9]*) echo "FAIL: cannot read card count ($d): '$COUNT'" >&2; exit 1 ;;
    esac
    [ "$COUNT" -gt 50000 ] || { echo "FAIL: staged $d db too small: $COUNT cards" >&2; exit 1; }
    echo "staged $d db: $COUNT cards"
done

# ── proxy stage: jar + deps + web root ──
cp "$BASE_REPO/Mage.WebProxy/target/mage-web-proxy-$VERSION.jar" "$STAGE/webproxy/"
cp -r "$BASE_REPO/Mage.WebProxy/target/lib" "$STAGE/webproxy/lib"
ln -sfn "$BASE_REPO/Mage.WebProxy/web" "$STAGE/webproxy/web"
[ -d "$STAGE/webproxy/web/frontend" ] || { echo "FAIL: no web/frontend in proxy stage" >&2; exit 1; }

# ── strict mock (rejects a thinking object) ──
setsid nohup python3 "$REPO/scripts/mock-llm-sideboard.py" "$MOCK_PORT" \
    > "$STAGE/mock.log" 2>&1 < /dev/null &
MOCK_PID=$!
sleep 1
curl -s -m 5 "http://$HOST:$MOCK_PORT/v1/models" | grep -q stub-model \
    || { echo "FAIL: mock not up"; tail "$STAGE/mock.log" >&2; exit 1; }
echo "mock up on $MOCK_PORT (pid $MOCK_PID)"

# ── server ──
( cd "$STAGE/server" && setsid nohup java -Xmx2g \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED \
    --add-opens java.base/java.io=ALL-UNNAMED \
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens java.base/java.text=ALL-UNNAMED \
    --add-opens java.base/java.net=ALL-UNNAMED \
    --add-opens java.base/java.security=ALL-UNNAMED \
    --add-opens java.base/sun.security.action=ALL-UNNAMED \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/sun.reflect=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.access=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.ref=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED \
    -cp "lib/*:plugins/*" \
    -Dxmage.config.path=config/config.xml \
    -Dxmage.llm.model="stub-model" \
    -Dxmage.llm.endpoint="http://$HOST:$MOCK_PORT/v1/chat/completions" \
    -Dxmage.llm.reasoningeffort="default" \
    -Dxmage.llm.thinking_dialect="effort" \
    -Dxmage.llm.skip_thinking_probe=true \
    -Dxmage.llm.personality_enabled=true \
    -Dxmage.llm.transcript_dir="$STAGE/gamelogs" \
    -Dxmage.llm.verbose="true" \
    -Dxmage.llm.model_context_window=131072 \
    mage.server.Main >> "$STAGE/server.log" 2>&1 < /dev/null & disown )
echo "server starting (log: $STAGE/server.log)…"
SERVER_UP=0
for i in $(seq 1 180); do
    grep -q 'Started MAGE server' "$STAGE/server.log" 2>/dev/null && { SERVER_UP=1; echo "SERVER UP after ${i}s"; break; }
    grep -qi 'Address already in use' "$STAGE/server.log" 2>/dev/null && \
        { echo "FAIL: server port conflict"; tail -25 "$STAGE/server.log" >&2; exit 1; }
    sleep 1
done
[ "$SERVER_UP" = 1 ] || { echo "FAIL: server not up in 180s"; tail -30 "$STAGE/server.log" >&2; exit 1; }

BAD="$(grep -c "Can.t load plugin" "$STAGE/server.log" 2>/dev/null || true)"
if [ "${BAD:-0}" != "0" ]; then
    echo "FAIL: $BAD plugin load errors on server boot" >&2
    grep -m5 "Can.t load plugin" "$STAGE/server.log" >&2
    exit 1
fi
echo "server boot: 0 plugin load errors"

# ── proxy ──
( cd "$STAGE/webproxy" && setsid nohup java \
    --add-opens java.base/java.lang=ALL-UNNAMED \
    --add-opens java.base/java.util=ALL-UNNAMED \
    --add-opens java.base/java.io=ALL-UNNAMED \
    --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
    --add-opens java.base/java.text=ALL-UNNAMED \
    --add-opens java.base/java.net=ALL-UNNAMED \
    --add-opens java.base/java.security=ALL-UNNAMED \
    --add-opens java.base/sun.security.action=ALL-UNNAMED \
    --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens java.base/sun.reflect=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.access=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.ref=ALL-UNNAMED \
    --add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
    --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED \
    -Djava.net.preferIPv4Stack=true \
    -jar mage-web-proxy-$VERSION.jar --bind "$HOST" --port "$PROXY_PORT" \
    >> "$STAGE/proxy.log" 2>&1 < /dev/null & disown )
echo "proxy starting (log: $STAGE/proxy.log)…"
PROXY_UP=0
for i in $(seq 1 60); do
    if (exec 3<>"/dev/tcp/$HOST/$PROXY_PORT") 2>/dev/null; then PROXY_UP=1; echo "PROXY UP after ${i}s"; break; fi
    sleep 1
done
[ "$PROXY_UP" = 1 ] || { echo "FAIL: proxy not accepting"; tail -30 "$STAGE/proxy.log" >&2; exit 1; }
# make sure it stayed up (CardsDbGuard failures exit right after binding attempts)
sleep 2
pgrep -f "mage-web-proxy-$VERSION.jar" >/dev/null 2>&1 \
    || { echo "FAIL: proxy exited after start"; tail -30 "$STAGE/proxy.log" >&2; exit 1; }

# ── record PIDs for teardown ──
SERVER_PID="$(for p in $(pgrep -f 'mage.server.Main' 2>/dev/null); do
    cwd="$(readlink /proc/$p/cwd 2>/dev/null || true)"
    [ "$cwd" = "$STAGE/server" ] && echo "$p"
done | head -1)"
PROXY_PID="$(for p in $(pgrep -f "mage-web-proxy-$VERSION.jar" 2>/dev/null); do
    cmd="$(tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null || true)"
    case "$cmd" in *"--port $PROXY_PORT"*) echo "$p" ;; esac
done | head -1)"
{
    echo "SERVER_PID=${SERVER_PID:-}"
    echo "PROXY_PID=${PROXY_PID:-}"
    echo "MOCK_PID=${MOCK_PID:-}"
    echo "PLUGIN=$PLUGIN"
} > "$STAGE/pids.env"

echo
echo "STAGE READY ($PLUGIN): server $HOST:$SERVER_PORT | proxy $HOST:$PROXY_PORT | mock $HOST:$MOCK_PORT"
echo "gamelogs: $STAGE/gamelogs"
