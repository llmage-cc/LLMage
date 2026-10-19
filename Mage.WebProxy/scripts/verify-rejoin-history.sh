#!/usr/bin/env bash
# Compile + run the proxy-side rejoin history buffer harness.
#   bash Mage.WebProxy/scripts/verify-rejoin-history.sh
#
# Compiles GameHistoryHarness against the built Mage.WebProxy classes (and the
# module's runtime deps for Gson), then runs it. No XMage server needed.
set -euo pipefail
cd "$(dirname "$0")/../.."          # repo root

CLASSES="Mage.WebProxy/target/classes"
if [ ! -d "$CLASSES" ]; then
  echo "ERROR: $CLASSES is missing — build the proxy first:" >&2
  echo "  mvn -q -nsu -pl Mage.WebProxy install -DskipTests" >&2
  exit 1
fi

# Gson is the only dependency this harness touches (WebProxySession.GSON).
GSON_JAR="$(ls Mage.WebProxy/target/lib/gson-*.jar 2>/dev/null | head -1 || true)"
if [ -z "$GSON_JAR" ]; then
  GSON_JAR="$(ls ~/.m2/repository/com/google/code/gson/gson/*/gson-*.jar 2>/dev/null | tail -1 || true)"
fi
if [ -z "$GSON_JAR" ]; then
  echo "ERROR: gson jar not found (build the proxy so target/lib is populated)" >&2
  exit 1
fi

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# The harness lives in scripts/ but declares package mage.webproxy so it can drive
# package-private GameHistory. javac takes the file directly (no package-dir copy).
javac -nowarn -cp "$CLASSES:$GSON_JAR" -d "$OUT" Mage.WebProxy/scripts/GameHistoryHarness.java
java -cp "$OUT:$CLASSES:$GSON_JAR" mage.webproxy.GameHistoryHarness
