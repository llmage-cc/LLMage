# LLMage documentation

LLMage is a fork of [XMage](https://github.com/magefree/mage) that adds LLM-driven AI
opponents and a browser client. This wiki covers what the fork adds and how to build, run, and
extend it. Base XMage rules, formats, and the stock desktop client are documented in the
[XMage wiki](https://github.com/magefree/mage/wiki).

> **New here?** Read [What LLMage is](getting-started/overview.md), then
> [Quick start](getting-started/quick-start.md). To play immediately, see
> [Your first game against an LLM](getting-started/first-llm-game.md).

## Getting started

| Page | What it covers |
|---|---|
| [What LLMage is](getting-started/overview.md) | The fork, its three additions, and how an LLM opponent works |
| [Quick start](getting-started/quick-start.md) | Build, configure one endpoint, start the server, connect |
| [Installation](getting-started/installation.md) | Prerequisites, Maven profiles, assembly, release layout |
| [Your first game against an LLM](getting-started/first-llm-game.md) | Create a table, add an LLM seat, and start playing |

## Player guide

| Page | What it covers |
|---|---|
| [The desktop client](player-guide/desktop-client.md) | Connecting, the lobby, tables, chat, and spectator flow |
| [Game controls](player-guide/game-controls.md) | Priority, mana, targeting, combat, mulligan, sideboard, prompts |
| [Game modes](player-guide/game-modes.md) | Duel, multiplayer, Commander, constructed, draft, tournaments |
| [The web client](player-guide/web-client.md) | The browser client, connect form, lobby, LLM tab, settings |
| [The web deck editor](player-guide/web-deck-editor.md) | Card search, import/export, sideboard, validation |
| [The web replay viewer](player-guide/web-replay.md) | Saved games, playback, seeking, LLM sidecar |
| [LLM opponents](player-guide/llm-opponents.md) | Models, personalities, per-seat configuration, decision flow |
| [LLM chat and logs](player-guide/llm-chat-and-logs.md) | Conversation views, transcripts, game logs, post-game analysis |

## Operator guide

| Page | What it covers |
|---|---|
| [Server configuration](operator-guide/server-configuration.md) | `config.xml`, ports, authentication, data directories, JVM flags |
| [Deployment](operator-guide/deployment.md) | Build flavours, release layout, launchers, reverse proxy, upgrades |
| [LLM providers](operator-guide/llm-providers.md) | OpenAI-compatible endpoints, keys, models, thinking, retries |
| [Operations](operator-guide/operations.md) | Logs, monitoring, backups, replay retention, health checks |
| [Security](operator-guide/security.md) | Authentication, connection limits, table ownership, key handling |

## Developer guide

| Page | What it covers |
|---|---|
| [Architecture](developer-guide/architecture.md) | Modules, data flow, the LLM pipeline, and the web proxy |
| [Building LLMage](developer-guide/building.md) | Maven modules, profiles, assembly, tests, build freshness |
| [Extending LLMage](developer-guide/extending.md) | Providers, personalities, player types, proxy routes, tests |
| [Testing](developer-guide/testing.md) | JUnit suites, LLM tests, replay tests, verification harnesses |
| [Wire compatibility](developer-guide/wire-compatibility.md) | SUID pins, version checks, stock/LLMage capability contract |
| [What changed from upstream XMage](developer-guide/fork-changes.md) | File-level map of the fork's delta |
| [Contributing](developer-guide/contributing.md) | Workflow, coding style, tests, commit and PR conventions |

## Reference

| Page | What it covers |
|---|---|
| [Commands and scripts](reference/commands.md) | Every operational script, argument, and exit code |
| [File layout](reference/file-layout.md) | Repository modules and runtime directories |
| [Keyboard shortcuts](reference/keyboard-shortcuts.md) | Desktop and web shortcuts in one table |
| [Troubleshooting](reference/troubleshooting.md) | Symptom, cause, and fix for common failures |
| [Frequently asked questions](reference/faq.md) | Cost, keys, models, hosting, compatibility, privacy |
| [Glossary](reference/glossary.md) | Terms used across the docs |

## Conventions

- Commands and configuration examples use placeholders such as `<server-host>`, `<port>`,
  `<path-to-xmage>`, `<model-name>`, and `<api-key>`. Replace them with your own values.
- Fork-specific behaviour is marked **LLMage addition**. Unmarked behaviour is inherited from
  XMage and may be documented upstream.
- Screenshots use generic player, table, and deck names. They are illustrative, not a record of
  a particular deployment.
