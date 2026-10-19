# The desktop client

The desktop client is the Java application you run on your own computer. It connects to an LLMage server over the XMage protocol and is where you browse the lobby, create tables, play games, watch other tables, and open the LLM conversation window. This page covers those player-facing parts. Inherited XMage features — the deck editor, card image download, the card viewer — behave as they do upstream; the [XMage wiki](https://github.com/magefree/mage/wiki) documents them.

> **Who this is for:** players who run the desktop client on their own computer.

## Start the client

Start it from a built checkout:

```bash
scripts/start-client.sh
```

The script runs `mage.client.MageFrame` with the jars from the client's release directory and a 4 GB heap. It needs a completed build; if the release directory is missing, see [Installation](../getting-started/installation.md). The connect window opens with the server address you used last.

## Connect to a server

The connect window opens at start-up.

| Field | What to enter |
|---|---|
| Server name | Hostname or IP of the XMage server. Use `127.0.0.1` for a server on the same machine. |
| Port | TCP port of the server. The default is `17171`. |
| Username | The name other players see. The client stores it per server address. |
| Password | The password of a registered account. Leave it empty on a server that does not require registration. |
| User's flag | Country flag shown next to your name. |
| Automatically connect to this server next time | Reconnects at the next start without showing this window. |
| Proxy Settings... | Opens **Preferences → Network** for a connection proxy (Socks, HTTP or None): server, port, user name and password. |
| Register new user... | Creates an account on the server. |
| Forgot password... | Starts the reset flow for an account that has an email address. |

The row of shortcut buttons in the connect window fills in public XMage servers. Those servers do not run the LLMage fork, so the `Computer - LLM` seat type and the LLM windows do not exist there. Point the Server name field at your own LLMage server instead.

The client remembers the user name and password separately for each server address, and remembers the last port used.

## The lobby

A successful login opens a window titled **Server's lobby**. The desktop client shows the server's main room; it does not switch between rooms. The window has one table list for active tables, a second list for finished matches, the lobby chat and user list on the right, and the server's message of the day at the bottom.

### Reading the table list

| Column | Meaning |
|---|---|
| Icon | A match or a tournament. |
| Deck type | The deck validator used at the table, such as `Constructed - Standard` or `Variant Magic - Commander`. |
| Name | The table name chosen by its owner. |
| Seats | Occupied and total seats. |
| Owner | The player who created the table. |
| Game type | The rules variant, such as `Two Player Duel` or `Commander Free For All`. |
| Info | A short summary of the table options. |
| Status | Waiting, constructing, drafting or dueling. |
| Password | A marker when the table requires a password. |
| Created | When the table was opened. |
| Skill, Rating, Quit %, Min rating | Join information for the table. |

The last column holds the action the client offers you:

| Action | When it appears |
|---|---|
| Join | The table is waiting for players and you are not its owner. |
| Watch | A running non-tournament game that allows spectators. |
| Show | A tournament that has started its draft, construction or matches. |
| Replay | A finished match that the server saved. |
| Blank | No action is available to you. |

The filter row narrows the list by table type (**Matches**, **Constructed tourney**, **Limited tourney**), skill (**Beginner**, **Casual**, **Serious**), rating (**Rated**, **Unrated**), format (**Standard**, **Modern**, **Pioneer**, **Legacy**, **Vintage**, **Premodern**, **Commander**, **Oathbreaker**, **Tiny Leader**, **Limited**, **Other**) and whether the table is open or password-protected.

**New Table** and **New Tournament** sit above the list.

### Chat and local commands

The right-hand pane holds the lobby chat and the connected users. Type a message and press Enter. Two commands are handled by the client itself:

- `/ignore <player>` and `/unignore <player>` change your local ignore list for that server.
- `/llm` or `\llm` opens the LLM conversation window for the game you are playing or watching.

The lobby, each table and each game have their own chat, and the game window keeps a log pane beside its chat.

## Create a table

**New Table** opens a dialog with three groups of settings: what the table is, who sits at it, and how the match runs.

| Field | Meaning |
|---|---|
| Name, Password | Table name and optional join password. |
| Deck Type | The deck validator applied to every deck at the table. |
| Game Type | The rules variant and the number of seats it allows. |
| Players | Seat count, bounded by the selected game type. |
| Wins | Games a player must win to take the match. |
| Time Limit | Activity time per player before the match is lost. |
| Buffer Time | Extra time granted each time a player's clock starts. |
| Skill Level | Beginner, Casual or Serious label shown in the lobby. |
| Rated | Whether the match affects player ratings. |
| Rollbacks | Allows a rollback to an earlier turn when every player agrees. |
| Spectators allowed | Whether other users may watch the game. |
| Allowed quit %, Minimum rating | Join gates for other players. |
| EDH power level | Commander table information. |
| Range of Influence, Attack Option | Multiplayer settings, enabled only for game types that use them. |
| Custom Options... | Mulligan type, free mulligans, Planechase, custom starting life and custom starting hand. |

Deck Type and Game Type must belong to the same family. If you pair a Commander deck with a duel, or a Tiny Leaders deck with a free-for-all, **Create** stops and names the expected family. [Game modes](game-modes.md) lists the valid pairs.

### Seats

**Player 1 (You)** has a Type selector. Leave it on `Human` to play the game yourself, or set it to an AI type to start an all-AI game and watch it.

> **LLMage addition.** The `Computer - LLM` seat type and the **LLM Config...** button belong to the fork. A stock XMage server does not offer them.

Each row under **Other Players** has a type, a name, a deck field and, for `Computer - LLM`, an **LLM Config...** button. The deck field opens a file picker, and AI seats also offer **Generate** for a random deck. An AI seat without a deck stops table creation with a warning.

**LLM Config...** opens the per-seat configuration with five tabs:

| Tab | Contents |
|---|---|
| Core | Model, API endpoint, API key, reasoning effort, thinking switch, temperature, token limit, timeout. |
| Behavior | Streaming, verbose logging, deck assessment, concede permission, REASON-line request, transcript directory. |
| Personality | Personality switch, personality name, bot name. |
| Conversation | Maximum exchanges, history size, thinking size per message. |
| Summarization | Trigger size, context ratio, model context window, target size, target ratio, exchanges kept. |

A seat left untouched inherits the server defaults. [LLM opponents](llm-opponents.md) explains what the fields change during play.

If Player 1 is an AI seat, the client joins the AI seats, starts the match and opens it as a spectator; there is no **Start** click. Otherwise **Create** opens the **Waiting for players** window, which holds the seat table, a table chat, **Move Up**, **Move Down**, **Cancel** and **Start**. The owner sees **Start** become available when the table reports that it is ready to start.

## Join, watch, and replay

**Join** on a waiting table opens the join dialog: choose your deck, enter the password if the table has one, and confirm.

**Watch** opens a running game as a spectator. Spectators see the board, the stack and the game log; hand cards stay hidden until the owner grants a request. Right-click a player panel to ask to see that hand, once per game, and right-click the battlefield for **Stop watching**.

**Show** opens the tournament window: tournament name, type, state, start and end time, a standings table with location, player name, state, points, results and history, and the **Quit Tournament** and **Close Window** buttons.

**Replay** on a finished match asks the server for its saved copy of the game and opens it in a game pane with **Previous**, **Play**, **Stop**, **Next** and **Skip Forward** controls. The server offers the action when it saved the game with the game-saving option enabled.

**Load Replay** in the toolbar accepts a `.game` file from disk. The client does not read the board from that file. It takes the game identifier from the file name (`<game-uuid>.game`) and asks the server to load the game of that identifier from its own saved-games directory. A file with any other name is rejected, and a game the server no longer has cannot be replayed. Keep the original file name.

## The LLM chat window

> **LLMage addition.** The conversation window is fork-specific; a stock XMage client has no equivalent.

**LLM Chat** in the toolbar opens a conversation window for the game in front of you; `/llm` in chat does the same. The window holds one tab per LLM seat, titled with the player and model name, with a dot in the title while that seat is streaming a reply. Each tab shows the system prompt, the prompts sent for individual decisions and the model's answers. The header reports the seat, the model, the current context size and the streaming time.

The window rebuilds from the current game state, so you can close and reopen it at any point. With no game on screen there is nothing to show, and the button does nothing.

## Preferences

**Preferences** opens a tabbed dialog:

| Tab | What it changes |
|---|---|
| Main | Client behaviour: card names on cards, permanent player names, automatic target choice. |
| My Avatar | The avatar shown to other players. |
| GUI Size | Scale of cards and interface elements. |
| GUI Theme | Colours and theme assets. |
| GUI Images | Card image location, language, storage and rendering. |
| Game - Phases & Priority | Which phases stop your skips, and the default skip behaviour. |
| Game - Hotkeys | Rebinding of game keys. |
| Sounds | Game, draft and skip sounds, and background music. |
| Network | Proxy used for the server connection. |

Key rebinding lives under **Game - Hotkeys**: click the button for an action, press a key or a combination, press Space to clear it, or Escape to cancel. Changes apply when the next game starts. [Keyboard shortcuts](../reference/keyboard-shortcuts.md) is the complete list of defaults for both clients.

## Limits

- The client needs a reachable server; it does not run games offline.
- It speaks the XMage protocol on the server port. It does not connect through the web proxy's HTTP port; use the [web client](web-client.md) for that path.
- Replay depends on the server's saved game and on the original file name.
- The LLM seat type, the LLM chat window and per-seat LLM configuration require an LLMage server. A stock XMage server will not offer them.

## Related

- [Game controls](game-controls.md)
- [Game modes](game-modes.md)
- [Your first game against an LLM](../getting-started/first-llm-game.md)
- [The web client](web-client.md)
- [LLM opponents](llm-opponents.md)
- [Keyboard shortcuts](../reference/keyboard-shortcuts.md)
