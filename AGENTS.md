# AGENTS.md — LLMage (XMage LLM Integration)

Guidelines for AI coding assistants working on this codebase.
Based on principles from [mage-bench](https://github.com/GregorStocks/mage-bench) AGENTS.md,
adapted for this project.

## Project Overview

**LLMage** (llmage.cc) — a fork of [XMage](https://github.com/magefree/mage) with
LLM-powered AI opponents.
Three areas of work:

1. **LLM Player plugin** (`Mage.Server.Plugins/Mage.Player.LLM/`) — server-side AI player
   that delegates game decisions to an external LLM via OpenAI-compatible API. Extends
   `ComputerPlayer`, not the client.
2. **Web client** (`Mage.WebProxy/`) — Jetty WebSocket proxy serving a React frontend,
   bridging browsers to the XMage server. Backend: `mvn -pl Mage.WebProxy install
   -DskipTests`; frontend: `cd Mage.WebProxy/web/frontend/react && npm run build`
   (dev: `npm run dev`, React 19 + Zustand 5 + Vite + TS strict with
   `exactOptionalPropertyTypes`). Default port 8090; restart with
   `fuser -k 8090/tcp`. Also copied into `release/plugins/` so it's on the
   server's plugin classpath, but it runs standalone via `start-proxy.sh`.
3. **Core XMage fixes** — occasional patches to `Mage.Server`, `Mage.Common`, `Mage.Client`
   to support LLM/webclient functionality. Keep these minimal and well-documented.

See `LLM-README.md` for user-facing LLM setup docs.

## Build System

```bash
mvn clean install -DskipTests          # Full build (ALWAYS use -DskipTests for deploy)
mvn test -pl Mage.Tests                 # Run JUnit tests
mvn test -pl Mage.Tests -Dtest=LLMBasicTest   # Specific test class
mvn test -pl Mage.Tests -Dtest="LLM*"   # All LLM tests
```

Convenience targets in the root `Makefile`: `make build` (install + package) and
`make test` (runs tests with `-Dxmage.dataCollectors.printGameLogs=false`).

**Build freshness:** The `target/` directories get stale. If you see weird classloading
or NoSuchMethodError, run `mvn clean` first. Check build timestamps with:
```bash
stat --format='%y' Mage.Server/target/mage-server.jar
```

## LLMage/Stock Build Flavors

The WebProxy + React frontend build in three flavors so the official-server
artifact carries zero LLM code:

- **`-Pllmage`** — the LLMage build (deploy/release default). Registers
  `/llm-relay`, jar manifest `LLMage-Build: true`.
- **`-Pstock`** — official-server artifact: relay classes compile out
  (`LLMageRoutes` seam stub), manifest `LLMage-Build: false`; frontend built
  with `VITE_LLMAGE=false` (`__LLMAGE__` vite define) so the LLM UI tree-shakes
  out and branding is neutral. Build both together: `Mage.WebProxy/scripts/build-stock.sh`.
- **(no profile)** — "minimal", the unit-test build; same null seam.

**⚠️ Flavor staleness trap:** an *incremental* rebuild after a
flavor switch can leave a stale seam class in `target/classes` — producing a jar with
manifest `LLMage-Build: true` but the `LLMageRoutes` **stub** (relay returns null → `/llm-relay`
never registered; boot log `Stock build: /llm-relay route not registered (llmage=false)` while
`/config` still says `llmage:true`). No error surfaces otherwise. Always `mvn clean` before a
deploy build after any flavor switch, and verify at the class level:
`unzip -p <jar> mage/webproxy/LLMageRoutes.class | strings | grep -q LLmRelayRoute` — or live,
`curl -X OPTIONS https://host/llm-relay?...` → 404 means you shipped the stub.

**Activation is EXPLICIT ONLY** (`-Pllmage` / `-Pstock`). Do not convert to
property-based activation (`-Dllmage=true` style): a `!true` condition also
fires when the property is ABSENT, silently flipping default builds to stock
(measured 2026-09-25). Plain `mvn install` intentionally builds minimal.

**Runtime capability contract** (`ServerCapabilities.probe`, Mage.WebProxy):
right after `connectStart` the proxy reads the server's own `ServerState` —
`llm` = `playerTypes` contains `COMPUTER_LLM`. The `connected` WS message
carries `{llm, replayScrubber, replaySidecar, serverVersion, playerTypes}`.
The probe is honest by construction: an LLMage server reports `llm=true` even
to a stock proxy; the stock client's suppression is compile-time (bundle), not
wire-side. When `llm=false` the frontend hides the LLM tab, LLM seat option,
per-seat LLM config, and Share-LLM-Chat; the proxy fail-fasts
`fetch_models`/`fetch_personalities`/`llm_decision`/`llm_error`/`replay_seek`
with "does not support LLM features" and rejects `COMPUTER_LLM`/`llmConfig`
on create_table/create_tournament. `GET /config` reports the build flavor as
`{"hideServerTarget":…,"llmage":…}`.

**Wire-compat contract**: `MatchView`/`Seat`/`MatchOptions` suids are
pinned to the official-server (upstream `xmage_1.4.61V1`) values; extra fork
fields ride harmlessly (name-matched per field). Guard: `WireCompatSuidTest`
in Mage.WebProxy — if you touch those classes' fields, re-derive the pin
deliberately instead of letting it change by accident. Live checks:
`Mage.WebProxy/scripts/verify-capability-gating.py` (legs a/b) and
`verify-capability-gating-legc.py` (leg c: stock build ↔ LLMage server, local).

## Deploy

**Preferred: `scripts/deploy-release.sh`** (run from the repo root; all operational
scripts live in `scripts/`). It extracts the Maven assembly
(`Mage.Server/target/mage-server.zip`, which carries the full runtime dependency set),
copies everything to the right place, and **md5-verifies every JAR against its build
output**. Run it after `mvn clean install -DskipTests` + `(cd Mage.Server && mvn package
assembly:single -DskipTests)`.

It exists because the hand-copy list below is **incomplete on its own**: it covers the core
JARs and the two plugins that live in `release/plugins/`, but not the ~25 player/game/
tournament/deck plugin JARs that `bootstrap-plugins.sh` symlinks **from `release/lib/`**. A
fresh clone deployed from the list below alone starts with no game types. (`release/lib/` is
not tracked — `Mage.Server/release/` only ships `config/` and the launcher scripts in git.)

The script also encodes the layout rule: the Maven assembly puts player/game plugins in its
`plugins/`, but bootstrap reads them from `release/lib/` — so the player/game plugins are copied into
`release/lib/` (for bootstrap), while the LLM player + web proxy go to `release/plugins/`.

<details><summary>Manual copy list (what the script automates)</summary>

```bash
cp Mage.Server.Plugins/Mage.Player.LLM/target/mage-player-llm.jar \
   Mage.Server/release/plugins/mage-player-llm-1.4.62.jar
cp Mage.Server/target/mage-server.jar \
   Mage.Server/release/lib/mage-server-1.4.62.jar
cp Mage.Client/target/mage-client.jar \
   Mage.Client/release/lib/mage-client-1.4.62.jar
cp Mage/target/mage.jar Mage.Server/release/lib/mage-1.4.62.jar
cp Mage.Common/target/mage-common.jar Mage.Server/release/lib/mage-common-1.4.62.jar
cp Mage.Sets/target/mage-sets-1.4.62.jar Mage.Server/release/lib/mage-sets-1.4.62.jar
cp Mage.WebProxy/target/mage-web-proxy-1.4.62.jar \
   Mage.Server/release/plugins/mage-web-proxy-1.4.62.jar
```

</details>

**Do not skip `mage-sets`** — without it the server loads 0 cards and enters a
restart loop. Before copying, delete stale JARs from older versions in both
`release/lib/` and `release/plugins/` (e.g. any `*-1.4.61.jar` once on 1.4.62);
leftover old versions on the classpath cause subtle breakage.

**Important:** Copy to `Mage.Server/release/lib/` and `release/plugins/`, NOT the
top-level `release/` directory. Verify with `md5sum` that the copied JARs match the
build output. Restart both server and client after deploying — the JVM keeps old
classes otherwise. Then run `scripts/bootstrap-plugins.sh` (it fails loudly on a dangling link).

> Older notes mention a pre-built tarball; the repository ships source only, so
> build from source (see `INSTALL.md`).

## Running

Operational scripts live in `scripts/` for server kill/restart and bot-game
launch/monitoring:

- `start-server.sh` — **canonical LLM configuration point.** All `xmage.llm[2].*`
  Java properties are set here as `-D` flags, driven by env vars with defaults
  inline. To change models/effort/dialects: export `LLM1_MODEL`, `LLM1_EFFORT`,
  `LLM1_THINKING_DIALECT`, etc. (defaults: `gemma4:31b-cloud`/`high` +
  `deepseek-v4-flash:cloud`/`max` — confirmed intended, don't revert) or edit
  the env block in the script. Also archives old logs and bootstraps plugin
  symlinks — run this rather than invoking `java` directly.
  - **These properties only reach GUI/web-created tables if `LLMPlayer.init()` reads
    them.** They did not, for `reasoningeffort`, until 2026-09-16 — `LLM1_EFFORT` was
    silently ignored and the seat ran at `LLMConfig`'s `"max"` default. After
    changing any
    effort/dialect value, **confirm it took effect** by grepping the boot line
    `LLM thinking probe OK (... effort=<level>)` for the next table join.
  - Per-seat web-UI overrides are applied by `applyOverrides()` and only write non-null
    fields, so a form left at "(server default)" correctly inherits this script's values.
- `start-server-charm.sh` — hosted-deployment launcher: reads the API key from the
  file named by `$XMAGE_KEY_FILE` (**outside** the repo), exports it as
  `LLM1_KEY`/`LLM2_KEY`, and defaults both seats to the selected provider's
  endpoint (`XMAGE_LLM_PROVIDER=charm` (default) or `ollama`; ollama defaults to
  `https://ollama.com/v1/chat/completions`). There is deliberately NO
  in-repo key fallback (an in-repo key file is one `git add -f` from being committed).
  Use this rather than putting a key in `start-server.sh`. No secret is ever committed.
- `run-bot-game.sh` — launches a scripted LLM-vs-LLM game (server must already
  be running). Exit codes: 0 = completed, 1 = startup failure, 2 = timeout.
- `monitor-games.sh` — watches an in-progress game.
- `start-proxy.sh` — starts the web proxy (requires built frontend + proxy JAR).
  Passes `-Djava.net.preferIPv4Stack=true`: on dual-stack hosts `localhost` resolves to
  `::1` first while the XMage server listens IPv4-only, and the handshake otherwise fails
  with "Server is likely offline". For the same reason, type `127.0.0.1` (not
  `localhost`) into the web client's Server field. `--bind <ip>` selects the interface;
  binding a non-localhost address also requires adding that exact origin to
  `WebProxyServer.isAllowedOrigin()` or the WebSocket upgrade is refused.
  - **Deployment toggle — hide the connect form's Server/Port fields**:
    `-Dwebproxy.hideServerTarget=true` (default false) makes the proxy report
    `{"hideServerTarget":true}` at `GET /config`; the connect overlay then renders no
    Server/Port inputs and sends a `connect` message without them. It **requires**
    `-Dwebproxy.pinnedXmageTarget=host:port` — the proxy refuses to start otherwise
    (the browser would have no target to connect to). Use it for hosted deployments
    where the operator owns the target; local/LAN runs keep the fields. Verified by
    `Mage.WebProxy/scripts/verify-hidden-target-e2e.py` (live proxy) and
    `react/scripts/verify-connect-target-toggle.sh` (frontend, Node).
- `launch-rig-detached.sh` — starts the server **detached** (setsid double-fork, PPid 1)
  so it survives terminal-session teardown; refuses to start if a server is already running.
  Use this instead of invoking `start-server.sh`/`start-server-charm.sh` directly from a
  shell session — a plain child dies with the session.

**Deck format pitfall:** use `.txt` decklists, not plain-name `.dck` files.
`DckDeckImporter` silently skips any line without `[SET:NUM]`, so a plain-name
`.dck` imports as a 0-card deck — which causes the same mysterious 0-cards
restart loop as a missing mage-sets JAR (see the issue tracker entry on
zero-card decks).

## Testing

When changing Java code, add or update tests in `Mage.Tests/`. Run tests with:

```bash
mvn test -pl Mage.Tests -Dtest=LLMBasicTest        # Specific test class
mvn test -pl Mage.Tests -Dtest="LLM*"              # All LLM tests
```

XMage test fixtures use `CardTestPlayerBase` and its LLM variant
`CardTestPlayerBaseLLM` in `Mage.Tests/src/test/java/org/mage/test/LLM/`.

## CI Flakes

**CI flakes are not acceptable. Never re-run tests to work around a failure.**
If a test fails, the failure has a root cause — find it and fix it.

Common flake sources in this project:

- **Serialization races in multi-game sessions**: `stripAbilitiesForSerialization` in
  `saveGame` mutated live card objects (cleared `PlayLandAbility` etc.) breaking game 2+.
  This was removed — accept `NotSerializableException` as best-effort. If you see similar
  mutation bugs, check whether serialization is touching live game state.
- **NPE in CardView**: `getSpellAbility()` can return null. Always null-check.
- **Tournament sub-table cleanup**: The health check removes sub-tables between games.
  `isMatchTableStillValid` returns true for `tournamentSubTable`. Don't "fix" this —
  it's intentional.

## Coding Style: Fail Fast

**Never add graceful fallbacks, silent defaults, or backwards-compatibility shims.**
If something fails or is missing, crash immediately with a clear error. Do not invent
fallback behavior, even if it seems "safe" or "helpful." This includes:

- Falling back to a default value when a config/file/path is missing
- Catching exceptions and continuing with degraded behavior
- Keeping old code paths around for backwards compatibility
- Adding `try-catch` that swallows the error and returns a "reasonable" default

If you think a fallback or graceful degradation is genuinely the right call, **stop and
explicitly ask the maintainer to confirm** — don't just include it in code. Models are far too eager
to add these and they hide bugs.

If a staged migration genuinely requires a temporary compatibility wrapper or shim, it
must be obviously temporary. Add a `TODO(shim):` marker at the shim site with
machine-readable expiration metadata:

```java
// TODO(shim): expires=2026-09-01 Delete this wrapper once all callers use the new API.
// TODO(shim): expires=issue:mana-tap-control Delete this once mana plans are done.
```

Do not add an unlabeled shim.

```java
// Bad: hides the bug
try {
    result = session.sendPlayerString(gameId, choice);
} catch (Exception e) {
    result = false; // silently fail
}

// Good: surfaces the bug
assert session != null : "sendPlayerString called with null session";
boolean result = session.sendPlayerString(gameId, choice);
if (!result) {
    throw new IllegalStateException("sendPlayerString failed for choice: " + choice);
}
```

## XMage RPC Debugging

XMage's server-side RPC layer has several traps that cost hours of debugging:

1. **`MageServerImpl.executeWithResult` catches `Exception` and rethrows as
   `MageException(message-only)`** — stack traces are lost on the wire to the
   client. Our `handleException` logs the full trace server-side *before*
   flattening, so check the server log for "RPC call failed" when you see a
   `MageException` with no cause client-side. **Which file that lands in:**
   `/tmp/xmage-server-console.log` — the rig launcher
   (`scripts/launch-rig-detached.sh`) redirects the JVM's stdout there, and
   that file always exists while the server runs. `mageserver.log` (repo root)
   is written only when the server's own `log4j.properties` wins the root
   logger; see the Logging section for the classpath-order failure mode.

2. **`catch(Exception)` misses `Error` subclasses** like `NoSuchMethodError`,
   `NoClassDefFoundError`, `StackOverflowError`. Use `catch(Throwable)` in RPC
   boundary code.

3. **Silent `return false`**: Many server methods return `false` on failure with no
   logging. Add `logger.error` at EVERY `return false` point when debugging. Add
   `logger.info("ENTERED: ...")` and step-by-step logs to trace silent failures.

4. **`SessionImpl` uses JBoss Remoting (bisocket transport)**. Callbacks arrive
   asynchronously on listener threads. Thread safety in callback handlers is critical —
   use `volatile` or `synchronized` for shared state.

## LLM Player Architecture

Key things to know about `LLMPlayer.java`:

- **Server-side plugin**, not a client. Extends `ComputerPlayer` (which extends
  `PlayerImpl`). Lives in `Mage.Server.Plugins/Mage.Player.LLM/`.
- **`fetchModelContextWindow()` MUST stay in `LLMPlayer.init()`** — it auto-detects
  the model's context window from Ollama at startup. Moving it breaks context-aware
  summarization.
- **Deck validation**: deck format validation must stay enabled for ALL players including
  AI/LLM. Do not skip it as a workaround for invalid decks.
- **Conversation summarization**: Compresses old history when approaching context
  window limit. Per-model ratio config. Don't break the `summarizeContextRatio` /
  `summarizeTargetRatio` / `summarizeKeepExchanges` parameters.
- **Game log via DataCollector**: `GameImpl.informPlayers()` is unmodified (matches
  upstream). Game events are captured into `GameState.gameLog` by `LLMGameLogCollector`
  (registered in `DataCollectorServices.init()`), which calls `state.addGameLog()` on
  each `onGameLog` callback. The `gameLogEnabled` flag on `GameState` gates accumulation
  — it's a no-op for non-LLM games (flag defaults false, enabled by `LLMPlayer.init`).
  Do NOT re-add `state.addGameLog()` directly in `GameImpl.informPlayers()` — use the
  DataCollector hook to stay aligned with upstream.
- **Thinking dialects**: per-model props
  `xmage.llm[2].thinking_dialect` (`effort` | `budget`), `thinking_max_tokens`
  (cap, default 32768, 0 = no cap), `terse_answer_stop` (default true),
  `skip_thinking_probe` (default false; true for sandboxed CI). A startup probe
  validates thinking params against the endpoint at boot — invalid config fails
  fast, not at first call.
- **API retry policy** (`LLM_MAX_RETRIES = 5`): retry 429/5xx, IOExceptions, and
  empty-answer responses (honor `Retry-After`, else exponential backoff 2s→32s cap);
  fail immediately on other 4xx. After 5 attempts, crash — never loop forever.

## Tournament LLM Specifics

- **Join P1 first** when P1 is AI. The `NewTournamentDialog` collects per-player decks
  and configs starting from P1.
- **`startTournament` accepts `READY_TO_START`** state.
- **Health check behavior**: sub-table cleanup / `isMatchTableStillValid` — see the
  warning under "CI Flakes" above; don't change it.
- **`TableManagerImpl.addPlayer`** creates a fresh `LLMPlayer` copy + `Deck.copy()` for
  sub-tables. This ensures clean state per round.
- **Per-player configs**: Each `TournamentPlayer` carries its `LLMPlayerConfig` through
  the full tournament lifecycle. Configs are wired into sub-table seats at match start.

## Branches

Create feature branches from `master`. Name them descriptively with a topic prefix:

```bash
git checkout master && git pull
git checkout -b llm-mana-tap-control
git checkout -b fix-serialization-mutation
```

## Issues

**File issues for bugs you discover**, even if you're not fixing them. Future agent
sessions benefit from documented issues — they provide context, reproduction steps, and
save re-investigation time.

File issues on the repository's GitHub issue tracker. Include a description,
reproduction steps, and severity. Do not commit issue files to the tree.

## Notes for Future Agents

Keep durable observations in the commit message or a GitHub issue — the narrative
of a change belongs with the change, not in a dated progress log. Useful things to
record:
- Debugging sessions that took hours (so the next agent doesn't repeat the journey)
- XMage upstream quirks that affect our code
- Build/deploy pitfalls discovered the hard way

## Logging

**Two log destinations — know which one you are reading.**

- `/tmp/xmage-server-console.log` — **the live server log**. The rig launcher
  (`scripts/launch-rig-detached.sh`) redirects the JVM's stdout/stderr there, so
  it exists on every boot and carries everything the server prints, including
  LLM thinking output (when verbose). It survives from boot to boot — the
  launcher appends (`>>`), it is not rotated.
- `mageserver.log` (repo root) — written **only when the server's own
  `log4j.properties` wins the root logger**. Its appender set is
  `console, logfile` with `logfile.File=mageserver.log`. Rotated to
  `oldlogs/mageserver_<ts>.log` by `scripts/archive-logs.sh` at each boot
  (invoked from `start-server.sh`).

**The classpath-order trap.** `mage-server-<v>.jar` and
`mage-web-proxy-<v>.jar` BOTH carry a root `log4j.properties`. The proxy's is
`rootLogger=INFO,stdout` (one appender, no file); the server's is
`info, console, logfile`. reload4j resolves `log4j.properties` by classpath
enumeration, so whichever jar comes first in `Mage.Server/release/lib/*` wins —
and because that order is directory enumeration order (not sorted), a proxy jar
in `lib/` produces a server that boots with `- log appender: stdout | Level:
INFO` and **writes no `mageserver.log` at all**. Check the boot line:

```bash
grep "log appender" /tmp/xmage-server-console.log
# GOOD: - log appender: console | Level: INFO
#       - log appender: logfile | Level: INFO | File: mageserver.log
# BAD:  - log appender: stdout | Level: INFO      ← proxy jar shadowed the config
```

Guards: `scripts/deploy-release.sh` never copies the proxy jar to `release/lib/`
(it skips `mage-web-proxy-*` in the jar-list loop, then fails the deploy if one
is found there anyway) and keeps its `release/plugins/` copy — `plugins/*` is
strictly after `lib/*` on the server classpath, so the server's config then wins
deterministically. `scripts/monitor-games.sh`, `scripts/monitor-watch.sh` and
`scripts/pipeline.sh` fail loud when `mageserver.log` is absent while a server
runs, instead of reporting false zeros.

Other log locations:
- `gamelogs/game_<hash>/` — LLM transcripts and post-game analysis per game
- `gamelogsJson/` — JSON game logs
- `gamesHistory/` — game history records
- `oldlogs/` and `oldlogs+saves/` — archived logs

When LLM verbose mode is on, the server log shows `LLM request body: N chars` before
each API call and `LLM conversation summarized: X msgs -> Y chars` when compression fires.
