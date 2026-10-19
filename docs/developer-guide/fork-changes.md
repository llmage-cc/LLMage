# What changed from upstream XMage

LLMage is a fork of XMage 1.4.62. It keeps the upstream rules engine, card set, formats, and desktop client behavior, and adds three things: a server-side player that delegates decisions to an LLM, a browser client with a WebSocket proxy, and the core hooks those two need. This page maps the changes by area so a developer coming from upstream knows what is new, what is patched, and what is deliberately untouched.

> **Who this is for:** developers familiar with XMage who need the fork's delta before reading the code.

## At a glance

| Area | Change | Why it matters |
|---|---|---|
| LLM player | New `Mage.Player.LLM` plugin | Turns any seat into an LLM-driven opponent |
| Core engine | Additive types, state capture, replay seeking | Gives the plugin context and gives the web client replay control |
| Server | Bot handler, LLM transport, streaming updates, config | Runs LLM seats and keeps clients live while a model thinks |
| Desktop client | LLM config and conversation windows | Configures and observes an LLM seat from the existing client |
| Web proxy and client | New module with Jetty and React | Playable browser client, including deck editing and replay |
| Build and packaging | Proxy flavors, deploy and bootstrap scripts | Ships an LLM-free artifact for stock servers from one tree |
| Tests | LLM suites, proxy suites, UID gates, live harnesses | Protects decisions, protocol, and saved games |

## LLM player

`Mage.Server.Plugins/Mage.Player.LLM` is a new module. `LLMPlayer` extends `ComputerPlayer`, so the engine treats it as an ordinary AI seat, but it overrides the decision methods that matter:

- priority, mulligan, and sideboard
- card and player targets
- choice, pile, mode, trigger order, and replacement effect
- amounts, X announcements, and target amounts
- attackers and blockers
- spell side for modal double-faced, split, and adventure cards
- voluntary concession as a priority-menu action

Each decision is rendered into a text prompt by `LLMGameSerializer`, sent to an OpenAI-compatible endpoint by `LLMClient`, parsed, validated, and executed. A rejected action is retried with the engine's rejection reason; a model that keeps choosing an illegal action passes priority and logs a fatal error instead of killing the match.

The plugin also adds:

- **Conversation memory.** Prior decisions travel as compact summaries. Long games are summarized when they approach the model's context window, with the most recent exchanges kept verbatim.
- **Thinking control.** A per-model dialect selects `reasoning_effort` or a `thinking` budget object, with a descent ladder for empty answers and a final attempt that disables thinking.
- **Retries.** Five attempts for 429, 5xx, I/O errors, and empty answers, honoring `Retry-After` and otherwise backing off from 2 to 32 seconds. Other 4xx errors fail immediately.
- **Transcripts.** Every exchange can be written to a per-game text file, including the system prompt, the user prompt, reasoning, and the final answer.
- **Personalities.** A 35-name registry supplies a character description and playstyle note, and the display name carries the model prefix.
- **Replay data.** Each exchange is recorded against the game-state index so the replay viewer can show what the model saw and answered.
- **Client-key seats.** A browser can hold the provider key; the server routes prompts to the browser and never stores the secret.

The plugin is deliberately a server component. It does not run in the desktop client and does not need a browser unless the seat uses a browser-held key.

## Core engine

The engine changes are additive and mostly serve the plugin or the web view:

| Change | Purpose |
|---|---|
| `PlayerType.COMPUTER_LLM` | Makes the seat type visible in `ServerState` and selectable in both clients |
| `LLMPlayerConfig` | Serializable per-seat overrides sent from a client to the server |
| `Seat.llmPlayerConfig` | Carries a seat's overrides through table creation |
| `MatchOptions.llmPlayerConfigs` and `llmChatPublic` | Carries all seat overrides and the chat-visibility flag |
| `Personality` | Name registry and prompt section builder |
| `GameState.gameLog` and truncation cursor helpers | In-memory event history for prompts, with rollback awareness |
| `LLMGameLogCollector` | Feeds `informPlayers` events into the game log through the existing data-collector hook |
| `Player.isStreaming()` | Lets the server poll live state while a model is thinking |
| `GameView.llmDebug` and `replayStateIndex` | Conversation data and true replay position for the web client |
| `MatchView.playerList` | Structured player rows for the replay browser |
| `Replays.seekTo` and replay-session seeking | Scrubber support that upstream does not have |
| `LLMBridgeRegistry` and `LLMPromptPayload` | Classloader-safe bridge between the plugin and the server transport |
| `Deck.createCard` name fallback | Imports a card when set code or collector number is missing |

`GameImpl.informPlayers` is unmodified. The game log is populated by the data collector, not by an edit in the hot path. That choice keeps upstream merges simpler and keeps non-LLM games free of the cost: the log flag defaults to false and is enabled only when an LLM seat starts.

## Server

`Mage.Server` gains the runtime plumbing for LLM games:

- **`BotGameHandler`** implements the `\botgame` chat command and creates an LLM-vs-LLM table with generated names, decks, and configurations.
- **`LLMClientTransport`** registers with `LLMBridgeRegistry` at boot and routes browser-key prompts from a game to the browser that owns the table. It also pins a route for the post-game reflection after the table is torn down.
- **`MageServerImpl.llmDecision`** accepts a browser's answer and completes the waiting future.
- **`GameController`** runs a 500 ms streaming poller while any player reports `isStreaming()`, forcing game-state updates so the board does not freeze during a long model call.
- **`GameSessionPlayer` and `GameSessionWatcher`** forward LLM conversation updates to the desktop debug window and the web LLM tab.
- **`TableController`** auto-watches a bot game after the table reaches dueling state, and applies per-seat configs at player creation.
- **`PlayerFactory`** delivers a seat config through a thread-local before the plugin constructs, so context-window detection and the thinking probe use the seat's endpoint, not the server default.
- **`config.xml`** gains the `llm*` and `llm2*` attributes and the `Computer - LLM` player type. The launcher passes the effective values as system properties.

Tournament handling also carries per-player LLM configs through the full lifecycle and creates a fresh player and deck copy for each sub-table, so a later round does not inherit the previous round's conversation state.

## Desktop client

The Swing client remains the upstream client with three additions:

- **LLM Config dialog.** A tabbed editor for Core, Behavior, Personality, Conversation, and Summarization settings, opened from the new-table dialog or a seat panel.
- **LLM Chat window.** A per-bot, real-time view of the conversation with a context-size counter, opened from the toolbar or the `/llm` local command.
- **Player panels.** The new-player and table-player panels expose the LLM type and a config button, including setting Player 1 to an LLM seat for all-AI spectating.

The rest of the desktop client, including its deck editor, lobby, and game controls, follows upstream behavior. Fork-specific differences are limited to the LLM entry points.

## Web proxy and web client

`Mage.WebProxy` is a new module. It contains a Jetty 12 server and a React 19 frontend built with Vite and TypeScript. The proxy:

- serves the static frontend and a `/ws` WebSocket endpoint
- creates one `SessionImpl` per browser socket and translates JSON messages to server calls
- exposes `GET /config` for deployment flags
- registers `/llm-relay` in the LLMage flavor for providers that do not allow direct browser CORS
- probes the connected server's capabilities and gates every LLM and replay-scrubber path
- enforces origin allow-listing, a per-host connection cap, anonymous-name checks, table ownership, and a start-game join gate

The frontend covers the lobby, table creation, deck selection and editing, the game board and prompts, chat, an LLM tab, settings, spectating, and a replay viewer with a scrubber and an LLM chat sidecar. The stock build removes the LLM UI at compile time and neutralizes branding, so the same source can produce an LLM-free artifact for stock servers.

The proxy is the only place where the browser protocol exists. The server never sees a browser message; it sees a `SessionImpl` call, exactly as it would from the desktop client.

## Build, packaging, and scripts

The fork adds build flavors to the proxy so one source tree produces both artifacts:

- `-Pllmage` compiles the relay and marks the JAR `LLMage-Build: true`.
- `-Pstock` compiles a null seam, marks the JAR `LLMage-Build: false`, and pairs with `VITE_LLMAGE=false` for the bundle.
- No profile builds a minimal proxy for tests.

`scripts/deploy-release.sh` replaces hand-copied JAR lists with an assembly-driven deploy that verifies every JAR and enforces the `lib/` versus `plugins/` layout. `scripts/bootstrap-plugins.sh` materializes the plugin symlinks and fails on a dangling link. Launchers add server, client, proxy, detached, bot-game, monitoring, and log-archive entry points. The root `Makefile` keeps convenience targets for build, test, packaging, card verification, and AI-game load runs.

These scripts exist because the runtime layout is easy to get subtly wrong: a missing sets JAR produces a zero-card restart loop, a missing plugin link produces an unknown player type, and a proxy JAR in `lib/` changes the server's logging configuration.

## Tests

The fork adds several test assets. `Mage.Tests/src/test/java/org/mage/test/LLM` holds the decision, prompt, configuration, retry, replay, and concurrency suites, with a scripted client for deterministic answers. `Mage.WebProxy/src/test` holds the serializer, capability, ownership, and connection-policy tests. The shell harnesses under `scripts/` and `Mage.WebProxy/scripts/` exercise the live proxy, the browser protocol, sideboarding, post-game reflection, and the compatibility legs.

Two serialization gates protect saved games: a per-class UID inventory and a persisted manifest of the save surface. They fail on drift rather than letting a rebuild orphan a replay corpus. See [Testing](testing.md) for commands and coverage.

## Correctness fixes

Several upstream behaviors needed adjustment for LLM play. The notable ones:

- **Chrome Mox imprint.** The imprint choice now goes through the LLM instead of the stock AI `choose()` path.
- **Urza's Saga and filtered library searches.** Cards that fail the search filter are annotated as invalid, so the model does not pick an illegal target.
- **Any-target spells.** Player targets are resolved alongside card targets; effects such as Orcish Bowmasters no longer drop a player target.
- **Ponder-style ordering.** Multi-step single-card selection is presented step by step instead of as an ambiguous list.
- **Land-drop loop.** Lands are no longer offered after the land drop is consumed.
- **Large library and graveyard searches.** Searches over a large set accept a free-text card name instead of a numbered list that exceeds the prompt budget.
- **Priority retry.** A repeated rejected action now gets the concrete rejection reason and a do-not-repeat constraint; the final fallback passes priority instead of ending the match.
- **Face-down and stack rendering.** Hidden information and stack objects are rendered without leaking face-down identities or stale stack state.
- **Post-game reflection.** Reflection runs off the game thread, so a slow provider cannot block sideboarding or the next game.

## What did not change

Card rules, card implementations, format definitions, tournament structures, the deck validator, and the base desktop client behavior are upstream. LLMage does not fork the rules engine to make the LLM's life easier. When a card or format question is not about the LLM integration, the upstream wiki and upstream tests are authoritative:

- [XMage wiki](https://github.com/magefree/mage/wiki)

The fork also does not require an LLM to run. A table with only human and stock AI seats behaves like XMage, and the LLM plugin is unused.

One inherited behavior is worth knowing: `LLMPlayer` does not override `isComputer()`, so it reports true through the base `Player` implementation. Card-specific AI workarounds that check that flag therefore apply to LLM seats too. A card that behaves differently for a computer player will behave that way for an LLM; do not assume the LLM seat takes the human code path.

## Compatibility notes

The fork keeps stock-server wire compatibility through explicit serialVersionUID pins on the shared view and match classes, and it gates LLM features by probing the server's player types. Both mechanisms are described in [Wire compatibility](wire-compatibility.md). The version check is exact, so a stock peer must run the same release string as the LLMage build.

## Related

- [Architecture](architecture.md)
- [Wire compatibility](wire-compatibility.md)
- [Extending LLMage](extending.md)
- [Overview](../getting-started/overview.md)
- [LLM opponents](../player-guide/llm-opponents.md)
