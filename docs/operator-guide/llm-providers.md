# LLM providers

Every LLM seat talks to one HTTP endpoint that speaks the OpenAI chat-completions shape. That
single contract covers a local Ollama daemon, a hosted instance, an aggregator, a server you run
yourself, and a commercial API. What differs is the model name, the key, whether the provider
reports a context window, and which thinking parameters it accepts.

> **Who this is for:** an operator configuring provider access for a server, not a player choosing
> a model at the table. The player-facing view is on
> [LLM opponents](../player-guide/llm-opponents.md).

## The endpoint contract

A seat sends `POST` requests to the endpoint exactly as configured. Three requirements follow:

- The endpoint must end in `/v1/chat/completions` or `/v1`. Model discovery and context-window
  detection strip that suffix to build their own URLs; anything else fails fast.
- The key, when set, is sent as a bearer token in the `Authorization` header.
- Streaming is on by default; a provider that buffers the whole answer still works.

Temperature is omitted unless a seat overrides it, so the provider's default applies. Output length
follows the `max_tokens` settings below; with no cap the field is omitted and the provider decides.

## Provider recipes

| Provider | Endpoint | Model names | Context window |
|---|---|---|---|
| Local Ollama | `http://localhost:11434/v1/chat/completions` | Whatever `ollama list` shows, for example `<model-name>` | Read from `/api/show`. No key. |
| Hosted Ollama | `https://ollama.com/v1/chat/completions` | Cloud model identifiers | The URL contains `ollama`, so the Ollama probe path is used. |
| OpenRouter, Groq, Cerebras, LM Studio | The provider's `/v1/chat/completions` URL | Provider-specific identifiers | Read from `/v1/models`, which exposes `context_length`. |
| OpenAI | `https://api.openai.com/v1/chat/completions` | Provider model identifiers | Not exposed by `/v1/models`; set it explicitly. |
| vLLM, llama.cpp server, and similar | Your server's `/v1/chat/completions` | The served model name | Read from `/v1/models` when the server reports it, otherwise set it explicitly. |

The Ollama-versus-OpenAI distinction is a URL heuristic: a URL containing `ollama` or the port
`11434` is treated as Ollama, everything else as OpenAI-compatible. A remote Ollama behind an
unrelated hostname is misclassified; put `11434` in the URL or set the window manually.

## How a seat gets its configuration

On the bot-game path the precedence is: a JVM `-D` property, the matching `config.xml` attribute,
the built-in default. Table seats read the properties and their per-seat values; the file's `llm*`
attributes feed the bot-game handler. Per-seat values win for that seat only, and an empty field
inherits. The second seat reads `xmage.llm2.*` first, falling back to `xmage.llm.*` for streaming,
verbosity, conversation limits, and the key.

A seat then resolves its context window, probes thinking support, and starts deciding; a failure at
either step aborts seat creation, not the game.

## Property reference: connection and model

| Property | Launcher variable | Default | Meaning |
|---|---|---|---|
| `xmage.llm.endpoint` | `LLM1_ENDPOINT` | `http://localhost:11434/v1/chat/completions` | Chat-completions URL. |
| `xmage.llm.model` | `LLM1_MODEL` | `deepseek-v4-flash:cloud` in code; the launcher supplies its own | Model identifier sent to the provider. |
| `xmage.llm.key` | `LLM1_KEY` | empty | Provider key. Use the environment, not this property. |
| `xmage.llm.model_context_window` | `LLM1_CONTEXT_WINDOW` | `131072`, not explicit | Total context in tokens. Setting it also disables detection. |
| `xmage.llm.stream` | `LLM1_STREAM` | `true` | Stream tokens as they arrive. |
| `xmage.llm.verbose` | `LLM1_VERBOSE` | `false` in code; the launcher forces `true` | Log full prompts and responses. |
| `xmage.llm.transcript_dir` | — | `none` in code; the launcher passes `gamelogs` | Write one transcript file per game and seat. Set `llmTranscriptDir` in `config.xml` or this property. |
| `xmage.llm.max_tokens` | `LLM_MAX_TOKENS` | `0`; the launcher sets `65536` | Cap on total output, reasoning included. `0` omits the field. |

The `xmage.llm2.*` names mirror these. There is no third seat: LLM-versus-LLM games use two.

## Property reference: reasoning and retries

| Property | Launcher variable | Default | Meaning |
|---|---|---|---|
| `xmage.llm.reasoningeffort` | `LLM1_EFFORT` | `max` in code, `high` in the launcher | One of `low`, `medium`, `high`, `max`, as the model defines them. |
| `xmage.llm.reasoning_effort_levels` | `LLM1_EFFORT_LEVELS` | `max,high,medium,low` | Descent ladder for empty answers, highest first. |
| `xmage.llm.thinking_dialect` | `LLM1_THINKING_DIALECT` | `effort` | `effort` sends `reasoning_effort`; `budget` sends `thinking.budget_tokens`. |
| `xmage.llm.thinking_budget_tokens` | `LLM1_THINKING_BUDGET` | `16000,8000,4000,1000` | Budget ladder used in `budget` mode. |
| `xmage.llm.thinking_max_tokens` | `LLM1_THINKING_MAX_TOKENS` | `32768` | Hard cap on reasoning tokens in `effort` mode. `0` disables the cap. |
| `xmage.llm.terse_answer_stop` | `LLM1_TERSE_STOP` | `true` | Sends `stop: ["\n"]` on prompts with a strict answer format. |
| `xmage.llm.skip_thinking_probe` | `LLM1_SKIP_PROBE` | `false` | Skips the startup probe. For automated environments with no live endpoint. |
| `xmage.llm.ask_reason` | `LLM1_ASK_REASON` | `false` | Asks for an extra `REASON:` line after the answer. |
| `xmage.llm.concede_enabled` | `LLM1_CONCEDE` | `true` in code, `false` in the launcher | Allows the seat to concede. |
| `xmage.llm.personality_enabled` | `LLM1_PERSONALITY_ENABLED` | `true` in code, `false` in the launcher | Injects a personality into the system prompt. |
| `xmage.llm.personality_name` | `LLM1_PERSONALITY_NAME` | none | Forces a specific personality instead of deriving one from the bot name. |
| `xmage.llm.bot_name` | `LLM1_NAME` | none | Forces a specific seat name instead of a generated one. |

Some settings exist only per seat and have no server property: temperature and its override flag,
the thinking on/off switch, deck assessment, the per-call timeout, and the client-key bridge
fields. Set those in the table dialog.

## Property reference: conversation memory

| Property | Launcher variable | Default | Meaning |
|---|---|---|---|
| `xmage.llm.conversation_max_exchanges` | `LLM_CONV_MAX_EXCHANGES` | `0` | Maximum retained user/assistant pairs. `0` is unlimited. |
| `xmage.llm.conversation_max_history_chars` | `LLM_CONV_MAX_CHARS` | `0` | Maximum retained characters. `0` is unlimited. |
| `xmage.llm.conversation_think_chars_per_msg` | `LLM_CONV_THINK_CHARS_PER_MSG` | `0` | Truncate stored reasoning per message. `0` keeps it whole. |
| `xmage.llm.summarize_context_ratio` | `LLM_SUMMARIZE_CONTEXT_RATIO` | `0.65` in code, `0.5` in the launcher | Fraction of the context window that triggers summarization. |
| `xmage.llm.summarize_target_ratio` | `LLM_SUMMARIZE_TARGET_RATIO` | `0.25` | Target summary size as a fraction of the window. |
| `xmage.llm.summarize_keep_exchanges` | `LLM_SUMMARIZE_KEEP_EXCHANGES` | `5` | Recent exchanges kept verbatim after a summary. |

Unlimited history is the default because summarization handles overflow. Raising the limits on a
small-context model produces long, expensive prompts and more empty answers; lower them instead.

## Key handling

Keys are read from the environment, never from a JVM flag. A `-D` property lands in the process
command line, which any local user can read.

```bash
export LLM1_KEY='<api-key>'
export LLM2_KEY='<api-key>'        # optional, for LLM-versus-LLM games
```

For a hosted launch, write the key to a file outside the repository and point the launcher at it:

```bash
XMAGE_KEY_FILE=<path-outside-repo>/provider.key scripts/start-server-charm.sh
```

The file must not live inside the checkout: a tracked key is one commit away from publication, and
the hosted launcher has no in-repository fallback. Create it mode `600` and keep it out of backups
that leave the host.

Two rules:

- **Keys never reach a replay file.** The runtime key field is transient, so a `.game` save never
  carries it. The seat-config object that travels from client to server is a different class and
  does carry it, which is why a browser-supplied key works.
- **Changing a seat's endpoint drops the server key.** A per-seat override that points at another
  endpoint and supplies no key clears the inherited key instead of sending it to the new host.

The `xmage.llm.key` property exists for automated tests only. Do not set it in production.

## Model discovery

The web client can list models from an endpoint instead of requiring a typed name. The proxy tries
the Ollama tag list first and falls back to the OpenAI-compatible model list, sending the key as a
bearer token. A failed lookup returns an empty list rather than an error dialog.

The proxy only dials hosts on its model allowlist, which defaults to loopback. Add a remote
provider before fetching from it:

```bash
XMAGE_ALLOWED_MODEL_HOSTS=api.example.com scripts/start-proxy.sh
```

## Context windows

The seat needs a token budget before it can decide when to summarize. Three paths supply it:

1. **Ollama** — `POST /api/show`, then the `model_info` entry whose key contains `context_length`.
2. **OpenAI-compatible** — `GET /v1/models`, match the entry whose `id` equals the model, read
   `context_length` or `context_window`.
3. **Explicit** — `xmage.llm.model_context_window`, which skips detection.

A provider that omits the field and an operator who omits the override fail seat creation with a
message naming the model and the property to set. Guessing a window produces silent context
overflows later, which look like the model forgetting the game.

Client-key seats always need an explicit window: the server holds no key for them and cannot query
the provider, so the browser sends the detected value with the seat configuration.

## Thinking dialects

Two wire formats cover the providers in use:

| Dialect | Request shape | Used by |
|---|---|---|
| `effort` | `reasoning_effort: <level>` | Models that take an effort level. The default. |
| `budget` | `thinking: {type: "enabled", budget_tokens: N}` | Anthropic-style endpoints. |

In `effort` mode the non-standard `thinking` object is emitted only for endpoints classified as
Ollama. Strict OpenAI-compatible gateways reject unknown request fields with HTTP 400, so they get
the bare `reasoning_effort` field instead. `budget` mode always emits the object, because that
dialect is object-based by definition.

At seat construction the player makes one minimal call with the configured thinking payload. If
the endpoint rejects it, the seat fails at the table with the provider's error, not thirty turns
into a game. `xmage.llm.skip_thinking_probe=true` skips that call for environments with no live
endpoint; do not set it on a server that plays games.

When a model rejects every effort level, the last retry runs with thinking disabled, giving the
whole output budget to the answer.

## Retries and rate limits

A decision is attempted up to five times.

| Attempt | Behaviour after an empty answer |
|---|---|
| 1 | Configured effort. Prompts over 100,000 characters start one rung lower. |
| 2 | Same effort plus a nudge to answer in the expected format. |
| 3 and 4 | Descend one effort level per attempt, with the nudge. |
| 5 | Thinking disabled for this call. |
| exhausted | The decision fails with an error in the log. No move is invented. |

Only empty answers walk the ladder. Infrastructure failures retry at the original effort.

HTTP failures follow a separate rule: rate limits and server errors (429, 500, 502, 503, 504) retry
after 2, 4, 8, and 16 seconds, and a provider's `Retry-After` header wins. Other client errors
(400, 401, 403, 404) are terminal: retrying a bad key or model name only burns the game clock. A
call may take up to ten minutes by default; raise the timeout only for models that need it.

## Per-seat overrides

The table dialog lets each seat set its own endpoint, model, key, effort, thinking switch,
temperature, output cap, timeout, conversation limits, personality, and transcript directory. Empty
fields inherit the server value, so one table can pit two providers against each other without a
server change.

A browser-supplied key takes a different path: the seat is marked as client-key, the server never
holds the key, and each request body is handed to the seat owner's browser, which attaches the key
and calls the provider. The proxy relays only to hosts on its model allowlist.

## Stock and LLMage builds

The proxy probes the connected server for its player types and reports capabilities in the
`connected` message. Against an official server, which does not advertise the LLM player type, the
LLM tab, LLM seat options, and per-seat configuration disappear, and LLM requests are rejected
rather than downgraded silently.

The `GET /config` route reports the build flavour (`"llmage":true` or `false`). A stock proxy
reports `false` and has no relay route compiled in.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| HTTP 401 at the first decision | Key missing, wrong, or not exported into the server's environment | Export `LLM1_KEY` before starting, or point `XMAGE_KEY_FILE` at a readable file. |
| HTTP 404 naming the model | Model identifier does not exist at that provider | Copy the exact identifier from the provider's model list. |
| HTTP 400 "unknown field" | Strict gateway rejecting the thinking object | Use `effort` dialect, or disable thinking for that seat. |
| `LLM startup thinking probe FAILED` | Endpoint rejects the configured dialect or effort level | Match the dialect to the provider, or lower the effort. |
| `Failed to detect context window` | Provider omits the field and no override is set | Set `xmage.llm.model_context_window`. |
| Repeated empty answers | Reasoning consuming the whole output budget | Lower effort, keep the thinking cap, or choose a larger model. |
| Seat creation fails with "Connection refused" | Endpoint down, or local Ollama not running | Start the provider, or fix the endpoint URL. |
| HTTP 429 during a busy table | Provider rate limit | Retries pace themselves; reduce concurrent LLM seats or raise the provider tier. |

## Related

- [Server configuration](server-configuration.md)
- [Deployment](deployment.md)
- [Security](security.md)
- [Operations](operations.md)
- [LLM opponents](../player-guide/llm-opponents.md)
- [LLM chat and logs](../player-guide/llm-chat-and-logs.md)
- [Glossary](../reference/glossary.md)
