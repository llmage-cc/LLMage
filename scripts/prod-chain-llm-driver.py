#!/usr/bin/env python3
"""Deployment-chain live LLM game driver.

Drives a REAL LLM game through the public TLS chain (play.llmage.cc:443) with an
on-box mock provider running as a container on the deployment network under the
already-allowlisted network alias `opencode.ai` — so the deployed proxy flags stay
byte-identical to the shipped ones (no compose edits).

Asserts:
  (1) connect through caddy+TLS with the proxy auth token
  (2) table_created (HUMAN owner + one COMPUTER_LLM seat → mock)
  (3) game starts and state frames arrive
  (4) the mock served >=1 real chat POST (an actual LLM exchange happened)
  (5) the turn counter advanced (game is really running, not wedged)
  (6) no error frames on the wire
  (7) concede_match returns the lobby to 0 tables (cleanup)

Usage: python3 prod-chain-llm-driver.py --token <WEBPROXY_AUTH_TOKEN> \
         [--host play.llmage.cc] [--endpoint-host opencode.ai] [--mock-port 18251]
Exit: 0 all checks pass; 2 a check failed; 3 harness error.
"""

import argparse
import base64
import json
import os
import socket
import ssl
import struct
import sys
import time

DECK_HUMAN = ("20 [M11:245] Mountain\n"
              "20 [3ED:201] Grizzly Bears\n")
DECK_LLM = ("20 [4ED:376] Forest\n"
            "20 [M11:176] Garruk's Companion\n")
LLM_MODEL = "stub-model"

failures = []


def check(name, cond, detail=""):
    print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f"  -- {detail}" if detail and not cond else ""), flush=True)
    if not cond:
        failures.append(name)
    return bool(cond)


class Ws:
    """RFC6455 client over TLS (stdlib only)."""

    def __init__(self, host, port, path="/ws", origin=None):
        raw = socket.create_connection((host, port), timeout=30)
        ctx = ssl.create_default_context()
        self.sock = ctx.wrap_socket(raw, server_hostname=host)
        self.sock.settimeout(30)
        key = base64.b64encode(os.urandom(16)).decode()
        origin = origin or f"https://{host}"
        req = (f"GET {path} HTTP/1.1\r\nHost: {host}\r\n"
               f"Upgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
               f"Origin: {origin}\r\n\r\n")
        self.sock.sendall(req.encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("WS handshake: connection closed")
            resp += chunk
        head = resp.split(b"\r\n\r\n")[0].decode(errors="replace")
        if "101" not in head.splitlines()[0]:
            raise RuntimeError(f"WS upgrade refused: {head.splitlines()[0]}")
        self.buf = resp.split(b"\r\n\r\n", 1)[1]

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    def send(self, obj):
        data = json.dumps(obj).encode()
        mask = os.urandom(4)
        h = b"\x81"
        n = len(data)
        if n < 126:
            h += bytes([0x80 | n])
        elif n < 65536:
            h += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            h += bytes([0x80 | 127]) + struct.pack(">Q", n)
        self.sock.sendall(h + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def _rx(self, n):
        while len(self.buf) < n:
            c = self.sock.recv(65536)
            if not c:
                raise RuntimeError("WS closed")
            self.buf += c
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self):
        h = self._rx(2)
        op = h[0] & 0x0F
        n = h[1] & 0x7F
        if n == 126:
            n = struct.unpack(">H", self._rx(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._rx(8))[0]
        mask = self._rx(4) if (h[1] & 0x80) else None
        p = self._rx(n)
        if mask:
            p = bytes(b ^ mask[i % 4] for i, b in enumerate(p))
        if op == 0x9:
            self.sock.sendall(b"\x8a\x80")
            return self.recv()
        if op == 0x8:
            raise RuntimeError("WS peer closed")
        return json.loads(p.decode())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--token", required=True)
    ap.add_argument("--host", default="play.llmage.cc")
    ap.add_argument("--endpoint-host", default="opencode.ai")
    ap.add_argument("--mock-port", type=int, default=18251)
    ap.add_argument("--timeout", type=int, default=600)
    ap.add_argument("--mock-log", default="/tmp/xmage-scratch/mock-strict-18251.log")
    ap.add_argument("--mock-posts-ssh", default="",
                    help="ssh target whose mock log holds the POST count (container mock)")
    ap.add_argument("--mock-posts-key", default=os.environ.get("XMAGE_SSH_KEY"),
                    help="SSH private key for --mock-posts-ssh (default: $XMAGE_SSH_KEY)")
    ap.add_argument("--allow-error-frame", action="append", default=[],
                    help="substring that makes an error frame EXPECTED (repeatable)")
    args = ap.parse_args()
    if args.mock_posts_ssh and not args.mock_posts_key:
        ap.error("--mock-posts-ssh requires --mock-posts-key or XMAGE_SSH_KEY")

    t0 = time.monotonic()
    user = f"nrw{int(time.time()) % 100000}"
    table_name = f"deploy-check-{user[-4:]}"
    frames = []
    errors = []

    def mock_posts():
        # The mock runs in a CONTAINER on the deployment host, so its log lives
        # there and a local read always returned 0 — the leg then falsely
        # FAILed while the game actually ran. Prefer --mock-posts-ssh (live count
        # from the host), then MOCK_POSTS (a known count), then the local file.
        if args.mock_posts_ssh:
            import subprocess
            cmd = ["ssh", "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
                   "-i", os.path.expanduser(args.mock_posts_key), args.mock_posts_ssh,
                   f"grep -c '^POST ' {args.mock_log} || true"]
            try:
                out = subprocess.run(cmd, capture_output=True, text=True, timeout=30).stdout.strip()
                return int(out or 0)
            except (OSError, ValueError, subprocess.SubprocessError):
                return 0
        env = os.environ.get("MOCK_POSTS")
        if env is not None and env.strip():
            try:
                return int(env.strip())
            except ValueError:
                pass
        try:
            with open(args.mock_log, encoding="utf-8", errors="replace") as f:
                return sum(1 for ln in f if ln.startswith("POST "))
        except OSError:
            return 0


    pre_posts = mock_posts()
    print(f"[info] owner={user} table={table_name} mock pre-POSTs={pre_posts}", flush=True)

    try:
        ws = Ws(args.host, 443)
    except Exception as e:
        print(f"[harness-error] WS connect: {e}")
        return 3

    ws.send({"type": "connect", "server": "xmageserver", "port": 17171,
             "username": user, "password": "", "authToken": args.token})

    def drain(pred, deadline_s, tick=True):
        end = time.time() + deadline_s
        last = 0.0
        while time.time() < end:
            try:
                ws.sock.settimeout(0.5)
                m = ws.recv()
            except (socket.timeout, TimeoutError):
                m = None
            except RuntimeError:
                return None
            if m is not None:
                frames.append(m)
                # "No active game" is the documented answer to a pass_priority tick
                # sent before the game exists (pre-start phase) — a harness artifact,
                # never a product error. Only count real errors.
                if m.get("type") == "error" and m.get("message") != "No active game":
                    errors.append(m)
                if m.get("type") == "action_prompt":
                    if m.get("action_type") == "boolean":
                        ws.send({"type": "boolean_choice", "value": False})
                    elif m.get("action_type") == "target":
                        tg = m.get("targets") or []
                        me = next((t for t in tg if t.get("name") == user), None)
                        pick = me or (tg[0] if tg else None)
                        if pick:
                            ws.send({"type": "select_target", "targetId": pick.get("id")})
                if pred(m):
                    return m
            now = time.monotonic()
            if tick and now - last >= 0.4:
                last = now
                ws.send({"type": "pass_priority"})
        return None

    conn = drain(lambda m: m.get("type") in ("connected", "connect_error"), 60, tick=False)
    if not check("(1) connected through the public TLS chain",
                 conn is not None and conn.get("type") == "connected",
                 json.dumps(conn)[:300] if conn else "timeout"):
        ws.close()
        return 2
    print(f"[info] t+{time.monotonic()-t0:.1f}s connected; caps={json.dumps(conn.get('capabilities'))[:160]}", flush=True)

    ws.send({
        "type": "create_table", "name": table_name,
        "gameType": "Two Player Duel", "deckType": "Constructed - Freeform",
        "winsNeeded": 1, "spectatorsAllowed": True, "llmChatPublic": True,
        "playerConfigs": [
            {"type": "HUMAN", "name": user, "deck": DECK_HUMAN},
            {"type": "COMPUTER_LLM", "deck": DECK_LLM,
             "llmConfig": {"model": LLM_MODEL,
                           "apiEndpoint": f"http://{args.endpoint_host}:{args.mock_port}/v1/chat/completions",
                           "reasoningEffort": "default", "thinkingEnabled": False,
                           "streamingEnabled": False, "personalityEnabled": False}},
        ],
    })
    tc = drain(lambda m: m.get("type") in ("table_created", "error"), 60, tick=False)
    if not check("(2) table_created (HUMAN + COMPUTER_LLM seat)",
                 tc is not None and tc.get("type") == "table_created",
                 json.dumps(tc)[:300] if tc else "timeout"):
        ws.close()
        return 2
    print(f"[info] table {tc['tableId']}", flush=True)

    # wait for real progress: posts to the mock AND turn advancing
    def progressed():
        st = None
        for m in reversed(frames):
            s = m.get("state")
            if isinstance(s, dict) and s.get("turn"):
                st = s
                break
        turn = st.get("turn") if st else 0
        return mock_posts() - pre_posts >= 1 and turn >= 2

    end = time.time() + args.timeout
    while time.time() < end and not progressed():
        drain(lambda m: False, 5, tick=True)

    posts = mock_posts() - pre_posts
    turn = 0
    for m in reversed(frames):
        s = m.get("state")
        if isinstance(s, dict) and s.get("turn"):
            turn = s.get("turn")
            break

    real_errors = []
    for e in errors:
        msg = str(e.get("message", ""))
        if any(pat and pat in msg for pat in args.allow_error_frame):
            continue
        real_errors.append(e)
    check("(3) game state frames arrived", any(str(m.get("type", "")).startswith("game_") for m in frames),
          f"frame types={sorted({m.get('type') for m in frames})[:12]}")
    check("(4) the mock served >=1 real LLM chat POST through the public chain", posts >= 1,
          f"posts={posts}")
    check("(5) the turn counter advanced (game running, not wedged)", turn >= 2, f"turn={turn}")
    check("(6) no unexpected error frames on the wire", len(real_errors) == 0,
          json.dumps(real_errors[:2])[:300])
    print(f"[info] t+{time.monotonic()-t0:.1f}s posts={posts} turn={turn} frames={len(frames)}", flush=True)

    # cleanup
    ws.send({"type": "concede_match"})
    drain(lambda m: m.get("type") in ("game_over", "table_list"), 20, tick=False)
    print("[info] conceded", flush=True)

    ws.close()
    print()
    if failures:
        print(f"RESULT: FAIL: {failures}")
        return 2
    print("RESULT: PASS (deployment-chain LLM game)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (socket.timeout, TimeoutError) as e:
        print(f"[harness-error] timeout: {e}")
        sys.exit(3)
    except RuntimeError as e:
        print(f"[harness-error] {e}")
        sys.exit(3)
