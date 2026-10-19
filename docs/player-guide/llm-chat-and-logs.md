# LLM chat and logs

An LLM seat does not speak in the table chat, but it does leave a trail. You can watch its reasoning arrive token by token during a game, read the whole conversation afterwards, and find the same material in text files on the server. This page shows where each of those views lives, who is allowed to see it, and what the files contain.

> **Who this is for:** Players who want to follow an LLM seat's thinking during a game or find the record of a finished one.

> **LLMage addition.** The conversation viewer, the sharing option, and the transcript files are fork features. They appear only when you play on a server that runs the LLM player plugin.

## Watching a seat think in the web client

The game board has a right rail with three tabs: **Game Log**, **Chat**, and **LLM**. During a live game the LLM tab exists only when the connected server supports LLM seats and the current game has an LLM seat that has produced at least one exchange. On a stock server, or in a game between humans, it is not offered.

When conversations are visible, the tab opens on a row of seat buttons, one per LLM in the game, labelled with the seat's player name. Selecting a seat shows its conversation:

- A header line with the model, the number of messages, and the current context usage, such as `Context: 64800 chars / 131072 tokens (25%)`. The percentage compares the conversation text against the model's context window, and it is the number to watch in a long game.
- **Deck Assessment**, the pre-game plan the seat wrote for itself.
- **Match Review (for sideboarding)**, once a first game of a match has ended.
- The conversation itself, one collapsible block per message. A user block carries a small line naming the prompt type, the turn, and the phase, for example `priority · T4 · Combat / Declare Attackers`. An assistant block shows the answer, with a **Reasoning** block underneath when the model returned reasoning text.
- **Post-Game Reflection**, once the seat has written one.


While a request is in flight, a box docked at the bottom of the panel reads `LLM thinking...` with a running timer, followed by the reasoning stream and then the answer as they arrive. The message list above it updates when the request completes.

The **Auto-scroll** checkbox keeps the newest block in view. Turn it off to read an earlier decision while the game continues. If the panel opens empty while the game clearly has a bot, the table is not sharing conversations with you; the next section explains why.

## Watching a seat think in the desktop client

The desktop client has the same material in a separate window. Select **LLM Chat** on the game toolbar, or type `/llm` (or `\llm`) in chat, to open **LLM History**.

The window holds one tab per LLM seat, titled with the seat name and model. Each tab header reads `Player: … | Model: … | Exchanges: … | Context: … | Messages: …`, and the tab title gains a dot while that seat is streaming. The body is the conversation in order: system prompt, then each prompt and answer. Reasoning text appears inline when the provider returns it.

## Who can see an LLM's chat

Live visibility is a table setting. In the web client, **Share LLM Chat Live** appears in the New Game dialog under the advanced options, next to **Allow Spectators**. It is off by default.

| Setting | Who sees the conversation |
|---|---|
| Off | Visible only to the seat that owns the conversation; a human seat never matches, so in a game where you play against a bot the LLM tab stays empty for you. |
| On | Every player and spectator at the table sees every LLM seat's conversation while the game runs. |
| Replay | Always available. A saved game carries its LLM exchanges regardless of the live setting. |

The option is worth turning on when you want to study how a model plays, and worth leaving off in a competitive game: the conversation includes the seat's plan and its reading of the board, which names cards in hidden zones. The desktop client's history window shows the conversations the server sends for each LLM seat in the game.

Replays keep the full material even when live sharing was off, because the game is over by the time anyone opens them.

## Chat and game log are not the LLM's chat

Three different streams are easy to confuse:

- **Chat** is the table chat between people. You can type there, and opponents can answer. The LLM seat does not read it and does not reply in it, so a question typed into chat never reaches the model.
- **Game Log** is the engine's own account of the game: lands played, spells cast, attacks, life changes, and results, each attributed to the seat that acted. Bot actions appear under the bot's name, mixed with yours. It shows what happened, never why.
- **LLM** is the model conversation: prompts, reasoning, and answers. It shows why, and it is the only place the seat's intent is visible.

A provider failure is visible in the game log as a line naming the seat and `LLM provider failure`. In the web client that line is highlighted and the redundant loss announcement that follows it is suppressed, so the reason stays readable. A two-seat win by this route shows **Victory (by forfeit)** with a note that the opponent forfeited.

## Transcript files

When the server has a transcript directory configured, each LLM seat writes one transcript per game. The default directory is `gamelogs` under the server's working directory, and the layout is:

```text
gamelogs/game_<game-id>/<player>_<model>_transcript-<timestamp>.txt
```

The game directory uses the last eight characters of the game identifier. A best-of-three match therefore produces one directory with one file per game per seat, and the timestamp in the name keeps the games apart.

Each file opens with the game identifier, player name, model, endpoint, and start time. Every exchange after that is a block:

```text
═══════════════════════════════════════════════════
EXCHANGE #12
Time: ...
═══════════════════════════════════════════════════

--- SYSTEM PROMPT ---
...
--- USER PROMPT ---
...
--- LLM THINKING / REASONING ---
...
--- LLM STATED REASON ---
...
--- LLM ANSWER ---
3 mana:L1,L2
```

The system prompt is printed once and later exchanges replace it with a one-line marker naming the exchange where it last appeared, which keeps the file readable. When the provider hides reasoning but bills for it, the thinking section says so and gives the token count instead of pretending the model did not think. The stated-reason section appears only when the seat asks for a one-sentence justification after the answer.

## Post-game files

Two kinds of file can land beside the transcript for the same game.

| File | Contents |
|---|---|
| `<player>_<model>_postgame-<timestamp>.txt` | The seat's reflection: how the game went, what it did well and badly, what was outside its control, whether the plan held, a self-rating, any engine problems it noticed, an engine-experience rating, and whether it had fun. In a multi-game match the file also holds the match review written for sideboarding. A separate post-game file holds the revised strategy written after sideboarding. |
| `<player>_<model>_sideboard-<timestamp>.txt` | The sideboard exchange: the model's answer, its reasoning, the swaps applied, and the resulting maindeck and sideboard counts. Written whenever the sideboard phase runs with a non-empty sideboard, including when the seat decides on no changes. |

The reflection runs after the game has been recorded, on its own thread. A slow or failed reflection costs the file and a loud log line, never the match: the result stands and the next game starts on time.


The reflection text is not stored inside the replay file, because the game is saved before the reflection finishes. The post-game file is its permanent record; the live LLM tab shows it while the board is still open.

## JSON game logs

The desktop client has a preference, **Save JSON game logs**, that writes the engine's action stream to a `gamelogsJson` folder in the client's working directory, one file per game. These logs record what the engine did, not what the model thought: prompts, reasoning, and answers are not written to them. Use them to inspect game events; use the transcript for the model's side.

## Verbose server logging

An operator can turn on verbose LLM logging, and the **Behavior** tab exposes the same switch per seat. The server log then carries the full prompt, the reasoning, and the answer for every call, plus two lines that are useful when a game feels slow or a file looks wrong:

- `LLM request body: N chars` before each request, which shows how large the prompt has grown.
- `LLM conversation summarized: X msgs -> Y chars`, printed when old history is compressed.

Verbose output is large and includes the seat's view of hidden cards, so leave it off for normal play and enable it only while diagnosing a seat.

## Where the files live

The LLM paths are relative to the directory the server runs from; the JSON log folder is relative to the client's working directory.

| Path | Written by |
|---|---|
| `gamelogs/game_<game-id>/` | LLM transcripts, post-game reflections, sideboard summaries |
| `gamelogsJson/` | JSON game logs from the desktop client preference |
| Server log file | Server output, including verbose LLM detail when enabled |

The launcher moves the previous run's `gamelogs`, `gamelogsJson`, and log files into an archive folder when the server starts, so a fresh session begins with empty directories. If a transcript is missing, check the archive before concluding it was never written.

## Related

- [LLM opponents](llm-opponents.md)
- [Web client](web-client.md)
- [Web replay](web-replay.md)
- [LLM providers](../operator-guide/llm-providers.md)
- [File layout](../reference/file-layout.md)
