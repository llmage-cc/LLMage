#!/usr/bin/env python3
"""T4 — format-correction summary.

A hostile mock answers the FIRST attempt at each in-game decision in a shape
the engine's format validator rejects (a prose line instead of the bare
answer). The engine re-requests with a correction prompt; the mock answers the
correction properly. The stored compact summary for that correction exchange
must read:

    Retry of the <promptType> decision — previous answer was not in the
    required format (<hint>).

and the literal placeholder `format correction` must NOT appear anywhere in the
replay-LLM data (the string is what the webui/transcripts read).

The engine also writes a per-game LLM transcript; that file carries the FULL
user prompt, never the compact summary, so the summary is asserted on the
REPLAY data (the same data the webui LLM panel renders) via
llmviewer_replay_probe.

Usage: python3 llmviewer-t4-driver.py [--timeout 900]
Exit: 0 pass; 2 a check failed; 3 harness error.
"""

import argparse
import base64
import glob
import json
import os
import socket
import struct
import subprocess
import sys
import time

DECK_A = ("20 [M11:245] Mountain\n"
          "20 [3ED:201] Grizzly Bears\n"
          "SB: 2 [M11:245] Mountain\n")
DECK_B = ("20 [4ED:376] Forest\n"
          "20 [M11:176] Garruk's Companion\n"
          "SB: 2 [4ED:376] Forest\n")

PLACEHOLDER = "format correction"
DESCRIPTIVE = "Retry of the "


failures = []


def check(name, cond, detail=""):
    print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f"  -- {detail}" if detail and not cond else ""), flush=True)
    if not cond:
        failures.append(name)
    return bool(cond)


class Ws:
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
                raise RuntimeError("WS handshake closed")
            resp += chunk
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
        masked = h[1] & 0x80
        n = h[1] & 0x7F
        if n == 126:
            n = struct.unpack(">H", self._rx(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._rx(8))[0]
        mask = self._rx(4) if masked else None
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
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--proxy", type=int, default=8191)
    ap.add_argument("--xmage", type=int, default=17291)
    ap.add_argument("--stage", default="/tmp/llmviewer-t4")
    ap.add_argument("--timeout", type=int, default=900)
    args = ap.parse_args()

    t0 = time.monotonic()
    ev = os.path.join(args.stage, "evidence")
    os.makedirs(ev, exist_ok=True)
    frames_path = os.path.join(ev, "wire-frames.jsonl")
    if os.path.exists(frames_path):
        os.remove(frames_path)

    ws = Ws(args.host, args.proxy)
    user = f"llmt4{int(time.time()) % 10000}"
    ws.send({"type": "connect", "server": args.host, "port": args.xmage,
             "username": user, "password": ""})

    def drain(pred, deadline_s, tick_ms=400, tick=False):
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
                with open(frames_path, "a") as f:
                    f.write(json.dumps(m) + "\n")
                # Autopilot the engine's boolean asks (mulligan keep etc.) —
                # without it the opening blocks and the LLM seats never move.
                if m.get("type") == "action_prompt":
                    if m.get("action_type") == "boolean":
                        ws.send({"type": "boolean_choice", "value": False})
                    elif m.get("action_type") == "target":
                        tg = m.get("targets") or []
                        if tg:
                            ws.send({"type": "select_target", "targetId": tg[0].get("id")})
                if pred(m):
                    return m
            now = time.monotonic()
            # Only keep priority moving once a game is actually running — a
            # pass_priority before that answers "No active game", which would
            # otherwise trip a stop predicate keyed on error frames.
            if tick and now - last >= tick_ms / 1000:
                last = now
                ws.send({"type": "pass_priority"})
        return None

    conn = drain(lambda m: m.get("type") in ("connected", "connect_error"), 120)
    if conn is None or conn.get("type") != "connected":
        print(f"[harness-error] connect: {conn}")
        return 3
    print(f"[info] connected as {user}")

    ws.send({
        "type": "create_table", "name": f"llmt4-{user[-4:]}",
        "gameType": "Two Player Duel", "deckType": "Constructed - Freeform",
        "winsNeeded": 1, "spectatorsAllowed": True, "llmChatPublic": True,
        "playerConfigs": [
            {"type": "COMPUTER_LLM", "name": "t4-a", "deck": DECK_A,
             "llmConfig": {"model": "stub-model",
                           "apiEndpoint": "http://127.0.0.1:18301/v1/chat/completions",
                           "reasoningEffort": "default", "thinkingEnabled": False,
                           "streamingEnabled": False, "personalityEnabled": False}},
            {"type": "COMPUTER_LLM", "name": "t4-b", "deck": DECK_B,
             "llmConfig": {"model": "stub-model",
                           "apiEndpoint": "http://127.0.0.1:18303/v1/chat/completions",
                           "reasoningEffort": "default", "thinkingEnabled": False,
                           "streamingEnabled": False, "personalityEnabled": False}},
        ],
    })
    tc = drain(lambda m: m.get("type") in ("table_created", "error"), 60)  # no tick: table phase
    if not check("(1) table_created", tc is not None and tc.get("type") == "table_created",
                 json.dumps(tc)[:300] if tc else "timeout"):
        return 2
    print(f"[info] table {tc['tableId']}")

    # single game; keep priority moving so the decision loop runs
    over = drain(lambda m: m.get("type") == "game_over", args.timeout, tick=True)
    if not check("(2) game finished (game_over)", over is not None, "timeout"):
        return 2
    print(f"[info] t+{time.monotonic() - t0:.1f}s game_over")

    # the save is written right after game end
    sd = os.path.join(args.stage, "server", "saved")
    games = []
    deadline = time.time() + 120
    while time.time() < deadline:
        games = sorted(glob.glob(os.path.join(sd, "*.game")), key=os.path.getmtime)
        if games:
            break
        time.sleep(1)
    if not check("(3) a .game save was written", bool(games), f"none in {sd}"):
        return 2
    gid = os.path.basename(games[-1])[:-len(".game")]
    print(f"[info] save {gid}")

    # ── probe the replay data for the correction summary ──
    # The probe prints one line per stored record: "rec type=<t> summary=<s>".
    # Assert on THOSE lines — the earlier version grepped the whole dump, which
    # also contains the full prompts and passed spuriously.
    probe_cp = f"{args.stage}/server/lib/*:{args.stage}/server/plugins/*:/tmp/llmviewer-probe"
    r = subprocess.run(
        ["java", "-Xmx3g",
         "--add-opens", "java.base/java.lang=ALL-UNNAMED",
         "--add-opens", "java.base/java.util=ALL-UNNAMED",
         "--add-opens", "java.base/java.io=ALL-UNNAMED",
         "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
         "-cp", probe_cp, "llmviewer_replay_probe", gid],
        cwd=f"{args.stage}/server", capture_output=True, text=True, timeout=300)
    out = r.stdout + r.stderr
    with open(os.path.join(ev, "t4-probe.txt"), "w") as f:
        f.write(out)
    print(out)

    corrected, literal_sum, descriptive_sum = 0, 0, 0
    for ln in out.splitlines():
        ln = ln.strip()
        if not ln.startswith("rec type="):
            continue
        if not ln.split("summary=", 1)[0].strip().endswith("-corrected"):
            continue
        corrected += 1
        summary = ln.split("summary=", 1)[1]
        if summary.strip() == PLACEHOLDER:
            literal_sum += 1
        if summary.startswith(DESCRIPTIVE):
            descriptive_sum += 1
    with open(os.path.join(ev, "t4-summary-verdict.json"), "w") as f:
        json.dump({"corrected_records": corrected,
                   "literal_summaries": literal_sum,
                   "descriptive_summaries": descriptive_sum}, f, indent=2)
    print(f"[info] corrected records={corrected} literal={literal_sum} descriptive={descriptive_sum}")

    check("(4) format-correction records exist to assert on", corrected >= 1,
          f"corrected records: {corrected}")
    check("(5) no corrected record stores the literal 'format correction'",
          literal_sum == 0, f"{literal_sum}/{corrected} still literal")
    check("(6) every corrected record stores the descriptive summary",
          descriptive_sum == corrected,
          f"{descriptive_sum}/{corrected} descriptive")

    # Also assert at the mock level: at least one correction request was served.
    mock_log = f"/tmp/xmage-scratch/mock-strict-18301.log"
    corr = 0
    try:
        with open(mock_log) as f:
            corr = sum(1 for ln in f if "is_correction=yes" in ln)
    except OSError:
        pass
    check("(6) the engine issued >= 1 format-correction request", corr >= 1,
          f"correction requests seen: {corr}")

    ws.close()
    print()
    print("RESULT: PASS (T4)" if not failures else f"RESULT: FAIL: {failures}")
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
