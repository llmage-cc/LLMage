#!/usr/bin/env bash
# Ask-reason live verification — ONE-COMMAND wrapper.
#
# Runs the full verification sequence for the ask-reason feature

#
#   leg flag-on-shapes1   the REAL case: every decision prompt carries the REASON
#          suffix on the wire (suffix=yes in the mock log) and the captured
#          reason lands in the transcript as '--- LLM STATED REASON ---'
#   leg flag-on-noshapes  flag ON, plain single-line mock (no MOCK_REASON_SHAPES):
#          no reason line ever arrives — the move must complete identically
#   leg flag-on-h1-action-only    hostile: action-only answer, no REASON line
#   leg flag-on-h2-reason-first   hostile: 'REASON:' as the first line (the
#          engine's format-correction path must recover the move)
#   leg flag-on-h3-trailing-junk  hostile: trailing junk after the reason line
#   leg flag-on-h4-empty-label    hostile: empty/mangled 'REASON:' label
#   leg flag-off                   ask_reason=false: ZERO 'has_suffix=yes' posts
#   leg flag-off-baseline OPTIONAL (BASELINE_PLUGIN_JAR set): same stack, the
#          PRE-FEATURE plugin jar, flag unset → the strongest
#          "flag-OFF == current master behavior" check available
#
# Then postchecks assert, over the collected evidence:
#   - transcript reason sections per leg policy (shapes1/h3 carry the mock
#     text; noshapes/h1/h4 carry none; h2 not asserted)
#   - prompt-body byte identity: flag-on mulligan minus the suffix == flag-off
#     mulligan, and flag-off == pre-feature baseline, byte-for-byte after
#     normalizing only the random per-game card-instance id tails (and one
#     pre-match opponent-line timing race, both non-feature-dependent)
#   - the freeform assessment class (no suffix by design) is byte-identical
#     on/off/baseline
#   - stop=["\n"] present flag-OFF, absent flag-ON (terse stop skipped)
#
# Each leg runs on a FRESH stack (restart between legs — a finished game leaves
# the previous one live and the next connect can stall).
#
# Usage:  bash scripts/verify-reason-field-live.sh
#         [--skip-flag-on] [--skip-hostile] [--skip-flag-off] [--quick]
# Env:    RUN_TAG (default timestamp), EVIDENCE_DIR,
#         BASELINE_PLUGIN_JAR (optional pre-feature mage-player-llm jar).
# Exit:   0 all legs passed; 2 a leg failed; 3 harness/setup error.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
EVIDENCE="${EVIDENCE_DIR:-$REPO/reasonverify-evidence}"
LOGS="$EVIDENCE/logs"
HOST=127.0.0.1
PROXY_PORT=8161
XMAGE_PORT=17261
RUN_TAG="${RUN_TAG:-$(date +%H%M%S)}"
QUICK=0
SKIP_FLAG_ON=0
SKIP_HOSTILE=0
SKIP_FLAG_OFF=0

while [ $# -gt 0 ]; do
    case "$1" in
        --quick) QUICK=1 ;;
        --skip-flag-on) SKIP_FLAG_ON=1 ;;
        --skip-hostile) SKIP_HOSTILE=1 ;;
        --skip-flag-off) SKIP_FLAG_OFF=1 ;;
        *) echo "unknown arg: $1" >&2; exit 3 ;;
    esac
    shift
done

mkdir -p "$LOGS"
FAILED=()

run_leg() {  # name ask_reason shapes require_decisions concede_turn [plugin_jar]
    local name="$1" ask="$2" shapes="$3" reqdec="$4" concede="$5" plugin="${6:-}"
    echo
    echo "════════════════════════════════════════════════════════════"
    echo "  LEG: $name   (ask_reason=$ask shapes='${shapes:-off}'${plugin:+ plugin=$(basename "$plugin")})"
    echo "════════════════════════════════════════════════════════════"
    bash "$HERE/reasonverify-teardown-stack.sh" >/dev/null 2>&1 || true
    if [ -n "$plugin" ]; then
        ASK_REASON="$ask" MOCK_REASON_SHAPES="$shapes" MOCK_DUMP_BODIES=1 PLUGIN_JAR="$plugin" \
            bash "$HERE/reasonverify-launch-stack.sh" \
            > "$LOGS/stack-$name-$RUN_TAG.log" 2>&1
    else
        ASK_REASON="$ask" MOCK_REASON_SHAPES="$shapes" MOCK_DUMP_BODIES=1 \
            bash "$HERE/reasonverify-launch-stack.sh" \
            > "$LOGS/stack-$name-$RUN_TAG.log" 2>&1
    fi
    local rc=$?
    if [ $rc -ne 0 ]; then
        echo "STACK LAUNCH FAILED ($name) — see $LOGS/stack-$name-$RUN_TAG.log" >&2
        tail -15 "$LOGS/stack-$name-$RUN_TAG.log" >&2
        FAILED+=("$name:stack")
        return 2
    fi
    tail -3 "$LOGS/stack-$name-$RUN_TAG.log"
    python3 "$HERE/reasonverify-live-driver.py" \
        --expect "$( [ "$ask" = true ] && echo flag-on || echo flag-off )" \
        --shapes "$shapes" --evidence-dir "$EVIDENCE" --run-name "$name" \
        --host "$HOST" --proxy "$PROXY_PORT" --xmage "$XMAGE_PORT" \
        --require-decisions "$reqdec" --concede-turn "$concede" \
        2>&1 | tee "$LOGS/driver-$name-$RUN_TAG.log" | tail -30
    local drc=${PIPESTATUS[0]}
    if [ "$drc" -ne 0 ]; then
        FAILED+=("$name:driver")
        return "$drc"
    fi
    return 0
}

# ── leg 1: flag-ON, normal REASON shape ──
if [ "$SKIP_FLAG_ON" = 0 ]; then
    run_leg "flag-on-shapes1" true 1 3 8
    # flag-ON with a PLAIN single-line mock (no MOCK_REASON_SHAPES): no reason
    # line ever arrives — the move must complete identically (hostile #1's
    # production analogue: a model that ignores the REASON instruction).
    run_leg "flag-on-noshapes" true "" 2 8
fi

# ── legs 2-4: hostile shapes ──
if [ "$SKIP_HOSTILE" = 0 ]; then
    run_leg "flag-on-h1-action-only" true h1 2 8
    run_leg "flag-on-h2-reason-first" true h2 2 8
    run_leg "flag-on-h3-trailing-junk" true h3 2 8
    if [ "$QUICK" = 0 ]; then
        run_leg "flag-on-h4-empty-label" true h4 2 8
    fi
fi

# ── leg 5: flag-OFF (no suffix must reach the wire) ──
if [ "$SKIP_FLAG_OFF" = 0 ]; then
    run_leg "flag-off" false "" 2 8
fi

# ── leg 7 (optional): pre-feature baseline jar, ask_reason unset ──
BASELINE_LEG=""
if [ -n "${BASELINE_PLUGIN_JAR:-}" ]; then
    run_leg "flag-off-baseline" false "" 2 8 "$BASELINE_PLUGIN_JAR"
    BASELINE_LEG="flag-off-baseline"
fi

bash "$HERE/reasonverify-teardown-stack.sh" >/dev/null 2>&1 || true

# ── post-checks: prompt-body byte diff + transcript reason audit ──
echo
echo "════════════════════════════════════════════════════════════"
echo "  POST-CHECKS"
echo "════════════════════════════════════════════════════════════"
LEGS=(flag-on-shapes1 flag-on-noshapes flag-on-h1-action-only flag-on-h2-reason-first
      flag-on-h3-trailing-junk flag-on-h4-empty-label flag-off)
[ -n "${BASELINE_LEG:-}" ] && LEGS+=("$BASELINE_LEG")
python3 "$HERE/reasonverify-postchecks.py" --evidence-dir "$EVIDENCE" \
    --legs "${LEGS[@]}" 2>&1 \
    | tee "$LOGS/postchecks-$RUN_TAG.log"
PRC=${PIPESTATUS[0]}
if [ "$PRC" -ne 0 ]; then FAILED+=("postchecks"); fi

echo
if [ ${#FAILED[@]} -gt 0 ]; then
    echo "RESULT: FAIL — ${FAILED[*]}"
    exit 2
fi
echo "RESULT: PASS (all legs + post-checks)"
exit 0
