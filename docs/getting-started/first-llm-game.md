# Your first game against an LLM

This page walks from a running server to a finished game: connect a client, create a table with one
LLM seat, choose what that seat runs on, start the game, and read what the model is doing while it
plays. Both clients are covered, because they expose the same server features through different
screens.

> **Who this is for:** a new user who has a working install and wants to play a first game.

## Before you start

You need three things:

- A server started from an LLM-capable build, listening on port 17171.
- One configured model endpoint. The server launcher reads `LLM1_MODEL` and `LLM1_ENDPOINT` from
  the environment and defaults to a local Ollama endpoint.
- One client: the web client on port 8090, or the desktop client.

If the server is not running yet, work through [Quick start](quick-start.md) first.

## Play in the browser

### Connect

Start the proxy with `scripts/start-proxy.sh` and open <http://localhost:8090/>. In the connect
form, enter `127.0.0.1` in **Server** and `17171` in **Port**. The literal address matters: on a
dual-stack host, `localhost` resolves to IPv6 first and the handshake fails with "Server is likely
offline".

Enter a username. A default server has authentication turned off, so a name of 3 to 14 characters
using letters, digits, or underscores is enough, and the password can stay empty. Select
**Connect**.

### Create the table

In the lobby, select **New Game**. The left column holds the match settings:

| Field | Suggested first game |
|---|---|
| Game Name | Anything you like |
| Game Type | `Two Player Duel` |
| Deck Type | `Constructed - Freeform` for a first run, or the format your deck is legal in |
| Wins Needed | `Best of 1` |

The **Players** column starts with your seat and one opponent seat.


### Turn the opponent into an LLM seat

1. In the **Opponent 1** row, set **Type** to `LLM Player`.
2. Leave the **Name** field empty to let the server generate one, or type a name.
3. Fill the deck box. Paste a decklist, upload a `.dck` or `.txt` file, or select
   **Generate random deck**.
4. Leave every LLM field at its default; an untouched field inherits the server's model.

The `LLM Player` option appears only when the connected server advertises the LLM player type. If
it is missing, the client is talking to a server or proxy built without LLM support.

### Choose what the seat runs on, or inherit the server default

Selecting the seat type opens a configuration block with five tabs. Every field starts empty or at
its default, and an untouched field inherits the server's value.

| Tab | Fields | What to set for a first game |
|---|---|---|
| Core | Model, Provider, API Key, Reasoning Effort, Thinking Enabled, Override Temperature, Temperature, Max Output Tokens, Timeout | Leave **Model** at `(server default)` and **Provider** empty. Set **Reasoning Effort** only if you want a faster or deeper game |
| Behavior | Streaming, Verbose Logging, Deck Assessment, Allow Concede, Ask for REASON line, Transcript Dir | Leave as is |
| Personality | Personality Enabled, Personality, Bot Name | Leave **Personality** and **Bot Name** empty for a random pairing |
| Conversation | Max Exchanges, Max History Chars, Think Chars/Msg | Leave at zero, which means unlimited |
| Summarization | Trigger Chars, Context Ratio, Context Window, Target Chars, Target Ratio, Keep Exchanges | Leave as is; the context window is detected from the endpoint where possible |

Two optional changes are worth knowing about:

- **Model** — type or select a model for this seat only. The proxy can list the models the endpoint
  offers; a seat with no provider of its own cannot list them, because the endpoint is the server's.
- **API Key** — a key typed here stays in your browser. Leave it empty when the server already has
  a key for that endpoint.

To give the opponent a fixed character instead of a random one, open **Personality** and choose a
name in **Personality**; that name's description and playstyle are injected into the seat's system
prompt. **Bot Name** pins the display name independently.

If you also want to watch the game rather than play it, set your own seat's type to `LLM Player`
as well. Both seats then play and you observe.

### Start the game

Select **Start Game**. With every seat filled, the table starts immediately.

## Play in the desktop client

Start the client from the archive you built (see [Installation](installation.md)), then connect to
`127.0.0.1` on port 17171.

1. In the lobby, open the **New Table** dialog.
2. Leave **Player 1 (You)** as a human seat and give it a deck.
3. Under **Other Players**, set the opponent's **Type** to `Computer - LLM`.
4. Select **LLM Config...** on that seat to open the same five tabs, or leave it closed to inherit
   the server defaults.
5. Fill the **Deck** field with the file picker, or select **Generate** for a random deck.
6. Select **Create**.

### The fastest route: a bot game

The server accepts a chat command that builds the whole table for you. Open the lobby chat and
type:

```text
\botgame
```

That creates a table with one LLM seat against the built-in AI opponent and generates both decks.

| Command | Result |
|---|---|
| `\botgame` | LLM against the built-in AI, generated decks |
| `\botgame Legacy` | Same, with Legacy deck construction rules |
| `\botgame --llm2` | LLM against a second, independently configured LLM |
| `\botgame --llm2 --bo3` | Best of three between the two LLM seats |
| `\botgame /path/deck.dck` | LLM against the AI, with your deck for the LLM seat |
| `\botgame --llm2 /path/one.dck /path/two.dck` | Two decks, one per LLM seat |

Bot games are always `Two Player Duel` tables. They allow spectators and start without a second
human. Deck paths must be absolute.

## What you see once the game starts

**A pause before the first play.** The model reads its decklist with full card text and writes a
gameplan before it acts. The first decision of a game therefore takes longer than later ones.

**A generated name.** Unless you type a name for the seat, an LLM opponent is named
`<model>-<personality>`, for example `gemma4-Kestrel`. The model part is the model identifier with
any tag or path stripped, and the personality comes from a fixed pool of 35 names, unique within a
game. A name you enter in the seat's **Name** field is kept as typed.

**A board that keeps moving.** While any LLM is thinking, the server pushes game state to clients
every 500 ms, so the interface does not freeze between decisions and you can follow the stack.

**Decisions that look like normal play.** The model answers with a numbered choice from the options
the engine offers. You see the resulting play in the game log and on the board; the reasoning
behind it stays in the LLM panel.

**Table chat is for people.** The **Chat** tab carries messages between the humans at the table. The
model's side of the conversation is the prompt-and-answer exchange in the LLM panel, not a chat
message.

**Time between turns.** A turn can take seconds or minutes depending on the model, its reasoning
level, and the size of the game state. Lower reasoning effort shortens the wait.

## Watch the opponent think

### Web client

The right rail has a **Game Log**, a **Chat**, and — when the game has an LLM seat — an **LLM** tab.
The LLM tab holds one entry per seat, with collapsible sections for the pre-game **Deck Assessment**,
each decision's **Reasoning**, the **Match Review** written for sideboarding, and the
**Post-Game Reflection**. Streaming tokens appear as they arrive.

You can also right-click a player's header and select **View LLM reasoning** to jump straight to
that seat's conversation.


Visibility is gated by the build flavor and by how the table was created. Your own LLM seats are
always visible. Opponents and spectators see the reasoning only when the table was created with
**Share LLM Chat Live** enabled; replays include it regardless.

### Desktop client

The **LLM Chat** toolbar button opens a window titled **LLM History** with one tab per bot. It shows
the system prompt, each user prompt, the response, and streaming output separated into reasoning
and answer, plus a running context-usage figure in the header. The chat command `/llm` opens the
same window.

## Where the game's files land

| File | Contents |
|---|---|
| `gamelogs/game_<hash>/<player>_<model>_transcript-<timestamp>.txt` | Every exchange: system prompt with the decklist, each prompt, the reasoning, the answer |
| `gamelogs/game_<hash>/<player>_<model>_postgame-<timestamp>.txt` | Post-game reflection, match review, revised strategy |
| `gamelogs/game_<hash>/<player>_<model>_sideboard-<timestamp>.txt` | The swaps the model proposed between games, with the counts it applied |
| `saved/` | Replay files for finished games |
| Server console or `mageserver.log` | Startup lines, request sizes, summarization events, provider errors |

Paths are relative to the server's working directory, which is the repository root when you start
it with the provided script. A best-of-three match writes a separate set of files per game.

## First-run problems

| Symptom | Cause | Fix |
|---|---|---|
| No `LLM Player` in the seat type list | Server built or deployed without the LLM plugin, or the proxy is the stock flavor | Redeploy, rebuild the proxy with `-Pllmage`, restart both |
| The table fails as the LLM joins, with a provider error | Wrong endpoint, model, key, or an unsupported thinking setting — the startup probe rejects it before play | Check `LLM1_ENDPOINT`, `LLM1_MODEL`, and `LLM1_KEY`; use a reasoning level the model accepts |
| `401` or `404` from the provider | Missing key, or the endpoint is not a chat-completions URL | Export the key; use the full `/v1/chat/completions` URL |
| "Empty answer" and the game stops | The model spent its whole output budget on reasoning | Lower the reasoning effort or the thinking token cap |
| The deck imports empty, or a bot game refuses to start | A `.dck` file with bare card names; lines without a set and collector number are skipped | Export the deck from a client, or use a `.txt` list |
| Turns take minutes | High reasoning effort on a large model | Lower the effort, or choose a faster model for that seat |
| The proxy exits with `Frontend directory not found` | The browser bundle was never built | Build the bundle, then start the proxy again |

More causes, including version mismatches and replay problems, are covered in
[Troubleshooting](../reference/troubleshooting.md).

## Related

- [LLM opponents](../player-guide/llm-opponents.md)
- [Web client](../player-guide/web-client.md)
- [Desktop client](../player-guide/desktop-client.md)
- [LLM chat and logs](../player-guide/llm-chat-and-logs.md)
- [Quick start](quick-start.md)
