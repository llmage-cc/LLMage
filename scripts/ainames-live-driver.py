#!/usr/bin/env python3
"""Live driver: web-launched LLM seats get generated names + personality.

Shape (the REAL web path, no explicit seat name):
  1. owner creates a table via the proxy with playerConfigs
     [HUMAN(owner), COMPUTER_LLM seat WITHOUT "name" + llmConfig→strict mock].
     The proxy derives the seat name "AI-<index>" (here: AI-1) — the exact
     shape deployed games carried ("You are playing as: AI-0").
  2. Proxy auto-joins + auto-starts.
  3. Assertions:
     (1) start_game received
     (2) a state push carries the LLM seat renamed to stub-model-<Personality>
         (and NO seat named AI-1)                       [generated-name leg]
         — or, for --expect base, the seat still named AI-1
     (3) transcript file on disk: '<generated>_stub-model_transcript-*.txt'
         (fixed) / 'AI-1_stub-model_transcript-*.txt' (base)
     (4) server log: 'Auto-generated bot name: stub-model-' present (fixed)
         / 'Preserving existing bot name: AI-1' present (base)
     (5) the strict mock served >=1 chat POST — a real LLM exchange happened
         (the human autopilot answers the mulligan ask, unblocking the engine)
     (6) transcript system prompt carries '=== YOUR PERSONALITY ===' (fixed)
         / has none (base) — the section is keyed off the generated name.

Usage: python3 ainames-live-driver.py --expect fixed|base [--host ..]
       [--proxy 8141] [--xmage 17241] [--gamelogs DIR] [--server-log FILE]
       [--mock-log FILE]
Exit: 0 expected shape observed; 2 unexpected; 3 harness error.
"""

import argparse
import base64
import glob
import json
import os
import re
import socket
import struct
import sys
import time

LLM_SEAT_NAME_DERIVED = "AI-1"          # proxy: "AI-" + i for the unnamed AI seat at index 1
LLM_MODEL = "stub-model"
LLM_ENDPOINT = "http://127.0.0.1:18241/v1/chat/completions"

DECK_LLM = ("20 [M11:245] Mountain\n"
            "20 [3ED:201] Grizzly Bears\n"
            "SB: 2 [M11:245] Mountain\n")
DECK_HUMAN = ("20 [M11:245] Mountain\n"
              "20 [3ED:201] Grizzly Bears\n")

failures = []


def check(name, cond, detail=""):
    tag = "PASS" if cond else "FAIL"
    print(f"[{tag}] {name}" + (f"  -- {detail}" if detail and not cond else ""), flush=True)
    if not cond:
        failures.append(name)
    return cond


def stamp(t0):
    return f"t+{time.monotonic() - t0:7.3f}s"


class Ws:
    """Minimal RFC6455 client (stdlib only) — same shape as the committed
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
    def __init__(self, host, port, xmage_port, user, t0):
        self.user = user
        self.t0 = t0
        self.frames = []
        self.ws = Ws(host, port)
        self.ws.send({"type": "connect", "server": host, "port": xmage_port,
                      "username": user, "password": ""})
        msg = self.wait(lambda m: m.get("type") in ("connected", "connect_error"), 30)
        if msg is None:
            raise RuntimeError(f"{user}: no connected frame within 30s")
        if msg["type"] == "connect_error":
            raise RuntimeError(f"{user}: connect_error: {msg.get('message')}")
        print(f"[info] {stamp(t0)} {self.user} connected", flush=True)

    def wait(self, pred, timeout):
        end = time.time() + timeout
        while time.time() < end:
            try:
                self.ws.sock.settimeout(max(0.5, end - time.time()))
                msg = self.ws.recv()
            except (socket.timeout, TimeoutError):
                continue
            self.frames.append((time.monotonic(), msg))
            if pred(msg):
                return msg
        return None

    def wait_type(self, types, timeout, note=None):
        if isinstance(types, str):
            types = (types,)
        end = time.time() + timeout
        while time.time() < end:
            try:
                self.ws.sock.settimeout(max(0.5, end - time.time()))
                msg = self.ws.recv()
            except (socket.timeout, TimeoutError):
                continue
            self.frames.append((time.monotonic(), msg))
            self.autopilot(msg)
            if msg.get("type") in types:
                if note:
                    print(f"[info] {stamp(self.t0)} {self.user} <- "
                          f"{json.dumps(msg)[:200]}   ({note})", flush=True)
                return msg
        return None

    def autopilot(self, msg):
        """Answer benign engine prompts (mulligan keep / pick starting player)
        so play proceeds to the LLM seat's own decisions — without this the
        game blocks on the human's mulligan ask and the LLM never calls out.
        Mirrors verify-llm-sideboard-live.py's automations."""
        if msg.get("type") != "action_prompt":
            return False
        at = msg.get("action_type")
        if at == "boolean":
            self.ws.send({"type": "boolean_choice", "value": False})  # Keep hand
            return True
        if at == "target":
            targets = msg.get("targets") or []
            me = next((t for t in targets if t.get("name") == self.user), None)
            pick = me or (targets[0] if targets else None)
            if pick is not None:
                self.ws.send({"type": "select_target", "targetId": pick.get("id")})
                return True
        return False

    def drain(self, seconds=1.0):
        end = time.time() + seconds
        while time.time() < end:
            try:
                self.ws.sock.settimeout(max(0.2, end - time.time()))
                msg = self.ws.recv()
            except (socket.timeout, TimeoutError):
                continue
            self.frames.append((time.monotonic(), msg))
            self.autopilot(msg)

    def close(self):
        self.ws.close()


def mock_post_count(path):
    """Number of chat POSTs the mock has served (its log lines start 'POST ')."""
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return sum(1 for ln in f if ln.startswith("POST "))
    except OSError:
        return 0


def mock_rejected_count(path):
    """Chat POSTs the strict mock answered with 400 (a 'thinking' field sent)."""
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return sum(1 for ln in f if "REJECTED-400" in ln)
    except OSError:
        return 0


def read_text(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def seat_names_from_frames(frames):
    """Union of per-player names across game state frames (game_init /
    game_update / game_delta). Table-level frames can carry a string "state"
    (or per-row maps), so only dict-shaped game states are inspected."""
    names = set()
    for _, m in frames:
        if not str(m.get("type", "")).startswith("game_"):
            continue
        st = m.get("state")
        if not isinstance(st, dict):
            continue
        for p in st.get("players") or []:
            if isinstance(p, dict) and p.get("name"):
                names.add(p.get("name"))
    return names


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--expect", choices=("fixed", "base"), required=True)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--proxy", type=int, default=8141)
    ap.add_argument("--xmage", type=int, default=17241)
    ap.add_argument("--gamelogs", default="/tmp/ainames-stage/gamelogs")
    ap.add_argument("--server-log", default="/tmp/ainames-stage/server.log")
    ap.add_argument("--mock-log", default="/tmp/xmage-scratch/mock-strict-18241.log")
    ap.add_argument("--tag", default=str(int(time.time())))
    args = ap.parse_args()

    t0 = time.monotonic()
    tail = args.tag[-4:]
    owner_user = f"llmo{tail}"
    table_name = f"ainames-{tail}"

    print(f"=== AI-SEAT-NAME LIVE DRIVER ({args.expect})  proxy={args.host}:{args.proxy} "
          f"xmage={args.host}:{args.xmage} ===", flush=True)

    # snapshot transcript files and mock state BEFORE the table exists
    pre_tx = set(glob.glob(os.path.join(args.gamelogs, "game_*", "*_transcript-*.txt")))
    pre_mock = mock_post_count(args.mock_log)
    pre_mock_rej = mock_rejected_count(args.mock_log)
    print(f"[info] pre-existing transcript files: {len(pre_tx)}, "
          f"mock POSTs: {pre_mock} (rejected: {pre_mock_rej})", flush=True)

    try:
        owner = Client(args.host, args.proxy, args.xmage, owner_user, t0)
    except RuntimeError as e:
        print(f"[harness-error] owner connect: {e}")
        return 3

    # ── (1) create table: LLM seat WITHOUT a name (proxy derives AI-<i>) ──
    owner.ws.send({
        "type": "create_table",
        "name": table_name,
        "gameType": "Two Player Duel",
        "deckType": "Constructed - Freeform",
        "winsNeeded": 2,
        "playerConfigs": [
            {"type": "HUMAN", "name": owner_user, "deck": DECK_HUMAN},
            {"type": "COMPUTER_LLM", "deck": DECK_LLM,
             "llmConfig": {
                 "model": LLM_MODEL,
                 "apiEndpoint": LLM_ENDPOINT,
                 "reasoningEffort": "default",
                 "thinkingEnabled": False,
                 "streamingEnabled": False,
             }},
        ],
    })
    msg = owner.wait_type(("table_created", "error"), 60, "create_table")
    if not check("(1a) table_created received",
                 msg is not None and msg.get("type") == "table_created",
                 json.dumps(msg)[:300] if msg else "timeout"):
        return 3
    print(f"[info] {stamp(t0)} table id: {msg['tableId']}", flush=True)

    sg = owner.wait_type("start_game", 180, "waiting start_game (auto-start)")
    if not check("(1b) match auto-started (start_game received)",
                 sg is not None and sg.get("type") == "start_game",
                 f"last frames: {[m[1].get('type') for m in owner.frames[-14:]]}"):
        for _, m in owner.frames:
            if m.get("type") == "error":
                print(f"[info] error frame: {json.dumps(m)[:300]}", flush=True)
        return 2
    game_id = sg.get("gameId")
    print(f"[info] {stamp(t0)} game id={game_id}", flush=True)

    # ── (2) collect state frames; the rename happens during game init ──
    # Wait for game_init, then keep draining so game_update pushes land too.
    gi = owner.wait_type("game_init", 60, "game_init")
    if gi is None:
        print("[info] no game_init frame (game_update may carry state) — draining", flush=True)
    owner.drain(12.0)

    names = seat_names_from_frames(owner.frames)
    print(f"[info] {stamp(t0)} seat names seen in state pushes: {sorted(names)}", flush=True)

    if args.expect == "fixed":
        llm_names = [n for n in names if n.startswith(LLM_MODEL + "-")]
        check("(2a) a state push carries the LLM seat RENAMED to stub-model-<Personality>",
              len(llm_names) == 1,
              f"names={sorted(names)}")
        check("(2b) NO seat is still named 'AI-1'",
              LLM_SEAT_NAME_DERIVED not in names,
              f"names={sorted(names)}")
    else:
        check("(2b-base) the LLM seat still carries the derived 'AI-1' name",
              LLM_SEAT_NAME_DERIVED in names,
              f"names={sorted(names)}")

    # ── (3) transcript file on disk (written at useDeck time) ──
    tx_glob = os.path.join(args.gamelogs, "game_*", "*_transcript-*.txt")
    tx_file = None
    for _ in range(150):
        new = [p for p in glob.glob(tx_glob) if p not in pre_tx]
        if new:
            tx_file = sorted(new, key=os.path.getmtime)[-1]
            break
        time.sleep(0.4)
    if check("(3a) a new LLM transcript file was written", tx_file is not None,
             f"no new file matching {tx_glob}"):
        base = os.path.basename(tx_file)
        print(f"[info] transcript file: {tx_file}", flush=True)
        if args.expect == "fixed":
            check("(3b) transcript filename starts with a GENERATED name, not AI-1",
                  not base.startswith("AI-1_") and re.match(r"^stub-model-[A-Za-z]+_", base) is not None,
                  base)
        else:
            check("(3b-base) transcript filename starts with 'AI-1_'",
                  base.startswith("AI-1_"), base)

    # ── (4) server-log naming lines ──
    log_text = ""
    try:
        with open(args.server_log, encoding="utf-8", errors="replace") as f:
            log_text = f.read()
    except OSError as e:
        check("(4) server log readable", False, f"{args.server_log}: {e}")
    else:
        has_auto = "Auto-generated bot name: " + LLM_MODEL + "-" in log_text
        has_preserve_ai1 = "Preserving existing bot name: AI-1" in log_text
        has_personality = "Injected personality for " + LLM_MODEL + "-" in log_text
        if args.expect == "fixed":
            check("(4a) server log: 'Auto-generated bot name: stub-model-…'",
                  has_auto, "line not found")
            check("(4b) server log: 'Injected personality for stub-model-…'",
                  has_personality, "line not found")
            check("(4c) server log: NO 'Preserving existing bot name: AI-1'",
                  not has_preserve_ai1, "preserve line present")
        else:
            check("(4a-base) server log: 'Preserving existing bot name: AI-1'",
                  has_preserve_ai1, "line not found")
            check("(4b-base) server log: NO 'Auto-generated bot name'",
                  not has_auto, "auto-gen line present")

    # ── (5) play the opening so the LLM seat actually calls out ──
    # The engine blocks at the human's mulligan ask; autopilot answers it, and
    # passing priority keeps play moving to the LLM seat's own decisions.
    play_deadline = time.time() + 120
    last_pass = 0.0
    while time.time() < play_deadline:
        try:
            owner.ws.sock.settimeout(0.5)
            m = owner.ws.recv()
        except (socket.timeout, TimeoutError):
            m = None
        except RuntimeError:
            break
        if m is not None:
            owner.frames.append((time.monotonic(), m))
            owner.autopilot(m)
        now = time.time()
        if now - last_pass > 0.4:
            last_pass = now
            owner.ws.send({"type": "pass_priority"})
        if mock_post_count(args.mock_log) > pre_mock:
            break
    # give the transcript write a beat to flush
    owner.drain(3.0)
    posts = mock_post_count(args.mock_log) - pre_mock
    check("(5) the strict mock served >= 1 chat POST (a real LLM exchange happened)",
          posts >= 1, f"posts={posts}")
    rej = mock_rejected_count(args.mock_log) - pre_mock_rej
    check("(5b) no chat POST was rejected for a thinking field",
          rej == 0, f"rejected={rej}")

    # ── (6) personality section reached the LLM wire (transcript system prompt) ──
    tx_glob2 = os.path.join(args.gamelogs, "game_*", "*_transcript-*.txt")
    new_tx = [p for p in glob.glob(tx_glob2) if p not in pre_tx]
    body = read_text(sorted(new_tx, key=os.path.getmtime)[-1]) if new_tx else ""
    has_persona = "=== YOUR PERSONALITY ===" in body
    if args.expect == "fixed":
        check("(6) transcript system prompt carries '=== YOUR PERSONALITY ==='",
              has_persona, f"{len(body)} chars scanned")
    else:
        check("(6-base) transcript system prompt has NO personality section",
              not has_persona, "section present")

    # ── (7) the deck-assessment prompt calls the LLM by its generated name ──
    # buildDeckAssessmentPrompt() embeds "You are playing as: <name>" and is
    # cached in useDeck() — exactly where the web seat still carried "AI-<n>"
    # in deployed transcripts. It must now carry the generated name.
    playing_as = re.findall(r"You are playing as: ([^\r\n]+)", body)
    print(f"[info] 'You are playing as:' seen in transcript: {playing_as}", flush=True)
    if args.expect == "fixed":
        check("(7) transcript assessment prompt names the GENERATED seat, not AI-1",
              bool(playing_as) and all("AI-1" not in n for n in playing_as)
              and any(n.startswith(LLM_MODEL + "-") for n in playing_as),
              f"playing_as={playing_as}")
    else:
        check("(7-base) transcript assessment prompt still says 'AI-1'",
              any(n == "AI-1" for n in playing_as),
              f"playing_as={playing_as}")

    owner.close()
    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)}): " + "; ".join(failures))
        return 2
    print(f"RESULT: PASS (all checks, --expect {args.expect})")
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
