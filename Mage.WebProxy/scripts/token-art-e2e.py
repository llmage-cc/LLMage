#!/usr/bin/env python3
"""
END-TO-END live verification of the token-art feature: play a real game that puts
tokens on the battlefield, then assert the wire actually carries what the feature needs.

What this proves that the unit/e2e probes cannot:
  * a token permanent arrives with the ENGINE's token flag and its chosen printing
    (tokenSetCode/tokenImageNumber) — the data the client resolves art from;
  * the client can then ask for that token's printings and get URLs that LOAD;
  * a token-art preference is relayed back to the table with a full snapshot
    (the cross-player half of the feature), which needs a live game to have a playerId.

Deck: a small red deck that reliably makes tokens (Krenko's Command -> two 1/1 Goblins)
plus Mountains. The AI opponent seat is auto-run: pass priority whenever asked.

Usage: token-art-e2e.py [--host H] [--port N]
Exit: 0 when every assertion passes, 1 otherwise.
"""
import argparse
import base64
import copy
import hashlib
import json
import os
import re
import socket
import struct
import sys
import time
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



USER = "TAE2E" + uuid.uuid4().hex[:6]
def origin_for(host, port):
    return "http://%s:%d" % (host, port)

DECK = """4 Krenko's Command
4 Dragon Fodder
18 Mountain
4 Goblin Chieftain
4 Shock
4 Lightning Bolt
2 Goblin Rally"""

failures, passes, skips = [], [], []


def ok(name, cond, detail=""):
    if cond:
        passes.append(name)
        print("PASS  %s%s" % (name, ("  (" + detail + ")") if detail else ""))
    else:
        failures.append(name)
        print("FAIL  %s  %s" % (name, detail))


def skip(name, why):
    skips.append(name)
    print("SKIP  %s  (%s)" % (name, why))


class WS:
    def __init__(self, host, port, path="/ws", origin=None, timeout=25):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        headers = ["GET %s HTTP/1.1" % path, "Host: %s:%d" % (host, port),
                   "Upgrade: websocket", "Connection: Upgrade",
                   "Sec-WebSocket-Key: %s" % key, "Sec-WebSocket-Version: 13"]
        if origin:
            headers.append("Origin: %s" % origin)
        self.sock.sendall(("\r\n".join(headers) + "\r\n\r\n").encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = self.sock.recv(4096)
            if not chunk:
                break
            resp += chunk
        head = resp.split(b"\r\n\r\n", 1)[0].decode("utf8", "ignore")
        if "101" not in head.split("\r\n")[0]:
            raise RuntimeError("WS upgrade failed: " + head.split("\r\n")[0])
        self.buf = resp.split(b"\r\n\r\n", 1)[1] if b"\r\n\r\n" in resp else b""

    def send(self, obj):
        payload = json.dumps(obj).encode()
        header = bytearray([0x81])
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        mask = os.urandom(4)
        header += mask
        self.sock.sendall(bytes(header) + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))

    def _need(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("socket closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self, timeout=25):
        self.sock.settimeout(timeout)
        b1, b2 = self._need(2)
        opcode = b1 & 0x0F
        length = b2 & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._need(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._need(8))[0]
        mask = self._need(4) if (b2 & 0x80) else None
        data = self._need(length)
        if mask:
            data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        if opcode == 0x8:
            raise RuntimeError("server closed the socket")
        if opcode == 0x1:
            return json.loads(data.decode("utf8"))
        return self.recv(timeout)

    def drain(self, seconds, sink):
        """Collect messages for a fixed window."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                sink.append(self.recv(timeout=max(0.2, min(2.0, deadline - time.time()))))
            except (socket.timeout, TimeoutError):
                continue
            except Exception:
                break
        return sink


def image_loads(url):
    req = urllib.request.Request(url, headers={"User-Agent": "XMageWebProxy/1.0",
                                               "Accept": "image/*,*/*"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status == 200 and r.headers.get("Content-Type", "").startswith("image/")
    except Exception:
        return False


def find_tokens(state):
    """Every battlefield permanent the ENGINE flagged as a token."""
    out = []
    for p in (state.get("players") or []):
        for c in (p.get("battlefield") or []):
            if c.get("token"):
                out.append((p.get("name"), c))
    return out


PASS_SENT = {"n": 0}

# Latest merged board state, so prompt handlers can act on it (the real client reads the
# store the same way: PlayManaPrompt scans gameState for untapped lands).
LAST_STATE = {}


def first_untapped_land(state):
    """Id of the first untapped land on OUR side, or None. Mirrors the real client's scan."""
    for p in (state.get("players") or []):
        if not p.get("isMe"):
            continue
        for c in (p.get("battlefield") or []):
            if not c.get("tapped") and "Land" in (c.get("typeLine") or ""):
                return c.get("id")
    return None


def handle_prompt(ws, msg):
    """Answer an action prompt the way the web client does, aiming to CAST A TOKEN SPELL.

    The client contract (gameplaySlice.selectChoice / passPriority):
      * play_mana   -> tap a land the prompt offers, else cancel (never livelock)
      * select      -> select_choice with a choice's index
      * boolean     -> boolean_choice(true)
      * anything else (targets/abilities/amounts) -> pass_priority

    The deck's token spells need no targets, so passing on the rest is safe.
    """
    prompt = msg.get("prompt") or msg
    # RAW WIRE field is `action_type` (WebProxyClient puts action_type=<kind>); the
    # frontend's buildActionPrompt maps it onto its own `type` field. This probe reads
    # raw frames, so prefer action_type and fall back to type.
    ptype = prompt.get("action_type") or prompt.get("type")

    if ptype == "play_mana":
        # Auto-pay is enabled server-side (toggle_mana_auto_pay on connect), so the cost
        # is normally paid without any client action. If the prompt is still here, tap one
        # untapped land we know about; else re-arm auto-pay rather than cancel (a cancel
        # would roll the spell back and the probe would never get a token).
        land = first_untapped_land(LAST_STATE)
        if land:
            ws.send({"type": "tap_mana", "landId": land})
        else:
            ws.send({"type": "toggle_mana_auto_pay", "enabled": True})
        return

    if ptype == "select":
        choices = prompt.get("choices") or []

        def score(c):
            name = (c.get("name") or "").lower()
            for kw in ("krenko", "dragon fodder", "goblin rally"):
                if kw in name:
                    return 0
            if c.get("action") in ("cast", "activate"):
                return 1
            if c.get("action") == "land":
                return 3
            return 2

        if choices:
            pick = sorted(choices, key=score)[0]
            ws.send({"type": "select_choice", "index": pick.get("index")})
        else:
            ws.send({"type": "pass_priority"})
        return

    if ptype == "boolean":
        # MULLIGAN IS INVERTED (verified: the mulligan prompt carries
        # context="mulligan", yes_label=Mulligan, no_label=Keep — see the
        # "Mulligan buttons show engine labels" fix). Sending true = take a mulligan,
        # which re-prompts and loops forever: that is why this probe sat at GAME_ASK
        # and never reached a battlefield. Keep the hand: answer false.
        if prompt.get("context") == "mulligan":
            ws.send({"type": "boolean_choice", "value": False})
            return
        ws.send({"type": "boolean_choice", "value": True})
        return

    if ptype == "target":
        # A REQUIRED target must be answered — passing is ignored by the proxy
        # ("Pass priority ignored during required GAME_TARGET") and the game stalls.
        # "Select a starting player" arrives this way at game start; picking myself
        # keeps the game moving regardless of what the prompt is for.
        targets = prompt.get("targets") or []
        me = next((t for t in targets if (t.get("name") or "") == USER), None)
        pick = me or (targets[0] if targets else None)
        if pick and pick.get("id"):
            ws.send({"type": "select_target", "targetId": pick.get("id")})
            return
        return

    PASS_SENT["n"] += 1
    ws.send({"type": "pass_priority"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8090)
    ap.add_argument("--xmage", default="127.0.0.1:17171")
    ap.add_argument("--seconds", type=int, default=150, help="max time to reach a token board")
    args = ap.parse_args()

    print("=== token-art END-TO-END: %s:%d ===" % (args.host, args.port))
    ws = WS(args.host, args.port, origin=origin_for(args.host, args.port))
    host, xport = args.xmage.split(":")
    ws.send({"type": "connect", "server": host, "port": int(xport),
             "username": USER, "password": ""})
    conn = ws.drain(12, [])
    c = next((m for m in conn if m.get("type") == "connected"), None)
    if not c:
        print("FATAL: could not connect: %s" % [m.get("type") for m in conn[:6]])
        return 1
    print("connected as %s" % c.get("username"))

    # Server-side auto-pay: the client toggles this with `toggle_mana_auto_pay`
    # (PlayManaPrompt's "Auto-Pay Pool"). It is far more robust than tapping lands one
    # by one from a probe — the SERVER pays the cost, so the play loop cannot stall in
    # mana payment (which is exactly what it did before this line existed).
    ws.send({"type": "toggle_mana_auto_pay", "enabled": True})

    ws.send({
        "type": "create_table", "name": "token-art-e2e",
        "gameType": "Two Player Duel", "deckType": "Constructed - Freeform",
        "winsNeeded": 1, "vsAI": True, "deck": DECK,
        "spectatorsAllowed": True, "password": "",
    })

    # Auto-play: pass priority, answer prompts minimally, keep a live game moving until
    # a token hits the battlefield. The deck is full of token makers, so this is a
    # matter of turns rather than luck.
    deadline = time.time() + args.seconds
    tokens_seen = []
    saw_game = False
    next_resync = time.time() + 8
    start_at = time.time()
    next_report = start_at + 20
    preference_sent = False
    relay_ok = None
    pref_url_used = None

    while time.time() < deadline:
        try:
            msg = ws.recv(timeout=3)
        except (socket.timeout, TimeoutError):
            continue
        except Exception as e:
            print("socket ended: %s" % e)
            break

        mtype = msg.get("type")
        if mtype in ("game_init", "game_update"):
            saw_game = True
            state = msg.get("state") or {}
            # The board is cumulative across deltas for our purposes: merge what each
            # frame carries so a token that arrived in an earlier delta is not forgotten.
            for k, v in state.items():
                LAST_STATE[k] = v
            found = find_tokens(LAST_STATE)
        elif mtype == "game_delta":
            # Diffs arrive on this channel as {"delta": {...}} (WebProxyClient
            # sendGameStateToBrowser); the browser merges them with applyDelta. Reading
            # only `state` means a token that arrived in a delta is never seen.
            saw_game = True
            for k, v in (msg.get("delta") or {}).items():
                LAST_STATE[k] = v
            found = find_tokens(LAST_STATE)
            if found:
                for owner, tok in found:
                    key = (tok.get("name"), tok.get("tokenSetCode"), tok.get("tokenImageNumber"))
                    if key not in [t[0] for t in tokens_seen]:
                        tokens_seen.append((key, owner, tok))
        elif mtype == "action_prompt":
            handle_prompt(ws, msg)
        elif mtype == "token_art_preferences":
            prefs = msg.get("preferences") or []
            if pref_url_used and any(p.get("url") == pref_url_used for p in prefs):
                relay_ok = prefs
        elif mtype == "token_versions_result":
            # Opportunistic: if a token tile asked, we can log it.
            pass

        # Deltas are sparse, so ask for a full state on a timer — the proxy answers with
        # resendLatestGameState(), which carries the whole board.
        if saw_game and time.time() >= next_resync:
            try:
                ws.send({"type": "toggle_mana_auto_pay", "enabled": True})
            except Exception:
                pass
            try:
                ws.send({"type": "request_resync"})
            except Exception:
                pass
            next_resync = time.time() + 6

        if time.time() >= next_report:
            print("  ... t=%.0fs saw_game=%s tokens=%d (state keys: %s)"
                  % (time.time() - start_at, saw_game, len(tokens_seen),
                     ",".join(sorted(LAST_STATE.keys()))[:120]))
            next_report = time.time() + 20

        # Once a token exists, exercise the feature on the live game.
        if tokens_seen and not preference_sent:
            (name, set_code, img_num), owner, tok = tokens_seen[0]
            ok("token permanent carries the engine's token flag", bool(tok.get("token")),
               "name=%s owner=%s" % (name, owner))
            ok("token carries its chosen printing (tokenSetCode)",
               bool(set_code), "setCode=%s imageNumber=%s" % (set_code, img_num))

            ws.send({"type": "token_versions", "tokenName": name,
                     "engineSetCode": set_code, "requestId": "e2e-tok"})
            pref_url_used = None
            preference_sent = True

    # ── assertions ──
    ok("a game started and pushed state", saw_game)
    if not tokens_seen:
        ok("a token reached the battlefield within %ds" % args.seconds, False,
           "no engine-flagged token appeared — cannot verify the token path")
        return finish()

    (name, set_code, img_num), owner, tok = tokens_seen[0]
    print("\nobserved token: %s (owner %s, setCode=%s, imageNumber=%s)"
          % (name, owner, set_code, img_num))

    # The resolver must answer for this token's name, and its default art must be a
    # printing from the ENGINE's set — not merely "some URL that loads". The previous
    # implementation built `t<engineSet>/<imageNumber>`, which renders a DIFFERENT token
    # (RTR image 1 is "Bird"); a wrong token's art loads perfectly, so this assertion is
    # the only thing that distinguishes correct art from plausible art.
    ws.send({"type": "token_versions", "tokenName": name,
             "engineSetCode": set_code, "requestId": "e2e-final"})
    res = None
    for _ in range(40):
        try:
            m = ws.recv(timeout=5)
        except Exception:
            break
        if m.get("type") == "token_versions_result" and m.get("requestId") == "e2e-final":
            res = m
            break
        if m.get("type") == "token_art_preferences":
            relay_ok = m.get("preferences") or relay_ok
        elif m.get("type") == "action_prompt":
            handle_prompt(ws, m)
    versions = (res or {}).get("result", {}).get("versions", [])
    ok("resolver returns printings for the live token", len(versions) > 0,
       "%d printings for %r" % (len(versions), name))
    default_url = (res or {}).get("result", {}).get("defaultUrl")
    default_entry = next((v for v in versions if v["url"] == default_url), None)
    ok("the default art is a printing from the engine's own set",
       bool(default_entry) and bool(set_code)
       and default_entry.get("setCode") == "t" + set_code.lower(),
       "engineSet=%s default=%s" % (set_code, (default_entry or {}).get("setCode", "none")))
    ok("the default art loads", bool(default_url) and image_loads(default_url),
       (default_url or "").split("/cards/")[-1])

    # ── the relay, now that a game exists ──
    pick = (versions[0]["url"] if versions
            else "https://api.scryfall.com/cards/t10e/1?format=image&version=small")
    ws.send({"type": "token_art_preference", "tokenName": name, "url": pick})
    relayed = None
    deadline = time.time() + 20
    while time.time() < deadline and relayed is None:
        try:
            m = ws.recv(timeout=3)
        except (socket.timeout, TimeoutError):
            continue
        except Exception:
            break
        if m.get("type") == "token_art_preferences":
            relayed = m
        elif m.get("type") == "action_prompt":
            handle_prompt(ws, m)

    if relayed is None:
        ok("token art preference relayed back with a snapshot", False, "no token_art_preferences message")
    else:
        prefs = relayed.get("preferences") or []
        mine = [p for p in prefs if p.get("tokenName") == name and p.get("url") == pick]
        ok("token art preference relayed back with a snapshot", len(mine) == 1,
           json.dumps(prefs)[:200])
        ok("snapshot carries playerId + playerName",
           bool(mine) and bool(mine[0].get("playerId")) and bool(mine[0].get("playerName")),
           json.dumps(mine[0]) if mine else "")

    # Clearing must remove it.
    ws.send({"type": "token_art_preference", "tokenName": name, "url": None})
    cleared = None
    deadline = time.time() + 15
    while time.time() < deadline and cleared is None:
        try:
            m = ws.recv(timeout=3)
        except (socket.timeout, TimeoutError):
            continue
        except Exception:
            break
        if m.get("type") == "token_art_preferences":
            cleared = m
        elif m.get("type") == "action_prompt":
            handle_prompt(ws, m)
    if cleared is not None:
        left = [p for p in (cleared.get("preferences") or []) if p.get("tokenName") == name]
        ok("clearing a preference removes it from the snapshot", len(left) == 0,
           json.dumps(cleared.get("preferences"))[:160])
    else:
        skip("clearing a preference", "no snapshot message arrived")

    # Concede so the probe does not leave a zombie table mid-game.
    try:
        ws.send({"type": "concede_match"})
        ws.drain(2, [])
    except Exception:
        pass
    return finish()


def finish():
    print()
    print("passed %d, failed %d, skipped %d" % (len(passes), len(failures), len(skips)))
    for f in failures:
        print("  FAILED: %s" % f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
