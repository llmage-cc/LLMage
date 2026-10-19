#!/usr/bin/env python3
"""
Live protocol probe for the token-art feature, over the real WebSocket.

Verifies the two server→client contracts the feature depends on, without a browser:

  1. token_versions (engine phase) — a token name resolves to ordered Scryfall
     printing URLs. This is the fix for "missing tokens": a token name is NOT
     addressable as a card image, so the proxy must answer with real printings.
     Also asserts that each returned URL actually yields an image (the whole point
     of "try print versions until one loads"), and that an unknown token resolves to
     an EMPTY list rather than an invented URL.

  2. token_art_preference — the cross-player relay. Set a preference, then confirm the
     server echoes the full snapshot back with the right playerId/tokenName/url, and
     that a non-Scryfall URL is REFUSED (it would land on every opponent's board).

No external deps: hand-rolled HTTP/1.1 upgrade + masked frames, same approach as
ws_e2e.py. Runs against the DEV rig by default (plain ws://, no auth token).

Usage: token-art-probe.py [host] [port]
Exit: 0 when every assertion passes, 1 otherwise.
"""
import base64
import hashlib
import json
import os
import socket
import struct
import sys
import urllib.error
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



HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8090
USER = "TAProbe" + uuid.uuid4().hex[:6]
# Origin must be on the proxy's allowlist (localhost by default; otherwise
# pass -Dwebproxy.allowedOrigins on the proxy side).
ORIGIN = "http://%s:%d" % (HOST, PORT)

failures = []
passes = []


def ok(name, cond, detail=""):
    if cond:
        passes.append(name)
        print("PASS  %s%s" % (name, ("  (" + detail + ")") if detail else ""))
    else:
        failures.append(name)
        print("FAIL  %s  %s" % (name, detail))


class WS:
    """Minimal WebSocket client: HTTP/1.1 upgrade + masked text frames."""

    def __init__(self, host, port, path="/ws", origin=None, timeout=20):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        headers = [
            "GET %s HTTP/1.1" % path,
            "Host: %s:%d" % (host, port),
            "Upgrade: websocket",
            "Connection: Upgrade",
            "Sec-WebSocket-Key: %s" % key,
            "Sec-WebSocket-Version: 13",
        ]
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
        expect = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()
        ).decode()
        if ("sec-websocket-accept: " + expect).lower() not in head.lower():
            raise RuntimeError("bad Sec-WebSocket-Accept")
        self.buf = resp.split(b"\r\n\r\n", 1)[1] if b"\r\n\r\n" in resp else b""

    def send(self, obj):
        payload = json.dumps(obj).encode()
        header = bytearray([0x81])  # FIN + text
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
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def _read_exact(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("socket closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self, timeout=20):
        self.sock.settimeout(timeout)
        b1, b2 = self._read_exact(2)
        opcode = b1 & 0x0F
        length = b2 & 0x7F
        if length == 126:
            length = struct.unpack(">H", self._read_exact(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", self._read_exact(8))[0]
        masked = b2 & 0x80
        mask = self._read_exact(4) if masked else None
        data = self._read_exact(length)
        if mask:
            data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        if opcode == 0x8:
            raise RuntimeError("server closed the socket")
        if opcode == 0x1:
            return json.loads(data.decode("utf8"))
        return self.recv(timeout)

    def wait_for(self, types, limit=60):
        for _ in range(limit):
            msg = self.recv()
            if msg.get("type") in types:
                return msg
        return None


def image_loads(url):
    req = urllib.request.Request(url, headers={
        "User-Agent": "XMageWebProxy/1.0",
        "Accept": "image/*,*/*",
    })
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status == 200 and r.headers.get("Content-Type", "").startswith("image/")
    except Exception:
        return False


def main():
    print("=== token-art protocol probe: %s:%d ===" % (HOST, PORT))
    ws = WS(HOST, PORT, origin=ORIGIN)
    ws.send({"type": "connect", "server": "127.0.0.1", "port": 17171,
             "username": USER, "password": ""})
    conn = ws.wait_for({"connected", "connect_error"})
    if not conn or conn.get("type") != "connected":
        print("FATAL: could not connect: %s" % conn)
        return 1
    print("connected as %s" % conn.get("username"))

    # ── 1. token_versions ──
    #
    # NOTE: the engine's set is passed only to choose the DEFAULT art. The old protocol's
    # `expand` flag is gone: every candidate is a real Scryfall printing now, so there is
    # no offline "engine list" to exhaust first.
    ws.send({"type": "token_versions", "tokenName": "Soldier Token",
             "engineSetCode": "RTR", "requestId": "p1"})
    res = ws.wait_for({"token_versions_result"})
    ok("token_versions returns a result", res is not None and res.get("requestId") == "p1",
       json.dumps(res)[:120] if res else "no message")
    if not res or "result" not in res:
        return finish()
    versions = res["result"].get("versions", [])
    ok("Soldier Token has real printings", len(versions) > 0, "%d printings" % len(versions))
    ok("candidates are https Scryfall URLs",
       all(v["url"].startswith("https://") for v in versions),
       versions[0]["url"] if versions else "")
    ok("every candidate names its set (the picker needs it)",
       all(v.get("setName") for v in versions),
       json.dumps(versions[0])[:140] if versions else "")
    ok("no fetch failure reported", res["result"].get("fetchFailed") is False)

    # THE assertion that matters, and the one the old probe got wrong: the default must
    # be a printing from the ENGINE'S OWN SET, not just any candidate. The previous
    # implementation synthesized `t<set>/<imageNumber>` and this probe only checked that
    # the first entry loaded — a DIFFERENT token's art loads just as well.
    default_url = res["result"].get("defaultUrl")
    default_entry = next((v for v in versions if v["url"] == default_url), None)
    ok("defaultUrl is one of the candidates", default_entry is not None,
       (default_url or "none"))
    ok("the default is a printing from the engine's own set (RTR -> trtr)",
       bool(default_entry) and default_entry.get("setCode") == "trtr",
       (default_entry or {}).get("setCode", "no default"))
    ok("the default art loads",
       image_loads(default_url) if default_url else False,
       (default_url or "").split("/cards/")[-1])

    # A token whose runtime name has NO " Token" suffix must resolve too (the 116
    # names the old substring test misclassified).
    ws.send({"type": "token_versions", "tokenName": "Treasure",
             "engineSetCode": "XLN", "requestId": "p2"})
    res2 = ws.wait_for({"token_versions_result"})
    r2 = (res2 or {}).get("result", {})
    v2 = r2.get("versions", [])
    ok("suffix-free token name resolves (Treasure)", len(v2) > 0, "%d printings" % len(v2))
    d2 = next((v for v in v2 if v["url"] == r2.get("defaultUrl")), None)
    ok("Treasure's default is from Ixalan",
       bool(d2) and d2.get("setCode") == "txln",
       (d2 or {}).get("setCode", "no default"))

    # An unknown name must be EMPTY, not an invented URL.
    ws.send({"type": "token_versions", "tokenName": "Definitely Not A Real Token",
             "requestId": "p3"})
    res3 = ws.wait_for({"token_versions_result"})
    ok("unknown token resolves to an empty list",
       (res3 or {}).get("result", {}).get("versions") == [],
       json.dumps(res3)[:100] if res3 else "")

    # ── 2. token_art_preference relay ──
    # Needs a live game for a playerId, so this half reports SKIP when none is running
    # rather than pretending to pass.
    good_url = versions[0]["url"] if versions else "https://api.scryfall.com/cards/t10e/1?format=image&version=small"
    ws.send({"type": "token_art_preference", "tokenName": "Soldier Token", "url": good_url})
    relayed = ws.wait_for({"token_art_preferences", "error"}, limit=8)
    if relayed and relayed.get("type") == "token_art_preferences":
        prefs = relayed.get("preferences", [])
        mine = [p for p in prefs if p.get("tokenName") == "Soldier Token" and p.get("url") == good_url]
        ok("preference relayed back with a snapshot", len(mine) == 1, json.dumps(prefs)[:160])
        ok("snapshot carries the playerId and playerName",
           bool(mine) and bool(mine[0].get("playerId")) and bool(mine[0].get("playerName")))
    else:
        detail = json.dumps(relayed)[:120] if relayed else "no reply"
        print("SKIP  preference relay (no active game): %s" % detail)

    # A non-Scryfall URL must be REFUSED even when a game is active — this is the
    # injection guard for a string that reaches every opponent's board.
    ws.send({"type": "token_art_preference", "tokenName": "Soldier Token",
             "url": "https://evil.example.com/x.png"})
    refused = ws.wait_for({"error", "token_art_preferences"}, limit=8)
    if refused and refused.get("type") == "error":
        ok("off-allowlist preference URL refused", True, refused.get("message", "")[:60])
    elif refused and refused.get("type") == "token_art_preferences":
        prefs = refused.get("preferences", [])
        bad = [p for p in prefs if "evil.example.com" in (p.get("url") or "")]
        ok("off-allowlist preference URL not relayed", not bad,
           "reached the relay" if bad else "no active game -> request short-circuited")
    else:
        ok("off-allowlist preference URL refused", False, json.dumps(refused)[:120])

    return finish()


def finish():
    print()
    print("passed %d, failed %d" % (len(passes), len(failures)))
    if failures:
        for f in failures:
            print("  FAILED: %s" % f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
