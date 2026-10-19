# Building LLMage

LLMage builds from source with Maven and npm. There is no prebuilt release archive: a fresh checkout must be compiled once before a server or client can start. This page gives the module map, the build profiles that select a stock or LLMage web proxy, the commands that produce a runnable release tree, and the failure modes that waste the most time.

> **Who this is for:** developers setting up a checkout, producing a release tree, or diagnosing a build that succeeds but will not run.
>
> **LLMage addition.** The `llmage` and `stock` proxy profiles, the stock frontend switch, and the deploy/bootstrap scripts are fork additions.

## Prerequisites

| Tool | Minimum | Used for |
|---|---|---|
| JDK | 21 | Compiling and running the server and clients |
| Maven | 3.8 | The Java reactor and the assembly |
| Node.js | 20.19+ (20.x) or 22.12+ | Building the React frontend |
| npm | bundled with Node | Frontend dependencies |

The engine sources target Java 8 bytecode, but the build and runtime use JDK 21. The server and launcher scripts pass `--add-opens` flags that only exist on current JDKs, and the Jetty 12 proxy requires a modern runtime. Build and run with the same JDK.

The full reactor is large. Maven's default heap is not enough for `Mage.Sets`; set a larger heap in the shell that runs Maven:

```bash
export MAVEN_OPTS=-Xmx9g
```

## Modules

The root reactor contains 43 Maven projects. The top-level modules are:

| Module | Output | Contents |
|---|---|---|
| `Mage` | `mage-1.4.62.jar` | Rules engine, game state, player API |
| `Mage.Common` | `mage-common-1.4.62.jar` | RPC interfaces, `SessionImpl`, shared views |
| `Mage.Server` | `mage-server.jar` | Server, managers, RPC implementation |
| `Mage.Sets` | `mage-sets-1.4.62.jar` | Card implementations |
| `Mage.Client` | `mage-client.jar` | Swing desktop client |
| `Mage.WebProxy` | `mage-web-proxy-1.4.62.jar` | Jetty proxy and frontend host |
| `Mage.Server.Plugins` | per-plugin JARs | Player, game, tournament, deck plugins |
| `Mage.Server.Console` | `mage-server-console.jar` | Console UI and `BotGameRunner` |
| `Mage.Tests` | test classes | JUnit suites |
| `Mage.Verify` | test classes | Card-data verification |
| `Mage.Reports` | reports | Coverage aggregation |

`Mage.Server.Plugins/Mage.Player.LLM` is the LLM player. Its artifact is `mage-player-llm.jar`; the server loads it as a plugin, not as a compile-time dependency.

## Build profiles

Only `Mage.WebProxy` has build profiles. They select which implementation of the `LLmageRoutes` seam is compiled and what the JAR manifest says.

| Profile | Selection | Result |
|---|---|---|
| `-Pllmage` | `src/llmage/java` | `/llm-relay` registered; manifest `LLMage-Build: true` |
| `-Pstock` | `src/stock/java` | Relay class absent; manifest `LLMage-Build: false` |
| none | `src/minimal/java` | Relay stub returns null; no manifest marker; development/test only |

The default (no profile) build is the minimal flavor. It is safe to run against a normal server, but it has no browser-key relay. Activate a flavor explicitly. Do not add property-based activation: an absent property would silently select the wrong flavor.

The frontend has a matching switch. `VITE_LLMAGE=false` makes `__LLMAGE__` false at compile time, so the LLM UI branches are removed from the bundle. `Mage.WebProxy/scripts/build-stock.sh` builds the stock pair (bundle plus JAR) and verifies that no LLM strings or relay references remain.

## Build the Java modules

From the repository root:

```bash
export MAVEN_OPTS=-Xmx9g
mvn clean install -DskipTests
```

This compiles every module, runs the protobuf generation, and installs the artifacts into the local Maven repository. A clean full build is the reliable starting point after a branch switch or a dependency change.

For a quicker iteration on one module, include its dependencies:

```bash
mvn -pl Mage.Server -am install -DskipTests
```

## Build the web proxy

Build the LLMage flavor of the proxy and its frontend:

```bash
mvn -pl Mage.WebProxy -am install -DskipTests -Pllmage
cd Mage.WebProxy/web/frontend/react
npm ci
npm run build
```

The npm build writes `index.html` and `assets/` into `Mage.WebProxy/web/frontend/`. Those are build outputs, not tracked files. The proxy serves whichever bundle is present, so an old bundle survives a Java rebuild unless the frontend is rebuilt too.

For frontend development, `npm run dev` starts Vite on port 5173 and proxies `/ws` to the proxy at 8090. Hot module replacement is disabled in this configuration, so edits trigger a full page reload. Use it only during development.

To produce the stock pair in one step:

```bash
Mage.WebProxy/scripts/build-stock.sh
```

That script builds the frontend with `VITE_LLMAGE=false`, builds the JAR with `-Pstock`, then checks the bundle for LLM strings and the JAR for `LLmRelayHandler.class` and the manifest marker.

## Assemble and deploy a release tree

The Maven assembly lists the runtime dependency set. Build it, then let the deploy script copy authoritative bytes into the release tree:

```bash
cd Mage.Server
mvn package assembly:single -DskipTests
cd ..
scripts/deploy-release.sh
scripts/bootstrap-plugins.sh
```

`deploy-release.sh` reads `Mage.Server/target/mage-server.zip` as a file list, then copies each JAR from its module's `target/` or from the local Maven repository. It verifies that every copied JAR opens and compares checksums with the build output. It also enforces two layout rules:

- Player, game, tournament, and deck plugins go to `Mage.Server/release/lib/` so `bootstrap-plugins.sh` can link them into the repository-root `plugins/` directory.
- The LLM player and the web proxy go to `Mage.Server/release/plugins/`. The proxy must never be copied to `release/lib/`, because both the server and proxy JARs carry a root `log4j.properties`; a proxy JAR earlier on the classpath makes the server use the proxy's logging configuration and stop writing its log file.

`bootstrap-plugins.sh` creates or refreshes the `plugins/` symlinks and fails if any link dangles. Run it after every deploy, and before starting the server after a plugin rename.

Build the proxy with `-Pllmage` before deploying. The deploy guard requires `LLMage-Build: true` in the proxy manifest and refuses a minimal or stock JAR.

## Release layout

| Path | Contents |
|---|---|
| `Mage.Server/release/lib/` | Engine, server, sets, and plugin JARs |
| `Mage.Server/release/plugins/` | LLM player and web proxy JARs |
| `Mage.Server/release/config/` | `config.xml`, `log4j.properties`, security policy |
| `Mage.Server/release/startServer.sh` | Upstream-style server launcher |
| `Mage.Client/release/lib/` | Desktop client JARs |
| `Mage.WebProxy/web/frontend/` | Built `index.html` and `assets/` |
| `plugins/` at the repository root | Symlinks materialized by `bootstrap-plugins.sh` |

The project launchers in `scripts/` are the supported entry points: `start-server.sh`, `start-client.sh`, `start-proxy.sh`, and the detached variants. They set the classpath, JVM flags, and LLM properties consistently.

## Run the tests

A full build with tests:

```bash
mvn test
```

A single module:

```bash
mvn test -pl Mage.Tests
mvn test -pl Mage.WebProxy
```

The LLM integration tests need either a configured endpoint or a local Ollama; without one they are skipped. See [Testing](testing.md) for the suite layout, properties, and end-to-end harnesses.

## Build freshness

`target/` directories go stale in ways that produce misleading runtime errors. Two rules prevent most of them:

1. Run `mvn clean` after switching branches, changing a profile, or changing a shared class. An incremental rebuild can leave a class from the previous flavor in `target/classes`.
2. Rebuild the frontend after changing anything under `react/src`.

The flavor trap deserves its own warning. If a `-Pstock` build follows a `-Pllmage` build without `clean`, `target/classes` can contain the stock `LLmageRoutes` stub while the JAR manifest still says `LLMage-Build: true`. The proxy then starts, reports `llmage:true` at `/config`, and never registers `/llm-relay`. Always run `mvn clean` before a deploy build after a flavor switch, and check the class if the relay returns 404:

```bash
unzip -p Mage.WebProxy/target/mage-web-proxy-1.4.62.jar \
  mage/webproxy/LLmageRoutes.class | strings | grep -q LLmRelayRoute
```

A match means the LLMage implementation is present. No match means the stub was packaged.

## IDE setup

Import the root `pom.xml` as a Maven project and select JDK 21 for the project and for Maven. Set the Maven runner's VM options to `-Xmx9g` as well; IDE builds hit the same `Mage.Sets` memory wall.

Two working-directory details matter when launching from an IDE:

- `PluginUtil` resolves `plugins/` relative to the process working directory. Set the run configuration's working directory to the repository root, or plugin loading fails with "jar file not found".
- The server reads `config/config.xml` relative to its working directory by default; the project launcher points `-Dxmage.config.path` at `Mage.Server/config/config.xml`. Set the property when running a release-style configuration from an IDE.

For the desktop client, the main class is `mage.client.MageFrame`; for the server, use `mage.server.Main` or the launcher script. Running the launcher script from a terminal avoids most classpath and module-flag mistakes.

## Common build failures

| Symptom | Cause | Fix |
|---|---|---|
| `OutOfMemoryError` in `Mage.Sets` | Maven default heap | `export MAVEN_OPTS=-Xmx9g` |
| Server loads zero cards and restarts | Missing `mage-sets` JAR | Rebuild and redeploy; never omit `Mage.Sets` |
| `FATAL Unknown player type` at boot | Plugin JAR missing or symlink dangling | Run `scripts/bootstrap-plugins.sh`, check `config.xml` name and JAR |
| `NoSuchMethodError` or odd class-loading errors | Stale `target/` classes | `mvn clean` and rebuild |
| `/llm-relay` returns 404 | Minimal or stock proxy, or stale seam class | Build `-Pllmage` after `mvn clean`; check the manifest |
| Proxy starts but the page is blank | Frontend not built or stale | `npm ci && npm run build` in the React directory |
| `mvn -pl Mage.WebProxy` cannot resolve `mage-common` | Dependencies not installed | Add `-am`, or run the full install first |
| Client refuses to connect with a version message | Client and server are different releases | Build both from the same tree and version |
| Server writes no log file | Proxy JAR in `release/lib/` shadowing `log4j.properties` | Move it to `release/plugins/`, redeploy |
| Frontend build fails on TypeScript strictness | `exactOptionalPropertyTypes` violations | Fix the types; do not disable the flag |

## Related

- [Architecture](architecture.md)
- [Testing](testing.md)
- [Extending LLMage](extending.md)
- [Deployment](../operator-guide/deployment.md)
- [Commands](../reference/commands.md)
