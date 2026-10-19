#!/usr/bin/env python3
"""reasonverify post-checks: cross-leg assertions over the driver evidence.

Reads ${REASONVERIFY_EVIDENCE:-reasonverify-evidence}/<leg>/ (result.json, bodies/, mock-log-slice.txt,
*_transcript-*.txt) and checks:

  A. FLAG-ON legs 1-4: every leg completed (game_over), zero [FATAL], zero
     invalid-action-index, and the transcript carries '--- LLM STATED REASON ---'
     with the mock reason text (leg 1: >= 1 section; hostile legs: game must
     complete — a hostile shape may legitimately yield 0 sections for h2, but
     h1/h3 must never fail the move and h3 must still capture the reason).
  B. Prompt-body byte identity (definitive flag check):
       - flag-OFF mulligan/assessment bodies MUST NOT contain the REASON suffix
         block (byte-level containment check on the raw JSON).
       - flag-ON bodies MUST contain it, and removing the suffix from the ON body
         must reproduce the OFF body EXACTLY (byte-identical modulo the suffix).
         Compared fields: the last user message content (the full prompt string).
  C. mock-log suffix audit: flag-OFF leg has ZERO 'has_suffix=yes' posts;
     flag-ON legs have them.
  D. Hostile-shape policy: no leg may carry [FATAL] / 'invalid action index'.

Exit: 0 all post-checks passed; 2 a check failed; 3 harness/setup error.
"""
import argparse
import json
import os
import re
import sys

SUFFIX_MARK = "After your answer line, add one more line: REASON:"
# Per-game card-instance UUID tails like " (c41d)" vary run-to-run and are NOT
# feature-dependent — normalize them before byte-diffing prompts across runs.
INSTANCE_TAIL = re.compile(r"\([0-9a-f]{4}\)")
# Pre-match timing race, unrelated to the feature: the deck-assessment prompt
# sometimes renders the opponent line as the bound name "  rvowner" and
# sometimes as "  (opponent not yet known — will be determined at match start)"
# depending on match-init thread scheduling. Both variants were observed on
# BOTH the feature build and the pre-feature baseline jar. Canonicalize the
# marker to the known seat name (the harness fixes OWNER_NAME=rvowner).
OPPONENT_MARKER = "(opponent not yet known — will be determined at match start)"


def normalize_prompt(p):
    return INSTANCE_TAIL.sub("(id)", p).replace(OPPONENT_MARKER, "rvowner")


def load_result(evidence, leg):
    p = os.path.join(evidence, leg, "result.json")
    if not os.path.isfile(p):
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def transcript_body(evidence, leg):
    d = os.path.join(evidence, leg)
    hits = [f for f in os.listdir(d) if f.endswith(".txt") and "_transcript-" in f]
    if not hits:
        return ""
    with open(os.path.join(d, sorted(hits)[-1]), encoding="utf-8", errors="replace") as f:
        return f.read()


def last_user_content(path):
    """The last user message content of a dumped request body (the full prompt)."""
    with open(path, encoding="utf-8") as f:
        body = json.load(f)
    for msg in reversed(body.get("messages") or []):
        if msg.get("role") == "user":
            return msg.get("content") or ""
    return ""


def mock_suffix_counts(evidence, leg):
    p = os.path.join(evidence, leg, "mock-log-slice.txt")
    if not os.path.isfile(p):
        return None
    with open(p, encoding="utf-8", errors="replace") as f:
        lines = [ln for ln in f if ln.startswith("POST ")]
    return {
        "posts": len(lines),
        "suffix_yes": sum(1 for ln in lines if "has_suffix=yes" in ln),
        "corrections": sum(1 for ln in lines if "is_correction=yes" in ln),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--evidence-dir", required=True)
    ap.add_argument("--legs", nargs="+", required=True)
    args = ap.parse_args()

    failures = []

    def check(name, ok, detail=""):
        tag = "PASS" if ok else "FAIL"
        print(f"[{tag}] {name}" + (f"  -- {detail}" if detail and not ok else ""), flush=True)
        if not ok:
            failures.append(name)

    print("== post-checks over legs:", " ".join(args.legs), "==")

    for leg in args.legs:
        r = load_result(args.evidence_dir, leg)
        if r is None:
            check(f"{leg}: result.json present", False, "missing")
            continue
        # A. completion + no fatal
        check(f"{leg}: game completed (game_over)",
              bool(r.get("game", {}).get("game_over")), json.dumps(r.get("game")))
        checks = r.get("checks", {})
        check(f"{leg}: no [FATAL]/GameWorker error",
              checks.get("(6) no [FATAL] / GameWorker error in the server log", {}).get("ok") is True,
              json.dumps(checks.get("(6) no [FATAL] / GameWorker error in the server log")))
        check(f"{leg}: no 'invalid action index'",
              checks.get("(6b) no 'invalid action index' in the server log", {}).get("ok") is True,
              json.dumps(checks.get("(6b) no 'invalid action index' in the server log")))
        # C. mock suffix audit per leg
        ms = mock_suffix_counts(args.evidence_dir, leg)
        if ms is None:
            check(f"{leg}: mock-log slice present", False, "missing")
        elif "flag-off" in leg:
            # flag-OFF legs (incl. the pre-feature baseline jar leg): no
            # REASON suffix may reach the wire.
            check(f"{leg}: ZERO 'has_suffix=yes' posts (no REASON suffix on the wire)",
                  ms["suffix_yes"] == 0, json.dumps(ms))
        else:
            check(f"{leg}: prompts carried the REASON suffix",
                  ms["suffix_yes"] >= 1, json.dumps(ms))

    # A2. transcript reason sections per flag-on leg
    #   shapes1 / h3  → a reason line is present and must be captured
    #   h1 / h4       → NO reason line (missing / empty label) → no section,
    #                   and the move still plays (checked in A above)
    #   h2            → REASON-first answer takes the correction path; section
    #                   presence is not asserted
    for leg in [l for l in args.legs if l.startswith("flag-on")]:
        r = load_result(args.evidence_dir, leg) or {}
        body = transcript_body(args.evidence_dir, leg)
        sr = body.count("--- LLM STATED REASON ---")
        has_mock = "mock stated reason." in body
        if not body:
            check(f"{leg}: transcript exists", False, "no transcript file")
        elif leg.endswith("shapes1") or leg.endswith("h3-trailing-junk"):
            check(f"{leg}: transcript carries '--- LLM STATED REASON ---'",
                  sr >= 1, f"sections={sr}")
            check(f"{leg}: stated reason text is the mock's", has_mock,
                  f"mock text present={has_mock}")
        elif leg.endswith("h1-action-only") or leg.endswith("h4-empty-label") \
                or leg.endswith("noshapes"):
            check(f"{leg}: NO stated-reason section (no reason line in the answer)",
                  sr == 0, f"sections={sr}")
        else:
            check(f"{leg}: transcript exists", True, "")

    # B. byte-identity assertions on deterministic bodies
    #    - mulligan: a DECISION prompt → suffix appended when flag on; the ON
    #      body minus the suffix block must equal the OFF body byte-for-byte.
    #    - assessment: a FREEFORM class (LLMClient.chat, no suffix by design) →
    #      the ON and OFF bodies must be byte-identical with no stripping.
    #    - baseline leg (optional): flag-OFF bodies on the CURRENT build vs the
    #      PRE-FEATURE baseline jar must be byte-identical (modulo instance ids).
    on_leg = "flag-on-shapes1" if "flag-on-shapes1" in args.legs else None
    off_leg = "flag-off" if "flag-off" in args.legs else None
    base_leg = "flag-off-baseline" if "flag-off-baseline" in args.legs else None
    for kind in ("mulligan", "assessment"):
        on_path = os.path.join(args.evidence_dir, on_leg or "", "bodies", f"{kind}.json")
        off_path = os.path.join(args.evidence_dir, off_leg or "", "bodies", f"{kind}.json")
        if not (on_leg and off_leg and os.path.isfile(on_path) and os.path.isfile(off_path)):
            check(f"{kind}: body files for byte diff present", False,
                  f"on={os.path.isfile(on_path)} off={os.path.isfile(off_path)}")
            continue
        on_prompt = last_user_content(on_path)
        off_prompt = last_user_content(off_path)
        on_n = normalize_prompt(on_prompt)
        off_n = normalize_prompt(off_prompt)
        if kind == "mulligan":
            check("flag-off mulligan: no REASON suffix in the wire prompt",
                  SUFFIX_MARK not in off_n,
                  "suffix marker present")
            check("flag-on mulligan: REASON suffix present in the wire prompt",
                  SUFFIX_MARK in on_n, "suffix marker absent")
            idx = on_n.find(SUFFIX_MARK)
            stripped = ""
            if idx >= 0:
                start = on_n.rfind("\n", 0, idx)
                stripped = on_n[:start] if start >= 0 else on_n
            check("mulligan: flag-on stripped == flag-off byte-identical (instance IDs normalized)",
                  stripped == off_n,
                  f"len(on-stripped)={len(stripped)} vs len(off)={len(off_n)}")
        else:
            # freeform class: no suffix on either side, bodies must match exactly
            check("assessment (freeform): flag-on == flag-off byte-identical",
                  on_n == off_n,
                  f"len(on)={len(on_n)} vs len(off)={len(off_n)}")
            check("assessment: no REASON suffix on the wire (freeform class)",
                  SUFFIX_MARK not in on_n, "suffix marker present")
        # B2. flag-OFF (current build) == pre-feature baseline jar
        if base_leg:
            base_path = os.path.join(args.evidence_dir, base_leg, "bodies", f"{kind}.json")
            if not os.path.isfile(base_path):
                check(f"{kind}: baseline body present", False, base_path)
            else:
                base_n = normalize_prompt(last_user_content(base_path))
                check(f"{kind}: flag-OFF == pre-feature baseline byte-identical "
                      f"(instance IDs normalized)",
                      off_n == base_n,
                      f"len(off)={len(off_n)} vs len(base)={len(base_n)}")
                check(f"{kind}: baseline has no REASON suffix",
                      SUFFIX_MARK not in base_n, "suffix marker present")

    print()
    if failures:
        print(f"POSTCHECKS: FAIL ({len(failures)}): " + "; ".join(failures))
        return 2
    print("POSTCHECKS: PASS (all)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
