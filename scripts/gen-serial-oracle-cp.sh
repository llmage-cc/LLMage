#!/usr/bin/env bash
# gen-serial-oracle-cp.sh — build the classpath fragment the SerialSaveScope oracle needs.
#
# Two things force a DEPENDENCY classpath beyond the modules' own target/classes:
#
#  1. THIRD-PARTY field types. org.apache.log4j.Logger etc. are declared as field types on mage
#     classes; ObjectStreamClass reflects over ALL declared fields, so any unresolvable field type
#     makes lookup throw (the deckprobe NoClassDefFoundError lesson of 2026-09-30).
#
#  2. The H2 JDBC DRIVER (runtime scope). --stream-uids deserializes save graphs with a real
#     ObjectInputStream, so it runs the classes' own readObject hooks — and one of them reaches
#     mage.abilities.effects.common.ChooseACardNameEffect$TypeOfName, whose enum <clinit> touches
#     mage.cards.repository.CardRepository. CardRepository.<init> opens an H2 connection
#     (DatabaseUtils.prepareH2Connection) via ormlite's JdbcConnectionSource, which needs
#     org.h2.Driver on the classpath. h2 is declared <scope>runtime</scope> in the root pom
#     (pom.xml:~297), so `-DincludeScope=compile` OMITS it: the oracle logs
#     "Driver class was not found for H2 database. Class not found: org.h2.Driver" /
#     "No suitable driver" and can mis-resolve the classes whose stream UIDs it is there to read.
#     Build the deps classpath with -DincludeScope=runtime so the driver is present.
#
# Output: scripts/.serial-oracle-cp.txt (colon-joined jars, consumed by check-serial-uid-drift.sh
# and check-save-uid-manifest.sh)
# Usage:  bash scripts/gen-serial-oracle-cp.sh        (from the worktree root or scripts/)
# Cheap: five offline mvn calls; safe under the shared maven lock.
#
# 2026-10-02 FIX: the filter used to run on the WHOLE one-line colon classpath, so a module whose
# classpath contains a single /org/mage/*.jar (Mage.Sets, Mage.Server, the plugins — all of them
# depend on mage core) had its ENTIRE classpath deleted by that grep line, and the accumulated CP
# was only Mage's ~1 KB. A thin CP made ObjectStreamClass.lookup() throw on unresolved field types,
# so the oracle silently under-reported Serializable classes — the gate passed on a false negative.
# The filter must be applied PER ENTRY, not per line.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="scripts/.serial-oracle-cp.txt"
TMP="$(mktemp)"
trap 'rm -f "$TMP" "$TMP.mod"' EXIT
: > "$TMP"

for m in Mage Mage.Common Mage.Sets Mage.Server Mage.Server.Plugins/Mage.Player.AI; do
  rm -f "$TMP.mod"
  flock /tmp/xwave-maven.lock \
    mvn -o -q dependency:build-classpath \
        -DincludeScope=runtime \
        -Dmdep.outputFile="$TMP.mod" \
        -pl "$m" >/dev/null 2>&1 || { echo "WARN: build-classpath failed for $m (offline? module moved?)" >&2; continue; }
  [ -s "$TMP.mod" ] || { echo "WARN: empty classpath for $m" >&2; continue; }
  # tr the colon classpath to one entry per line, THEN drop the module's OWN /org/mage artifact
  # (stale .m2 copies must not shadow target/classes), one entry per line.
  tr ':' '\n' < "$TMP.mod" | grep -vE '/org/mage/[^/]+/[^/]+/[^/]*\.jar$' | grep -v '^$' >> "$TMP" || true
done

sort -u "$TMP" | paste -sd: - > "$OUT"
entries=$(tr ':' '\n' < "$OUT" | grep -c . || true)
echo "Oracle deps CP written: $OUT ($(wc -c < "$OUT") bytes, $entries jars)"
