#!/usr/bin/env bash
# Launch the XMage server for web-UI LLM games (charm or ollama-cloud provider).
#
# Secret hygiene: this file contains NO secrets and must never gain any. The API
# key is read at launch time from the file named by $XMAGE_KEY_FILE (outside the
# repo) and delivered to the server via the ENVIRONMENT (LLM1_KEY/LLM2_KEY,
# exported here and re-exported by start-server.sh). NEVER pass keys as JVM -D
# properties: they would sit in the world-readable /proc/<pid>/cmdline.
#
# Key resolution: set $XMAGE_KEY_FILE to a readable key file outside the repo.
#
# There is deliberately NO ./key.txt fallback inside the repo: an in-repo key file
# is one `git add -f` away from being committed.
#
# Provider selection: XMAGE_LLM_PROVIDER=charm (default) | ollama.
# Both LLM seats use the selected provider; effort defaults to "low" and can be
# overridden with XMAGE_LLM_EFFORT. Per-game overrides can still be set in the
# web UI's LLM config form — a field left empty there falls back to these
# server defaults.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

PROVIDER="${XMAGE_LLM_PROVIDER:-charm}"
case "$PROVIDER" in
    charm)
        PROVIDER_KEY_FILE="ckey.txt"
        DEFAULT_ENDPOINT="https://hyper.charm.land/v1/chat/completions"
        DEFAULT_MODEL="deepseek-v4.1-flash"
        ;;
    ollama)
        PROVIDER_KEY_FILE="okey.txt"
        DEFAULT_ENDPOINT="https://ollama.com/v1/chat/completions"
        DEFAULT_MODEL="deepseek-v4-flash:cloud"
        ;;
    *)
        echo "FATAL: XMAGE_LLM_PROVIDER='$PROVIDER' not supported (charm|ollama)" >&2
        exit 1
        ;;
esac

KEY_FILE="${XMAGE_KEY_FILE:-}"
if [ -z "$KEY_FILE" ]; then
    for candidate in "$HERE/../../ignore/$PROVIDER_KEY_FILE" "$HERE/../../key.txt"; do
        if [ -r "$candidate" ]; then KEY_FILE="$candidate"; break; fi
    done
fi
[ -n "$KEY_FILE" ] && [ -r "$KEY_FILE" ] || {
    echo "FATAL: no readable key file for provider '$PROVIDER' (set XMAGE_KEY_FILE to a readable key file outside the repo)" >&2
    exit 1
}
LLM_KEY="$(tr -d '[:space:]' < "$KEY_FILE")"
[ -n "$LLM_KEY" ] || { echo "FATAL: key file is empty: $KEY_FILE" >&2; exit 1; }

ENDPOINT="${XMAGE_LLM_ENDPOINT:-$DEFAULT_ENDPOINT}"
MODEL="${XMAGE_LLM_MODEL:-$DEFAULT_MODEL}"
EFFORT="${XMAGE_LLM_EFFORT:-low}"

export LLM1_MODEL="$MODEL" LLM1_ENDPOINT="$ENDPOINT" LLM1_KEY="$LLM_KEY" LLM1_EFFORT="$EFFORT"
export LLM2_MODEL="$MODEL" LLM2_ENDPOINT="$ENDPOINT" LLM2_KEY="$LLM_KEY" LLM2_EFFORT="$EFFORT"

# ── Argentum-P1 policy runtime (COMPUTER_P1 seat) ──────────────────────
# The COMPUTER_P1 seat (mage.player.p1.P1Player) drives a Python policy-net
# subprocess. P1Worker resolves these from the SERVER process environment (once
# per seat, at join) — they are NOT JVM -D flags:
#   P1_PYTHON       venv interpreter with torch + safetensors (the net is not an LLM)
#   P1_PKG_DIR      cwd for `-m argentum_ml.p1.serve` (the dir CONTAINING argentum_ml/)
#   P1_CHECKPOINT   checkpoint directory (the model weights)
# The runtime lives beside the repo (<repo-parent>/argentum-p1-runtime, ~1.4 GB)
# and is NOT part of the repo — resolved here exactly like the key files above.
# Unset, P1Worker falls back to a pruned scratch path, so a COMPUTER_P1 seat
# would fail at createPlayer. No secret here (paths only); override any of the
# three from the environment if the runtime moves.
P1_RUNTIME_DIR="${P1_RUNTIME_DIR:-$HERE/../../argentum-p1-runtime}"
export P1_PYTHON="${P1_PYTHON:-$P1_RUNTIME_DIR/venv/bin/python}"
export P1_PKG_DIR="${P1_PKG_DIR:-$P1_RUNTIME_DIR/pkg/ml/src}"
export P1_CHECKPOINT="${P1_CHECKPOINT:-$P1_RUNTIME_DIR/ckpt-0016}"

cd "$HERE"
exec ./start-server.sh