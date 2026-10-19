# Installation

This page turns a checkout into a runnable tree: prerequisites, Maven build, deploy, the two
clients, and the checks that prove each part works. The repository ships source only, so the first
run is always a build.

> **Who this is for:** a new user who has never run XMage and wants a complete, verifiable install.

## Prerequisites

| Component | Minimum | Purpose |
|---|---|---|
| JDK | 21 | Compiles and runs the server, desktop client, and proxy |
| Maven | 3.9+ | Builds every module |
| Node.js and npm | A current LTS release | Builds the web client bundle only |
| Git | Any | Clones the repository |
| An OpenAI-compatible endpoint | — | Required for LLM seats, not for the build |

Ollama is the usual local endpoint. Any hosted provider that speaks the chat-completions API works
as well, and you can install without Ollama entirely if every seat will use a hosted model.

Install the JDK and Maven with your platform's package manager — on Debian or Ubuntu:
`sudo apt install openjdk-21-jdk maven`. For local models, install Ollama from
<https://ollama.com/download>. Verify the toolchain before continuing:

```bash
java -version
mvn -version
```

The build is plain Maven and runs on any operating system with a JDK. The launcher and deploy
scripts in `scripts/` are bash, so Linux and macOS run them directly; on Windows, run them from a
POSIX shell, or use the `.bat` launchers inside the built archives.

### Memory and disk

| Resource | Requirement |
|---|---|
| Build memory | A 9 GB Maven heap; 10 GB of free RAM is a practical floor |
| Server memory | 2 GB heap by default; the launcher sets it and `XMAGE_SERVER_HEAP` overrides it |
| Desktop client memory | 4 GB heap, set by the launch script |
| Disk | Several GB for the build outputs plus the card database, saved games, and transcripts |

## Get the source

```bash
git clone <repository-url> <path-to-xmage>
cd <path-to-xmage>
```

Every command on this page runs from the repository root. The scripts resolve the root from their
own location, so they also work when called by absolute path.

## Build flavors

The web proxy and the browser bundle each build in one of three flavors. The Java modules outside
`Mage.WebProxy` are identical in all three.

| Profile | What it changes | Use it for |
|---|---|---|
| none (minimal) | The proxy jar has no `/llm-relay` route; the browser bundle is whatever npm last produced | Unit-test builds, and connecting to a normal server without the browser-key bridge |
| `-Pllmage` | The proxy jar registers `/llm-relay` and its manifest says `LLMage-Build: true` | Local play and any LLMage deployment. The deploy script requires this flavor |
| `-Pstock` | The relay classes compile out and the manifest says `LLMage-Build: false`; pair it with a stock bundle | Connecting to official XMage servers |

Profile activation is explicit. A plain `mvn install` builds the minimal flavor on purpose.

The bundle flavor is chosen separately, at build time, by the `VITE_LLMAGE` variable passed to npm:

```bash
npm run build                          # LLMage bundle
VITE_LLMAGE=false npm run build        # stock bundle
```

`Mage.WebProxy/scripts/build-stock.sh` builds the matching jar and bundle together.

> **LLMage addition.** Build the proxy with `-Pllmage` whenever you intend to play against LLM
> seats in a browser. The minimal and stock proxies run, but the LLM tab's relay is absent and the
> deploy script stops with an error.

### Switching flavors

An incremental rebuild after a flavor switch can leave a stale class in `target/classes`: the jar
manifest says `LLMage-Build: true` while the relay route is still the stub. Run `mvn clean` before
the build whenever you change flavor.

## Full build

```bash
export MAVEN_OPTS=-Xmx9g
mvn clean install -DskipTests
```

This compiles the reactor, including `mage-sets`, and installs the artifacts into the local Maven
repository. The raised heap is not optional: at Maven's default heap the build dies in `mage-sets`
with `OutOfMemoryError`.

Expect several minutes on a desktop machine. Most of the time goes to `mage-sets`, which is also
the module you must never omit at deploy time.

### Build the proxy in the LLMage flavor

```bash
mvn -pl Mage.WebProxy install -DskipTests -Pllmage
```

The reactor's plain build produced the minimal proxy. This command replaces it with the flavor the
deploy script and the LLM web client expect. After the first full build, this is the only Maven
step needed for proxy changes.

### Build the browser bundle

```bash
(cd Mage.WebProxy/web/frontend/react && npm ci && npm run build)
```

`npm ci` installs the locked dependency tree; `npm run build` type-checks and bundles the React
client. The output lands in `Mage.WebProxy/web/frontend/` as `index.html` and `assets/`. Both are
build outputs: do not edit them by hand.

### Build the server assembly

```bash
(cd Mage.Server && mvn package assembly:single -DskipTests)
```

This writes `Mage.Server/target/mage-server.zip`. The deploy script uses the zip's file list as the
authoritative set of runtime jars; it does not trust the zip's bytes.

## Deploy into the release tree

```bash
scripts/deploy-release.sh
```

The script copies every jar named by the assembly from its module's `target/` directory or the
local Maven repository, verifies that each copy opens as a jar, and checks the deployed proxy's
manifest flavor. It fails rather than deploy a partial or mixed tree.

| Path | Contents |
|---|---|
| `Mage.Server/release/lib/` | Core runtime jars and the player, game, tournament, and deck plugins |
| `Mage.Server/release/plugins/` | The LLM player plugin and the web proxy jar |
| `plugins/` (repository root) | Symlinks recreated by `scripts/bootstrap-plugins.sh`; the server resolves player types here |
| `Mage.Server/config/config.xml` | Server configuration read at startup |

Two layout rules matter:

- **The proxy jar must never sit in `release/lib/`.** Both the server and the proxy ship a
  `log4j.properties`, and the JVM resolves it by classpath order. A proxy jar in `lib/` silently
  takes over logging.
- **Do not hand-copy jars.** An incomplete list that omits `mage-sets` makes the server load zero
  cards and restart in a loop. The deploy script exists to prevent that.

## Build the desktop client

```bash
mvn -pl Mage.Client package assembly:single -DskipTests
cd Mage.Client/target && unzip mage-client.zip -d mage-client
./mage-client/startClient.sh
```

The archive carries its own `lib/` directory and launcher scripts, so it runs from wherever you
unpack it. The repository's `scripts/start-client.sh` expects the same jars in
`Mage.Client/release/lib/`, which a source checkout does not contain; use the archive unless you
populate that directory yourself.

## Start the web client

```bash
scripts/start-proxy.sh
```

The proxy serves the bundle at <http://localhost:8090/> and binds `127.0.0.1` only. Two flags
change that:

| Flag | Effect |
|---|---|
| `--port <port>` | Listen on another port |
| `--bind <address>` | Listen on another interface, for example a LAN address |

Binding beyond localhost also requires that exact browser origin in the proxy's allow-list, or the
WebSocket upgrade is refused and the page loads but never connects. See
[Deployment](../operator-guide/deployment.md) for the origin and host allow-list variables.

## Verify the install

Work through these four checks in order; each one isolates a different layer.

1. **Deploy output.** The script ends with `release deploy complete`, prints the jar counts, and
   reports `mage-web-proxy flavor: LLMage`.
2. **Server start.** `scripts/start-server.sh` prints the plugin symlink count and starts without a
   `FATAL` line. The first start builds the card database under `db/` and takes longer.
3. **Proxy configuration.** With the proxy running, `curl -s http://127.0.0.1:8090/config` returns
   a JSON object whose `llmage` field is `true`. A `false` value means the proxy jar was built with
   the stock profile. A minimal jar also reports `true`, because the flag defaults to enabled when
   the manifest carries no flavor marker; the deploy script's marker check is what keeps a minimal
   proxy out of a release tree.
4. **Server capability.** Connect a client. The seat type list offers `LLM Player` only when the
   connected server advertises the LLM player type; if the option is missing, the client is talking
   to a server built or deployed without the plugin.

The first time an LLM seat joins a table, the plugin sends a one-token probe carrying the
configured thinking payload. A wrong endpoint, key, or reasoning level fails there, at table join,
rather than in the middle of a game.

## Common build failures

| Symptom | Likely cause | Fix |
|---|---|---|
| `OutOfMemoryError` in `mage-sets` | Default Maven heap | `export MAVEN_OPTS=-Xmx9g` and rebuild |
| `FATAL: Mage.Server/target/mage-server.zip missing` | Assembly never built | Run the `assembly:single` step |
| `FATAL: deployed mage-web-proxy is the STOCK flavor` | Wrong profile | Rebuild with `-Pllmage` and deploy again |
| `FATAL: no authoritative source for <jar>` | A module was not installed | Run `mvn clean install -DskipTests` again |
| `mage-web-proxy JAR not found` | Proxy never built | `mvn -pl Mage.WebProxy install -DskipTests -Pllmage` |
| `Frontend directory not found` | Bundle never built | `npm ci && npm run build` in the React directory |
| Server loads zero cards and restarts | `mage-sets` jar missing from `release/lib/` | Redeploy with the script |
| `FATAL Unknown player type: Computer - mad` | Plugin symlinks missing or dangling | Run `scripts/bootstrap-plugins.sh` |
| `Address already in use` | Another server instance holds the port | Stop it, or change `port` in `config.xml` |
| `NoSuchMethodError` after a rebuild | Stale `target/` classes | `mvn clean` and rebuild |
| Browser shows an older interface after an upgrade | Stale bundle left in `web/frontend/` | Rebuild the bundle and restart the proxy |

## Upgrading an existing install

Rebuild and redeploy; the deploy script deletes stale `1.4.5x` and `1.4.6x` jars from the release
tree before copying the new ones. Restart the server, the proxy, and any desktop client afterwards,
because a running JVM keeps the classes it loaded. Saved games and transcripts survive an upgrade in
their existing directories.

## Related

- [Quick start](quick-start.md)
- [Deployment](../operator-guide/deployment.md)
- [Building in depth](../developer-guide/building.md)
- [Troubleshooting](../reference/troubleshooting.md)
