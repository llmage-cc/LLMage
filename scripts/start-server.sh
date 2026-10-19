#!/usr/bin/env bash
# Start the XMage server with LLM player support
# Usage: ./start-server.sh
#
# Runs from pre-built JARs in Mage.Server/release/lib/ and plugins/.
# Edit the LLM_CONFIG block below to change models.
# See LLM-README.md for full documentation.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# ── Archive previous logs so agents only see the current game ───────────
# Moves gamelogs/, gamelogsJson/, *.log into oldlogs/ with a timestamp.
# Safe to skip if archive-logs.sh is missing (e.g. fresh checkout).
if [ -x ./scripts/archive-logs.sh ]; then
    ./scripts/archive-logs.sh
else
    echo "WARNING: archive-logs.sh not found — previous logs will accumulate" >&2
fi

# ── Materialize plugin symlinks from release JARs ──────────────────────
# The server loads plugins from ./plugins/ (relative to CWD). Missing links
# = "Unknown player type" FATALs and AI seats never joining
#. Idempotent, fails on
# dangling links.
./scripts/bootstrap-plugins.sh

# ── JDK 21 module flags for JBoss Remoting ────────────────────────────
JAVA_OPENS=(
  --add-opens java.base/java.lang=ALL-UNNAMED
  --add-opens java.base/java.util=ALL-UNNAMED
  --add-opens java.base/java.io=ALL-UNNAMED
  --add-opens java.base/java.lang.reflect=ALL-UNNAMED
  --add-opens java.base/java.text=ALL-UNNAMED
  --add-opens java.base/java.net=ALL-UNNAMED
  --add-opens java.base/java.security=ALL-UNNAMED
  --add-opens java.base/sun.security.action=ALL-UNNAMED
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED
  --add-opens java.base/sun.reflect=ALL-UNNAMED
  --add-opens java.base/jdk.internal.access=ALL-UNNAMED
  --add-opens java.base/jdk.internal.ref=ALL-UNNAMED
  --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
  --add-opens java.desktop/java.beans=ALL-UNNAMED
  --add-opens java.rmi/sun.rmi.transport=ALL-UNNAMED
)

# ── LLM Configuration ─────────────────────────────────────────────────
# Models can be overridden via environment variables LLM1_MODEL / LLM2_MODEL,
# which is how the autonomous test orchestrator selects random models per game.
# Reasoning effort can also be overridden via LLM1_EFFORT / LLM2_EFFORT.
#
# Custom providers: set LLM{n}_ENDPOINT / LLM{n}_KEY / LLM{n}_CONTEXT_WINDOW
# env vars (see "Custom Providers" in LLM-README.md). Endpoints must be
# OpenAI-compatible chat-completions URLs. Context window detection:
# Ollama /api/show, or /v1/models for providers that expose context_length
# there (OpenRouter, Groq, Cerebras, LM Studio). Providers that don't
# (e.g. OpenAI itself) REQUIRE LLM{n}_CONTEXT_WINDOW (tokens).
LLM1_MODEL="${LLM1_MODEL:-gemma4:31b-cloud}"
LLM1_ENDPOINT="${LLM1_ENDPOINT:-http://localhost:11434/v1/chat/completions}"
LLM1_KEY="${LLM1_KEY:-}"
LLM1_EFFORT="${LLM1_EFFORT:-high}"
LLM1_STREAM="true"
LLM1_VERBOSE="true"
LLM1_CONCEDE="false"
LLM1_ASK_REASON="${LLM1_ASK_REASON:-false}"
LLM1_PERSONALITY_ENABLED="false"
LLM1_PERSONALITY_NAME=""
LLM1_NAME=""

# LLM 2 (player 2, used with \botgame --llm2): change model and endpoint here
LLM2_MODEL="${LLM2_MODEL:-deepseek-v4-flash:cloud}"
LLM2_ENDPOINT="${LLM2_ENDPOINT:-http://localhost:11434/v1/chat/completions}"
LLM2_KEY="${LLM2_KEY:-}"
LLM2_EFFORT="${LLM2_EFFORT:-max}"
LLM2_STREAM="true"
LLM2_VERBOSE="true"
LLM2_CONCEDE="false"
LLM2_ASK_REASON="${LLM2_ASK_REASON:-false}"
LLM2_PERSONALITY_ENABLED="false"
LLM2_PERSONALITY_NAME=""
LLM2_NAME=""

# ── Conversation memory (limits per bot) ──────────────────────────────
# 0 = unlimited (infinity). : defaults changed from
# 100000/9999999/1000000 to 0 — summarization handles context overflow.
LLM_CONV_MAX_EXCHANGES="0"
LLM_CONV_MAX_CHARS="0"
LLM_CONV_THINK_CHARS_PER_MSG="${LLM_CONV_THINK_CHARS_PER_MSG:-0}"  # 0 = keep full reasoning; set a positive cap for low-context models that need thinking truncation
LLM_MAX_TOKENS="65536"                              # cap total output (reasoning + answer) to prevent reasoning loops
LLM_EFFORT_LEVELS="${LLM1_EFFORT_LEVELS:-max,high,medium,low}"   # descent ladder for empty-answer retries (highest first)

# Thinking request dialect: 'effort' (reasoning_effort, DeepSeek/ollama-cloud — default)
# or 'budget' (thinking.budget_tokens, Anthropic-style). The startup probe in
# LLMPlayer.init validates the configured dialect against the endpoint and crashes
# at boot if it's rejected — never mid-game. Budget ladder used only in budget mode.
LLM1_THINKING_DIALECT="${LLM1_THINKING_DIALECT:-effort}"
LLM1_THINKING_BUDGET="${LLM1_THINKING_BUDGET:-16000,8000,4000,1000}"
# Hard reasoning cap in effort mode: bounds the thinking stream so a reasoning
# loop can't consume the whole max_tokens budget and leave the answer empty.
# Verified accepted alongside reasoning_effort on ollama-cloud. 0 = no cap.
LLM1_THINKING_MAX_TOKENS="${LLM1_THINKING_MAX_TOKENS:-32768}"
# Terse-answer stop: action/target prompts send stop:["\n"] so continuation
# text never reaches the validators. Verified on ollama-cloud.
LLM1_TERSE_STOP="${LLM1_TERSE_STOP:-true}"
LLM1_SKIP_PROBE="${LLM1_SKIP_PROBE:-false}"                      # true = skip startup thinking probe (CI)

LLM2_CONV_MAX_EXCHANGES="0"                        # 0 = unlimited (infinity)
LLM2_CONV_MAX_CHARS="0"                            # 0 = unlimited (infinity)
LLM2_CONV_THINK_CHARS_PER_MSG="${LLM2_CONV_THINK_CHARS_PER_MSG:-0}"  # 0 = keep full reasoning
LLM2_MAX_TOKENS="65536"                             # cap total output (reasoning + answer) to prevent reasoning loops
LLM2_EFFORT_LEVELS="${LLM2_EFFORT_LEVELS:-max,high,medium,low}"  # descent ladder for empty-answer retries (highest first)
LLM2_THINKING_DIALECT="${LLM2_THINKING_DIALECT:-effort}"
LLM2_THINKING_BUDGET="${LLM2_THINKING_BUDGET:-16000,8000,4000,1000}"
LLM2_THINKING_MAX_TOKENS="${LLM2_THINKING_MAX_TOKENS:-32768}"
LLM2_TERSE_STOP="${LLM2_TERSE_STOP:-true}"
LLM2_SKIP_PROBE="${LLM2_SKIP_PROBE:-false}"

# ── Conversation summarization (compress history to prevent ctx overflow) ─
# Context window is auto-detected from Ollama if using local endpoint.
# These ratios scale with the model's context window for per-model tuning.
LLM_SUMMARIZE_CONTEXT_RATIO="0.5"                    # trigger summarization at this fraction of context window
LLM_SUMMARIZE_TARGET_RATIO="0.25"                    # target summary size as fraction of context (chars ~ tokens × 2 × ratio)
LLM_SUMMARIZE_KEEP_EXCHANGES="5"                     # number of recent exchanges to retain after summary

LLM2_SUMMARIZE_CONTEXT_RATIO="0.5"                   # trigger summarization at this fraction of context window
LLM2_SUMMARIZE_TARGET_RATIO="0.25"                   # target summary size as fraction of context (chars ~ tokens × 2 × ratio)
LLM2_SUMMARIZE_KEEP_EXCHANGES="5"                    # number of recent exchanges to retain after summary (was 8)

# ── Context window (custom providers) ────────────────────────────────
# Auto-detected for local endpoints (Ollama /api/show) and for OpenAI-compatible
# providers that expose context_length in /v1/models (OpenRouter, Groq, Cerebras,
# LM Studio). Providers that don't (e.g. OpenAI itself) REQUIRE an explicit
# value here — the player fails to start without one (Fail Fast).
LLM1_CONTEXT_WINDOW="${LLM1_CONTEXT_WINDOW:-}"
LLM2_CONTEXT_WINDOW="${LLM2_CONTEXT_WINDOW:-}"

# ── Conditional -D flags ───────────────────────────────────────────────
# SECURITY (2026-09-18, ): LLM API keys are NOT passed as
# -D flags — they'd sit in the JVM command line, world-readable at
# /proc/<pid>/cmdline. They are delivered via the environment instead
# (exported below); LLMPlayer.secretProp()/BotGameHandler read LLM1_KEY/LLM2_KEY
# from the environment. Non-secret LLM_* settings remain -D flags.
LLM_FLAGS=()
if [[ -n "$LLM1_CONTEXT_WINDOW" ]]; then LLM_FLAGS+=(-Dxmage.llm.model_context_window="$LLM1_CONTEXT_WINDOW"); fi
if [[ -n "$LLM2_CONTEXT_WINDOW" ]]; then LLM_FLAGS+=(-Dxmage.llm2.model_context_window="$LLM2_CONTEXT_WINDOW"); fi

# Keys ride in the environment (owner-readable /proc/PID/environ only), not argv.
export LLM1_KEY LLM2_KEY

# Dev-rig heap. Measured 2026-09-23 against the biggest save on disk
# (34.7 MB / 1274 states, heap_floor_probe): it OOMs at -Xmx768m and passes at
# 1g with ~730 MB live. A typical save walks in ~150 MB and an idle server holds
# ~150 MB after GC, so 2g leaves room for a second concurrent replay session
# (sessions are cached up to 1 h) without reserving unused headroom.
# Raise for heavy sweeps with XMAGE_SERVER_HEAP=4g ... (and the probes want 4g+).
SERVER_HEAP="${XMAGE_SERVER_HEAP:-2g}"

exec java -Xmx"$SERVER_HEAP" \
  "${JAVA_OPENS[@]}" \
  "${LLM_FLAGS[@]}" \
  -cp "Mage.Server/release/lib/*:Mage.Server/release/plugins/*" \
  -Dxmage.config.path="${XMAGE_CONFIG_PATH:-Mage.Server/config/config.xml}" \
  -Dxmage.llm.model="$LLM1_MODEL" \
  -Dxmage.llm.endpoint="$LLM1_ENDPOINT" \
  -Dxmage.llm.reasoningeffort="$LLM1_EFFORT" \
  -Dxmage.llm.reasoning_effort_levels="$LLM_EFFORT_LEVELS" \
  -Dxmage.llm.thinking_dialect="$LLM1_THINKING_DIALECT" \
  -Dxmage.llm.thinking_budget_tokens="$LLM1_THINKING_BUDGET" \
  -Dxmage.llm.thinking_max_tokens="$LLM1_THINKING_MAX_TOKENS" \
  -Dxmage.llm.terse_answer_stop="$LLM1_TERSE_STOP" \
  -Dxmage.llm.skip_thinking_probe="$LLM1_SKIP_PROBE" \
  -Dxmage.llm.stream="$LLM1_STREAM" \
  -Dxmage.llm.verbose="$LLM1_VERBOSE" \
  -Dxmage.llm.concede_enabled="$LLM1_CONCEDE" \
  -Dxmage.llm.ask_reason="$LLM1_ASK_REASON" \
  -Dxmage.llm.personality_enabled="$LLM1_PERSONALITY_ENABLED" \
  -Dxmage.llm.personality_name="$LLM1_PERSONALITY_NAME" \
  -Dxmage.llm.bot_name="$LLM1_NAME" \
  -Dxmage.llm.transcript_dir="gamelogs" \
  -Dxmage.llm2.model="$LLM2_MODEL" \
  -Dxmage.llm2.endpoint="$LLM2_ENDPOINT" \
  -Dxmage.llm2.reasoningeffort="$LLM2_EFFORT" \
  -Dxmage.llm2.reasoning_effort_levels="$LLM2_EFFORT_LEVELS" \
  -Dxmage.llm2.thinking_dialect="$LLM2_THINKING_DIALECT" \
  -Dxmage.llm2.thinking_budget_tokens="$LLM2_THINKING_BUDGET" \
  -Dxmage.llm2.thinking_max_tokens="$LLM2_THINKING_MAX_TOKENS" \
  -Dxmage.llm2.terse_answer_stop="$LLM2_TERSE_STOP" \
  -Dxmage.llm2.skip_thinking_probe="$LLM2_SKIP_PROBE" \
  -Dxmage.llm2.stream="$LLM2_STREAM" \
  -Dxmage.llm2.verbose="$LLM2_VERBOSE" \
  -Dxmage.llm2.concede_enabled="$LLM2_CONCEDE" \
  -Dxmage.llm2.ask_reason="$LLM2_ASK_REASON" \
  -Dxmage.llm2.personality_enabled="$LLM2_PERSONALITY_ENABLED" \
  -Dxmage.llm2.personality_name="$LLM2_PERSONALITY_NAME" \
  -Dxmage.llm2.bot_name="$LLM2_NAME" \
  -Dxmage.llm.conversation_max_exchanges="$LLM_CONV_MAX_EXCHANGES" \
  -Dxmage.llm.conversation_max_history_chars="$LLM_CONV_MAX_CHARS" \
  -Dxmage.llm.conversation_think_chars_per_msg="$LLM_CONV_THINK_CHARS_PER_MSG" \
  -Dxmage.llm.max_tokens="$LLM_MAX_TOKENS" \
  -Dxmage.llm2.conversation_max_exchanges="$LLM2_CONV_MAX_EXCHANGES" \
  -Dxmage.llm2.conversation_max_history_chars="$LLM2_CONV_MAX_CHARS" \
  -Dxmage.llm2.conversation_think_chars_per_msg="$LLM2_CONV_THINK_CHARS_PER_MSG" \
  -Dxmage.llm2.max_tokens="$LLM2_MAX_TOKENS" \
  -Dxmage.llm.summarize_context_ratio="$LLM_SUMMARIZE_CONTEXT_RATIO" \
  -Dxmage.llm.summarize_target_ratio="$LLM_SUMMARIZE_TARGET_RATIO" \
  -Dxmage.llm.summarize_keep_exchanges="$LLM_SUMMARIZE_KEEP_EXCHANGES" \
  -Dxmage.llm2.summarize_context_ratio="$LLM2_SUMMARIZE_CONTEXT_RATIO" \
  -Dxmage.llm2.summarize_target_ratio="$LLM2_SUMMARIZE_TARGET_RATIO" \
  -Dxmage.llm2.summarize_keep_exchanges="$LLM2_SUMMARIZE_KEEP_EXCHANGES" \
  mage.server.Main
