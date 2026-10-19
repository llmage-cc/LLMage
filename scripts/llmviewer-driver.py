#!/usr/bin/env python3
"""Replay-viewer driver: play a 2-LLM-seat Bo3 through the web proxy and leave
the saved .game files for the replay-viewer legs (T1/T2/T3).

Shape:
  1. A NON-SEATED owner creates a Bo3 table with TWO COMPUTER_LLM seats, both
     pointed at the committed strict mock (scripts/mock-llm-sideboard.py), with
     spectatorsAllowed=true + llmChatPublic=true so the replay frames carry the
     llmDebug/llm buckets UNFILTERED (GameStateSerializer hides other players'
     chat unless llmChatPublic — a spectator sees both buckets only with it set).
  2. The proxy auto-joins both AI seats and auto-starts (hasAI && allJoined).
  3. The driver waits for game 1 game_over, the game-2 start_game, game 2
     game_over, then for both `saved/<uuid>.game` files.
  4. Evidence: raw frames (jsonl), a summary JSON, per-game llmDebug snapshots
     from the LIVE wire (for the T2 replay/live parity check), and server-log
     greps.

Deck: deliberately small (deck-out ends each game in ~10 turns) and carries an
SB: line so the LLM seat's sideboard() path writes its summary.

Env: MOCK_STALL_REFLECTION_SECONDS is honoured by the mock (T3 leg); this driver
passes it through untouched.

Usage:
  python3 llmviewer-driver.py --leg t1t2 [--stall 0] [--timeout 2400]
Exit: 0 both games finished + both saves present; 2 a check failed; 3 harness error.
"""

import argparse
import base64
import glob
import json
import os
import socket
import struct
import sys
import time

DECK_A = ("20 [M11:245] Mountain\n"
          "20 [3ED:201] Grizzly Bears\n"
          "SB: 2 [M11:245] Mountain\n")
DECK_B = ("20 [4ED:376] Forest\n"
          "20 [M11:176] Garruk's Companion\n"
          "SB: 2 [4ED:376] Forest\n")

failures = []


def check(name, cond, detail=""):
    tag = "PASS" if cond else "FAIL"
    print(f"[{tag}] {name}" + (f"  -- {detail}" if detail and not cond else ""), flush=True)
    if not cond:
        failures.append(name)
    return cond


def info(msg):
    print(f"[info] {msg}", flush=True)


class Ws:
    """Minimal RFC6455 client (stdlib only), same shape as the committed
    verify-*.py drivers."""

    def __init__(self, host, port, path="/ws"):
        self.sock = socket.create_connection((host, port), timeout=30)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\n"
               f"Upgrade: websocket\r\nConnection: Upgrade\r\n"
               f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
               f"Origin: http://{host}:{port}\r\n\r\n")
        self.sock.sendall(req.encode())
        resp = b""
        while b"\r\n\r\n" not in resp:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("WS handshake: connection closed")
            resp += chunk
        head = resp.split(b"\r\n\r\n")[0].decode()
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
        header = b"\x81"
        n = len(data)
        if n < 126:
            header += bytes([0x80 | n])
        elif n < 65536:
            header += bytes([0x80 | 126]) + struct.pack(">H", n)
        else:
            header += bytes([0x80 | 127]) + struct.pack(">Q", n)
        self.sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def _read_exact(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("WS closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self):
        h = self._read_exact(2)
        fin_op = h[0]
        masked = h[1] & 0x80
        n = h[1] & 0x7F
        if n == 126:
            n = struct.unpack(">H", self._read_exact(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._read_exact(8))[0]
        mask = self._read_exact(4) if masked else None
        payload = self._read_exact(n)
        if mask:
            payload = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        op = fin_op & 0x0F
        if op == 0x9:  # ping -> pong
            self.sock.sendall(b"\x8a\x80")
            return self.recv()
        if op == 0x8:
            raise RuntimeError("WS closed by peer")
        return json.loads(payload.decode())


class Client:
    def __init__(self, host, port, xmage_port, user, t0, out):
        self.user = user
        self.t0 = t0
        self.frames = []
        self.out = out
        self.ws = Ws(host, port)
        self.ws.send({"type": "connect", "server": host, "port": xmage_port,
                      "username": user, "password": ""})
        msg = self.wait(lambda m: m.get("type") in ("connected", "connect_error"), 30)
        if msg is None:
            raise RuntimeError(f"{user}: no connected frame within 30s")
        if msg["type"] == "connect_error":
            raise RuntimeError(f"{user}: connect_error: {msg.get('message')}")
        info(f"t+{time.monotonic() - t0:7.3f}s {user} connected")

    def wait(self, pred, timeout):
        end = time.time() + timeout
        while time.time() < end:
            try:
                self.ws.sock.settimeout(max(0.5, end - time.time()))
                msg = self.ws.recv()
            except (socket.timeout, TimeoutError):
                continue
            self.record(msg)
            if pred(msg):
                return msg
        return None

    def record(self, msg):
        self.frames.append((time.monotonic(), msg))
        if self.out:
            with open(self.out, "a", encoding="utf-8") as f:
                f.write(json.dumps({"t": round(time.monotonic() - self.t0, 3),
                                    "msg": msg}) + "\n")

    def drain_until(self, stop_pred, deadline_s, tick=lambda ws: None, tick_ms=400):
        """Read frames until stop_pred(msg) is truthy or the deadline passes.
        tick() runs at most every tick_ms (used to keep priority moving)."""
        end = time.time() + deadline_s
        last_tick = 0.0
        while time.time() < end:
            try:
                self.ws.sock.settimeout(0.5)
                msg = self.ws.recv()
            except (socket.timeout, TimeoutError):
                msg = None
            except RuntimeError:
                break
            if msg is not None:
                self.record(msg)
                res = stop_pred(msg)
                if res:
                    return msg
            now = time.monotonic()
            if (now - last_tick) * 1000 >= tick_ms:
                last_tick = now
                tick(self.ws)
        return None

    def close(self):
        self.ws.close()


def saved_dir_glob(stage):
    return os.path.join(stage, "server", "saved")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--leg", default="t1t2")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--proxy", type=int, default=8181)
    ap.add_argument("--xmage", type=int, default=17281)
    ap.add_argument("--stage", default="/tmp/llmviewer-stage")
    ap.add_argument("--timeout", type=int, default=2400)
    args = ap.parse_args()

    t0 = time.monotonic()
    ev = os.path.join(args.stage, "evidence", args.leg)
    os.makedirs(ev, exist_ok=True)
    frames_path = os.path.join(ev, "wire-frames.jsonl")
    if os.path.exists(frames_path):
        os.remove(frames_path)

    owner_user = f"llmvw{int(time.time()) % 10000}"

    info(f"=== replay-viewer driver leg={args.leg} proxy={args.host}:{args.proxy} "
         f"xmage={args.host}:{args.xmage} ===")
    info(f"owner(spectator)={owner_user}  evidence={ev}  stall={os.environ.get('MOCK_STALL_REFLECTION_STALL', 'n/a')}")

    try:
        owner = Client(args.host, args.proxy, args.xmage, owner_user, t0, frames_path)
    except RuntimeError as e:
        info(f"[harness-error] owner connect: {e}")
        return 3

    # ── (1) create Bo3 with TWO LLM seats; the creator is NOT seated ──
    owner.ws.send({
        "type": "create_table",
        "name": f"llmvw-{args.leg}",
        "gameType": "Two Player Duel",
        "deckType": "Constructed - Freeform",
        "winsNeeded": 2,
        "spectatorsAllowed": True,
        "llmChatPublic": True,
        "playerConfigs": [
            {"type": "COMPUTER_LLM", "name": "llm-a", "deck": DECK_A,
             "llmConfig": {"model": "stub-model",
                           "apiEndpoint": "http://127.0.0.1:18281/v1/chat/completions",
                           "reasoningEffort": "default", "thinkingEnabled": False,
                           "streamingEnabled": False, "personalityEnabled": False}},
            {"type": "COMPUTER_LLM", "name": "llm-b", "deck": DECK_B,
             "llmConfig": {"model": "stub-model",
                           "apiEndpoint": "http://127.0.0.1:18283/v1/chat/completions",
                           "reasoningEffort": "default", "thinkingEnabled": False,
                           "streamingEnabled": False, "personalityEnabled": False}},
        ],
    })
    msg = owner.wait(lambda m: m.get("type") in ("table_created", "error"), 60)
    if not check("(1) table_created received",
                 msg is not None and msg.get("type") == "table_created",
                 json.dumps(msg)[:400] if msg else "timeout"):
        owner.close()
        return 2
    table_id = msg["tableId"]
    info(f"t+{time.monotonic() - t0:7.3f}s table id: {table_id}")

    # ── (2) game 1 runs; a NON-SEATED watcher receives `game_init`, never
    # `start_game`, so drive off the wire's game_over + the saved file instead
    # of a start signal the proxy does not send to spectators.
    sd = saved_dir_glob(args.stage)
    before = set(os.listdir(sd)) if os.path.isdir(sd) else set()

    def new_saves():
        if not os.path.isdir(sd):
            return []
        return [f for f in sorted(set(os.listdir(sd)) - before) if f.endswith(".game")]

    def stop_over1(m):
        if m.get("type") == "game_over":
            return True
        st = m.get("state")
        if isinstance(st, dict) and st.get("llmDebug"):
            live1["state"] = st
        return False

    # ── (3) game 1 runs to completion; capture the LAST live llmDebug state ──
    live1 = {}

    over1 = owner.drain_until(stop_over1, args.timeout)
    if not check("(2) game 1 finished (game_over)", over1 is not None,
                 f"timed out after {args.timeout}s"):
        for _, m in owner.frames:
            if m.get("type") == "error":
                info(f"error frame: {json.dumps(m)[:300]}")
        owner.close()
        return 2
    info(f"t+{time.monotonic() - t0:7.3f}s game 1 game_over")

    # Resolve game 1's id + save from the new files on disk.
    g1 = None
    want = {}
    deadline = time.time() + 180
    while time.time() < deadline and g1 is None:
        ns = new_saves()
        if ns:
            g1 = ns[0][:-len(".game")]
            want[g1] = os.path.join(sd, ns[0])
        time.sleep(1)
    if not check("(2b) game-1 save written", g1 is not None, f"nothing new in {sd}"):
        owner.close()
        return 2
    info(f"t+{time.monotonic() - t0:7.3f}s game 1 id={g1}")
    if live1.get("state"):
        with open(os.path.join(ev, "live-game1-llmdebug.json"), "w", encoding="utf-8") as f:
            json.dump(live1["state"], f, indent=2)

    # ── (4) game 2 starts (sideboarding happens automatically for LLM seats)
    # and runs to completion ──
    live2 = {}
    seen_buckets = {"yes": False}

    def stop_over2(m):
        st = m.get("state")
        if isinstance(st, dict):
            if st.get("llmDebug"):
                live2["state"] = st
            if st.get("replayLLMBuckets"):
                seen_buckets["yes"] = True
        return m.get("type") == "game_over"

    over2 = owner.drain_until(stop_over2, args.timeout)
    if not check("(4) game 2 finished (game_over / match end)", over2 is not None,
                 f"timed out after {args.timeout}s"):
        owner.close()
        return 2
    info(f"t+{time.monotonic() - t0:7.3f}s game 2 game_over")
    if live2.get("state"):
        with open(os.path.join(ev, "live-game2-llmdebug.json"), "w", encoding="utf-8") as f:
            json.dump(live2["state"], f, indent=2)

    g2 = None
    deadline = time.time() + 180
    while time.time() < deadline:
        ns = new_saves()
        got = [f[:-len(".game")] for f in ns if f[:-len(".game")] != g1]
        if got:
            g2 = got[0]
            want[g2] = os.path.join(sd, f"{g2}.game")
            break
        time.sleep(1)
    check("(4b) game 2 has a distinct gameId", g2 is not None and g2 != g1,
          f"g1={g1} g2={g2}")
    if g2 is None:
        owner.close()
        return 2
    info(f"t+{time.monotonic() - t0:7.3f}s game 2 id={g2}")

    # ── (6) both .game saves present in the server's saved/ dir ──
    want.setdefault(g1, None)
    want.setdefault(g2, None)
    deadline = time.time() + 180
    while time.time() < deadline and any(v is None for v in want.values()):
        for g in list(want):
            if want[g] is not None:
                continue
            p = os.path.join(sd, f"{g}.game")
            if os.path.isfile(p) and os.path.getsize(p) > 0:
                want[g] = p
        time.sleep(1)
    check(f"(6) game-1 save written ({g1[:8]})", want[g1] is not None,
          f"no {sd}/{g1}.game")
    check("(6b) game-2 save written", want[g2] is not None,
          f"no {sd}/{g2}.game")
    for g, p in want.items():
        if p:
            info(f"save {g[:8]}: {p} ({os.path.getsize(p)} bytes)")

    summary = {
        "leg": args.leg,
        "owner_user": owner_user,
        "table_id": table_id,
        "game1": g1,
        "game2": g2,
        "game1_save": want.get(g1),
        "game2_save": want.get(g2),
        "game1_save_bytes": os.path.getsize(want[g1]) if want.get(g1) else None,
        "game2_save_bytes": os.path.getsize(want[g2]) if want.get(g2) else None,
        "replay_buckets_seen_on_wire": seen_buckets["yes"],
        "elapsed_s": round(time.monotonic() - t0, 1),
    }
    with open(os.path.join(ev, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)

    owner.close()
    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)}): " + "; ".join(failures))
        return 2
    print(f"RESULT: PASS — {json.dumps(summary)}")
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
