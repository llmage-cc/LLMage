# Quick start

This page takes a fresh clone to a game against an LLM in six steps: build once, point the server
at one model endpoint, start the server, start the web client, create a table with an LLM seat, and
watch it play. It assumes a Linux or macOS shell and a local Ollama install. If you want to know why
a step works or how to choose a different endpoint, read [Installation](installation.md) instead.

> **Who this is for:** a new user who wants a working game with as few decisions as possible.

## 1. Check the prerequisites

```bash
java -version     # must report 21
mvn -version      # must report 3.8 or newer
node -v           # any current LTS release
ollama --version  # optional if you use a hosted API
```

You need one OpenAI-compatible endpoint. This page uses a local Ollama server at
`http://localhost:11434/v1/chat/completions`, which is the endpoint the launch script assumes.

## 2. Build everything

The repository ships source only. There are no jars, no `target/` directories, and no frontend
bundle in a fresh clone.

```bash
cd <path-to-xmage>
export MAVEN_OPTS=-Xmx9g
mvn clean install -DskipTests
mvn -pl Mage.WebProxy install -DskipTests -Pllmage
(cd Mage.WebProxy/web/frontend/react && npm ci && npm run build)
(cd Mage.Server && mvn package assembly:single -DskipTests)
scripts/deploy-release.sh
```

| Step | Why it is needed |
|---|---|
| `mvn clean install -DskipTests` | Builds every module into its `target/` directory. The 9 GB heap is required; the default heap runs out of memory in `mage-sets` |
| `mvn -pl Mage.WebProxy install -Pllmage` | Rebuilds the proxy in the LLMage flavor. A plain build produces the minimal proxy, and the deploy script rejects it |
| `npm ci && npm run build` | Produces the browser bundle in `Mage.WebProxy/web/frontend/` |
| `mvn package assembly:single` | Produces `Mage.Server/target/mage-server.zip`, which the deploy script reads for its jar list |
| `scripts/deploy-release.sh` | Copies every runtime jar into `Mage.Server/release/` and verifies each one |

The build takes several minutes; most of it is spent in `mage-sets`. The deploy prints the jar
counts and ends with `release deploy complete`.

## 3. Choose one model

The server reads its model settings from environment variables. Set these before starting it:

```bash
export LLM1_MODEL="<model-name>"
export LLM1_ENDPOINT="http://localhost:11434/v1/chat/completions"
```

`LLM1_KEY` is empty for a keyless local endpoint. For a hosted provider, export the key as well
and set `LLM1_CONTEXT_WINDOW` if that provider does not publish context sizes. The default model
when `LLM1_MODEL` is unset is `gemma4:31b-cloud`.

Pull the model first if you are using Ollama:

```bash
ollama pull <model-name>
```

## 4. Start the server and the web client

In one terminal:

```bash
scripts/start-server.sh
```

The server listens on port 17171. The script archives old logs, recreates the plugin symlinks,
and builds the card database on the first start, so the first run takes longer than later ones.

In a second terminal:

```bash
scripts/start-proxy.sh
```

Open <http://localhost:8090/>.

![Connect form with Server, Port, Username, Password and Connect](../media/getting-started/web-connect.png)

| Field | Value for a local server |
|---|---|
| Server | `127.0.0.1` — use the literal address, not `localhost` |
| Port | `17171` |
| Username | Letters, digits, and underscores only; 3 to 14 characters. A default server does not require registration |
| Password | Leave empty when the server has authentication turned off |
| Auth token | Leave empty; it is only for hosted proxies |

Select **Connect**.

## 5. Create a table with an LLM seat

In the lobby, select **New Game**. Set the game name, game type, and deck type, then work down the
**Players** column:

1. The first row is your seat, labelled **You**.
2. In the second row, change **Type** from `AI (Mad)` to `LLM Player`.
3. Fill the deck box: paste a decklist, upload a `.dck` or `.txt` file, or select
   **Generate random deck**.
4. Leave the LLM fields alone to inherit the model from step 3, or set **Model** for this seat
   only.
5. Select **Start Game**.

Every seat is filled, so the game starts immediately. The model reads the decklist first and
writes a gameplan; the opening turns arrive more slowly than later ones while that happens.

## 6. Watch the opponent think

During the game, open the **LLM** tab in the right rail. It shows one entry per LLM seat with the
conversation, the pre-game deck assessment, the model's reasoning, and the post-game reflection.
The board keeps updating while the model works, so the game does not look frozen.

## Use the desktop client instead

The desktop client is a second build target:

```bash
mvn -pl Mage.Client package assembly:single -DskipTests
cd Mage.Client/target && unzip mage-client.zip -d mage-client
./mage-client/startClient.sh
```

Connect to `127.0.0.1` on port 17171, then type `\botgame` in the lobby chat. The server creates a
table, seats an LLM player against the built-in AI, and generates both decks. Add `--llm2` for
LLM against LLM, or `--bo3` for a best-of-three match.

## If a step fails

| Symptom | Cause | Fix |
|---|---|---|
| `OutOfMemoryError` during the build | Maven's default heap | `export MAVEN_OPTS=-Xmx9g` and rebuild |
| `mage-web-proxy is the STOCK flavor` | Proxy built without `-Pllmage` | Rebuild with `-Pllmage`, then deploy again |
| `mage-web-proxy JAR not found` | Proxy never built | `mvn -pl Mage.WebProxy install -DskipTests -Pllmage` |
| `Frontend directory not found` | Bundle never built | `npm ci && npm run build` in the React directory |
| "Server is likely offline" in the browser | `localhost` resolved to IPv6 | Type `127.0.0.1` in the Server field |
| No `LLM Player` option in the seat type list | Server or proxy built without LLM support | Rebuild the proxy with `-Pllmage`, redeploy, restart both |
| Server starts but loads zero cards | A runtime jar is missing from `release/lib/` | Run `scripts/deploy-release.sh` again; do not copy jars by hand |

More symptoms and their causes are in [Troubleshooting](../reference/troubleshooting.md).

## Related

- [Installation](installation.md)
- [Your first game against an LLM](first-llm-game.md)
- [LLM providers](../operator-guide/llm-providers.md)
- [Commands](../reference/commands.md)
