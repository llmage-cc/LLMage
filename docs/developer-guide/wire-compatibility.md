# Wire compatibility

LLMage talks to XMage servers with Java serialization and talks to browsers with JSON. The JSON protocol is private to the proxy and its own frontend, so both ends ship together. The Java wire is shared with stock XMage servers, and that is where compatibility has to be deliberate. This page explains the serialVersionUID pins, the exact version check, and the capability contract that lets one web client serve both LLMage and stock servers.

> **Who this is for:** developers changing shared view or match classes, packaging a stock build, or diagnosing a connection that fails during serialization.
>
> **LLMage addition.** The explicit SUID pins, the exact-version policy, and the capability probe are fork mechanisms; the Java serialization rules they rely on are standard.

## Two wire surfaces

| Surface | Transport | Payload | Compatibility owner |
|---|---|---|---|
| Client to server | JBoss Remoting over bisocket | Java-serialized objects | `Mage.Common` and `Mage` class shapes |
| Browser to proxy | WebSocket | JSON messages | `Mage.WebProxy` and its bundled frontend |

The proxy is a client from the server's point of view: it uses the same `SessionImpl`, the same RPC interfaces, and the same serialized views as the desktop client. A change that breaks the proxy's serialization also breaks the desktop client against the same server.

## How Java serialization decides compatibility

For a class without an explicit `serialVersionUID`, the JVM computes one from the class name, modifiers, interfaces, fields, and methods. Adding a field changes the computed value. When the value on the wire differs from the value in the receiving JVM, deserialization throws `InvalidClassException` before any application code runs.

An explicit `serialVersionUID` overrides the computation. With a matching value, Java serialization matches fields by name and type:

- A field present in the stream but absent in the receiving class is ignored.
- A field absent from the stream but present in the receiving class keeps its default value.
- A field present on both sides is read normally.

That behavior is what lets the fork add fields to shared classes and still exchange them with a stock server: the extra field rides along and the stock side ignores it. It only works while the explicit UID matches. A mismatched UID rejects the whole object, even if the added field would have been harmless.

## Pinned classes

`Mage.WebProxy/src/test/java/mage/webproxy/WireCompatSuidTest` asserts the pins:

| Class | Pinned UID | Direction | Why it is pinned |
|---|---:|---|---|
| `MatchView` | `-294697495608883183` | Proxy reads from server | Replay browser lists finished matches; a mismatch throws on `getFinishedMatches` |
| `Seat` | `5595719867240619273` | Proxy sends to server | `Seat` is embedded in `MatchOptions` on table creation |
| `MatchOptions` | `-6545123451908010934` | Proxy sends to server | `create_table` and `create_tournament` arguments |
| `GameView` | `1` | Proxy reads from server | Upstream pins `1` explicitly; the fork's LLM debug fields ride under it |

The values were measured from the upstream client distribution, not guessed. The fork-added fields are `MatchView.playerList`, `Seat.llmPlayerConfig`, `MatchOptions.llmPlayerConfigs` and `MatchOptions.llmChatPublic`, and `GameView.llmDebug` plus `GameView.replayStateIndex`.

Replay files have a separate set of pins:

| Class | Pinned UID | Why |
|---|---:|---|
| `LLMPlayer` | `-21758999425528155` | The player is embedded in saved games; a rebuild alone must not change the replay format |
| `LLMConfig` | `-134329132618624147` | Configuration travels inside the saved game graph |
| `LLMPlayerConfig` | `6112746117441871205` | Per-seat overrides travel client to server and through saves |

These are not wire-compatibility pins in the same sense, but they follow the same rule: change them only as a deliberate format change, never as a side effect of a rebuild.

## Version checks

The product version is `1.4.62-V1`. `MageVersion.compareTo` compares major, minor, and release numerically, then compares the release-info string when strict mode is on. Strict mode is on, so `1.4.62-V1` and `1.4.62-V2` are different versions.

Both ends enforce equality:

- The server's `connectUser` and `connectAdmin` reject a client whose version does not compare equal to the server's.
- The client's `SessionImpl.connectStart` reads the server state after login and rejects a server whose version does not compare equal to the client's.
- The proxy uses the same `MageVersion` source for its login and registration calls, so it is subject to the same check.

The practical consequence is that a proxy, desktop client, and server must come from the same release. A stock server is compatible only when it reports the same version string; the capability probe runs after the version check, so a different release never reaches it.

Version is not a capability signal. An LLMage server and a stock server can report the same version string while exposing different player types. The probe, not the version, decides what the UI may offer.

## Capability contract

Immediately after `connectStart` succeeds, the proxy reads the server's `ServerState` and builds a `ServerCapabilities` object. The probe uses only what the server reports:

| Flag | Source | Meaning |
|---|---|---|
| `llm` | `playerTypes` contains `COMPUTER_LLM` | The server can create LLM seats |
| `p1` | `playerTypes` contains the policy-net seat type | The server can create policy-net seats |
| `replayScrubber` | Same value as `llm` | The server supports `Replays.seekTo` |
| `replaySidecar` | Same value as `llm` | The server writes replay sidecar metadata |

The scrubber and sidecar flags are coupled to `llm` because the upstream replay interface has no `seekTo` and the version string cannot distinguish the two server kinds. When an upstream release carries the method, the gate can be split.

The `connected` WebSocket message carries the probe result:

```json
{
  "type": "connected",
  "username": "Player One",
  "capabilities": {
    "llm": true,
    "p1": false,
    "replayScrubber": true,
    "replaySidecar": true,
    "serverVersion": "1.4.62-V1",
    "playerTypes": ["HUMAN", "COMPUTER_MAD", "COMPUTER_LLM"]
  }
}
```

When `llm` is false, the frontend hides the LLM tab, the LLM seat option, the per-seat LLM configuration, and Share LLM Chat. The proxy also rejects LLM-carrying messages before they reach the server:

| Message or action | Behavior when `llm` is false |
|---|---|
| `fetch_models` | Error: server does not support LLM features |
| `fetch_personalities` | Error: server does not support LLM features |
| `llm_decision` | Error: server does not support LLM features |
| `llm_error` | Error: server does not support LLM features |
| `replay_seek` | Error: server does not support LLM features |
| `create_table` with an LLM seat or `llmConfig` | Rejected |
| `create_tournament` with LLM seats or configs | Rejected |

The gates are fail-fast. They do not silently drop a request or downgrade it to a human seat.

The probe is honest by construction. An LLMage server reports `llm=true` even to a stock proxy, because the flag comes from the server's own player types. A stock client suppresses the UI at compile time; it does not rely on the server lying about its capabilities.

`GET /config` reports the proxy build, not the server:

```json
{"hideServerTarget":false,"llmage":true}
```

`llmage` is resolved from the `-Dllmage.build` property first, then the JAR manifest's `LLMage-Build` entry, then a default of true. The deploy guard requires the positive manifest entry, so a minimal test JAR cannot be mistaken for a release artifact.

## Compatibility matrix

All rows assume the same `MageVersion` string on both ends.

| Proxy artifact | Server | Lobby and games | LLM seats and UI | Replay scrubber |
|---|---|---|---|---|
| LLMage build | LLMage server | Yes | Yes | Yes |
| LLMage build | Stock server | Yes | Hidden; messages rejected | No |
| Stock build | LLMage server | Yes | Absent from the bundle; server reports `llm=true` | Yes, when the server reports it |
| Stock build | Stock server | Yes | No | No |

A minimal proxy build has no relay and no manifest marker; it is the unit-test build. Pair it with the stock frontend for a stock-like local artifact, or use `-Pstock` and `build-stock.sh` for a release. Do not ship the minimal JAR.

## Upgrade path

1. Build every module from the same tree and version, with `mvn clean`.
2. Build the proxy in the intended flavor and rebuild the frontend.
3. Deploy the JARs and run `scripts/bootstrap-plugins.sh`.
4. Restart the server and the proxy. A running JVM keeps old classes.
5. Check `GET /config` for the expected `llmage` value.
6. Connect a browser and confirm the `capabilities` object matches the server kind.
7. Run the capability-gating scripts for the legs you ship.
8. If the release changes a save-surface class, run the serialization gates before overwriting an existing replay corpus.

A rolling upgrade is not supported: the version check is exact, so old and new processes cannot share a server.

## Changing a pinned class

When a field must be added to `Seat`, `MatchOptions`, `MatchView`, or `GameView`:

1. Measure the upstream class's computed UID with `ObjectStreamClass` against the upstream JAR for the release you target. Do not copy a value from a blog or a previous fork.
2. Decide whether the fork keeps the old pin or adopts the upstream value. Keeping the old pin preserves compatibility with the release the pin was measured from; adopting a new value requires every peer to upgrade.
3. Update the class and the pin in the same commit. Add the field in a way that a stock peer can ignore: a serializable type, no constructor requirement, and a safe default.
4. Run `mvn test -pl Mage.WebProxy -Dtest=WireCompatSuidTest`.
5. Run the capability-gating scripts against both server kinds.
6. Repeat for `LLMPlayer` and the replay config classes if the change reaches saved games, and run the UID gates.

Never let the computed UID drift by rebuilding without a pin. A silent rebuild that changes the computed value is the exact failure the pins exist to prevent.

## Failure symptoms

| Symptom | Likely cause | Check |
|---|---|---|
| `InvalidClassException` on table creation | `MatchOptions` or `Seat` UID mismatch | `WireCompatSuidTest`; compare both builds |
| `InvalidClassException` on finished matches | `MatchView` UID mismatch | Same |
| `MageVersionException` or "server version is not compatible" on connect | Different release strings | Compare `MageVersion` output on both ends |
| LLM tab absent on an LLMage server | Stock bundle, or `llm=false` probe | `GET /config`; `capabilities.playerTypes` |
| "does not support LLM features" on an LLMage server | LLM plugin not loaded, so `COMPUTER_LLM` is absent | Server boot log; `plugins/` symlinks |
| `/llm-relay` returns 404 | Minimal or stock proxy, or stale seam class | Manifest; `LLmRelayRoute` in the JAR |
| Replay seek returns an error on a stock server | No `seekTo` upstream | Expected; the capability flag should be false |

## Related

- [Architecture](architecture.md)
- [Building LLMage](building.md)
- [Testing](testing.md)
- [Web client](../player-guide/web-client.md)
- [Deployment](../operator-guide/deployment.md)
