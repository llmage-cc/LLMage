# Troubleshooting

Failures in LLMage usually come from one of four places: an incomplete build or deploy, a connection that reaches the wrong address, a provider that rejects the request, or a file that another build wrote. This page lists the failures seen in practice, each with the symptom you will actually observe, the usual cause, and the fix.

> **Who this is for:** anyone whose install, server, connection, or game is failing.

## Build and start

### The Maven build dies in `mage-sets` with `OutOfMemoryError`

**Symptom.** `mvn clean install` fails while compiling the card sets, with a heap error rather than a compile error.

**Likely cause.** The full reactor does not fit Maven's default heap.

**Fix.** Raise the heap for the build shell and rerun:

```bash
export MAVEN_OPTS=-Xmx9g
mvn clean install -DskipTests
```

### The server starts but loads no cards, and games end and restart in a loop

**Symptom.** The server accepts connections, but every game ends immediately and a new one takes its place. The startup log reports no cards.

**Likely cause.** The `mage-sets` jar is missing from `Mage.Server/release/lib/`. This happens after a hand-copied deploy that omitted it, or after a partial assembly copy. Without the set classes the card database is empty, so no deck can be built and no game can progress.

**Fix.** Deploy with the script rather than by hand, then restart:

```bash
mvn clean install -DskipTests
(cd Mage.Server && mvn package assembly:single -DskipTests)
scripts/deploy-release.sh
```

`deploy-release.sh` takes its jar list from the Maven assembly, so it cannot silently omit a runtime dependency. If you must copy jars manually, verify that `mage-sets-<version>.jar` is present before starting.

### The server runs but `mageserver.log` never appears

**Symptom.** `monitor-games.sh`, `monitor-watch.sh`, and `pipeline.sh` stop with "mageserver.log is MISSING while the server is running", or report zero errors while the console shows activity.

**Likely cause.** The web-proxy jar is sitting in `Mage.Server/release/lib/`. Both the server jar and the proxy jar carry a root `log4j.properties`, and the JVM picks the first one it finds by classpath order. `lib/*` precedes `plugins/*`, so the proxy's configuration wins, the server logs only to standard output, and no file is written.

**Fix.** Remove the proxy jar from `lib/` and keep it in `plugins/`:

```bash
rm -f Mage.Server/release/lib/mage-web-proxy-*.jar
scripts/deploy-release.sh
```

Confirm the boot line in the console log reads `- log appender: console` followed by `- log appender: logfile`. A line reading `- log appender: stdout` means the proxy's configuration is still winning.

### A port is already in use

**Symptom.** The server or proxy exits immediately with a bind error, or a second instance starts and then fails to accept connections.

**Likely cause.** An earlier JVM is still running, or another service owns the port. The defaults are `17171` for the server and `8090` for the proxy.

**Fix.** Find and stop the process that owns the port, then start once:

```bash
ss -ltnp | grep -E '17171|8090'
```

If the port belongs to something you need, change the server's `port` in its config file or pass `--port` to the proxy instead.

### `NoSuchMethodError` or `ClassNotFoundException` after a rebuild

**Symptom.** The server or client starts and then fails on a class that clearly exists in the source tree, often only after a code change.

**Likely cause.** Stale `target/` output, or an old versioned jar still on the release classpath. Incremental builds can leave a previous class behind, and two versions of the same jar in `release/lib/` resolve in an arbitrary order.

**Fix.** Clean, rebuild, redeploy, and restart both JVMs:

```bash
mvn clean install -DskipTests
(cd Mage.Server && mvn package assembly:single -DskipTests)
scripts/deploy-release.sh
```

The JVM keeps classes it has already loaded, so a restart is part of the fix, not an optional step.

### An AI seat never joins and the table waits

**Symptom.** A table reports "waiting for another player", or the server logs an unknown player type when the game starts.

**Likely cause.** The `plugins/` symlinks are missing or dangling. The server resolves player and game types from that directory, and a link that points at a deleted jar is skipped silently.

**Fix.** Recreate the links and check the exit status:

```bash
scripts/bootstrap-plugins.sh
```

The script fails loudly and names every dangling link. `start-server.sh` runs it at every boot, so this usually means the release tree was changed while the server was down.

### The proxy exits because the card database is empty

**Symptom.** The web proxy refuses to start, reporting that the cards database is empty or incomplete.

**Likely cause.** The proxy opens `db/cards.h2.mv.db` from its working directory and deliberately never scans card classes to populate it. A new checkout, or a database deleted by a clean, leaves it with nothing to serve.

**Fix.** Start the server once so it builds the database, or copy a populated `cards.h2.mv.db` into `db/` before starting the proxy. The proxy and the server can share the same database file.

## Connecting

### "Wrong client version"

**Symptom.** The desktop client or the proxy refuses the connection and prints both versions.

**Likely cause.** The client and server were built from different trees, or one of them was not restarted after a deploy.

**Fix.** Rebuild, redeploy, and restart both sides from the same checkout. Versions come from `<mage-version>` in the root `pom.xml`; a client from one build will not talk to a server from another.

### "Server is likely offline" from the web client

**Symptom.** The connect form rejects an address that is reachable, and the server log shows no connection attempt.

**Likely cause.** `localhost` resolved to an IPv6 address while the server listens on IPv4, or the proxy cannot reach the target you entered.

**Fix.** Enter `127.0.0.1` instead of `localhost`, and make sure the XMage server's port is reachable from the proxy host. The proxy already prefers IPv4 for its own JVM; the browser field needs the literal address.

### The web UI loads but the WebSocket never connects

**Symptom.** The page renders, the connect button does nothing useful, and the browser console shows a refused upgrade.

**Likely cause.** The proxy is bound to an address whose origin is not on its allow-list. Binding to `0.0.0.0` or a LAN address without adding that origin makes the UI reachable while every WebSocket upgrade is refused.

**Fix.** Bind to the interface you need and add its exact origin:

```bash
XMAGE_PROXY_ALLOWED_ORIGINS=http://<proxy-host>:8090 \
  scripts/start-proxy.sh --bind <proxy-host>
```

### The connect form has no server or port fields

**Symptom.** The web client shows only username, password, and auth token, and cannot be pointed at a different server.

**Likely cause.** This is the hosted-deployment mode, not a fault. It is enabled by hiding the target fields, and the proxy refuses to boot if the target is not pinned at the same time.

**Fix.** The deployment needs both settings together:

```bash
XMAGE_PROXY_PINNED_TARGET=<server-host>:17171 \
XMAGE_PROXY_HIDE_SERVER_TARGET=true \
  scripts/start-proxy.sh
```

If the proxy exits instead of starting, it is telling you the pin is missing. Remove the hide flag to get the fields back for local use.

## LLM seats

### The LLM seat fails when it joins the table

**Symptom.** The game starts without the LLM player, or the table reports an error from the provider as the seat initializes.

**Likely cause.** A rejected key (`401`), a wrong endpoint or model name (`404`), or a provider that does not accept the configured thinking parameters. Rate limits and server errors are retried with backoff; other `4xx` responses fail immediately with the provider's message.

**Fix.** Check the key, the chat-completions URL, and the model identifier in that order. A `404` almost always means the endpoint path is wrong rather than the model. A `400` on the startup probe usually means the thinking dialect or effort level is not accepted by that model.

### The startup thinking probe fails

**Symptom.** The seat crashes at table join, before the first decision, with the provider's error.

**Likely cause.** The configured thinking dialect (`effort` or `budget`) or the reasoning effort level is not supported by the endpoint.

**Fix.** Set the dialect the provider documents, and use an effort level the model lists. The boot line `LLM thinking probe OK` confirms the accepted combination. Skipping the probe hides the problem until mid-game; use it only in automated tests.

### The seat fails to start because no context window is known

**Symptom.** An error naming the model's context window, before any decision is made.

**Likely cause.** The provider does not publish `context_length` in its model list, and no manual value was set. Autodetection works for Ollama and for providers that expose the field.

**Fix.** Set the window explicitly:

```bash
export LLM1_CONTEXT_WINDOW=128000
```

### Answers come back empty and the bot stalls or crashes

**Symptom.** The log reports an empty answer with a large amount of reasoning, or the seat fails after several attempts.

**Likely cause.** The model spent its whole output budget on thinking and never produced a decision. The player retries with a nudge and a lower effort level, and hard-caps reasoning tokens, but a model that loops at every level still fails.

**Fix.** Lower `LLM1_EFFORT`, keep the reasoning cap enabled, and use a model that reliably returns a short answer for structured prompts. If the seat still loops, it fails with a distinct error instead of passing priority silently.

### Every decision takes a long time

**Symptom.** The board appears frozen between actions while a bot thinks.

**Likely cause.** Model latency at a high effort level. The board keeps updating during the wait, so a frozen board is not evidence of a crash.

**Fix.** Lower the effort level for that seat, or use a faster model for routine decisions. The LLM tab in the web client and the LLM Chat window in the desktop client show the streaming response and the context usage, which distinguishes "thinking" from "stuck".

## Games, decks, and files

### A deck imports as 0 cards

**Symptom.** A game refuses to start, or the server reports that the deck imported as only a few cards.

**Likely cause.** A `.dck` file whose card lines lack `[SET:NUMBER]` codes. The `.dck` importer skips any line it cannot match, so a plain-name list becomes an empty deck. A file with an unrecognised extension imports as an empty deck as well.

**Fix.** Use a `.txt` decklist, which accepts plain card names, or add set codes to every line:

```text
4 [SET:NUMBER] Card Name
```

Bot-game deck paths must be absolute. A deck that imports as fewer than fifteen cards is refused rather than starting a degenerate match.

### A replay will not open: "legacy gzip replay container"

**Symptom.** Opening an old save reports that the file is not a zstd replay.

**Likely cause.** The replay container changed to zstd, and the reader accepts only the current format.

**Fix.** Convert the file once:

```bash
scripts/replay-codec-migrate.sh saved --dry-run
scripts/replay-codec-migrate.sh saved
```

The conversion verifies a byte-exact round trip before replacing the original.

### A replay will not open: `InvalidClassException`

**Symptom.** A save made by a different build fails during deserialization, naming a class and two serial version values.

**Likely cause.** The engine's serializable classes do not carry fixed `serialVersionUID` values for every save-surface class, so a rebuild can change one. A save written by build A then cannot be read by build B.

**Fix.** Recover the save with the current build:

```bash
scripts/replay-uid-recover.sh saved/<uuid>.game
```

The recovered file is written beside the original unless you pass `--in-place`. To stop this happening again, run `make check-serial-uid` before committing a change that touches a save-path class.

### The server runs out of memory during a long game

**Symptom.** `OutOfMemoryError` in the server log, most often while loading a large replay save.

**Likely cause.** The default server heap is `2g`, which is sized for ordinary play and one replay session, not for a heavy sweep or several large saves at once.

**Fix.** Raise the heap for the server process:

```bash
XMAGE_SERVER_HEAP=4g scripts/start-server.sh
```

The desktop client starts with its own `-Xmx4g`. If the failure happens during the build instead, raise `MAVEN_OPTS` as described above.

## Related

- [Commands and scripts](commands.md)
- [File layout](file-layout.md)
- [Installation](../getting-started/installation.md)
- [Server configuration](../operator-guide/server-configuration.md)
- [LLM providers](../operator-guide/llm-providers.md)
- [FAQ](faq.md)
