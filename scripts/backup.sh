#!/bin/bash
# Backup XMage source tree (excluding images, builds, and logs)
# Usage: ./backup.sh [output_dir]  (default: ~/Downloads)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC_DIR="$ROOT"
OUTPUT_DIR="${1:-$HOME/Documents/Backups/Xmage}"
DATE=$(date +%Y%m%d)
OUTFILE="$OUTPUT_DIR/llmage-$DATE.tar.zst"

if [ ! -d "$SRC_DIR/.git" ]; then
    echo "Error: $SRC_DIR is not a git repo" >&2
    exit 1
fi

echo "Backing up $SRC_DIR -> $OUTFILE"
echo "Excluding: .git, plugins/images, */target, */release, logs, gamelogs, oldlogs, saves, db"

cd "$(dirname "$SRC_DIR")"
tar --zstd \
    --exclude='.git' \
    --exclude='plugins/images' \
    --exclude='*/target' \
    --exclude='*/release' \
    --exclude='*.log' \
    --exclude='gamelogs' \
    --exclude='gamelogsJson' \
    --exclude='oldlogs' \
    --exclude='oldlogs+saves' \
    --exclude='saved' \
    --exclude='db' \
    -cf "$OUTFILE" "$(basename "$SRC_DIR")"

echo "Done: $(ls -lh "$OUTFILE" | awk '{print $5, $9}')"