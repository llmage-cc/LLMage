#!/usr/bin/env bash
# replay-uid-recover.sh — convert replay .game saves written by an OLDER build so
# THIS build can load them. See Mage.Server .../ReplayUidRecover.java for the mechanism.
#
# WHY: engine classes carry no pinned serialVersionUID, so every rebuild re-derives
# theirs; a save from another build fails with InvalidClassException. The public
# corpus is split across three such generations (
# measurement.md §1).
#
# Usage:
#   scripts/replay-uid-recover.sh [--in-place] <file-or-dir>...
#     (default writes <name>.recovered.game beside the source; --in-place overwrites,
#      keeping a .orig copy the first time)
#
# Requires the Mage.Server build output on the classpath (mvn -pl Mage.Server ... install)
# and a JDK. Reuses scripts/.serial-oracle-cp.txt (dependency CP) when present.
set -euo pipefail
cd "$(dirname "$0")/.."

WT="$(pwd)"
CLS="$WT/Mage.Server/target/classes"
[ -d "$CLS" ] || { echo "FATAL: $CLS missing — build Mage.Server first (mvn -pl Mage.Server install -DskipTests)" >&2; exit 2; }

DEPS=""
[ -f scripts/.serial-oracle-cp.txt ] && DEPS=":$(cat scripts/.serial-oracle-cp.txt)"

# module classes for the engine graph
MODCP=""
for m in Mage Mage.Common Mage.Server Mage.Sets; do
  [ -d "$WT/$m/target/classes" ] && MODCP="$MODCP:$WT/$m/target/classes"
done

CP="$CLS$MODCP$DEPS"
exec java --add-opens java.base/java.io=ALL-UNNAMED \
     -cp "$CP" mage.server.game.ReplayUidRecover "$@"
