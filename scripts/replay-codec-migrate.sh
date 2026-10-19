#!/usr/bin/env bash
# Convert replay .game saves from the legacy gzip container to zstd (ReplayCodec).
#
# WHY: replays moved to a zstd container on 2026-09-24 (Mage.Server ReplayCodec).
# The reader is zstd-only by design (explicit "legacy gzip replay container"
# error), so any save written before the switch must be converted once to stay
# watchable. This is a STREAMING conversion (gzip -dc | zstd): it never holds
# the ~350 MB raw stream in memory, and it verifies a byte-exact round trip
# before replacing the original.
#
# Usage:
#   scripts/replay-codec-migrate.sh [dir]            # default: saved
#   scripts/replay-codec-migrate.sh saved --dry-run  # list what would convert
#
# Safe to re-run: zstd files are skipped (magic check), a failed verification
# leaves the original untouched, and the swap is tmp + rename (atomic on the
# same filesystem). Run it while the server is stopped, or at least not
# mid-save for the file being converted — a save in progress would be replaced
# by its verified snapshot (not corrupt, just the pre-save bytes), and the
# server would rewrite it in zstd on the next game end anyway.
set -euo pipefail

DIR="${1:-saved}"
DRY_RUN=""
for a in "$@"; do
    [ "$a" = "--dry-run" ] && DRY_RUN=1
done
[ -d "$DIR" ] || { echo "FATAL: no such directory: $DIR" >&2; exit 1; }

command -v zstd >/dev/null || { echo "FATAL: zstd CLI not installed" >&2; exit 1; }
# Level must match ReplayCodec.LEVEL.
LEVEL=9

shopt -s nullglob
files=("$DIR"/*.game)
if [ ${#files[@]} -eq 0 ]; then
    echo "no .game files in $DIR"
    exit 0
fi

converted=0; skipped=0; failed=0
for f in "${files[@]}"; do
    magic=$(head -c4 "$f" | od -An -tx1 | tr -d ' \n')
    case "$magic" in
        28b52ffd)
            skipped=$((skipped+1)); continue ;;
        1f8b*)
            : ;; # legacy gzip — convert
        *)
            echo "SKIP  $(basename "$f"): unknown magic $magic (not gzip, not zstd)"
            failed=$((failed+1)); continue ;;
    esac
    if [ -n "$DRY_RUN" ]; then
        echo "WOULD CONVERT $(basename "$f")  ($(du -h "$f" | cut -f1))"
        continue
    fi
    tmp="$f.zstd-tmp"
    echo "converting $(basename "$f") ($(du -h "$f" | cut -f1))..."
    # Record owner/group/mode BEFORE the swap. When this script runs as root
    # (needed to write a uid-10001-owned docker volume), tmp+rename would
    # otherwise leave the converted file root-owned — and the server runs as
    # uid 10001, so the next save of that game id fails (the docker-stack
    # latent-ownership trap). Restore them after the swap.
    orig_owner=$(stat -c '%u:%g' "$f")
    orig_mode=$(stat -c '%a' "$f")
    # Stream gzip -> zstd, then verify the round trip decodes byte-identical
    # to the original gzip payload BEFORE replacing anything.
    gzip -dc "$f" | zstd -$LEVEL -q -f -o "$tmp"
    if ! cmp -s <(gzip -dc "$f") <(zstd -dc "$tmp"); then
        echo "FAIL  $(basename "$f"): round-trip mismatch — original kept" >&2
        rm -f "$tmp"
        failed=$((failed+1)); continue
    fi
    mv -f "$tmp" "$f"
    chown "$orig_owner" "$f" 2>/dev/null || true
    chmod "$orig_mode" "$f" 2>/dev/null || true
    converted=$((converted+1))
    echo "  -> $(du -h "$f" | cut -f1)"
done

echo "done: converted=$converted skipped(zstd)=$skipped failed=$failed  dir=$DIR"
[ "$failed" -eq 0 ]
