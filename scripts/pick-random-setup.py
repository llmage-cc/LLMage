#!/usr/bin/env python3
"""
Pick a random game setup for autonomous LLM-vs-LLM testing.

Selects:
  - A random format (Legacy, Modern, or Vintage)
  - 2 random decks from that format (different decks)
  - 2 random different models from the configured model pool

Outputs a single line: MODEL1 MODEL2 DECK1 DECK2 FORMAT
(Paths are space-free, so the line is simple to parse with shell.)

Usage:
  python3 pick-random-setup.py
  python3 pick-random-setup.py --json     # JSON output for scripts

Exits 0 on success, 1 if no decks found.
"""
import json
import os
import random
import sys
from pathlib import Path

DECKS_DIR = Path.home() / "Documents" / "xmageDecks"

# Model pool for autonomous testing. deepseek-v4-flash:0731-cloud is preferred
# but not yet available in Ollama, so we fall back to :cloud.
DEEPSEEK_PREFERRED = "deepseek-v4-flash:0731-cloud"
DEEPSEEK_FALLBACK = "deepseek-v4-flash:cloud"

MODEL_POOL = [
    DEEPSEEK_PREFERRED,           # will be validated against Ollama at runtime
    "gemma4:31b-cloud",
    "minimax-m3:cloud",
    "nemotron-3-super:cloud",
    "nemotron-3-ultra:cloud",
    "qwen3.5:397b-cloud",
]

OLLAMA_MODELS_URL = "http://localhost:11434/v1/models"


def get_available_models():
    """Fetch the list of model IDs available in Ollama."""
    import urllib.request
    try:
        with urllib.request.urlopen(OLLAMA_MODELS_URL, timeout=5) as resp:
            data = json.load(resp)
            return {m["id"] for m in data.get("data", [])}
    except Exception:
        return set()


def pick_models(available):
    """Pick 2 different models, validating against Ollama availability."""
    pool = list(MODEL_POOL)
    # Resolve deepseek variant
    if DEEPSEEK_PREFERRED in pool and DEEPSEEK_PREFERRED not in available:
        pool = [DEEPSEEK_FALLBACK if m == DEEPSEEK_PREFERRED else m for m in pool]

    # Filter to only what's actually available
    valid = [m for m in pool if m in available]
    if len(valid) < 2:
        # If fewer than 2 available, use the full pool anyway (server will error clearly)
        valid = pool

    m1, m2 = random.sample(valid, 2)
    return m1, m2


def pick_decks():
    """Pick a random format and 2 different decks from that format."""
    legacy = sorted(DECKS_DIR.glob("Legacy*.dck"))
    modern = sorted(DECKS_DIR.glob("Modern*.dck"))
    vintage = sorted(DECKS_DIR.glob("Vintage*.dck"))

    formats = []
    if len(legacy) >= 2:
        formats.append(("Legacy", legacy))
    if len(modern) >= 2:
        formats.append(("Modern", modern))
    if len(vintage) >= 2:
        formats.append(("Vintage", vintage))

    if not formats:
        print("ERROR: need at least 2 decks per format in " + str(DECKS_DIR),
              file=sys.stderr)
        sys.exit(1)

    fmt_name, decks = random.choice(formats)
    d1, d2 = random.sample(decks, 2)
    return str(d1), str(d2), fmt_name


def main():
    available = get_available_models()
    if not available:
        print("WARNING: could not reach Ollama at " + OLLAMA_MODELS_URL,
              file=sys.stderr)

    m1, m2 = pick_models(available)
    d1, d2, fmt = pick_decks()

    if "--json" in sys.argv:
        print(json.dumps({
            "model1": m1, "model2": m2,
            "deck1": d1, "deck2": d2,
            "format": fmt,
        }))
    else:
        print(f"{m1} {m2} {d1} {d2} {fmt}")


if __name__ == "__main__":
    main()