#!/usr/bin/env bash
# Bundle + run the game-header geometry checks in a REAL Chromium (playwright-core).
#
#   bash scripts/verify-header-gap.sh
#
# Covers : .game-header-bar's menu child
# carries an inline margin-left:auto, which absorbs all free space, so
# space-between left the title flush against #turn-info (0 px measured). The fix
# adds a --ui-scale gap; this harness loads the REAL layout.css + base.css from
# disk with the REAL GameHeader markup and MEASURES the geometry:
#   gap > 0 at --ui-scale 1 / 1.35 / 2, the gap scales, the menu stays pinned
#   right, the title keeps its padding, and a negative control (gap stripped)
#   collapses back to 0 px.
#
# playwright-core + its Chromium come from the HOST (NODE_PATH
# =$XMAGE_PLAYWRIGHT_NODE_PATH, default ~/.local/lib/node_modules; browsers in
# ~/.cache/ms-playwright), NOT from this project's node_modules — the harness
# falls back to a static declaration assertion (and says so) if the browser
# cannot launch.
set -euo pipefail
cd "$(dirname "$0")/.."
HERE="$(pwd)"

# Locate the host playwright-core: explicit NODE_PATH wins, then the known
# per-user location. Keeps the harness runnable from a worktree whose
# node_modules symlink points at the main checkout.
HOST_MODULES="${XMAGE_PLAYWRIGHT_NODE_PATH:-$HOME/.local/lib/node_modules}"
if [ ! -d "$HOST_MODULES/playwright-core" ]; then
  echo "WARNING: playwright-core not found under $HOST_MODULES — the harness will use its static fallback" >&2
fi

cat > /tmp/xmage-header-gap-entry.ts <<EOF
import '${HERE}/scripts/verify-header-gap.ts'
EOF
./node_modules/.bin/rolldown /tmp/xmage-header-gap-entry.ts --file /tmp/xmage-header-gap.mjs --format esm --platform node >/dev/null
NODE_PATH="$HOST_MODULES" XMAGE_VERIFY_DIR="$HERE/scripts" node /tmp/xmage-header-gap.mjs
