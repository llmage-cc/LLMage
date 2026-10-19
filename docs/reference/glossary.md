# Glossary

The vocabulary used across this wiki. Terms are grouped by subject; within each group they are alphabetical. Where a term has a precise meaning in the code, the definition follows the code rather than casual usage.

> **Who this is for:** any reader of this documentation who meets an unfamiliar term.

## The project

| Term | Meaning |
|---|---|
| **XMage** | The upstream Java project that implements Magic: The Gathering rules, cards, clients, and a server. LLMage is a fork of it. |
| **LLMage** | This project: XMage plus an LLM-driven player, a browser client with its WebSocket proxy, and the engine changes those two need. |
| **Engine** | The rules core: card and ability model, game state, turn and priority handling, and deck validation. It lives in the `Mage` module and does not depend on either client. |
| **Fork** | A copy of another project that adds or changes behaviour. LLMage tracks XMage and carries its own additions on top. |
| **Upstream** | The original XMage project. Material described as inherited or stock comes from there; this wiki documents the fork's additions. |
| **Stock build** | A build of the web proxy and frontend with the LLM code compiled out. It connects to official XMage servers and shows neutral branding. |
| **LLMage build** | A build with the LLM relay and LLM interface included. Required for LLM seats in the browser client. |
| **Minimal build** | The default Maven output when no profile is given: the proxy with the LLM relay compiled out to a stub. Used mainly by unit tests. |

## Playing a game

| Term | Meaning |
|---|---|
| **Lobby** | The client area that lists rooms and tables before you join a game. |
| **Room** | A named chat and table group inside the lobby. |
| **Table** | One game's container: its seats, format, deck choices, and state. A table exists before the game starts and disappears when it ends. |
| **Seat** | One player slot at a table. A seat has a player type, a deck, and, for an LLM seat, its own configuration. |
| **Host** | The player who created a table. Some table actions are restricted to the host. |
| **Priority** | The rules window in which a player may act. The client shows the available actions whenever you have priority. |
| **Auto-pass** | A standing instruction to pass priority automatically until a named point: next turn, end of turn, next main phase, or the stack resolving. |
| **Skip mode** | The engine-side state behind an auto-pass. Unlike a plain flag, it cancels itself when the game state changes in a way that matters. |
| **MAD AI** | The built-in heuristic opponent inherited from XMage. It plays without any model endpoint. |
| **MCTS AI** | A Monte Carlo tree search opponent, also inherited and also model-free. |
| **Draft bot** | An AI that picks cards in a draft. It is separate from the game-playing AI seats. |
| **Bo3** | Best of three: the first seat to two game wins takes the match. Sideboarding happens between games. |
| **Sideboard** | The fifteen-card supplementary deck used between games of a match. |
| **Sub-table** | The table a tournament creates for one match. It is exempt from the server's idle-table cleanup so a long match can finish. |

## The LLM player

| Term | Meaning |
|---|---|
| **LLM seat** | A player slot whose decisions come from a language model. Its player type is `Computer - LLM`, and the code constant is `COMPUTER_LLM`. |
| **Player type** | The server-side class that implements a seat: human, MAD AI, MCTS AI, draft bot, or LLM. The server loads each type from a plugin jar named in its configuration. |
| **Model** | The identifier the provider uses for the language model, for example a name with a size or variant suffix. Set per seat. |
| **Endpoint** | The chat-completions URL the player posts to. Any OpenAI-compatible service works. |
| **Personality** | A written character description and playstyle instruction attached to a bot name and injected into the system prompt when enabled. |
| **Name pool** | The 35 bot names the server assigns from. Each name maps to one personality, and names are unique within a game. |
| **Gameplan** | The short strategy the model writes after reading its decklist, before the first turn. It stays in the system prompt for the rest of the game. |
| **System prompt** | The standing instructions sent with every request: rules for the answer format, the full decklist, the gameplan, and the personality section. |
| **Thinking dialect** | The shape of the reasoning request: `effort` sends a reasoning-effort level, `budget` sends a token budget for thinking. |
| **Reasoning effort** | A named level (`low`, `medium`, `high`, `max`) asking the model to think harder. The model must accept the level; the startup probe checks this. |
| **Thinking budget** | A token allowance for reasoning, used in the `budget` dialect. |
| **Effort ladder** | The ordered list of effort levels the player descends when a model returns no answer, one rung per retry. |
| **Reasoning cap** | A hard limit on reasoning tokens in effort mode, so a thinking loop cannot consume the whole output budget. |
| **Terse stop** | A stop sequence on validated prompts that cuts the answer at the first newline, so trailing text never reaches the parser. |
| **Context window** | The number of tokens a model can accept in one request. Detected from the provider when possible, set manually otherwise. |
| **Conversation memory** | The running history of prompts and answers kept for a seat. Limits can cap exchanges, characters, or stored reasoning. |
| **Summarization** | Compression of older conversation into a short narrative when the history approaches a fraction of the context window. |
| **Transcript** | The per-game text file holding the system prompt, every request and answer, and any reasoning. |
| **Post-game reflection** | The model's own review after a game: what went well, what went wrong, and any technical problems it noticed. |
| **Match review** | The sideboard-focused analysis written between games of a match. |
| **Sideboard summary** | The file recording the model's proposed card swaps and the swaps actually applied. |
| **Startup probe** | A one-token test call at table join that validates the endpoint, key, model, and thinking parameters before play begins. |
| **Retry** | A repeated request after a rate limit, a server error, an I/O failure, or an empty answer, with exponential backoff. |
| **Fail Fast** | The project's error policy: stop with a clear message instead of falling back to a heuristic or a silent default. |
| **Per-seat override** | A setting changed for one seat in the client, taking precedence over the server's default for that seat only. |

## Build, wire, and storage

| Term | Meaning |
|---|---|
| **Release tree** | The deployed directory a launcher reads: `Mage.Server/release/` for the server, `Mage.Client/release/` for the client. |
| **Assembly** | The Maven step that produces `mage-server.zip`, the authoritative list of runtime jars. |
| **Plugin symlink** | An entry under `plugins/` pointing at a jar in the release tree. The server resolves player, game, deck, and tournament types through these links. |
| **Web proxy** | The Jetty service that serves the React client and bridges browser WebSocket messages to the XMage server. |
| **Web client** | The browser interface served by the proxy. |
| **Desktop client** | The Swing application started by the client launcher. |
| **Capability probe** | The proxy's check of what the connected server supports. The result decides whether LLM features and the replay scrubber are offered. |
| **`/llm-relay`** | The proxy route that lets a browser supply its own model key for a seat. Present only in the LLMage build. |
| **Wire compatibility** | The guarantee that fork-only fields on shared message classes do not break a stock peer. |
| **SUID** | `serialVersionUID`: the identifier Java uses to decide whether a serialized class matches the current code. |
| **Replay orphaning** | The failure where a rebuild changes a SUID and saves written by the previous build can no longer be read. |
| **UID manifest** | The checked-in map of class names to pinned SUIDs, compared against the JVM's computed values by a gate. |
| **`.game` file** | One saved match. Written in a zstd container with a checksum. |
| **Replay sidecar** | The `.json` file beside a `.game` save, holding display metadata for the replay browser. The retention tool deletes both files together. |
| **Legacy gzip container** | The older replay compression format. The current reader rejects it and names the conversion script. |
| **Replay scrubber** | The browser replay control that seeks to any point in a saved match. Offered only when the server supports it. |
| **Card database** | The local H2 database of cards and expansions, built by scanning the compiled set classes. The server rebuilds it when its build stamp does not match. |
| **Set code** | The short code identifying an expansion, used with a collector number to name an exact printing. |
| **`.dck`** | The native deck format. Card lines need a `[SET:NUMBER]` code; plain names are skipped. |
| **`.txt` decklist** | A text deck format that accepts plain card names, which makes it the safer choice for hand-written lists. |
| **Deck importer** | The class that reads a deck file. The server selects one from the file extension, and an unknown extension yields an empty deck. |
| **Health check** | The server task that removes idle or invalid tables. Tournament sub-tables are excluded from it. |

## Related

- [Overview](../getting-started/overview.md)
- [Commands and scripts](commands.md)
- [File layout](file-layout.md)
- [FAQ](faq.md)
