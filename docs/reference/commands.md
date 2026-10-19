# Commands and scripts

Every operational entry point in LLMage is a script under `scripts/`, plus a short set of Maven and npm commands used to build the tree. This page lists each one with its purpose, its arguments, the environment it reads, and what its exit codes mean.

> **Who this is for:** operators and developers who run LLMage from a shell.

## How the scripts are organised

- Operational scripts live in `scripts/`. Two exceptions sit at the repository root: the upstream `clean_dbs.sh` and the `Makefile`.
- Scripts resolve the repository root from their own location, so they run from any directory. A directory argument resolves against your current directory.
- Processes start in the foreground unless you use a detached launcher. The detached launchers use `setsid` so the JVM outlives the shell that started it.
- Exit codes are part of the contract: the headless tools use `0` for success, `1` for a real failure, `2` for a timeout or a failed assertion, and `3` for a missing harness prerequisite. The entries below give the exact meaning per script.
- Secrets come from the environment, never from a `-D` flag; a key in the process arguments is readable by every local user.

## Build and deploy

| Command | What it does |
|---|---|
| `export MAVEN_OPTS=-Xmx9g` | Raises the Maven heap. The reactor runs out of memory in `mage-sets` at the default heap. |
| `mvn clean install -DskipTests` | Builds every module and installs the artifacts locally. |
| `(cd Mage.Server && mvn package assembly:single -DskipTests)` | Produces `Mage.Server/target/mage-server.zip`, the runtime jar list the deploy reads. |
| `scripts/deploy-release.sh` | Copies the assembly into `Mage.Server/release/lib/` and `release/plugins/`, verifying each jar by MD5. Fails if a proxy jar reaches `lib/`. |
| `mvn -pl Mage.WebProxy install -DskipTests -Pllmage` | Builds the proxy in the LLMage flavor, with the `/llm-relay` route. A plain build omits it. |
| `(cd Mage.WebProxy/web/frontend/react && npm ci && npm run build)` | Builds the frontend into `Mage.WebProxy/web/frontend/`. |
| `mvn -pl Mage.Server.Console install -DskipTests` | Builds the headless runner that `run-bot-game.sh` needs. |
| `Mage.WebProxy/scripts/build-stock.sh` | Builds the stock proxy and frontend pair for official servers. |

`make build`, `make test`, and `make package` wrap the install, JUnit, and packaging steps.

## Start and stop

| Script | What it does | Usage and notes |
|---|---|---|
| `start-server.sh` | The canonical LLM configuration point. Archives logs, recreates plugin symlinks, then starts `mage.server.Main` with the `xmage.llm*` properties set from its environment block. | `scripts/start-server.sh`. No arguments. Reads `LLM1_*`, `LLM2_*`, `LLM_*`, `XMAGE_CONFIG_PATH`, `XMAGE_SERVER_HEAP`. Port `17171` by default. |
| `start-server-charm.sh` | Hosted launcher. Reads a key file, exports it to both seats, and delegates to `start-server.sh`. | `XMAGE_KEY_FILE=<path> scripts/start-server-charm.sh`. `XMAGE_LLM_PROVIDER` selects a preset (`charm` or `ollama`); endpoint, model, and effort are overridable. Exits `1` without a readable key file. |
| `start-client.sh` | Starts the Swing desktop client. | `scripts/start-client.sh`. The header documents host and port arguments, but the client only parses its own mode flags; enter the address in the connect dialog. |
| `start-proxy.sh` | Starts the web proxy that serves the React client and bridges browsers to the server. | `scripts/start-proxy.sh [--port <port>] [--bind <ip>]`. Defaults to `127.0.0.1:8090`. Exits `1` if the proxy jar is missing; the frontend must be built first or there is no UI to serve. |
| `bootstrap-plugins.sh` | Recreates `plugins/` symlinks from the release jars; fails on a dangling link. | `scripts/bootstrap-plugins.sh`. Idempotent; called by `start-server.sh`. |
| `launch-rig-detached.sh` | Starts the server detached, plus the proxy unless told otherwise. Refuses to start if a server is already running. | `scripts/launch-rig-detached.sh [--plain] [--no-proxy]`. `--plain` uses the plain server defaults instead of the hosted preset. Console output goes to a log under the system temp directory. |
| `launch-proxy-detached.sh` | Starts only the proxy detached, with the same guards. | `scripts/launch-proxy-detached.sh [--bind <ip>] [--port <port>]`. |
| `archive-logs.sh` | Moves `gamelogs/`, `gamelogsJson/`, `gamesHistory/`, and current logs into timestamped directories under `oldlogs/`. Never deletes. | `scripts/archive-logs.sh`. Runs at every server boot. |

To stop a server, stop the JVM you started: `Ctrl+C` in the foreground, or the PID printed by the detached launcher. Confirm the port is free before restarting.

## Deployment and maintenance

| Script | What it does | Usage and notes |
|---|---|---|
| `clean_dbs.sh` | Deletes every `cards.h2*` database file. The next server start rebuilds the card database from the set classes. | `bash clean_dbs.sh` from the root. Upstream file, kept at the root on purpose. |
| `backup.sh` | Writes a zstd tarball of the source tree, excluding `.git`, build outputs, logs, saves, the card database, and downloaded card images. | `scripts/backup.sh [output-dir]`. Defaults to a `Documents/Backups/Xmage` folder in your home directory. |
| `replay-retention.sh` | Prunes `<uuid>.game` and `<uuid>.json` pairs by byte budget or age, oldest first. Dry-run by default. | `scripts/replay-retention.sh [DIR] --max-bytes SIZE --max-age-days N --keep N [--apply]`. One policy required. Deletes `.json` before `.game`, skips files modified in the last 120 seconds, and ignores non-UUID names. Exit `0` completed, `1` fatal. |
| `replay-codec-migrate.sh` | Converts legacy gzip saves to the zstd container. Verifies the round trip before replacing the file. | `scripts/replay-codec-migrate.sh [dir] [--dry-run]`. Defaults to `saved`; needs the `zstd` command. Safe to re-run. |
| `replay-uid-recover.sh` | Rewrites a save written by another build so the current build can read it. | `scripts/replay-uid-recover.sh [--in-place] <file-or-dir>...`. Without `--in-place`, writes `<name>.recovered.game` beside the source. |

## Watch a running server

| Script | What it does | Usage and notes |
|---|---|---|
| `monitor-games.sh` | One-shot check: live runner count, fatal and invalid-action lines, and the age of each transcript. | `scripts/monitor-games.sh`. Exit `1` when no server is running, `3` when its log file is missing. |
| `monitor-watch.sh` | Background watcher for parallel matches. Polls every five minutes and flags a transcript older than fifteen minutes while a runner is alive. | `scripts/monitor-watch.sh`. State and log live under the system temp directory. |

Both monitors fail loudly when the server log is missing instead of reporting zeros. That symptom points to the logging classpath race described in [Troubleshooting](troubleshooting.md).

## Run games without a client

| Script | What it does | Usage and notes |
|---|---|---|
| `run-bot-game.sh` | Runs a headless LLM-versus-LLM match through `BotGameRunner`. The server must already be running. | `scripts/run-bot-game.sh [--bo3] [--timeout-minutes N] [--deck1 PATH] [--deck2 PATH] [--host HOST] [--port PORT] [--vs-stock-ai]`. Exit `0` completed, `1` startup failure, `2` timeout. Defaults the host to `127.0.0.1`. |
| `pipeline.sh` | Starts the server if needed, runs one bot game with a 45-minute timeout, then analyses the result. | `scripts/pipeline.sh`. Exit `0` no bugs, `1` potential bugs, `2` pipeline error. |
| `analyze-postgame.py` | Extracts the technical-issues section of the newest reflection and checks each claim against known false positives. | `python3 scripts/analyze-postgame.py [game-dir]`. Exit `1` unresolved claims, `2` no files or a parse failure. |
| `pick-random-setup.py` | Prints a random format, two decks, and two models for a test game. | `python3 scripts/pick-random-setup.py [--json]`. Needs a local Ollama and deck collection. Exit `1` when no decks are found. |

`run-bot-game.sh` forwards unknown arguments to the runner unchanged. The runner defaults to LLM versus LLM; `--vs-stock-ai` makes the second seat the built-in MAD AI.

## Replay and serialization gates

These are developer gates, not player tools. They catch the class of change that makes existing saves unreadable.

| Script | What it does | Usage and notes |
|---|---|---|
| `check-serial-uid-drift.sh` | Fails when a save-path class has no pinned `serialVersionUID` and no allowlist row. With `--changed`, also fails when a changed class's computed UID differs from its pin. | `scripts/check-serial-uid-drift.sh [--changed [BASE]] [--update-allowlist]`. Exit `0` pass, `1` violations, `2` build error. |
| `check-save-uid-manifest.sh` | Recomputes the class-to-UID manifest and fails on any difference: changed value, missing class, new class, or unresolvable row. | `scripts/check-save-uid-manifest.sh [--write-manifest] [--affected-saves DIR...] [--self-test] [--debug]`. `--affected-saves` reports whether a corpus would still load. |
| `self-test-save-uid-manifest.sh` | Negative control: perturbs one class, asserts the manifest gate fails while the per-class gate passes, then restores the file. | `scripts/self-test-save-uid-manifest.sh`. |
| `gen-serial-oracle-cp.sh` | Rebuilds the dependency classpath the two gates use. | `scripts/gen-serial-oracle-cp.sh`. Run after a dependency change. |

`make check-serial-uid` runs both gates. `make check-serial-uid-selftest` runs the control and needs `XMAGE_GAME_FIXTURES` pointing at a directory of `.game` saves, since the repository ships none.

## Verification harnesses (development and test only)

Each harness drives a live stack, reserves its own ports, and starts its own server, proxy, and often a mock provider. Run one at a time.

| Script | What it proves |
|---|---|
| `mock-llm-sideboard.py` | A strict OpenAI-compatible mock with deterministic answers per prompt class and an optional delay on the post-game reflection. |
| `verify-llm-sideboard-live.sh` | A best-of-three with one LLM seat; asserts the sideboard and post-game files are written. |
| `verify-early-submit-repro.sh` | Measures the end-of-game to sideboard-submission race, with fixed and base legs. |
| `verify-run-bot-game-host.sh` | That the bot-game wrapper passes a loopback host when the caller gives none. No server needed. |
| `verify-reason-field-live.sh` | That the optional reason line reaches the transcript with the flag on, and prompts stay byte-identical with it off. |
| `verify-postgame-reflection-async.sh` | That a held reflection does not block the match or the next game. |
| `verify-replay-llm-crossgame.sh` | That per-game LLM replay records stay bound to their own game across a match and a corpus. |
| `verify-replay-retention.sh` | That the retention decision table, deletions, and safety rails hold on synthetic fixtures. |
| `ainames-launch-stack.sh` | Stands up the stack used to check that web-created seats get generated names, not placeholders. |

The live harness wrappers exit `0` when every check passes, `2` when a check fails, and `3` when the stack or a prerequisite is missing, so a broken setup is never mistaken for a product failure. The two offline replay harnesses differ: they exit `1` for a failed phase and `2` for a usage or build error.

Each wrapper has companion drivers in the same directory (the `reasonverify-*` and `ainames-*` families, a `.py` driver beside each `.sh` wrapper, and the Java driver behind the cross-game harness). Run the wrapper, not the companion.

## In-client commands

Both clients send chat lines beginning with a backslash to the server as commands. The web client also handles three `/` commands locally.

| Command | Effect |
|---|---|
| `\history <player>` or `\h <player>` | Shows that player's match history. |
| `\me` | Shows your own history. |
| `\list` or `\l` | Lists the server's commands. |
| `\whisper <player> <text>` or `\w <player> <text>` | Sends a private message. |
| `\card <name>` | Prints a card's oracle text. |
| `\ignore [player]` / `\unignore <player>` | Shows, adds to, or removes from your ignore list. |
| `\botgame [options]` | Creates a bot game and spectates it. |
| `\game`, `\fix`, `\pings` | Print game-state debug data, attempt a stuck-game repair, and print ping data. |

The web client also understands `/help`, `/clear`, and `/whisper` (or `/w`) without a round trip. Everything else beginning with `/` is forwarded unchanged, so the server stays authoritative.

`\botgame` takes the flags below in any order, then an optional format name or one or two absolute deck paths.

| Form | Result |
|---|---|
| `\botgame` | LLM seat versus MAD AI, both on generated 40-card decks. |
| `\botgame Legacy` | Same, with Legacy deck construction. |
| `\botgame --bo3` | Best-of-three instead of one game. |
| `\botgame --llm2` | LLM versus LLM, using the second model configuration. |
| `\botgame /path/one.dck /path/two.dck` | Two explicit decks. Paths must be absolute; a deck under fifteen cards is refused. |

## Environment variables

The launchers read these; a value in your shell wins over the script's default.

| Variable | Meaning |
|---|---|
| `LLM1_MODEL`, `LLM2_MODEL` | Model identifier for each seat. |
| `LLM1_ENDPOINT`, `LLM2_ENDPOINT` | OpenAI-compatible chat-completions URL. |
| `LLM1_KEY`, `LLM2_KEY` | API key, delivered through the environment. Empty for a keyless local endpoint. |
| `LLM1_EFFORT`, `LLM2_EFFORT` | `low`, `medium`, `high`, or `max`. |
| `LLM1_CONTEXT_WINDOW`, `LLM2_CONTEXT_WINDOW` | Context window in tokens, required when the provider publishes none. |
| `LLM1_THINKING_DIALECT`, `LLM2_THINKING_DIALECT` | `effort` or `budget`. |
| `LLM1_ASK_REASON`, `LLM2_ASK_REASON` | Appends a one-line reason request to each prompt. Off by default. |
| `LLM1_SKIP_PROBE`, `LLM2_SKIP_PROBE` | Skips the startup thinking probe. CI only. |
| `XMAGE_CONFIG_PATH` | Server config file; defaults to `Mage.Server/config/config.xml`. |
| `XMAGE_SERVER_HEAP` | Server heap; defaults to `2g`. |
| `XMAGE_KEY_FILE` | Key file outside the repository, read by the hosted launcher. |
| `XMAGE_LLM_PROVIDER`, `XMAGE_LLM_MODEL`, `XMAGE_LLM_ENDPOINT`, `XMAGE_LLM_EFFORT` | Provider preset and its overrides, applied to both seats. |
| `XMAGE_PROXY_PINNED_TARGET` | Pins every browser session to one `host:port`. |
| `XMAGE_PROXY_HIDE_SERVER_TARGET` | Hides the server and port fields. Requires the pin. |
| `XMAGE_PROXY_ALLOWED_ORIGINS` | Extra origins allowed to open the WebSocket. |
| `XMAGE_ALLOWED_XMAGE_HOSTS` | Extra XMage hosts the connect form may target. |
| `XMAGE_ALLOWED_MODEL_HOSTS` | Extra model hosts the browser-key relay may call. |

## Related

- [Deployment](../operator-guide/deployment.md)
- [Operations](../operator-guide/operations.md)
- [Building](../developer-guide/building.md)
- [File layout](file-layout.md)
- [Troubleshooting](troubleshooting.md)
