#!/usr/bin/env python3
"""
Focused test of the token SERIALIZATION contract (tokenSetCode / tokenImageNumber).

Why this exists separately from the full-game E2E: driving a random game until a token
spell is drawn is slow and flaky, and it conflates two questions. This script answers the
one that actually matters for the feature, deterministically:

  does a token permanent reaching the browser carry the printing identity the client
  needs to build a loadable art URL?

It plays a token-heavy deck against an AI seat and, on every frame, scans the FULL state
(reconstructed from game_init + every game_delta, plus periodic request_resync) for
engine-flagged tokens. When one appears it asserts the serialized fields and then verifies
the URL those fields imply actually returns an image.

Usage: token-serialize-probe.py [--seconds N]
Exit: 0 when a token was observed AND its printing resolved to a real image, 1 otherwise.
"""
import argparse
import importlib.util
import json
import os
import sys
import time
import socket
import urllib.request
import uuid


# XMage rejects a username longer than 14 characters ("User name may not be longer than
# 14 characters"). The server reports that via SHOW_USERMESSAGE, NOT as a connect_error --
# so an over-long generated name looks like a silent create_table failure. Fail Fast here
# instead: this cost a long debug cycle on 2026-09-21.
XMAGE_MAX_USERNAME = 14


def checked_username(base, suffix=""):
    """Return base+suffix, asserting the combined length is a legal XMage username."""
    name = base + suffix
    if len(name) > XMAGE_MAX_USERNAME:
        raise ValueError(
            "username %r is %d chars; XMage rejects names longer than %d (the server "
            "reports it as SHOW_USERMESSAGE, which is easy to misread as a table failure)"
            % (name, len(name), XMAGE_MAX_USERNAME))
    return name


def is_username_rejection(msg):
    """True when a frame is the server refusing the login name."""
    if msg.get("type") == "connect_error":
        return "User name" in (msg.get("message") or "")
    if msg.get("type") == "show_usermessage":
        text = json.dumps(msg.get("messages") or msg.get("message") or "")
        return "User name" in text
    return False



HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("ta", os.path.join(HERE, "token-art-e2e.py"))
ta = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ta)

# Token deck built for DETERMINISM, not flavour: Sprout is a 1-mana instant that makes a
# 1/1 Saproling, so a token lands on turn 1 with a single Forest. Earlier versions of this
# probe ran 400s of a token deck and saw nothing, because the auto-player kept taking
# choices[0] (the server lists lands first) and never had mana up for a bigger token spell.
# 24 Forests + 16 Sprouts means the opening hand is essentially always castable.
DECK = """16 Sprout
24 Forest"""



def first_untapped_land(state):
    """Id of the first untapped land on OUR side of the merged state, or None.

    Local copy: referencing the sibling module's helper unqualified raised NameError,
    which the loop's broad `except Exception: continue` swallowed — so mana payment
    silently never tapped and every cast stalled.
    """
    for p in (state.get("players") or []):
        if not p.get("isMe"):
            continue
        for c in (p.get("battlefield") or []):
            if not c.get("tapped") and "Land" in (c.get("typeLine") or ""):
                return c.get("id")
    return None


def merge(state, into):
    """Merge a state frame into a running dict (deltas carry partial state)."""
    for k, v in state.items():
        into[k] = v
    return into


def image_ok(url):
    req = urllib.request.Request(url, headers={"User-Agent": "XMageWebProxy/1.0",
                                              "Accept": "image/*,*/*"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status == 200 and r.headers.get("Content-Type", "").startswith("image/")
    except Exception:
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=420)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8090)
    args = ap.parse_args()

    ws = ta.WS(args.host, args.port, origin=ta.origin_for(args.host, args.port))
    ws.send({"type": "connect", "server": "127.0.0.1", "port": 17171,
             "username": "TSer" + uuid.uuid4().hex[:6], "password": ""})
    ws.drain(10, [])
    print("connected", flush=True)

    ws.send({"type": "toggle_mana_auto_pay", "enabled": True})
    ws.send({
        "type": "create_table", "name": "token-ser-probe",
        "gameType": "Two Player Duel", "deckType": "Constructed - Freeform",
        "winsNeeded": 1, "vsAI": True, "deck": DECK,
        "spectatorsAllowed": True, "password": "",
    })
    print("game requested", flush=True)

    state = {}
    HEART = {"n": 0}
    PROMPTS = {}
    PRINT_PROMPTS = {"n": 0}
    ERRORS = []
    NOCAST = {"n": 0}
    mull = {"asked": False}
    deadline = time.time() + args.seconds
    next_resync = time.time() + 12
    next_report = time.time() + 25
    start = time.time()
    found = None

    while time.time() < deadline and found is None:
        try:
            m = ws.recv(timeout=3)
        except (socket.timeout, TimeoutError):
            continue
        except Exception as e:
            print("  socket/recv ended: %r" % (e,), flush=True)
            break

        t = m.get("type")
        if t in ("error", "server_error", "connect_error"):
            ERRORS.append(m)
            print("  !! %s: %s" % (t, json.dumps(m)[:200]), flush=True)
        HEART["n"] += 1
        if HEART["n"] <= 40:
            print("  frame %d: %s" % (HEART["n"], t), flush=True)
        if HEART["n"] == 1:
            print("  FIRST FRAME RECEIVED after %.1fs" % (time.time() - start), flush=True)
        if t in ("game_init", "game_update"):
            merge(m.get("state") or {}, state)
        elif t == "game_delta":
            # The proxy sends DIFFS on this channel: {"type":"game_delta","delta":{...}}
            # (WebProxyClient.sendGameStateToBrowser). The browser applies them with
            # applyDelta; a probe that only reads `state` silently never sees a card that
            # arrived in a delta — which is why an earlier version of this script watched
            # a 69-turn game and reported "no token" when tokens were certainly present.
            merge(m.get("delta") or {}, state)

        elif t == "action_prompt":
            at = m.get("action_type") or m.get("type")
            PROMPTS[at] = PROMPTS.get(at, 0) + 1
            if PRINT_PROMPTS["n"] < 25:
                PRINT_PROMPTS["n"] += 1
                ch = m.get("choices")
                print("  prompt %d: action_type=%s context=%s choices=%s" %
                      (PRINT_PROMPTS["n"], at, m.get("context"),
                       ("%d [%s]" % (len(ch), ",".join(
                           "%s:%s" % (c.get("action"), (c.get("name") or "")[:14])
                           for c in ch[:3]))) if ch else "-"), flush=True)
            if at == "boolean":
                # Keep the opening hand (context=mulligan is INVERTED: true = mulligan).
                v = False if m.get("context") == "mulligan" else True
                ws.send({"type": "boolean_choice", "value": v})
            elif at == "select":
                # CRITICAL: the server lists LANDS FIRST in the choice list, so taking
                # choices[0] plays a Mountain every turn and the probe never casts a
                # spell — an earlier run of this probe watched 400s of a token deck and
                # saw zero tokens for exactly this reason. Choices carry `action`
                # (ActionChoice: land|cast|activate); prefer a castable spell, take a
                # land only when nothing is castable.
                choices = m.get("choices") or []
                castable = [c for c in choices if c.get("action") == "cast"]
                pick = (castable or choices)[0] if choices else None
                if choices and not castable:
                    NOCAST["n"] += 1
                    if NOCAST["n"] <= 6:
                        print("    (no castable choice; %d choice(s), first action=%s)" %
                              (len(choices), choices[0].get("action")), flush=True)
                if pick is not None:
                    ws.send({"type": "select_choice", "index": pick.get("index")})
                else:
                    ws.send({"type": "pass_priority"})
            elif at == "target":
                ts = m.get("targets") or []
                if m.get("required") and ts:
                    ws.send({"type": "select_target", "targetId": ts[0].get("id")})
                else:
                    ws.send({"type": "pass_priority"})
            elif at == "play_mana":
                # Tap an untapped land from OUR battlefield. Auto-pay mode alone does NOT
                # tap lands (GameController: MANA_AUTO_PAYMENT_ON only calls
                # setManaPaymentMode), so relying on it stalled the cast forever.
                land = first_untapped_land(state)
                if land:
                    ws.send({"type": "tap_mana", "landId": land})
                else:
                    # Nothing to tap: re-arm and let the prompt re-arrive rather than
                    # sending a cancel (a cancel rolls the spell back).
                    ws.send({"type": "toggle_mana_auto_pay", "enabled": True})
            else:
                ws.send({"type": "pass_priority"})

        # Scan the merged state for engine-flagged tokens.
        for p in (state.get("players") or []):
            for c in (p.get("battlefield") or []):
                if c.get("token"):
                    found = (p.get("name"), c)
                    break
            if found:
                break

        if time.time() >= next_resync:
            try:
                ws.send({"type": "request_resync"})
            except Exception:
                pass
            next_resync = time.time() + 10

        if time.time() >= next_report:
            n_bf = sum(len(p.get("battlefield") or []) for p in (state.get("players") or []))
            print("  t=%.0fs battlefield=%d keys=%s" %
                  (time.time() - start, n_bf,
                   ",".join(sorted(k for k in state.keys() if k in
                                   ("players", "turn", "phase", "stack", "combat")))), flush=True)
            next_report = time.time() + 25

    if found is None:
        print("RESULT: no token reached the battlefield in %ds" % args.seconds, flush=True)
        print("prompts answered:", PROMPTS, flush=True)
        print("select prompts with no castable choice:", NOCAST["n"], flush=True)
        print("server errors seen:", len(ERRORS), flush=True)
        for e in ERRORS[-5:]:
            print("   ", json.dumps(e)[:180], flush=True)
        return 1

    owner, tok = found
    print("\nTOKEN OBSERVED on the battlefield:")
    print("  owner=%s name=%r" % (owner, tok.get("name")))
    print("  token=%r tokenSetCode=%r tokenImageNumber=%r" %
          (tok.get("token"), tok.get("tokenSetCode"), tok.get("tokenImageNumber")))

    fails = []
    if tok.get("token") is not True:
        fails.append("token flag not true")
    if not tok.get("tokenSetCode"):
        fails.append("tokenSetCode MISSING (client cannot resolve art)")
    if tok.get("tokenImageNumber") is None:
        fails.append("tokenImageNumber MISSING")

    if tok.get("tokenSetCode"):
        num = tok.get("tokenImageNumber") or 1
        url = ("https://api.scryfall.com/cards/t%s/%s?format=image&version=small"
               % (str(tok["tokenSetCode"]).lower(), num))
        ok_img = image_ok(url)
        print("  implied art URL: %s -> %s" % (url.split("/cards/")[-1],
                                               "IMAGE OK" if ok_img else "NO IMAGE"))
        if not ok_img:
            fails.append("serialized printing does not yield an image")

    # Also exercise the resolver for this token's name over the same socket. The
    # engine's set decides the default art, so it is sent along.
    ws.send({"type": "token_versions", "tokenName": tok.get("name"),
             "engineSetCode": tok.get("tokenSetCode"), "requestId": "ser-final"})
    res = None
    t_end = time.time() + 20
    while time.time() < t_end and res is None:
        try:
            m = ws.recv(timeout=3)
        except Exception:
            continue
        if m.get("type") == "token_versions_result" and m.get("requestId") == "ser-final":
            res = m
        elif m.get("type") == "action_prompt":
            at = m.get("action_type") or m.get("type")
            if at == "select":
                ch = m.get("choices") or []
                if ch:
                    ws.send({"type": "select_choice", "index": ch[0].get("index")})
            elif at == "play_mana":
                # Tap an untapped land from OUR battlefield. Auto-pay mode alone does NOT
                # tap lands (GameController: MANA_AUTO_PAYMENT_ON only calls
                # setManaPaymentMode), so relying on it stalled the cast forever.
                land = first_untapped_land(state)
                if land:
                    ws.send({"type": "tap_mana", "landId": land})
                else:
                    # Nothing to tap: re-arm and let the prompt re-arrive rather than
                    # sending a cancel (a cancel rolls the spell back).
                    ws.send({"type": "toggle_mana_auto_pay", "enabled": True})
            elif at == "target":
                ts = m.get("targets") or []
                if m.get("required") and ts:
                    ws.send({"type": "select_target", "targetId": ts[0].get("id")})
                else:
                    ws.send({"type": "pass_priority"})
            else:
                ws.send({"type": "pass_priority"})
    versions = (res or {}).get("result", {}).get("versions", [])
    print("  resolver returned %d printing(s) for %r" % (len(versions), tok.get("name")))
    if not versions:
        fails.append("resolver returned no printings for the observed token")
    elif not image_ok(versions[0]["url"]):
        fails.append("resolver's first printing does not yield an image")

    # ── the relay, now that a live game exists ──
    name = tok.get("name")
    pick = versions[0]["url"] if versions else None
    if pick:
        ws.send({"type": "token_art_preference", "tokenName": name, "url": pick})
        relayed = None
        t_end = time.time() + 20
        while time.time() < t_end and relayed is None:
            try:
                m = ws.recv(timeout=3)
            except Exception:
                continue
            if m.get("type") == "token_art_preferences":
                relayed = m
            elif m.get("type") == "action_prompt":
                at = m.get("action_type") or m.get("type")
                if at == "select":
                    ch = m.get("choices") or []
                    if ch:
                        ws.send({"type": "select_choice", "index": ch[0].get("index")})
                elif at == "play_mana":
                    _l = first_untapped_land(state)
                    if _l:
                        ws.send({"type": "tap_mana", "landId": _l})
                    else:
                        ws.send({"type": "toggle_mana_auto_pay", "enabled": True})
                else:
                    ws.send({"type": "pass_priority"})
        if relayed is None:
            fails.append("preference was not relayed back")
        else:
            prefs = relayed.get("preferences") or []
            mine = [p for p in prefs if p.get("tokenName") == name and p.get("url") == pick]
            print("  relay snapshot: %d entr(ies), mine present=%s" % (len(prefs), bool(mine)))
            if len(mine) != 1:
                fails.append("relayed snapshot does not contain the preference")
            elif not mine[0].get("playerId"):
                fails.append("relayed snapshot missing playerId")

    print()
    if fails:
        print("FAIL:")
        for f in fails:
            print("  - " + f)
        return 1
    print("PASS: token printing identity is on the wire, its art loads, and the relay works")
    return 0


if __name__ == "__main__":
    sys.exit(main())
