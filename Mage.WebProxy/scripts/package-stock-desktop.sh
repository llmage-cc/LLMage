#!/usr/bin/env bash
# package-stock-desktop.sh — assemble the OFFICIAL-server (stock) desktop
# distribution: the artifact a player runs ON THEIR OWN MACHINE so the XMage
# server they connect to sees THEIR IP.
#
#   java -jar mage-web-proxy.jar   ← speaks JBoss Remoting natively, so the
#                                     Remoting socket originates locally —
#                                     the VPS is never in the path.
#
# Contents (dist/llmage-stock-desktop/):
#   mage-web-proxy-<v>.jar        stock flavor (-Pstock, LLMage-Build: false)
#   lib/*.jar                     the 42 runtime jars (manifest Class-Path)
#   web/frontend/                 stock bundle (VITE_LLMAGE=false)
#   db/cards.h2.mv.db             populated cards DB (proxy never scans)
#   scryfall resources            already inside mage-<v>.jar (no extra files)
#   start-stock-proxy.sh|.bat     launchers (localhost bind by default)
#   README.md                     quickstart + what-this-is
#
# The zip lands in Mage.WebProxy/dist/ (gitignored). Everything is verified:
# stock manifest, no relay classes, bundle has no LLM strings, db has cards.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"            # Mage.WebProxy/
REPO="$(cd "$ROOT/.." && pwd)"            # repo root
DIST="$ROOT/dist"
NAME="llmage-stock-desktop"

echo "== 1. stock build (flavor pair) =="
bash "$HERE/build-stock.sh"

echo "== 2. assemble staging tree =="
STAGE="$DIST/llmage-stock-desktop"
rm -rf "$STAGE"
mkdir -p "$STAGE/lib" "$STAGE/web/frontend" "$STAGE/db"

cp "$ROOT/target/"mage-web-proxy-*.jar "$STAGE/"
cp "$ROOT/target/lib/"*.jar "$STAGE/lib/"
# frontend exactly as served (assets + top-level files + styles/ + decks/)
# index.html is a Vite build output and is not tracked (react/src/main.tsx notes
# it is regenerated); a fresh clone has none, so name the fix instead of letting
# cp fail with a bare "No such file or directory".
if [ ! -f "$ROOT/web/frontend/index.html" ]; then
    echo "FAIL: web/frontend/index.html missing — build the frontend first:" >&2
    echo "      (cd $ROOT/web/frontend/react && npm ci && npm run build)" >&2
    exit 1
fi
cp "$ROOT/web/frontend/index.html" "$STAGE/web/frontend/"
cp -r "$ROOT/web/frontend/assets" "$STAGE/web/frontend/assets"
find "$ROOT/web/frontend" -maxdepth 1 -type f ! -name index.html \
    -exec cp {} "$STAGE/web/frontend/" \;
cp -r "$ROOT/web/frontend/styles" "$STAGE/web/frontend/styles"
if [ -d "$ROOT/web/frontend/decks" ]; then
    cp -r "$ROOT/web/frontend/decks" "$STAGE/web/frontend/decks"
else
    echo "FAIL: web/frontend/decks missing — run Mage.WebProxy/scripts/builtin-decks.sh first" >&2
    exit 1
fi

# cards DB: the proxy NEVER scans (CardScanner.scanned=true), so it needs a
# populated copy version-matched to the jar. The dev repo's db/ is the source.
DB="$REPO/db/cards.h2.mv.db"
if [ ! -f "$DB" ]; then
    echo "FAIL: cards DB not found at $DB" >&2
    exit 1
fi
SIZE=$(stat -c%s "$DB")
if [ "$SIZE" -lt 30000000 ]; then
    echo "FAIL: cards DB looks wrong ($SIZE bytes < 30 MB) — refusing to ship" >&2
    exit 1
fi
cp "$DB" "$STAGE/db/cards.h2.mv.db"
echo "cards db: $(du -h "$STAGE/db/cards.h2.mv.db" | cut -f1)"

echo "== 3. launchers =="
cat > "$STAGE/start-stock-proxy.sh" <<'EOF'
#!/usr/bin/env bash
# Start the stock (official-server) web client locally.
# Browser: http://localhost:8090  — connect to xmage.today or any XMage server.
# The XMage connection originates from THIS machine (your IP is what the
# server sees). Binds 127.0.0.1 only — nothing is exposed to your network.
cd "$(cd "$(dirname "$0")" && pwd)"
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
# Official servers are allowlisted by default; extend with
# XMAGE_ALLOWED_XMAGE_HOSTS if you want to route to other servers.
EXTRA_PROPS=("-Dwebproxy.allowedXmageHosts=xmage.today,beta.xmage.today,localhost,127.0.0.1,::1")
if [ -n "${XMAGE_ALLOWED_XMAGE_HOSTS:-}" ]; then
    EXTRA_PROPS+=("-Dwebproxy.allowedXmageHosts=$XMAGE_ALLOWED_XMAGE_HOSTS")
fi
exec java "${JAVA_OPENS[@]}" "${EXTRA_PROPS[@]}" -Djava.net.preferIPv4Stack=true \
    ${XMAGE_PROXY_EXTRA_OPTS:-} \
    -jar mage-web-proxy-*.jar --bind 127.0.0.1 --port "${PORT:-8090}" "$@"
EOF
chmod +x "$STAGE/start-stock-proxy.sh"

# Windows launcher (same flags; %* passes through, PORT env honored)
cat > "$STAGE/start-stock-proxy.bat" <<'EOF'
@echo off
rem Start the stock (official-server) web client locally.
rem Browser: http://localhost:8090 - binds 127.0.0.1 only.
cd /d "%~dp0"
set JAVA_OPENS=--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.text=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.security=ALL-UNNAMED --add-opens java.base/sun.security.action=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/sun.reflect=ALL-UNNAMED --add-opens java.base/jdk.internal.access=ALL-UNNAMED --add-opens java.base/jdk.internal.ref=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED
set PORT_ARG=8090
if not "%PORT%"=="" set PORT_ARG=%PORT%
for %%f in (mage-web-proxy-*.jar) do set PROXY_JAR=%%f
rem Official servers are allowlisted by default. XMAGE_ALLOWED_XMAGE_HOSTS overrides.
if "%XMAGE_ALLOWED_XMAGE_HOSTS%"=="" set XMAGE_ALLOWED_XMAGE_HOSTS=xmage.today,beta.xmage.today,localhost,127.0.0.1,::1
java %JAVA_OPENS% -Djava.net.preferIPv4Stack=true "-Dwebproxy.allowedXmageHosts=%XMAGE_ALLOWED_XMAGE_HOSTS%" -jar "%PROXY_JAR%" --bind 127.0.0.1 --port %PORT_ARG% %*
pause
EOF

echo "== 4. README =="
cat > "$STAGE/README.md" <<EOF
# LLMage Stock Desktop — web client for official XMage servers

This is a local web client for connecting to official XMage servers
(xmage.today, beta.xmage.today) straight from your machine — the game server
sees YOUR IP address, exactly as with the desktop client. Nothing routes
through a hosted server. This build has NO LLM features (those live in the
LLMage fork only).

## Run it

Needs Java 21+ (e.g. Eclipse Temurin: https://adoptium.net).

- macOS/Linux:   ./start-stock-proxy.sh
- Windows:       start-stock-proxy.bat

Then open http://localhost:8090 in your browser. In the connect form:

- Server:  xmage.today  (or beta.xmage.today)
- Port:    17171
- Username: any name (official servers need no account for basic play)

Stop it with Ctrl+C in the terminal.

## Notes

- Everything runs locally: this little web server binds 127.0.0.1 only.
- The cards database (db/cards.h2.mv.db) powers the deck editor; it must sit
  next to the jar — keep the folder layout as shipped.
- No API keys, no LLM features, no telemetry. Source: the LLMage/xmage fork,
  stock build (LLMage-Build: false).
EOF

echo "== 5. verify the package =="
JAR=$(ls "$STAGE"/mage-web-proxy-*.jar)
if unzip -p "$JAR" META-INF/MANIFEST.MF | grep -q "LLMage-Build: false"; then
    echo "OK: jar manifest LLMage-Build: false"
else
    echo "FAIL: stock jar manifest wrong" >&2; exit 1
fi
if unzip -l "$JAR" | grep -q "LLmRelay"; then
    echo "FAIL: relay classes in stock jar" >&2; exit 1
fi
BUNDLE=$(ls "$STAGE"/web/frontend/assets/index-*.js | head -1)
if grep -q "Share LLM Chat Live\|LLM Player\|llm-relay\|llmBridge\|fetchProviderModels\|getSeatKey\|clientKeyTag" "$BUNDLE"; then
    echo "FAIL: LLM strings in packaged bundle" >&2; exit 1
fi
# llm_prompt survives only as the emptied `case'llm_prompt':break;` — the
# switch needs the case for message shape; everything behind it compiles out
if [ "$(grep -o "llm_prompt" "$BUNDLE" | wc -l)" -gt 1 ]; then
    echo "FAIL: more than the dead llm_prompt case remains" >&2; exit 1
fi
echo "OK: bundle clean ($(basename "$BUNDLE"))"
LIBCOUNT=$(ls "$STAGE"/lib/*.jar | wc -l)
if [ "$LIBCOUNT" -lt 40 ]; then
    echo "FAIL: lib/ has only $LIBCOUNT jars" >&2; exit 1
fi
echo "OK: $LIBCOUNT lib jars"

echo "== 6. zip =="
cd "$DIST"
rm -f llmage-stock-desktop.zip
if command -v zip >/dev/null 2>&1; then
    zip -qr llmage-stock-desktop.zip llmage-stock-desktop
else
    # zip not installed (measured on this host) — python3 zipfile is equivalent
    python3 -c "
import shutil
shutil.make_archive('llmage-stock-desktop', 'zip', '.', 'llmage-stock-desktop')
"
fi
du -sh llmage-stock-desktop.zip
echo "STOCK DESKTOP PACKAGE COMPLETE: $DIST/llmage-stock-desktop.zip"