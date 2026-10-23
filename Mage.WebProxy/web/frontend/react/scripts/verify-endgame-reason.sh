#!/usr/bin/env bash
# Run the end-reason checks under plain Node (no bundle: utils/endGame.ts is
# pure and imports nothing, and Node 22.6+/26 type-strips the harness itself).
#   bash scripts/verify-endgame-reason.sh
set -euo pipefail
cd "$(dirname "$0")/.."
exec node scripts/verify-endgame-reason.ts
