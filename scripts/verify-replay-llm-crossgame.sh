#!/usr/bin/env bash
# Compile + run the cross-game replayLLMData scoping regression harness.
#
#   bash scripts/verify-replay-llm-crossgame.sh [--corpus DIR]...
#
# Phases (all offline — no server, no network, no live model):
#   [1] manufacture — a real Bo3 (turns 12/2/2) through the engine's own
#       player-reuse path (MatchImpl.initGame hands the SAME player objects to
#       game 2+), each game saved in the production replay format and reloaded
#       through the production load path. Asserts the replay map is empty at
#       every game start, every record binds to a frame of its own game, and an
#       earlier game's records never appear in a later game's artifact.
#   [2] walk — the same binding property over every *.game in each --corpus DIR
#       (the regression sweep over a real replay corpus).
#   [3] selftest — injects one stale (83dd631c signature), one in-range misbound,
#       one wrong-shape pre-save-bound record (must fire pre_save_unexpected) and
#       one late-bound record into a clean artifact and asserts the walker detects
#       all of them. Proves the harness discriminates (non-vacuity).
#
# The harness compiles against the BUILT tree (module target/classes + the
# local ~/.m2 mage jars), mirroring Mage.WebProxy/scripts/verify-rejoin-history.sh.
# Build first if the classes are missing:
#   mvn -q -nsu install -DskipTests
#
# Exit codes: 0 = all phases pass; 1 = a phase failed; 2 = usage/build error.
set -euo pipefail
cd "$(dirname "$0")/.."          # repo root
WT="$(pwd)"

CORPORA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --corpus) CORPORA+=("${2:?--corpus needs a DIR}"); shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done

# ---- build-classpath: built module classes first, then ~/.m2 deps ----------
CP=""
for c in "$WT/Mage/target/classes" "$WT/Mage.Common/target/classes" \
         "$WT/Mage.Sets/target/classes" "$WT/Mage.Server/target/classes"; do
  [ -d "$c" ] && CP="$CP:$c"
done
for c in "$WT"/Mage.Server.Plugins/*/target/classes; do
  [ -d "$c" ] && CP="$CP:$c"
done
if [ ! -d "$WT/Mage.Server.Plugins/Mage.Player.LLM/target/classes" ]; then
  echo "ERROR: Mage.Server.Plugins/Mage.Player.LLM/target/classes missing — build first:" >&2
  echo "  mvn -q -nsu install -DskipTests" >&2
  exit 2
fi
M2="${HOME}/.m2/repository"
MAGE_JARS="$(find "$M2/org/mage" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort | tr '\n' ':')"
CP="$CP:$MAGE_JARS"
for g in com/google/code/gson com/github/luben/zstd-jni ch/qos/reload4j org/slf4j/slf4j-api \
         com/google/guava com/google/protobuf com/google/errorprone com/google/j2objc org/jspecify \
         com/googlecode/jspf org/jboss org/ocpsoft concurrent trove org/apache/commons \
         org/apache/shiro commons-beanutils commons-collections javax/mail javax/activation \
         com/sun/jersey org/jvnet/mimepull javax/ws/rs org/xerial org/unbescape \
         javax/xml/bind com/sun/xml/bind org/glassfish com/sun/activation com/sun/istack \
         jakarta/xml/bind net/java/truevfs net/java/truecommons global/namespace/service-wight \
         javax/inject org/bouncycastle com/google/code/findbugs org/jsoup com/j256 com/h2database; do
  if [ -d "$M2/$g" ]; then
    J="$(find "$M2/$g" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null | sort | tr '\n' ':')"
    CP="$CP:$J"
  fi
done
CP="${CP#:}"

OUT="$(mktemp -d)"
WORK="$(mktemp -d)"
trap 'rm -rf "$OUT" "$WORK"' EXIT

echo "== compile harness"
javac -nowarn -cp "$CP" -d "$OUT" scripts/VerifyReplayLlmCrossgame.java

rc=0
run() { # run a harness phase from $WORK (keeps ./saved + mageserver.log out of the repo)
  local label="$1"; shift
  echo
  echo "== $label"
  ( cd "$WORK" && java -Xmx2500m -cp "$OUT:$CP" VerifyReplayLlmCrossgame "$@" ) || rc=1
}

run "phase [1] manufacture (Bo3, player-reuse path)" manufacture "$WORK/manufacture" 3 12,2,2
run "phase [3] selftest (injected stale + misbound must be detected)" selftest "$WORK/selftest"

if [ "${#CORPORA[@]}" -gt 0 ]; then
  run "phase [2] walk (corpus)" walk "${CORPORA[@]}"
else
  # Convenience default: the server's saved/ lives next to the server CWD; the
  # repo root has none, so the walk is skipped unless the caller points at a corpus.
  if [ -d "$WT/saved" ]; then
    run "phase [2] walk (default ./saved)" walk "$WT/saved"
  else
    echo
    echo "== phase [2] walk skipped (no --corpus given and $WT/saved does not exist)"
  fi
fi

echo
if [ "$rc" -eq 0 ]; then
  echo "verify-replay-llm-crossgame: PASS"
else
  echo "verify-replay-llm-crossgame: FAIL"
fi
exit "$rc"
