#!/usr/bin/env python3
"""
Analyze XMage LLM postgame reviews for bug claims.

Reads the most recent game's *postgame*.txt files, extracts section 7
("Technical issues with the game engine") from each bot's reflection,
and cross-references claims against known false-positive patterns.

Usage:
  python3 analyze-postgame.py [game_dir]
    game_dir — path to a specific game_<hash> directory (default: most recent)

Exit codes:
  0 — no real bugs found (or only known false positives)
  1 — potential real bugs found (claims that don't match known false positives)
  2 — analysis error (no postgame files, parse failure)
"""

import os
import re
import sys
import json
from pathlib import Path
from datetime import datetime

GAMELOGS_DIR = Path(__file__).resolve().parent.parent / "gamelogs"

# ── Known false-positive patterns ─────────────────────────────────────
# Each entry: (pattern, explanation)
# Patterns are regex applied to the claim text (case-insensitive)
KNOWN_FALSE_POSITIVES = [
    (
        r"brainstorm.*put back.*\d+.*cards.*instead of.*2",
        "Brainstorm put-back count: self-log noise from multiple selection prompts. "
        "Game log always shows exactly 2 put-back operations."
    ),
    (
        r"force of will.*rejected.*valid target",
        "Force of Will rejection: LLM had no other blue card to pitch. "
        "Alt cost shown but genuinely unpayable."
    ),
    (
        r"fatal push.*targeted.*murktide|fatal push.*mv.*\d+.*bug",
        "Fatal Push targeting: MV check is a resolution condition, not targeting restriction. "
        "Engine handled correctly."
    ),
    (
        r"solitude.*evoke.*rejected",
        "Solitude evoke: same root as FoW — alt cost not wired into execution path."
    ),
    (
        r"game ended.*draw.*but.*opponent.*-\d+.*life|result.*draw.*life.*-\d+",
        "Draw result with negative life: getWinnerDescription() fix deployed. "
        "If still appearing, check for remaining uncovered call sites."
    ),
    (
        r"skip auto-cancelled.*missed priority|skip.*auto.*cancel.*lost priority",
        "Skip auto-cancelled: means skip WAS active and opponent acted, "
        "which GAVE the player priority back. Not a bug."
    ),
    (
        r"fetch.*land.*activated.*nothing.*fetched|fetch.*no.*land.*found",
        "Fetch land with no result: LLM answered with prose instead of a number. "
        "Parser stripped non-digits, got empty string, never called addTarget."
    ),
    (
        r"sheoldred.*edict.*mode.*\d.*executed.*mode.*\d|sheoldred.*wrong.*mode",
        "Sheoldred's Edict mode confusion: LLM hallucinated card text. "
        "Verify actual modes on Scryfall before investigating."
    ),
    (
        r"dark depths.*thespian.*stage.*marit lage.*not.*creat|dark depths.*trigger.*no.*token",
        "Dark Depths/Thespian's Stage: KNOWN XMage core bug (SacrificeSourceEffect source lookup fails). "
        "Not fixed upstream as of 1.4.59V1."
    ),
    (
        r"play.*land.*offered.*repeatedly|play.*land.*\w+.*repeated.*\d+.*times",
        "Land-drop loop: cards with cycling have playable activated abilities. "
        "Fixed — canPlayLand() guard added."
    ),
    (
        r"counterspell.*nothing.*to.*target|counterspell.*empty.*stack",
        "Counterspell with empty stack: getPlayableAmount returns >0 for payable cards "
        "even without valid targets. XMage core limitation."
    ),
    (
        r"gameplan.*text.*describing.*opponent.*deck|gameplan.*wrong.*deck|gameplan.*swapped",
        "Gameplan text confusion: LLM misreads which deck the gameplan describes. "
        "Not an engine bug — LLM comprehension error."
    ),
    (
        r"conceded.*but.*was.*winning|conceded.*at.*20.*life|concede.*premature",
        "Premature concede: LLM chose to concede via the CONCEDE action. "
        "This is a legitimate LLM decision, not a bug."
    ),
    (
        r"state changed.*crash|state changed.*engine.*fail|state changed.*bug.*ended|state changed.*no.*natural",
        "State changed ending: opponent conceded (server log confirms 'LLM chose to CONCEDE'). "
        "The 'state changed' message is normal skip auto-cancellation when the game ends, not a crash."
    ),
    (
        r"chose.*\w+.*turn 1 null|turn 1 null.*chose|mulligan.*logged.*turn 1|scry.*logged.*turn 1",
        "Turn 1 null mulligan logging: the London mulligan scry step is logged under turn 1 null. "
        "This is a logging quirk, not a bug — the engine handled the mulligan correctly."
    ),
    (
        r"final.*game.*state.*inaccurate.*result|board.*showed.*no.*threat.*engine.*awarding.*win",
        "Inaccurate result perception: the opponent conceded (CONCEDE action). "
        "The LLM won because the opponent chose to concede, not because of an engine error. "
        "The game state was accurate — the LLM just didn't understand the concede."
    ),
    (
        r"decision.*log.*sparse|decision.*log.*incomplete|can't.*reconstruct.*hand",
        "Sparse decision log: the self-log only records LLM decisions, not draws or engine events. "
        "The COMPLETE GAME LOG section has full detail. Not a bug."
    ),
    (
        r"skip to end step before turn.*confus|skip.*end step.*weird",
        "Skip to end step: normal priority-pass action. LLM finds the phrasing confusing "
        "but the engine behavior is correct."
    ),
    (
        r"tap.*mountain.*for.*\{r\}.*no.*spell|tap.*mana.*nothing.*cast",
        "Tap mana with no follow-up: LLM floated mana then passed. "
        "Mana empties at phase change — normal MTG behavior, not a bug."
    ),
]


def find_latest_game_dir():
    """Find the most recent game_<hash> directory in gamelogs."""
    gamelogs = Path(GAMELOGS_DIR)
    if not gamelogs.exists():
        return None

    game_dirs = []
    for d in gamelogs.iterdir():
        if d.is_dir() and d.name.startswith("game_"):
            game_dirs.append((d.stat().st_mtime, d))

    if not game_dirs:
        return None

    game_dirs.sort(reverse=True)
    return game_dirs[0][1]


def find_postgame_files(game_dir):
    """Find all *postgame*.txt files in the game directory."""
    files = []
    for f in Path(game_dir).iterdir():
        if "_postgame-" in f.name and f.name.endswith(".txt"):
            files.append(f)
    return sorted(files)


def extract_section_7(text):
    """
    Extract the 'Technical issues' section (question 7) from postgame text.
    Returns the section text or None if not found.
    """
    # Find the start of question 7
    start_patterns = [
        r'(?:^|\n)\s*(?:###?\s*)?7\.\s*(?:Did you experience any technical issues)',
        r'(?:^|\n)\s*(?:###?\s*)?7\.\s*(?:Technical issues)',
        r'(?:^|\n)\s*\*\*7\.\*\*',
    ]

    start_idx = None
    for pat in start_patterns:
        m = re.search(pat, text, re.IGNORECASE)
        if m:
            start_idx = m.start()
            break

    if start_idx is None:
        return None

    # Find the end — start of question 8 or end of text
    end_patterns = [
        r'(?:^|\n)\s*(?:###?\s*)?8\.\s',
        r'(?:^|\n)\s*\*\*8\.\*\*',
    ]

    end_idx = len(text)
    for pat in end_patterns:
        m = re.search(pat, text[start_idx + 10:], re.IGNORECASE)
        if m:
            end_idx = start_idx + 10 + m.start()
            break

    return text[start_idx:end_idx].strip()


def extract_bug_claims(section_text):
    """
    Extract individual bug claims from section 7 text.
    Returns list of (claim_text, is_known_false_positive, explanation).
    Filters out section headers and "no issues" responses.
    """
    if not section_text:
        return []

    claims = []

    # Split on sub-question markers: a) b) c) d) or **a)** etc.
    # Also split on bullet points
    parts = re.split(
        r'\n\s*(?:[-*]\s*)?(?:\*\*)?[abcd](?:\*\*)?\)',
        section_text
    )

    for part in parts:
        part = part.strip()
        if len(part) < 30:  # too short to be meaningful
            continue

        # Skip section headers (the "7. Did you experience..." line itself)
        if re.match(r'^(?:###?\s*)?7\.\s*(?:Did you|Technical)', part, re.IGNORECASE):
            continue

        # Skip "no issues" / "none" responses
        if re.match(r'^(?:\*\*)?\s*(?:no|none|n/?a|not really|nothing)\b', part, re.IGNORECASE):
            continue
        if re.match(r'^(?:\*\*)?\s*(?:No invalid|None that I|I did not|I didn\'?t notice|No.*presented)', part, re.IGNORECASE):
            continue
        if re.match(r'^(?:Invalid options shown|No invalid options)', part, re.IGNORECASE):
            continue

        # Check against known false positives
        matched = False
        explanation = ""
        for pattern, expl in KNOWN_FALSE_POSITIVES:
            if re.search(pattern, part, re.IGNORECASE):
                matched = True
                explanation = expl
                break

        claims.append({
            "text": part[:300],  # truncate for readability
            "known_false_positive": matched,
            "explanation": explanation,
        })

    return claims


def analyze_game(game_dir):
    """Analyze a single game's postgame files."""
    postgame_files = find_postgame_files(game_dir)
    if not postgame_files:
        print(f"No postgame files found in {game_dir}")
        return 2

    print(f"=== Postgame Analysis: {Path(game_dir).name} ===\n")
    print(f"Files: {len(postgame_files)} postgame review(s)\n")

    all_claims = []
    real_bugs = []

    for pf in postgame_files:
        player_name = pf.name.split("_postgame-")[0]
        print(f"--- {player_name} ---")

        try:
            text = pf.read_text()
        except Exception as e:
            print(f"  ERROR reading file: {e}")
            continue

        section7 = extract_section_7(text)
        if not section7:
            print("  No section 7 (technical issues) found — bot reported no issues?\n")
            continue

        claims = extract_bug_claims(section7)
        if not claims:
            print("  Section 7 found but no specific bug claims extracted\n")
            continue

        for c in claims:
            all_claims.append(c)
            if c["known_false_positive"]:
                print(f"  [KNOWN] {c['text'][:120]}...")
                print(f"          → {c['explanation']}\n")
            else:
                real_bugs.append(c)
                print(f"  [⚠ REAL?] {c['text'][:120]}...")
                print(f"          → No known false-positive match — INVESTIGATE\n")

    # Summary
    print("=" * 60)
    print(f"SUMMARY: {len(all_claims)} total claims, "
          f"{len(all_claims) - len(real_bugs)} known false positives, "
          f"{len(real_bugs)} potential real bugs")

    if real_bugs:
        print("\n⚠ POTENTIAL REAL BUGS TO INVESTIGATE:")
        for i, c in enumerate(real_bugs, 1):
            print(f"\n  {i}. {c['text']}")
        return 1
    else:
        print("\n✓ No potential real bugs found — all claims match known false positives")
        return 0


def main():
    if len(sys.argv) > 1:
        game_dir = sys.argv[1]
    else:
        game_dir = find_latest_game_dir()
        if not game_dir:
            print("No game directories found in", GAMELOGS_DIR)
            sys.exit(2)

    print(f"Analyzing: {game_dir}")
    print(f"Time: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")

    exit_code = analyze_game(game_dir)
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
