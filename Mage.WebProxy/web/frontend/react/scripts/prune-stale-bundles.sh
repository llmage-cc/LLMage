#!/usr/bin/env bash
# Prune stale hashed Vite bundles from web/frontend/assets/.
#
# vite.config.ts sets emptyOutDir:false because web/frontend/ also holds the
# hand-written CSS that a full outDir wipe would delete. The cost is that old
# index-<hash>.js files accumulate and stay publicly servable by the proxy.
# This script removes every assets/index-*.js that the current index.html does
# NOT reference, leaving everything else (CSS, images) untouched.
#
# Run from the react frontend dir after `npm run build`, or from anywhere —
# paths resolve relative to this file.
set -euo pipefail

FRONTEND="$(cd "$(dirname "$0")/../.." && pwd)"   # web/frontend (script lives in react/scripts/)
ASSETS="$FRONTEND/assets"
INDEX="$FRONTEND/index.html"

if [ ! -f "$INDEX" ]; then
    echo "ERROR: $INDEX not found — run this after a frontend build." >&2
    exit 1
fi
if [ ! -d "$ASSETS" ]; then
    echo "assets/ does not exist yet — nothing to prune."
    exit 0
fi

# The current bundle is whatever index.html references.
CURRENT=$(grep -o 'assets/index-[^"]*\.js' "$INDEX" | sort -u)
if [ -z "$CURRENT" ]; then
    echo "ERROR: index.html references no assets/index-*.js bundle." >&2
    exit 1
fi

REMOVED=0
for f in "$ASSETS"/index-*.js; do
    [ -e "$f" ] || continue
    base="assets/$(basename "$f")"
    if ! grep -qF "$base" "$INDEX"; then
        rm "$f"
        echo "pruned stale bundle: $base"
        REMOVED=$((REMOVED + 1))
    fi
done

echo "prune complete: $REMOVED stale bundle(s) removed, current bundle kept ($CURRENT)"