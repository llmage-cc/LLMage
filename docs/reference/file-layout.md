# File layout

Where things live: the modules that make up the build, the directories the server creates at runtime, the files a deployed tree contains, and the formats you will find inside them. Use this page when a guide names a file and you need to know which copy of it is the live one.

> **Who this is for:** operators and developers who need to find, inspect, or back up a specific file.

## Repository modules

The Maven reactor builds twelve modules: nine ship runtime code, two hold tests and card-data verification, and one aggregates coverage reports.

| Module | Contents |
|---|---|
| `Mage` | The game engine: card and ability model, rules enforcement, players, game state, deck importers, card repository. |
| `Mage.Common` | Code shared by client and server: the remote session and connection classes, view objects, serialization, and the version check. |
| `Mage.Sets` | Card implementations, grouped into one package per initial letter, plus the expansion definitions. This is the module whose jar the server must find or it loads no cards. |
| `Mage.Server` | The server: entry point, game and table controllers, chat, configuration, and the `release/` runtime tree. |
| `Mage.Server.Console` | Headless console tools, including `BotGameRunner` used by the automated game scripts. |
| `Mage.Server.Plugins` | One module per player type, game type, deck type, and tournament type. The LLM player is `Mage.Player.LLM`. |
| `Mage.Client` | The Swing desktop client, its `release/` tree, sample decks, sounds, and backgrounds. |
| `Mage.WebProxy` | The Jetty WebSocket proxy plus the React frontend sources it serves. |
| `Mage.Plugins` | Plugin host module with a sample counter plugin. |
| `Mage.Tests` | JUnit suites and deck fixtures, including the LLM test base classes. |
| `Mage.Verify` | Card-data verification suite. |
| `Mage.Reports` | Aggregated code-coverage report; packaging-only, no sources of its own. |

Two supporting directories sit beside the modules:

- `Utils/` holds developer scripts for card generation and data downloads. None of them are needed to build or run.
- `repository/` is a small bundled Maven repository for a third-party dependency that is not on Maven Central.

`scripts/` holds every operational script described in [Commands and scripts](commands.md). `Makefile` wraps the common build, test, and packaging targets.

### Plugin modules

`Mage.Server.Plugins` contains one Maven module per pluggable type:

| Family | Examples |
|---|---|
| Players | `Mage.Player.LLM`, `Mage.Player.Human`, `Mage.Player.AI.MAD`, `Mage.Player.AIMCTS`, `Mage.Player.AI.DraftBot` |
| Games | `Mage.Game.TwoPlayerDuel`, `Mage.Game.FreeForAll`, `Mage.Game.CommanderDuel`, `Mage.Game.CommanderFreeForAll`, `Mage.Game.OathbreakerDuel`, `Mage.Game.TinyLeadersDuel`, `Mage.Game.MomirDuel` |
| Decks | `Mage.Deck.Constructed`, `Mage.Deck.Limited` |
| Tournaments | `Mage.Tournament.Constructed`, `Mage.Tournament.BoosterDraft`, `Mage.Tournament.Sealed` |

The server resolves these by jar name from the `plugins/` directory, using the entries in its configuration file. That is why a missing or dangling symlink turns into an "unknown player type" failure at table join.

## Build outputs

Every module writes to its own `target/` directory. The files that matter after a build:

| Path | Produced by |
|---|---|
| `Mage/target/mage-<version>.jar` | Core engine jar. |
| `Mage.Sets/target/mage-sets-<version>.jar` | Card implementations. Required at runtime. |
| `Mage.Server/target/mage-server-<version>.jar` | Server jar. |
| `Mage.Server/target/mage-server.zip` | Maven assembly with the full runtime dependency set; the input to `deploy-release.sh`. |
| `Mage.Client/target/mage-client.zip` | Client assembly, used by `make package`. |
| `Mage.Server.Console/target/mage-server-console-<version>.jar` | Headless runner used by `run-bot-game.sh`. |
| `Mage.WebProxy/target/mage-web-proxy-<version>.jar` | Thin proxy jar; its dependencies sit in `Mage.WebProxy/target/lib/`. |
| `Mage.WebProxy/web/frontend/index.html` and `assets/` | React build output. Generated, never edited by hand. |
| `Mage.Client/release/lib/`, `Mage.Server/release/lib/`, `Mage.Server/release/plugins/` | Populated by `deploy-release.sh`; not tracked by git. |

The version in every artifact name comes from `<mage-version>` in the root `pom.xml`.

## Runtime directories

The server resolves its state directories relative to its **working directory**. The launchers change to the repository root before starting the JVM, so a normal run keeps everything in one place:

```text
<path-to-xmage>/
├── db/                     card, user, feedback, record, and stats databases
├── saved/                  replay saves: <uuid>.game plus <uuid>.json
├── gamelogs/               one game_<hash>/ directory per game
│   └── game_<hash>/        transcripts, post-game reflections, sideboard summaries
├── gamelogsJson/           JSON game logs, one file per game
├── gamesHistory/           per-game history records
├── oldlogs/                rotated logs, one timestamped directory per boot
├── plugins/                symlinks created by bootstrap-plugins.sh
├── extensions/             optional server extension packages
└── mageserver.log          server log, when its logging config wins
```

| Directory | Written by | Contents and notes |
|---|---|---|
| `db/` | Server and proxy | `cards.h2.mv.db` (card and expansion tables), `authorized_user.h2.mv.db` (registered accounts), `feedback.h2.mv.db`, `table_record.db`, `user_stats.db`. The proxy opens the same `db/` path and refuses to start if the card database is empty or incomplete. |
| `saved/` | Server | One `.game` file per saved match in the zstd replay container, plus a `.json` metadata sidecar with the display information the replay browser lists. |
| `gamelogs/` | LLM player plugin | One `game_<hash>/` directory per game containing `<player>_<model>_transcript-<stamp>.txt`, `..._postgame-<stamp>.txt`, and `..._sideboard-<stamp>.txt`. |
| `gamelogsJson/` | Game session | One `game-<gameId>.json` per game, written when JSON logging is enabled. |
| `gamesHistory/` | Game-history data collector | One record per completed game. |
| `oldlogs/` | `archive-logs.sh` | `gamelogs_<stamp>/`, `gamelogsJson_<stamp>/`, `gamesHistory_<stamp>/`, and `mageserver_<stamp>.log`. Never deleted by the archiver. |
| `plugins/` | `bootstrap-plugins.sh` | Relative symlinks into `Mage.Server/release/lib/` and `release/plugins/`. |
| `extensions/` | Server | Created on first boot; each subdirectory is loaded as an extension package. |

The database files under `db/` are ignored by git. Deleting them is safe: the server rebuilds the card database from the compiled set classes on the next start, which takes noticeably longer than a normal boot.

## Deployed tree

After `deploy-release.sh` the two release trees are the only ones the launchers read:

```text
Mage.Server/release/
├── config/
│   ├── config.xml          server settings and plugin registry
│   ├── log4j.properties    server logging configuration
│   └── security.policy
├── lib/                    engine, sets, common, server, and plugin jars
├── plugins/                LLM player plugin and web proxy jar
├── server.msg.txt          message of the day
└── startServer.sh          upstream launcher, not used by scripts/

Mage.Client/release/
├── config/
│   ├── log4j.properties
│   └── security.policy
├── lib/                    client jars and dependencies
├── sample-decks/           deck files grouped by year and format
├── backgrounds/            client background images
└── startClient.sh          upstream launcher, not used by scripts/
```

The split between `lib/` and `plugins/` is deliberate. The server's classpath lists `lib/*` before `plugins/*`, and both the server and the proxy jars carry a root `log4j.properties`. Keeping the proxy jar in `plugins/` is what lets the server's own logging configuration win; see [Troubleshooting](troubleshooting.md).

`Mage.Server/config/config.xml` is the tracked configuration used by `start-server.sh`. `Mage.Server/config/config.rig.xml` is a second configuration with different ports, used by the detached rig launcher. Both list the same plugin families.

## Client-side files

| Path | Purpose |
|---|---|
| `Mage.Client/release/config/log4j.properties` | Desktop client logging configuration. |
| `Mage.Client/release/sample-decks/` | Ready-made decks, grouped by year and format; useful as deck-format examples. |
| `Mage.Client/serverlist.txt` | Cached server list written by the client; ignored by git. |
| `Mage.Client/plugins/images/`, `Mage.Client/plugins/plugin.data` | Downloaded card images and plugin data; ignored by git. |
| `Mage.WebProxy/web/frontend/` | The served frontend: `index.html`, `assets/`, plus the tracked `decks/`, `avatars/`, `styles/`, and `sounds/` directories. |
| `Mage.WebProxy/web/frontend/react/` | Frontend sources. `npm run build` writes the parent directory's `index.html` and `assets/`. |

## Replay and deck files

| File | Format |
|---|---|
| `<uuid>.game` | A complete match save. The current reader accepts only the zstd container; older gzip saves must be converted once. |
| `<uuid>.json` | Display metadata for the replay browser: players, decks, result, and timestamps. Display-only; an orphan `.game` is still a replay the browser accepts, but it carries no summary of its own. |
| `*.dck` | Native deck format. Card lines must carry a `[SET:NUMBER]` code; a plain card name is skipped by the importer. |
| `*.txt` | Text decklist. The importer accepts plain card names, which makes it the safer format for hand-written decks. |

A deck imported from a file extension the importer does not recognise yields an empty deck rather than an error, which is why the bot-game handler refuses to start a match with fewer than fifteen cards.

Base XMage behaviour is documented in the [upstream XMage wiki](https://github.com/magefree/mage/wiki).

## Related

- [Commands and scripts](commands.md)
- [Building](../developer-guide/building.md)
- [Deployment](../operator-guide/deployment.md)
- [Operations](../operator-guide/operations.md)
- [Troubleshooting](troubleshooting.md)
