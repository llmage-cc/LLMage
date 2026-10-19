# Testing

LLMage has seven test layers: engine and card tests, LLM decision tests, proxy unit tests, card-data verification, serialization gates, end-to-end harnesses, and a load test. Each layer answers a different question, and no single command runs all of them. This page maps the layers to commands and states what each one actually proves.

> **Who this is for:** developers changing engine, plugin, proxy, or prompt code who need the right test command and a realistic idea of its coverage.
>
> **LLMage addition.** The LLM suites, proxy suites, serialization gates, and live harnesses are fork additions; the base card and rules suite is upstream.

## Test layers

| Layer | Location | Question it answers |
|---|---|---|
| Engine and card tests | `Mage.Tests` | Do the rules and card implementations behave correctly? |
| LLM decision tests | `Mage.Tests/src/test/java/org/mage/test/LLM` | Does the LLM player turn a game state into the expected action? |
| Proxy unit tests | `Mage.WebProxy/src/test` | Do serializers, gates, and session logic behave without a server? |
| Card-data verification | `Mage.Verify` | Are card metadata, text, and set registration consistent? |
| Serialization gates | `scripts/check-*uid*` | Would this build orphan saved games or replays? |
| End-to-end harnesses | `scripts/verify-*`, `Mage.WebProxy/scripts/verify-*` | Does the real proxy, server, and browser protocol path work? |
| Load test | `Mage.Tests` load package | Does a batch of AI games complete without stalls? |

## Before running anything

Build and install the modules first. A test run that compiles against stale installed artifacts gives false results:

```bash
export MAVEN_OPTS=-Xmx9g
mvn clean install -DskipTests
```

For a single module with its dependencies installed first:

```bash
mvn -pl Mage.Tests -am install -DskipTests
mvn test -pl Mage.Tests
```

`-am` builds the modules the tests depend on. Without it, Maven resolves `mage-*` artifacts from the local repository, which may be older than the working tree.

## Engine and card tests

The main suite is the upstream XMage test framework plus fork additions:

```bash
mvn test -pl Mage.Tests
```

The suite contains about two thousand test classes. A full run takes a long time; during development, select a class or package:

```bash
mvn test -pl Mage.Tests -Dtest=LLMBasicTest
mvn test -pl Mage.Tests -Dtest="LLM*"
```

Useful switches:

| Property | Effect |
|---|---|
| `-Dxmage.dataCollectors.printGameLogs=false` | Suppresses per-game log output; used by `make test` |
| `-Dxmage.build.tests.treeViewRunnerShowAllLogs=true` | Shows stdout for passing tests too |
| `-Dxmage.llm.skip=true` | Skips the LLM integration tests |
| `-Dxmage.llm.endpoint=...` | Endpoint for the live LLM integration test |
| `-Dxmage.llm.model=...` | Model for the live LLM integration test |
| `-Dxmage.llm.reasoningeffort=...` | Effort level accepted by that model |

`make test` runs the whole reactor with game-log printing disabled. It is the closest local equivalent to a CI test run.

## The LLM suite

`Mage.Tests/src/test/java/org/mage/test/LLM` contains 52 test classes and roughly 260 test methods. They fall into these groups:

| Group | Examples | What is verified |
|---|---|---|
| Decision plumbing | `LLMBasicTest`, `LLMActionNumberRangeTest`, `LLMAltCostChoiceTest` | The chosen action reaches the engine and changes the game |
| Prompt construction | `LLMCardTextOnceTest`, `LLMCanonicalModeNumberingTest`, `LLMLabelHygieneTest` | Headers, numbering, card-text deduplication, labels |
| Configuration | `LLMApplyOverridesTest`, `LLMSeatConfigCreationTest`, `LLMConfigContextWindowTest` | Per-seat values override server defaults correctly |
| Thinking and retries | `LLMThinkingDialectTest`, `LLMEffortLadderTest`, `LLMNoThinkingFinalAttemptBodyTest` | The request body changes per attempt as designed |
| Failure handling | `LLMTerminalFailureEndgameTest`, `LLMSideboardTerminalFailureTest`, `LLMPriorityRetryExhaustedFallbackTest` | A dead provider ends the game cleanly instead of hanging |
| Replay and serialization | `LLMApiKeySerializationTest`, `LLMBetweenGamesReplayDataTest` | Keys are not persisted; replay data is scoped per game |
| Concurrency | `LLMGameLogConcurrencyTest`, `LLMPostGameSurvivesConcurrentLogTest` | Log appends and post-game writes do not race |

About twenty classes extend `CardTestPlayerBaseLLM`. Player A is the LLM seat, Player B is a scripted opponent. The fixture uses a dummy deck, disables deck assessment, and forces mulligan to keep so injected test cards are not discarded. Tests that need a specific model answer inject a scripted `LLMClient` subclass through `TestLLMPlayer.injectTestClient`; they do not contact a provider.

`LLMBasicTest` is the live integration test. It runs only when `-Dxmage.llm.endpoint` and `-Dxmage.llm.model` are set, or when a local Ollama answers at `localhost:11434`; otherwise it is skipped. Use a local model for this test. A real cloud key passed as a `-D` flag is visible in the process argument list.

Run the live test explicitly:

```bash
mvn test -pl Mage.Tests -Dtest=LLMBasicTest \
  -Dxmage.llm.endpoint=http://localhost:11434/v1/chat/completions \
  -Dxmage.llm.model=<model-name> \
  -Dxmage.llm.reasoningeffort=high
```

The suite proves that decision plumbing, request shape, retry behavior, and failure handling work. It does not prove that a model plays well. A model that passes every test can still make poor strategic choices; that is a prompt and model-quality question, not a unit-test question.

## Proxy unit tests

```bash
mvn test -pl Mage.WebProxy
```

The module has 38 test classes covering:

- capability probing and gating (`ServerCapabilitiesTest`, `WebProxyPublicHostingTest`)
- serializers for views, choices, combat, facedown cards, and rollback state
- connection policy (`PerHostConnectionLimiterTest`, `WebProxyConnectTargetTest`)
- authentication and registration paths
- table ownership, start-game join gating, and anonymous names
- replay listing, rejoin, and stale-state admission
- model fetching, error text, and fetch rate limits
- token art, avatars, static cache policy, and deck loading

These tests do not need a running server. They use the module's test seams, including `ServerCapabilities.forTest` and direct serializer calls. They are the fastest way to check a protocol or gating change.

Some proxy fixes also have standalone shell assertions that need only a JDK:

```bash
bash Mage.WebProxy/scripts/verify-lobby-proxy-units.sh
```

That harness compiles two dependency-free classes and checks the table-watch and start-game join decisions without Maven or a server.

## Card-data verification

```bash
mvn -pl Mage.Verify test -Dtest=VerifyCardDataTest \
  -Dxmage.dataCollectors.printGameLogs=false
```

To limit the run to specific sets:

```bash
mvn -pl Mage.Verify test -Dtest=VerifyCardDataTest \
  -Dxmage.tests.verifyCheckSetCodes="MSH;MSC"
```

The suite checks card metadata, rules text, set and rarity registration, and other data invariants. Run it after adding or editing cards. It does not test gameplay; pair it with a rules test in `Mage.Tests`.

## Serialization and replay gates

Two shell gates protect saved games and replays from silent class-shape drift:

```bash
mvn -pl Mage,Mage.Common,Mage.Server -am install -DskipTests
make check-serial-uid
```

`check-serial-uid` runs two checks. `check-serial-uid-drift.sh` inventories every serializable class in the save-surface modules and verifies that each has an explicit pin or an allow-list entry; with `--changed <base>` it also verifies that changed classes keep their pin. `check-save-uid-manifest.sh` compares the whole save surface against a persisted class-to-UID manifest and fails on a changed UID, a missing class, a new class, or a stale row.

The manifest gate has a self-test that perturbs an in-scope class and confirms the gate fails:

```bash
make check-serial-uid-selftest XMAGE_GAME_FIXTURES=<path-to-saves>
```

The repository ships no `.game` fixtures, so the self-test stops unless `XMAGE_GAME_FIXTURES` points at a directory of saves. The operational check is:

```bash
bash scripts/check-save-uid-manifest.sh --affected-saves <path-to-saves>
```

That command reads each save's stream UIDs and compares them with the UIDs this build computes. Use it before deploying a build over an existing replay corpus.

The offline cross-game replay harness checks how `replayLLMData` is scoped across games in a match:

```bash
bash scripts/verify-replay-llm-crossgame.sh --corpus <path-to-saves>
```

It compiles against the built tree and needs no server or network.

## End-to-end harnesses

These scripts exercise the real proxy and server path. Each reserves its own ports; do not run two at once. Most need a built release tree and a running local stack.

| Harness | What it proves |
|---|---|
| `scripts/run-bot-game.sh` | A headless LLM-vs-LLM game completes; exit 0 complete, 1 startup failure, 2 timeout |
| `scripts/pipeline.sh` | Server, bot game, and post-game analysis run as one loop |
| `Mage.WebProxy/scripts/verify-capability-gating.py` | The `connected` capabilities match the server kind, and LLM messages are rejected on a stock server |
| `Mage.WebProxy/scripts/verify-capability-gating-legc.py` | A stock proxy against an LLMage server connects, probes `llm=true`, and reads `MatchView` without a serialization error |
| `Mage.WebProxy/scripts/verify-hidden-target-e2e.py` | The connect form hides the server target when the proxy is pinned |
| `scripts/verify-llm-sideboard-live.sh` | A Bo3 with an LLM seat produces sideboard and post-game files against a strict mock provider |
| `scripts/verify-postgame-reflection-async.sh` | The post-game reflection does not block sideboarding or game 2 |
| `scripts/verify-early-submit-repro.sh` | Measures the game-over to sideboarding race and can assert the fixed behavior |
| `scripts/verify-reason-field-live.sh` | The optional reason line appears in transcripts only when enabled |
| `scripts/verify-replay-retention.sh` | The retention script keeps replay pairs consistent |

The live LLM harnesses use `scripts/mock-llm-sideboard.py`, a strict OpenAI-compatible mock with deterministic answers per prompt class. Using the mock keeps the tests fast and removes model variance. `run-bot-game.sh` defaults the host to `127.0.0.1` so a docker bridge address cannot capture `localhost`.

## Load test

The load test starts multiple AI games through the test client:

```bash
make test-ai-games-run MAX_GAMES_AMOUNT=10
```

It compiles the test tree, builds a classpath, and runs `org.mage.test.load.LoadTest`. Use it to check for stalls, leaks, and game-thread contention after a change to shared state or logging. It is not a substitute for the deterministic assertion suites.

## What the tests do not cover

- Model quality or strategic strength. Those require game corpora and evaluation, not unit tests.
- Browser rendering and interaction. The proxy tests cover the wire and serializers; visual regressions need a browser pass.
- Production scale, real network latency, and provider rate limits beyond the mocked retry paths.
- Card rules for every card. `VerifyCardDataTest` checks data, not every interaction.

## Writing a test

1. Pick the lowest layer that can fail for the reason you care about.
2. Make it deterministic. Inject a scripted client, a fixed deck, and a fixed seed where the framework allows it.
3. Assert on the engine state or the exact request, not on log text that will change.
4. Give the test a name that states the behavior, not the ticket.
5. Run it once with the fix removed to confirm it fails for the right reason, then restore the fix.

## Related

- [Building LLMage](building.md)
- [Architecture](architecture.md)
- [Extending LLMage](extending.md)
- [Wire compatibility](wire-compatibility.md)
- [Commands](../reference/commands.md)
