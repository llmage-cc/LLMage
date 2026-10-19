#!/usr/bin/env bash
# Isolated stack for the async post-game reflection live test. Ports are
# RESERVED for that test:
#   server 127.0.0.1:17281 (secondary 17283), proxy 127.0.0.1:8181,
#   strict mock 127.0.0.1:18281.
#
# Stage: /tmp/reflection-stage (server + proxy + frontend + mock).
#
# Env:
#   MOCK_STALL_REFLECTION_SECONDS=N  passed to the mock; N seconds held on the
#                                    post-game reflection prompt ("" = instant).
#   PLUGIN_JAR=<path>                override the LLM plugin jar under test.
#                                    Default: this checkout's release jar.
#
# Cards DB: warmed copy at /tmp/reflection-db/cards.h2.mv.db, seeded from
# /tmp/ainames-db/cards.h2.mv.db; the first boot after a fresh build may rescan
# the card table (~40s; wait window 180s, verify >= 90000 cards).
set -uo pipefail

# Repo root = the checkout this script lives in.
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Artifact names carry the Maven project version — read the single source of
# truth (<mage-version> in the root pom) instead of hardcoding it.
VERSION="$(awk -F'[<>]' '/<mage-version>/{print $3; exit}' "$REPO/pom.xml" 2>/dev/null || true)"
[ -n "$VERSION" ] || { echo "FAIL: cannot read <mage-version> from $REPO/pom.xml" >&2; exit 1; }
STAGE=/tmp/llm-reflection-stage
HOST=127.0.0.1
SERVER_PORT=17281
SECONDARY_PORT=17283
PROXY_PORT=8181
MOCK_PORT=18281
DB_SRC=/tmp/ainames-db/cards.h2.mv.db
DB_WARM=/tmp/llm-reflection-db/cards.h2.mv.db
H2="$HOME/.m2/repository/com/h2database/h2/1.4.197/h2-1.4.197.jar"

MOCK_STALL_REFLECTION_SECONDS="${MOCK_STALL_REFLECTION_SECONDS:-}"
PLUGIN_JAR="${PLUGIN_JAR:-$REPO/Mage.Server/release/plugins/mage-player-llm-$VERSION.jar}"

[ -r "$PLUGIN_JAR" ] || { echo "FAIL: plugin jar not found: $PLUGIN_JAR" >&2
    echo "  build first: flock /tmp/llm-maven.lock mvn -nsu clean install -DskipTests -Pllmage && bash scripts/deploy-release.sh" >&2
    exit 1; }
[ -r "$REPO/Mage.WebProxy/target/mage-web-proxy-$VERSION.jar" ] || { echo "FAIL: proxy jar not built" >&2; exit 1; }
[ -d "$REPO/Mage.WebProxy/web/frontend" ] || { echo "FAIL: frontend not built" >&2; exit 1; }
[ -r "$H2" ] || { echo "FAIL: h2 jar not found: $H2" >&2; exit 1; }
if [ ! -r "$DB_WARM" ]; then
    mkdir -p "$(dirname "$DB_WARM")"
    [ -r "$DB_SRC" ] || { echo "FAIL: staged cards DB not found: $DB_SRC" >&2; exit 1; }
    cp "$DB_SRC" "$DB_WARM"
fi

echo "=== STAGE ASSEMBLY (plugin=$(basename "$PLUGIN_JAR") stall='${MOCK_STALL_REFLECTION_SECONDS:-off}') ==="
for p in $SERVER_PORT $SECONDARY_PORT $PROXY_PORT $MOCK_PORT; do
    if ss -tln | grep -q ":$p "; then
        echo "FAIL: port $p in use (teardown first)" >&2; exit 1
    fi
done

rm -rf "$STAGE"
mkdir -p "$STAGE/server" "$STAGE/server/db" "$STAGE/webproxy/db" "$STAGE/gamelogs"

# ── server: worktree release + the plugin jar under test ──
cp -r "$REPO/Mage.Server/release/." "$STAGE/server/"
cp "$PLUGIN_JAR" "$STAGE/server/lib/mage-player-llm-$VERSION.jar"
cp "$PLUGIN_JAR" "$STAGE/server/plugins/mage-player-llm-$VERSION.jar"

# plugins/ must hold UNVERSIONED symlinks: PluginUtil resolves CWD-relative
# plugins/<jar> by the config.xml jar names (bootstrap-plugins.sh equivalent).
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
       -e "s/secondaryBindPort=\"17179\"/secondaryBindPort=\"$SECONDARY_PORT\"/" \
       -e "s/serverAddress=\"127.0.0.1\"/serverAddress=\"$HOST\"/" \
    "$STAGE/server/config/config.xml"
grep -q "port=\"$SERVER_PORT\"" "$STAGE/server/config/config.xml" \
    || { echo "FAIL: server port sed did not apply" >&2; exit 1; }
grep -q "secondaryBindPort=\"$SECONDARY_PORT\"" "$STAGE/server/config/config.xml" \
    || { echo "FAIL: secondary port sed did not apply" >&2; exit 1; }

# ── cards DB: warmed copy for BOTH server and proxy ──
cp "$DB_WARM" "$STAGE/server/db/cards.h2.mv.db"
cp "$DB_WARM" "$STAGE/webproxy/db/cards.h2.mv.db"

# ── proxy stage: jar + deps + web root ──
cp "$REPO/Mage.WebProxy/target/mage-web-proxy-$VERSION.jar" "$STAGE/webproxy/"
cp -r "$REPO/Mage.WebProxy/target/lib" "$STAGE/webproxy/lib"
ln -sfn "$REPO/Mage.WebProxy/web" "$STAGE/webproxy/web"
[ -d "$STAGE/webproxy/web/frontend" ] || { echo "FAIL: no web/frontend in proxy stage" >&2; exit 1; }

# ── strict mock (MOCK_STALL_REFLECTION_SECONDS passthrough for the stall leg) ──
MOCK_STALL_REFLECTION_SECONDS="$MOCK_STALL_REFLECTION_SECONDS" \
    setsid nohup python3 "$REPO/scripts/mock-llm-sideboard.py" "$MOCK_PORT" \
    > "$STAGE/mock.log" 2>&1 < /dev/null &
MOCK_PID=$!
sleep 1
curl -s -m 5 "http://$HOST:$MOCK_PORT/v1/models" | grep -q stub-model \
    || { echo "FAIL: mock not up"; tail "$STAGE/mock.log" >&2; exit 1; }
echo "mock up on $MOCK_PORT (pid $MOCK_PID, stall='${MOCK_STALL_REFLECTION_SECONDS:-off}')"

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
    -Dxmage.llm.personality_enabled=false \
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

CARDS="$(grep -oE 'cards: [0-9]+' "$STAGE/server.log" 2>/dev/null | tail -1 | awk '{print $2}')"
if [ -z "${CARDS:-}" ]; then
    # A fresh-build boot rescans the card table ("cards: updating") and never
    # re-prints a numeric line. Fall back: wait for the save to finish
    # (DB file stops growing), then sanity-check its size.
    echo "card count line absent (fresh-build rescan) — waiting for DB save…"
    DBFILE="$STAGE/server/db/cards.h2.mv.db"
    prev=0
    for _ in $(seq 1 40); do
        cur=$(stat -c %s "$DBFILE" 2>/dev/null || echo 0)
        [ "$cur" = "$prev" ] && [ "$cur" -ge 80000000 ] && break
        prev="$cur"; sleep 3
    done
    cur=$(stat -c %s "$DBFILE" 2>/dev/null || echo 0)
    [ "$cur" -ge 80000000 ] || { echo "FAIL: card DB not saved (size=$cur)"; exit 1; }
    echo "card repository: rescan saved, DB size $cur bytes"
else
    [ "$CARDS" -ge 90000 ] || { echo "FAIL: card repository too small: $CARDS" >&2; exit 1; }
    echo "card repository: $CARDS cards"
fi

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
sleep 2
pgrep -f "mage-web-proxy-$VERSION.jar" >/dev/null 2>&1 \
    || { echo "FAIL: proxy exited after start"; tail -30 "$STAGE/proxy.log" >&2; exit 1; }

# ── record PIDs for teardown (cwd/port-scoped, never pattern-wide) ──
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
    echo "MOCK_STALL_REFLECTION_SECONDS=${MOCK_STALL_REFLECTION_SECONDS:-}"
    echo "PLUGIN_JAR=$PLUGIN_JAR"
} > "$STAGE/pids.env"

echo
echo "STAGE READY: server $HOST:$SERVER_PORT | proxy $HOST:$PROXY_PORT | mock $HOST:$MOCK_PORT (stall='${MOCK_STALL_REFLECTION_SECONDS:-off}')"
echo "gamelogs: $STAGE/gamelogs"
