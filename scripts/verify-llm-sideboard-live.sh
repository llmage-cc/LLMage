#!/usr/bin/env bash
# Live LLM-sideboard / post-game-summary driver wrapper.
#
# Drives the isolated stack (web proxy 8121, XMage 17191, strict mock 18121)
# through a Bo3 with a COMPUTER_LLM seat and asserts the two file paths
# fire during real play (sideboard summary + post-game summary).
#
# Prereqs:
#   * stack up:  REPO=<worktree> bash /tmp/llm-sideboard-e2e/launch-stack.sh
#   * mock up:   python3 /tmp/llm-sideboard-e2e/mock-llm.py 18121   (stage copy;
#                scripts/mock-llm-sideboard.py here is the same code)
#
# Env: HOST, PROXY_PORT, XMAGE_PORT, LLM_GAMELOGS, SERVER_LOG, TAG.
# Exit codes: 0 all checks passed; 2 a check failed; 3 harness/setup error.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${PYTHON:-python3}"
DRIVER="${HERE}/verify-llm-sideboard-live.py"

HOST="${HOST:-127.0.0.1}"
PROXY_PORT="${PROXY_PORT:-8121}"
XMAGE_PORT="${XMAGE_PORT:-17191}"
GAMELOGS="${LLM_GAMELOGS:-/tmp/llm-sideboard-e2e/gamelogs}"
SERVER_LOG="${SERVER_LOG:-/tmp/llm-sideboard-server.log}"
TAG="${TAG:-run$(date +%H%M%S)}"

if [[ ! -f "${DRIVER}" ]]; then
    echo "driver not found: ${DRIVER}" >&2
    exit 3
fi

echo "== live LLM-sideboard driver =="
echo "   driver : ${DRIVER}"
echo "   proxy  : ${HOST}:${PROXY_PORT}   xmage: ${HOST}:${XMAGE_PORT}   tag: ${TAG}"
echo "   gamelogs: ${GAMELOGS}"

if ! (exec 3<>"/dev/tcp/${HOST}/${PROXY_PORT}") 2>/dev/null; then
    echo "proxy ${HOST}:${PROXY_PORT} is not accepting connections — start the isolated stack first" >&2
    exit 3
fi
exec 3<&- 3>&- 2>/dev/null || true

"${PY}" "${DRIVER}" --host "${HOST}" --proxy "${PROXY_PORT}" --xmage "${XMAGE_PORT}" \
    --gamelogs "${GAMELOGS}" --server-log "${SERVER_LOG}" --tag "${TAG}" "$@"
exit $?
