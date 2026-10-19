#!/usr/bin/env bash
# Live E2E wrapper — async post-game reflection.
#
# Run only as part of the async post-game reflection test.
# Ports are RESERVED for that test (server 17281/17283, proxy 8181,
# mock 18281) — do not claim them outside the test run.
#
# What it does, in order:
#   1. (unless --no-launch) stands up the isolated stack via
#      verify-postgame-reflection-async-launch.sh into /tmp/reflection-stage
#   2. CONTROL leg — no stall: all checks must pass, including "postgame files
#      ARE written" for games 1 and 2 with the reflection content.
#   3. STALL leg — stack restarted (mock holds the reflection response for
#      MOCK_STALL_REFLECTION_SECONDS, default 180s): the match must proceed
#      anyway — game-1 result recorded correctly (NOT a draw), sideboard ran,
#      game 2 started WITHOUT waiting for the hung reflection, match completed.
#   4. Teardown (unless --keep).
#
# Exit codes: 0 = both legs PASS; 2 = a leg FAILED (message names the leg);
#            3 = harness/setup error (stack did not come up, etc.).
#
# The failure this test guards against: the winner's reflection hung >600s ON
# the game worker thread; the exception propagated through checkIfGameIsOver,
# the match was recorded as "Game is a draw" and game 2 never started (zombie
# table). Reflection therefore runs OFF the game thread (async): match +
# sideboard proceed immediately; failure = no postgame file, loud log.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DRIVER="${HERE}/verify-postgame-reflection-async.py"
LAUNCHER="${HERE}/verify-postgame-reflection-async-launch.sh"
PY="${PYTHON:-python3}"

HOST="${HOST:-127.0.0.1}"
PROXY_PORT="${PROXY_PORT:-8181}"
XMAGE_PORT="${XMAGE_PORT:-17281}"
MOCK_PORT="${MOCK_PORT:-18281}"
STAGE="${STAGE:-/tmp/llm-reflection-stage}"
GAMELOGS="${LLM_GAMELOGS:-$STAGE/gamelogs}"
SERVER_LOG="${SERVER_LOG:-$STAGE/server.log}"
MOCK_LOG="${MOCK_LOG:-$STAGE/mock.log}"

# Mock holds the reflection response this long in the STALL leg. Must exceed
# the time the match needs to get from game-1 game_over to game-2 start
# (~10-40s observed in the sibling driver) so the "game 2 started while the
# reflection was still held" assertion is real.
STALL_SECONDS="${STALL_SECONDS:-180}"
GAME2_MAX_DELTA="${GAME2_MAX_DELTA:-120}"

NO_LAUNCH=0; KEEP=0
for arg in "$@"; do
    case "$arg" in
        --no-launch) NO_LAUNCH=1 ;;
        --keep)      KEEP=1 ;;
        *) echo "unknown option: $arg" >&2; exit 3 ;;
    esac
done

[ -f "$DRIVER" ] || { echo "FAIL(setup): driver not found: $DRIVER" >&2; exit 3; }

run_leg() {
    local leg="$1" stall="$2" tag="$3"
    echo
    echo "=================================================================="
    echo " LEG: ${leg^^}   (stall=${stall}s, tag=${tag})"
    echo "=================================================================="
    "$PY" "$DRIVER" --host "$HOST" --proxy "$PROXY_PORT" --xmage "$XMAGE_PORT" \
        --mock "$MOCK_PORT" --gamelogs "$GAMELOGS" --server-log "$SERVER_LOG" \
        --mock-log "$MOCK_LOG" --tag "$tag" --leg "$leg" --stall-seconds "$stall" \
        --game2-max-delta "$GAME2_MAX_DELTA"
    return $?
}

echo "== live postgame-reflection-async driver =="
echo "   stage   : ${STAGE}"
echo "   proxy   : ${HOST}:${PROXY_PORT}   xmage: ${HOST}:${XMAGE_PORT}   mock: ${HOST}:${MOCK_PORT}"
echo "   stall   : ${STALL_SECONDS}s (stall leg only)"
echo "   gamelogs: ${GAMELOGS}"

if [ "$NO_LAUNCH" = 0 ]; then
    [ -f "$LAUNCHER" ] || { echo "FAIL(setup): launcher not found: $LAUNCHER" >&2; exit 3; }
    MOCK_STALL_REFLECTION_SECONDS="" bash "$LAUNCHER" \
        || { echo "FAIL(setup): control-leg stack did not come up (launcher rc=$?)" >&2; exit 3; }
else
    (exec 3<>"/dev/tcp/${HOST}/${PROXY_PORT}") 2>/dev/null \
        || { echo "FAIL(setup): --no-launch but proxy ${HOST}:${PROXY_PORT} is not accepting" >&2; exit 3; }
    exec 3<&- 3>&- 2>/dev/null || true
fi

CTRL_TAG="c$(date +%H%M%S)"
run_leg control 0 "$CTRL_TAG"
CTRL_RC=$?
if [ "$CTRL_RC" != 0 ]; then
    echo
    echo "FAIL: CONTROL leg failed (rc=$CTRL_RC). The healthy path regressed:"
    echo "      postgame reflection files must still be written. See checks above."
    [ "$KEEP" = 1 ] || { [ -f "${HERE}/verify-postgame-reflection-async-teardown.sh" ] && bash "${HERE}/verify-postgame-reflection-async-teardown.sh" >/dev/null 2>&1 || true; }
    exit 2
fi

# ── STALL leg: restart the stack with the mock holding the reflection ──
if [ "$NO_LAUNCH" = 0 ]; then
    TEARDOWN="${HERE}/verify-postgame-reflection-async-teardown.sh"
    [ -f "$TEARDOWN" ] && bash "$TEARDOWN" || true
    echo
    echo "== restarting stack for the STALL leg (mock holds reflection ${STALL_SECONDS}s) =="
    MOCK_STALL_REFLECTION_SECONDS="$STALL_SECONDS" bash "$LAUNCHER" \
        || { echo "FAIL(setup): stall-leg stack did not come up (launcher rc=$?)" >&2; exit 3; }
fi

STALL_TAG="s$(date +%H%M%S)"
run_leg stall "$STALL_SECONDS" "$STALL_TAG"
STALL_RC=$?

if [ "$KEEP" = 0 ] && [ -f "${HERE}/verify-postgame-reflection-async-teardown.sh" ]; then
    bash "${HERE}/verify-postgame-reflection-async-teardown.sh" || true
fi

echo
if [ "$STALL_RC" != 0 ]; then
    echo "FAIL: STALL leg failed (rc=$STALL_RC). Ruling-C contract violated —"
    echo "      the hung reflection must NOT delay/break the match lifecycle."
    echo "      Expected: game-1 result correct (not a draw), sideboard ran,"
    echo "      game 2 started <=${GAME2_MAX_DELTA}s after game-1 end, match completed."
    exit 2
fi
echo "RESULT: BOTH LEGS PASS — control (postgame files written) + stall (match proceeds while reflection hangs)."
exit 0
