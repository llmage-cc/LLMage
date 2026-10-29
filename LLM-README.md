# LLM player documentation

The LLM player guide now lives in the wiki:

- [LLM opponents](docs/player-guide/llm-opponents.md)
- [LLM chat and logs](docs/player-guide/llm-chat-and-logs.md)
- [LLM providers](docs/operator-guide/llm-providers.md)

This file is kept as a pointer for old links.

## P1 policy seats (`COMPUTER_P1`)

`COMPUTER_P1` is a separate, non-LLM seat type (the Argentum P1 policy net,
deployed as `mage-player-p1.jar`). In this build it is **create/join only**: the
policy runtime — a Python venv plus a checkpoint directory — is not provisioned
in any image or deploy, so a `COMPUTER_P1` seat joins the table, is still held to
the deck restriction, and then passes priority instead of acting. It never casts,
attacks or blocks on its own, and it needs no API key or LLM configuration. The
seat remains restricted to the 55 training decklists.
