#!/usr/bin/env bash
# Archive current game/server logs into oldlogs/ with a timestamp.
# Run before each server launch so agents only see the current game's logs.
#
# Moves (never deletes):
#   gamelogs/        → oldlogs/gamelogs_<YYYYMMDD_HHMMSS>/
#   gamelogsJson/    → oldlogs/gamelogsJson_<YYYYMMDD_HHMMSS>/
#   gamesHistory/    → oldlogs/gamesHistory_<YYYYMMDD_HHMMSS>/  (only if non-empty)
#   mageserver.log   → oldlogs/mageserver_<YYYYMMDD_HHMMSS>.log
#   mageadmin.log    → oldlogs/mageadmin_<YYYYMMDD_HHMMSS>.log
#   mageclient.log   → oldlogs/mageclient_<YYYYMMDD_HHMMSS>.log
#   pipeline.log     → oldlogs/pipeline_<YYYYMMDD_HHMMSS>.log
#
# Skips items that don't exist or are empty. Never deletes anything.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

OLDLOGS="oldlogs"
mkdir -p "$OLDLOGS"
STAMP=$(date +%Y%m%d_%H%M%S)

archive_dir() {
    local src="$1"
    local label="$2"
    if [ -d "$src" ] && [ -n "$(ls -A "$src" 2>/dev/null)" ]; then
        local dest="${OLDLOGS}/${label}_${STAMP}"
        echo "  archiving $src/ → $dest/"
        mv "$src" "$dest"
    fi
}

archive_file() {
    local src="$1"
    local label="$2"
    if [ -f "$src" ] && [ -s "$src" ]; then
        local dest="${OLDLOGS}/${label}_${STAMP}.log"
        echo "  archiving $src → $dest"
        mv "$src" "$dest"
    fi
}

echo "[$(date)] Archiving current logs to ${OLDLOGS}/ ..."

archive_dir "gamelogs"      "gamelogs"
archive_dir "gamelogsJson"  "gamelogsJson"
archive_dir "gamesHistory"  "gamesHistory"

archive_file "mageserver.log" "mageserver"
archive_file "mageadmin.log"  "mageadmin"
archive_file "mageclient.log" "mageclient"
archive_file "pipeline.log"   "pipeline"

echo "[$(date)] Archive complete."