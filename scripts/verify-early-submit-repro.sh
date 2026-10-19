#!/usr/bin/env bash
# Early-sideboard-submit repro/regression gate (issue

#
# Drives two raw-WS clients against an ISOLATED Bo3 stack:
#   game server 127.0.0.1:17181 (secondary 17183), web proxy 127.0.0.1:8101.
#
# Modes (pass exactly one; no flag = informational run without assertions):
#   ./verify-early-submit-repro.sh --expect-fixed   post-fix gate (use this in CI)
#   ./verify-early-submit-repro.sh --expect-base    base-failure gate
#   ./verify-early-submit-repro.sh                  informational
#
# Env overrides: PROXY_PORT, XMAGE_PORT, HOST, TAG.
# Exit codes: 0 all checks passed; 2 check failed; 3 harness/setup error.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${PYTHON:-python3}"
DRIVER="${HERE}/verify-early-submit-repro.py"

HOST="${HOST:-127.0.0.1}"
PROXY_PORT="${PROXY_PORT:-8101}"
XMAGE_PORT="${XMAGE_PORT:-17181}"
TAG="${TAG:-run$(date +%H%M%S)}"

if [[ ! -f "${DRIVER}" ]]; then
    echo "driver not found: ${DRIVER}" >&2
    exit 3
fi

echo "== early-submit repro gate =="
echo "   driver: ${DRIVER}"
echo "   proxy : ${HOST}:${PROXY_PORT}   xmage: ${HOST}:${XMAGE_PORT}   tag: ${TAG}"

# Fail fast (with a clear message) when the stack is not up, so a missing
# stack is never misread as a check failure.
if ! (exec 3<>"/dev/tcp/${HOST}/${PROXY_PORT}") 2>/dev/null; then
    echo "proxy ${HOST}:${PROXY_PORT} is not accepting connections — start the isolated stack first" >&2
    exit 3
fi
exec 3<&- 3>&- 2>/dev/null || true

"${PY}" "${DRIVER}" --host "${HOST}" --proxy "${PROXY_PORT}" --xmage "${XMAGE_PORT}" \
    --tag "${TAG}" "$@"
exit $?
