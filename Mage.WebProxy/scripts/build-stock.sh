#!/usr/bin/env bash
# build-stock.sh — build the OFFICIAL-server (stock) artifact pair:
# frontend bundle with VITE_LLMAGE=false + proxy jar via -Pstock.
#
# Usage: Mage.WebProxy/scripts/build-stock.sh
# Output: the normal build locations, overwritten with stock artifacts.
# Build the LLMage pair with the plain commands (npm run build / mvn install).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"   # Mage.WebProxy/

echo "== stock frontend (VITE_LLMAGE=false) =="
cd "$ROOT/web/frontend/react"
VITE_LLMAGE=false npm run build

echo "== stock proxy jar (-Pstock) =="
cd "$ROOT"
mvn clean install -DskipTests -Pstock

echo "== verify: no LLM strings in the stock bundle, no relay in the jar =="
BUNDLE=$(ls -t "$ROOT"/web/frontend/assets/index-*.js | head -1)
if grep -q "Share LLM Chat Live\|LLM Player\|llm_config_section" "$BUNDLE"; then
    echo "FAIL: LLM UI strings present in stock bundle: $BUNDLE" >&2
    exit 1
fi
if grep -q "llm-relay" "$BUNDLE"; then
    echo "FAIL: /llm-relay referenced in stock bundle: $BUNDLE" >&2
    echo "      (llmBridgeClient must be tree-shaken or route-guarded)" >&2
    exit 1
fi
echo "OK: stock bundle clean ($(basename "$BUNDLE"))"

JAR=$(ls "$ROOT"/target/mage-web-proxy-*.jar | head -1)
if unzip -l "$JAR" | grep -q "LLmRelayHandler.class"; then
    echo "FAIL: LLmRelayHandler compiled into the stock jar: $JAR" >&2
    exit 1
fi
if ! unzip -p "$JAR" META-INF/MANIFEST.MF | grep -q "LLMage-Build: false"; then
    echo "FAIL: stock jar manifest lacks LLMage-Build: false" >&2
    exit 1
fi
echo "OK: stock jar clean ($(basename "$JAR"))"
echo "STOCK BUILD COMPLETE"