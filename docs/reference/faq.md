# Frequently asked questions

Short answers to the questions that come up before and after a first install. Each answer names the page that covers the subject in full.

> **Who this is for:** newcomers evaluating LLMage, and players who need a quick answer.

## What is LLMage, exactly?

A fork of XMage, the Java rules engine for Magic: The Gathering. It keeps the engine, the card pool, and the desktop client, and adds an AI player that delegates its decisions to a large language model over an OpenAI-compatible API, plus a browser client served by a WebSocket proxy. See [Overview](../getting-started/overview.md).

## Is it affiliated with Wizards of the Coast?

No. Magic: The Gathering is a trademark of Wizards of the Coast LLC. LLMage is not affiliated with, sponsored by, or endorsed by Wizards of the Coast or Scryfall. Card data and images are fetched at runtime from public sources for personal, non-commercial use.

## What licence does it use?

MIT, inherited from XMage, with the third-party exceptions and attributions listed in the repository's notice file. That file covers card icons, card data, and one GPL-licensed upstream component, so read it before redistributing a build.

## Do I need an API key?

Only if your model runs behind a hosted provider. A local Ollama endpoint needs no key. For a hosted endpoint, export the key in the shell that starts the server, or point the hosted launcher at a key file outside the repository. Never put a key in a tracked file or on the JVM command line, where any local user can read it.

## How much does it cost to run?

A local model costs whatever your hardware and electricity cost. A hosted provider bills per token under its own terms. LLMage adds no billing layer and no usage cap; the cost of a game is the cost of the calls the seats make.

## Which models work?

Any model behind an OpenAI-compatible chat-completions endpoint. That includes local Ollama, vLLM, llama.cpp server, and hosted providers such as OpenAI, Groq, or OpenRouter. The shipped launcher defaults to a Gemma model for the first seat and a DeepSeek model for the second; both are environment-overridable.

## Do I need a GPU?

No, if you use a hosted endpoint. For local models, the hardware requirement comes from the model, not from LLMage: a large model needs a capable GPU or a lot of system memory to run at a usable speed.

## Can I play without an LLM at all?

Yes. Human-versus-human games, the built-in MAD and Monte Carlo AI seats, and draft bots all work without any model endpoint. You can also watch two built-in AI seats play each other.

## Can I use the web client with an official XMage server?

Yes, with the stock build. That build compiles the LLM relay out of the proxy and tree-shakes the LLM interface out of the frontend, so it carries no LLM code and connects to official servers. The LLMage build also connects, but the server reports that it has no LLM support, so the LLM tab and LLM seat options are hidden. See [Web client](../player-guide/web-client.md).

## Which Java version do I need?

Java 21. Launch the server, client, and proxy through the provided scripts: they pass the module-access flags the remoting layer needs. Running the jars with a bare `java -jar` can fail with a misleading "Wrong java version" message even on a correct JDK.

## Does it run on Windows or macOS?

The Java code is portable and the client is a normal Swing application, but the documented workflow is Linux with bash: the launchers, deploy script, and monitoring tools are shell scripts. On Windows or macOS you can run the same Maven build and start the jars with equivalent commands; the scripts themselves expect a POSIX shell.

## Is there a prebuilt download?

No. The repository ships source only. You build once with Maven, deploy the jars into the release trees, and build the web frontend separately if you want the browser client.

## How many LLM seats can a game have?

Any seat can be set to `Computer - LLM`. The server has two named configurations, one for the first LLM seat and one for the second, and every LLM seat in a table or tournament can override the model, endpoint, effort, personality, and conversation settings individually. See [LLM opponents](../player-guide/llm-opponents.md).

## Can I watch two LLMs play each other?

Yes. Use `\botgame --llm2` in chat, or create a table and set both seats to `Computer - LLM`. An all-AI match can also run headless through the bot-game script, with the transcript written to the game log directory.

## Can I pick the bot's name and personality?

Yes. Each game assigns names from a 100-name pool; 35 of them map to a written personality and playstyle that can be injected into the system prompt, and the rest get a generic one. You can pin a name per seat, force a personality, or turn the personality section off entirely.

## Does the LLM see my hand or other hidden information?

Each LLM seat sees its own hand by name and the hand *size* of every other player. It sees the battlefield, graveyards, exile, the stack, and life totals, and it knows its own full decklist. It does not receive the contents of an opponent's hand or library.

## Is my game data sent to the model provider?

Yes. Prompts contain the game state, your decklist, recent game events, and excerpts of the game log. If the endpoint is a hosted service, that data leaves your machine under that provider's terms. Use a local endpoint if that is not acceptable.

## Where are the logs?

LLM transcripts, post-game reflections, and sideboard summaries go to `gamelogs/game_<hash>/`. The server log is `mageserver.log` when the server's logging configuration wins the classpath race, and the launcher's console log under the system temp directory always exists. See [LLM chat and logs](../player-guide/llm-chat-and-logs.md).

## Why does a bot sometimes take half a minute?

It is waiting for the model. A reasoning model at a high effort level can spend tens of seconds on one decision, and the server pushes a state update every half second while a seat is thinking, so the board keeps refreshing. Lower the effort level or choose a faster model if the pace matters more than the reasoning.

## Can the bot concede?

Yes, if the seat has voluntary concession enabled. In the shipped launcher both seats start with it off. When it is on, the model sees a concede option after the skip controls and can end the game itself.

## Are replays compatible between builds?

Not always. Engine classes that reach a replay stream do not all carry fixed serial version identifiers, so a save written by one build can fail to load in another. A recovery script rewrites an affected save for the current build, and a pre-commit check exists to stop new drift. See [Troubleshooting](troubleshooting.md).

## Can I import decks from other tools?

Yes, in several formats. The server picks an importer by file extension and understands the native `.dck` format, plain `.txt` lists, `.dec`, `.mwdeck`, `.dek`, `.cod`, `.o8d`, `.json`, `.draft`, and `.mtga` files. The web deck editor accepts plain `N Card Name` lines, `N [SET:NUM] Card Name` lines, and the annotated `N x Card Name (SET) NUM [Type]` form used by popular deck sites. See [Web deck editor](../player-guide/web-deck-editor.md).

## Does the web client support draft and sealed?

Not yet. The tournament setup lists those types, but limited games are not playable from the browser client. Constructed tournaments, tables, spectating, and replays are supported.

## Can I host a server for other people?

Yes. Authentication is off by default, so anyone who can reach the port can join with any name; enable it for a public server. The web proxy binds to loopback by default and needs both a bind address and an origin allow-list entry before browsers on another machine can connect. See [Security](../operator-guide/security.md).

## How do I contribute?

Open an issue for a bug or a proposal, and send a pull request against the default branch. Changes to the engine need tests, and changes to a serializable class need the serialization check to pass. See [Contributing](../developer-guide/contributing.md).

## Related

- [Overview](../getting-started/overview.md)
- [Installation](../getting-started/installation.md)
- [Glossary](glossary.md)
- [Troubleshooting](troubleshooting.md)
