#!/usr/bin/env bash
# stage-test-db.sh — populate Mage.WebProxy/db/cards.h2 for the proxy unit tests.
#
# Why this exists (both traps measured 2026-09-24 on a fresh clone):
#
# 1. `mvn test -pl Mage.WebProxy` runs with CWD = Mage.WebProxy/, and DeckLoaderTest
#    resolves [SET:NUM] codes through the real card DB at ./db/cards.h2. A missing or
#    EMPTY staging DB makes findCard return null, the name-vs-code check is skipped
#    by design (see DeckLoaderTest), and three rejection tests fail:
#      annotatedExportBacktracksParenthesizedNames / annotatedExportWrongCardForCodeIsRejected /
#      dckFormatWrongCardForCodeIsRejected
#
# 2. RepositoryUtil.isNewBuildRun drops the CARD table the first time a test JVM sees a
#    jar Build-Time not recorded in the BUILD table — so a plain `cp` of a populated DB
#    empties itself again on the first run after ANY rebuild. This script stamps the
#    BUILD rows with the installed mage jar's Build-Time so that drop never triggers.
#
# Usage:  Mage.WebProxy/scripts/stage-test-db.sh [source cards.h2.mv.db]
#         default source: <repo>/db/cards.h2.mv.db (the dev server's DB, ~92k cards)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
MODULE="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$MODULE/.." && pwd)"

SRC="${1:-$REPO/db/cards.h2.mv.db}"
[ -r "$SRC" ] || { echo "FATAL: source cards DB not found: $SRC" >&2; exit 1; }

H2="$(ls "$MODULE"/target/lib/h2-*.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$H2" ] || H2="$(ls "$HOME"/.m2/repository/com/h2database/h2/1.4.197/h2-*.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$H2" ] || { echo "FATAL: h2 jar not found (looked in $MODULE/target/lib and ~/.m2) — run a maven build first" >&2; exit 1; }

# The jar the tests actually load: the installed artifact in ~/.m2. `mvn -pl` resolves
# dependencies from the local repo, not the reactor, so this is the manifest JarVersion
# reads. (A full-reactor `mvn test` loads target/classes and reports "runtime", which
# skips the drop check — this staging then still provides the populated DB.)
MAGE_JAR="$(ls "$HOME"/.m2/repository/org/mage/mage/1.4.*/mage-1.4.*.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -n "$MAGE_JAR" ] || { echo "FATAL: mage jar not found in ~/.m2 — run: mvn install -DskipTests -pl Mage" >&2; exit 1; }

BUILD_TIME="$(unzip -p "$MAGE_JAR" META-INF/MANIFEST.MF | sed -n 's/^Build-Time: //p' | tr -d '\r' | sed -e 's/T/ /' -e 's/:[0-9][0-9]Z$//')"
[ -n "$BUILD_TIME" ] || { echo "FATAL: no Build-Time in the manifest of $MAGE_JAR" >&2; exit 1; }

mkdir -p "$MODULE/db"
rm -f "$MODULE/db/cards.h2.mv.db" "$MODULE/db/cards.h2.trace.db"
cp "$SRC" "$MODULE/db/cards.h2.mv.db"

H2_URL="jdbc:h2:file:$MODULE/db/cards.h2;AUTO_SERVER=TRUE;IGNORECASE=TRUE"

java -cp "$H2" org.h2.tools.Shell -url "$H2_URL" -user '' -password '' \
    -sql "INSERT INTO BUILD (ENTITY, LAST_BUILD) VALUES ('card', '$BUILD_TIME'), ('expansion', '$BUILD_TIME');" >/dev/null

COUNT="$(java -cp "$H2" org.h2.tools.Shell -url "$H2_URL" -user '' -password '' \
    -sql 'SELECT COUNT(*) AS cards FROM CARD;' | sed -n '2p' | tr -d ' \r')"
[ -n "$COUNT" ] && [ "$COUNT" != "0" ] || { echo "FATAL: staged DB has 0 cards — source $SRC is empty" >&2; exit 1; }

echo "staged $MODULE/db/cards.h2.mv.db: $COUNT cards, BUILD rows stamped '$BUILD_TIME'"
