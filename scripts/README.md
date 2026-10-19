# scripts/ — operational scripts for LLMage

Every operational script (launchers, deploy, monitoring, test tooling) lives here —
run them from the repo root, e.g. `bash scripts/start-server.sh`. All scripts resolve
the repo root from their own location, so they work from any CWD.

`clean_dbs.sh` remains at the repo root on purpose: it is an upstream XMage file,
and moving tracked upstream files creates conflicts on every upstream sync.

## Server / client / proxy

| script | what it does |
|---|---|
| `start-server.sh` | **Canonical LLM configuration point.** Archives old logs, bootstraps plugin symlinks, starts the server (`:17171`). Env-overridable models/effort/dialects; keys via env only. |
| `start-server-charm.sh` | Hosted-deployment launcher: reads the API key from the file named by `$XMAGE_KEY_FILE` (charm or ollama-cloud via `XMAGE_LLM_PROVIDER`), exports it, delegates to `start-server.sh`. |
| `start-client.sh` | Desktop Swing client (`Mage.Client/release/lib/*`). |
| `start-proxy.sh` | Web proxy (`:8090`); needs built frontend + proxy JAR. IPv4-stack fix included; hosted-deployment toggles via env. |
| `launch-rig-detached.sh` | Detached rig: `start-server[-charm].sh` + `start-proxy.sh` via setsid (survives shell-session teardown); docker-cgroup-aware pre-flight guards. |
| `launch-proxy-detached.sh` | Detached proxy only (same guard design). |
| `bootstrap-plugins.sh` | Recreates `plugins/` symlinks from release JARs; fails loudly on dangling links. |
| `deploy-release.sh` | Maven-assembly-driven deploy into `Mage.Server/release/` with per-JAR md5 verification. |
| `archive-logs.sh` | Rotates `gamelogs/`, `gamesHistory/`, `*.log` into `oldlogs/` (run by `start-server.sh`). |
| `backup.sh` | Zstd tarball of the source tree (excludes images/builds/logs/data). |
| `clean_dbs.sh` | *(upstream, repo root)* wipes cards H2 DB files. |

## Bot-game testing

| script | what it does |
|---|---|
| `run-bot-game.sh` | Headless LLM-vs-LLM game via `BotGameRunner` (exit 0 complete / 1 start fail / 2 timeout). |
| `pipeline.sh` | Full loop: ensure server → bot game → analyze postgame. |
| `analyze-postgame.py` | Extracts bug claims from postgame reviews, cross-checks known false positives. |
| `pick-random-setup.py` | Random format/deck/model selection for autonomous testing (needs local Ollama for the pool). |
| `monitor-games.sh` | One-shot game-stall/error monitor. |
| `monitor-watch.sh` | Background watcher for parallel Bo3 matches. |
| `diag-rollback-prompts.py`, `verify-rollback-replay-e2e.py` | Replay/rollback diagnostics (2026-09-21). |
| `webtest-deck.txt` | 63-card vanilla test deck for web-UI tests (MTGO-style text list, bare names). |

## Replay maintenance

| script | what it does |
|---|---|
| `replay-codec-migrate.sh` | One-time gzip→zstd conversion of `saved/*.game` (2026-09-24 codec switch; streamed, byte-exact verified, idempotent). |
| `replay-retention.sh` | Prunes replay pairs by size/age policy, oldest first — **dry-run by default**, `--apply` to delete. Pairs stay consistent; see `--help`. |
| `verify-replay-retention.sh` | Self-contained fixture harness for `replay-retention.sh` (no server/docker; temp trees under `$TMPDIR`). |
| `verify-replay-llm-crossgame.sh` | Offline harness for cross-game `replayLLMData` scoping: Bo3 manufacture on the engine's player-reuse path, corpus walk (`--corpus DIR`), injected-defect selftest. The walk also asserts that the `misbound_pre_save` beginStep family is limited to the characterized cleanup-step shape. No server/network; compiles against the built tree. |

## Live verification harnesses (web/LLM)

End-to-end drivers that exercise the REAL web-proxy path against a local stack (several
against `mock-llm-sideboard.py`), used as end-to-end gates. Each harness has its own
reserved ports — do not run two at once.

| script | what it does |
|---|---|
| `mock-llm-sideboard.py` | Strict OpenAI-compatible mock for live LLM-seat tests: deterministic answers per prompt class; `MOCK_STALL_REFLECTION_SECONDS=N` holds the post-game reflection (marker `[REFLECTION-STALL]`). |
| `verify-llm-sideboard-live.{py,sh}` | Live LLM-seat Bo3 driver: raw-WS human seat + a `COMPUTER_LLM` seat against the strict mock; asserts the sideboard-summary + postgame files. |
| `verify-early-submit-repro.{py,sh}` | Measures the game_over→SIDEBOARDING race (base vs fixed legs; `--expect-fixed`). |
| `verify-run-bot-game-host.sh` | No-server check of `run-bot-game.sh`'s default host handling (docker-bridge substitution trap). |
| `verify-reason-field-live.sh` + `reasonverify-{launch-stack.sh,live-driver.py,postchecks.py,teardown-stack.sh}` | Ask-reason REASON-line verification: flag-on wire shapes → transcript assertions; flag-off byte-identity vs the pre-feature build; hostile-shape legs. |
| `verify-postgame-reflection-async.sh` (+ `-launch.sh` / `-teardown.sh`, `.py` driver) | Async post-game reflection E2E: control leg (postgame files ARE written) + stall leg (match proceeds while the reflection is held). |
| `ainames-launch-stack.sh` / `ainames-live-driver.py` / `ainames-teardown-stack.sh` | Asserts web-launched LLM seats get generated names + personalities (no `AI-<n>` placeholders). |
| `VerifyReplayLlmCrossgame.java` | Driver behind `verify-replay-llm-crossgame.sh` (cross-game `replayLLMData` scoping). |
