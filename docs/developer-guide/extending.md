# Extending LLMage

Most changes fit one of seven extension points: an LLM provider, a personality, a prompt, a player type, a web-proxy route, a card mechanic, or a test. Each point has a small contract and a characteristic failure when the contract is broken. This page describes the contract at each point and the tests that catch a mistake before a game does.

> **Who this is for:** developers adding a provider, a bot character, a seat type, a protocol message, or a card, and needing to know which layer to touch.
>
> **LLMage addition.** Every extension point on this page is specific to the fork; card rules themselves follow the upstream card API.

## Extension points at a glance

| Extension | Primary location | Contract | Typical failure |
|---|---|---|---|
| LLM provider | `LLMConfig`, `LLMClient` | OpenAI-compatible chat completions; declared thinking dialect | HTTP 400 on an unknown field; empty answers |
| Personality | `mage.players.Personality` | Unique name, description, playstyle | Name collision; generic bot |
| Prompt | `LLMConfig` template or a one-shot prompt in `LLMPlayer` | Fixed headers, answer-only output | Parser or validator rejects answers |
| Player type | `PlayerType`, `config.xml`, a plugin module | Enum description, JAR, constructor | `Unknown player type` at boot |
| Proxy route | `WebProxyServer` or `WebProxySession` dispatch | JSON message shape, gates, error frame | Silent drop; blocked upgrade |
| Card mechanic | `Mage.Sets` | Upstream card API, test fixture | Missing LLM decision coverage |
| Test | `Mage.Tests` or `Mage.WebProxy/src/test` | Deterministic fixture, no live calls | Host-dependent suite |

## Adding an LLM provider

Any endpoint that implements the OpenAI chat completions shape works without code changes: set the endpoint URL, model name, optional API key, and the thinking dialect in the server configuration or per-seat dialog. Ollama, vLLM, llama.cpp server, LM Studio, OpenRouter, and hosted OpenAI-compatible gateways all use this path.

The request contract is:

| Element | Expectation |
|---|---|
| URL | Ends in `/v1/chat/completions` or `/v1`; context detection derives the base URL from either suffix |
| Authentication | The `Authorization` header carries the configured key, using the Bearer scheme |
| Body | `model`, `messages`, optional `max_tokens`, optional `temperature`, optional thinking fields |
| Thinking, `effort` dialect | Bare `reasoning_effort` on strict gateways; a `thinking` object on Ollama-classified endpoints |
| Thinking, `budget` dialect | `thinking: {type: "enabled", budget_tokens: N}` on any endpoint |
| Response | `choices[0].message.content`; reasoning in `reasoning_content` or the provider's thinking field; `usage`; `finish_reason` |
| Errors | 429 and 5xx are retryable; other 4xx fail immediately |

Endpoint classification is a heuristic: a URL containing `ollama` or the port `11434` is treated as Ollama; everything else is treated as a strict OpenAI-compatible gateway. The same rule exists in the engine (`LLMConfig.isOllamaEndpoint`) and in the proxy (`ThinkingShapeClassifier`). If you change the rule, change both or the setup-time warning in the web UI will disagree with the request the engine actually sends.

To add a provider with a genuinely different wire shape:

1. Add a classification branch beside `isOllamaEndpoint`, or an explicit dialect value.
2. Extend `LLMClient.buildRequestBodyConv` to emit the provider's body. Keep one builder and branch on the dialect; do not fork the retry or parsing code.
3. Extend `parseResponse` only if the response shape differs. Preserve `finishReason`: the empty-answer recovery uses it to distinguish a reasoning loop from a truncated answer.
4. Keep failure behavior fail-fast. A provider that rejects a field must produce the provider's error text, not a silent retry with the field removed.
5. Add a unit test for the body shape and a test for the error classification. `LLMThinkingDialectTest`, `LLMConfigOpenAIDetectionTest`, and the client tests are the models to follow.

Two operational details apply to browser-key seats: the browser calls the provider directly, so the provider must allow CORS or the call must go through `/llm-relay`; and the proxy only relays to hosts on its model-endpoint allow-list, configured with `-Dwebproxy.allowedModelHosts`. A provider that works from the server can still fail from the browser until its host is allow-listed.

## Adding a personality

Personalities live in `mage.players.Personality`, a static registry in the core module. Each entry has a name, a character description, and a playstyle note. The registry also drives bot naming: a seat named `model-Name` is displayed with that suffix, and the prompt section is selected by the suffix after the last hyphen.

To add one:

1. Add a `register("Name", new Personality(description, playstyle))` call in the static block.
2. Choose a name that is unique in the registry. The bot-name allocator picks from `allNames()` and excludes names already used in the game; a duplicate registration silently shadows an earlier one.
3. Write the description as character voice and the playstyle as strategic preference. Keep the final instruction intact: the personality guides style, it does not override the goal to win.
4. Add or extend a test for `Personality.buildPromptSection`, `forName`, and name resolution. `LLMBotNameCollisionTest` covers the allocator.

No server restart is needed beyond the normal rebuild: the registry is compiled into `Mage`, which both the server and the desktop client load. The web client receives the available names through `fetch_personalities`, so a new entry appears in the seat form without a frontend change.

Failure modes are mild but visible. A name that collides produces two identical display names and ambiguous game-log attribution. A personality that contains rules text or strategic instructions that contradict the deck can degrade play. A very long description consumes prompt budget on every call.

## Changing a prompt

There are two kinds of prompt:

- The long-lived system prompt in `LLMConfig.systemPromptTemplate`, which documents the response format, card-text convention, skip controls, and mana rules.
- One-shot prompts built in `LLMPlayer` for deck assessment, sideboarding, post-game reflection, match review, and revised strategy.

Decision prompts are assembled by `LLMGameSerializer` and carry fixed section headers. The model is instructed to answer with only a number, a comma-separated list, a card name, or yes/no. Validators and parsers depend on that contract, so keep the headers and the answer instruction stable when editing the surrounding prose.

Guidelines:

1. Put durable rules in the system prompt; put situation-specific instructions in the decision prompt.
2. Do not repeat full card text in every prompt. The serializer emits full text once per card per game and uses shorthand afterward. Repeating it inflates the context and defeats summarization.
3. Keep numbered action lists contiguous. The parser treats the number as an index into the list it built.
4. When adding a new decision header, add the matching instruction to the system prompt and a validator that rejects out-of-range answers.
5. Test with a scripted client that returns a known answer, not with a live model. `LLMCardTextOnceTest`, `LLMCanonicalModeNumberingTest`, and `LLMActionNumberRangeTest` show the pattern.

A prompt change that breaks format compliance usually shows up as repeated correction prompts, then empty answers, then a terminal concede. Check the transcript before changing retry policy: the model may be answering a question the prompt no longer asks.

## Adding a player type

A player type is implemented as a server-side plugin; clients only select it.

1. Add a constant to `mage.players.PlayerType` with a description and the two capability flags. The description is the wire-visible name.
2. Add a `<playerType>` entry to `Mage.Server/config/config.xml` with `name` equal to that description, the plugin JAR name, and the implementation class.
3. Implement the class in a module under `Mage.Server.Plugins`. Extend `PlayerImpl` or `ComputerPlayer` and provide the public constructor `(String name, RangeOfInfluence range, int skill)`.
4. Build the JAR, deploy it to `Mage.Server/release/lib/` (for bootstrap) or `release/plugins/`, and run `scripts/bootstrap-plugins.sh`.
5. Add the JAR to the server assembly if it must ship in the distribution, and to the deploy script's source mapping if its filename is unversioned.

The server resolves the type at boot. A missing JAR or a name mismatch produces `FATAL Unknown player type: <description>` and the type is unavailable; a table that already references it can hang waiting for a player. Both are configuration errors, not recoverable runtime conditions.

If the type needs a per-seat configuration object, follow the LLM pattern: a serializable config class in `Mage`, a field on `Seat` or `MatchOptions`, delivery through `PlayerFactory`, and a client dialog that fills it. Remember that `Seat` and `MatchOptions` are wire-pinned; adding a field is safe only while their explicit serialVersionUID stays at the upstream value.

## Adding a web-proxy route

The proxy has two route styles.

An HTTP context is registered in `WebProxyServer.start()` as a Jetty `ContextHandler`. Use this for a new endpoint such as a status page or a file download. Give it an explicit path, handle `OPTIONS` if a browser will call it cross-origin, cap request and response sizes, and return a JSON error body on failure.

A WebSocket message is a new `case` in the dispatch switch in `WebProxySession`, plus a handler method and a frontend sender/handler pair. The message contract is a JSON object with a `type` field and whatever payload the handler needs. The reply is either a typed message or the shared `error` frame.

Every new message handler must answer these questions before it ships:

| Question | Why |
|---|---|
| Is the session connected and authenticated? | Handlers run after `connect`; a null `SessionImpl` must produce an error, not a `NullPointerException` |
| Does it carry an LLM-only capability? | Call `requireServerLlmGate` or `requireServerLlm` so a stock server rejects it cleanly |
| Does it mutate a table or game the user does not own? | Reuse the ownership and start-game gates |
| Can it block? | Run network or disk work on the background executor, not the Jetty thread |
| Does it need a frontend branch? | Add the handler under `react/src/network` and the type under `react/src/types` |
| Is it LLM-only UI? | Guard it with `__LLMAGE__` so the stock bundle tree-shakes it |

Add a unit test for the handler's pure logic and a live WebSocket script when the route crosses the proxy boundary. The existing capability-gating scripts are the reference for a scripted connect and message exchange.

## Adding a card mechanic

Card code belongs in `Mage.Sets` and follows the upstream XMage card API. LLMage does not change that API. For the card class structure, set registration, and rules-test conventions, use the upstream wiki:

- [XMage wiki](https://github.com/magefree/mage/wiki)

Two fork-specific checks apply:

1. A new mechanic can introduce a decision the engine asks through a `Player` method that `LLMPlayer` does not override. In that case the LLM seat falls back to the inherited `ComputerPlayer` heuristic. Audit the new call site against the methods listed in [Architecture](architecture.md), and add an override plus a scripted-answer test if the decision matters.
2. Add a card test in `Mage.Tests` using `CardTestPlayerBase`. If the card exercises an LLM path, use `CardTestPlayerBaseLLM` and inject a scripted client. Run `VerifyCardDataTest` for the set before submitting.

Card text is also prompt input. A card whose rules text is ambiguous, missing, or duplicated can confuse every LLM seat in the game even when the card behaves correctly for a human. The card-data verification suite catches missing text and malformed metadata.

## Adding a test

Choose the smallest layer that can prove the behavior:

| Behavior | Test location | Base class or pattern |
|---|---|---|
| Prompt formatting, parsing, config | `Mage.Tests/.../LLM` | Plain JUnit, no game |
| A decision inside a real game | `Mage.Tests/.../LLM` | `CardTestPlayerBaseLLM` |
| A card's rules | `Mage.Tests/.../cards` | `CardTestPlayerBase` |
| Proxy serialization or gating | `Mage.WebProxy/src/test` | Plain JUnit 4 |
| Wire or browser path | `scripts/verify-*` | Live WebSocket driver |

LLM game tests extend `CardTestPlayerBaseLLM`. Player A is the LLM seat; the fixture's default deck is a dummy list, deck assessment is disabled, and mulligan is forced to keep so the test cards are not thrown away. Replace the live client with a scripted subclass through `TestLLMPlayer.injectTestClient`. A test must never depend on a live model unless it is explicitly gated and documented as an integration test.

Proxy tests are ordinary JUnit 4 tests compiled by Maven. They do not need a server; use the test seams in `ServerCapabilities.forTest` and the session serializers.

## Compatibility rules for any extension

- Do not change the fields of `Seat`, `MatchOptions`, `MatchView`, or `GameView` without reading [Wire compatibility](wire-compatibility.md) and re-running `WireCompatSuidTest`.
- Do not change `LLMPlayer`'s explicit serialVersionUID as part of an unrelated change; it pins the replay format.
- Keep core changes additive. A behavior change in an inherited method needs a test that fails without it.
- Prefer an explicit error over a fallback. A missing provider field, an unknown player type, or an unhandled message should fail loudly where it happens.
- Update this page and the architecture page when a new extension point becomes part of the public structure.

## Related

- [Architecture](architecture.md)
- [Testing](testing.md)
- [Wire compatibility](wire-compatibility.md)
- [LLM providers](../operator-guide/llm-providers.md)
- [Contributing](contributing.md)
