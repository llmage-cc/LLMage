#!/usr/bin/env python3
"""Live driver: ask-reason feature verification.

Drives ONE single-game match through the real web-proxy path:
  seat 0 HUMAN (owner, autopilot) + seat 1 COMPUTER_LLM (stub-model → strict mock).

Both decks are 40x Mountain: an all-basic deck makes the mulligan and
pre-game-deck-assessment prompts BYTE-DETERMINISTIC across runs (same card
names regardless of shuffle, fixed seat names), which is what lets the
flag-on/flag-off prompt bodies be byte-compared by the wrapper script.

Asserts (recorded in the JSON result; the wrapper enforces per-leg policy):
  (1) table_created       (2) start_game (auto-start)     (3) turn >= 2 reached
  (4) game_over           (5) mock served >= 1 chat POST  (6) no [FATAL]/GameWorker error
  (7) format-validation warnings in server log (count)    (8) mock suffix/correction counts
  (9) transcript: count of '--- LLM STATED REASON ---' sections + mock-reason text
  (10) copies mulligan + assessment request bodies for cross-run byte diffing

Usage:
  python3 reasonverify-live-driver.py --expect flag-on|flag-off
      [--shapes 1|h1|h2|h3|] [--evidence-dir DIR] [--run-name NAME]
      [--proxy 8161] [--xmage 17261] [--gamelogs /tmp/reasonverify-stage/gamelogs]
      [--server-log /tmp/reasonverify-stage/server.log]
      [--mock-log /tmp/xmage-scratch/mock-strict-18261.log]
      [--dump-dir /tmp/xmage-scratch/bodies-18261] [--timeout-s 300]
Exit: 0 all checks for the leg passed; 2 a check failed; 3 harness error.
"""
import argparse
import base64
import glob
import json
import os
import re
import shutil
import socket
import struct
import sys
import time

OWNER_NAME = "rvowner"           # fixed across runs → deterministic prompts
LLM_SEAT_NAME = "rvseat"         # fixed (not a placeholder → no auto-rename)
LLM_MODEL = "stub-model"
LLM_ENDPOINT = "http://127.0.0.1:18261/v1/chat/completions"

# All-basic deck: hand contents are the same card name regardless of shuffle.
DECK = "40 [M11:245] Mountain\n"


class Checks:
    def __init__(self):
        self.items = {}

    def add(self, name, ok, detail=""):
        self.items[name] = {"ok": bool(ok), "detail": str(detail)}
        tag = "PASS" if ok else "FAIL"
        print(f"[{tag}] {name}" + (f"  -- {detail}" if detail else ""), flush=True)
        return bool(ok)


class Ws:
    """Minimal RFC6455 client (stdlib only) — same shape as committed drivers."""

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

    def autopilot(self, msg):
        """Answer benign engine prompts on the human seat (keep / pick self)."""
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

    def close(self):
        self.ws.close()


def stamp(t0):
    return f"t+{time.monotonic() - t0:7.3f}s"


def read_text(path, limit=None):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read(limit) if limit else f.read()
    except OSError:
        return ""


def count_lines(path, prefix=None):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return sum(1 for ln in f if prefix is None or ln.startswith(prefix))
    except OSError:
        return 0


def newest(globpat, before=()):
    hits = [p for p in glob.glob(globpat) if p not in before]
    return sorted(hits, key=os.path.getmtime)[-1] if hits else None


def state_turn(msg):
    st = msg.get("state") or {}
    if "turn" in st:
        return st.get("turn")
    d = msg.get("delta") or {}
    if "turn" in d:
        return d.get("turn")
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--expect", choices=("flag-on", "flag-off"), required=True)
    ap.add_argument("--shapes", default="")
    ap.add_argument("--evidence-dir", required=True)
    ap.add_argument("--run-name", required=True)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--proxy", type=int, default=8161)
    ap.add_argument("--xmage", type=int, default=17261)
    ap.add_argument("--gamelogs", default="/tmp/reasonverify-stage/gamelogs")
    ap.add_argument("--server-log", default="/tmp/reasonverify-stage/server.log")
    ap.add_argument("--mock-log", default="/tmp/xmage-scratch/mock-strict-18261.log")
    ap.add_argument("--dump-dir", default="/tmp/xmage-scratch/bodies-18261")
    ap.add_argument("--timeout-s", type=int, default=300)
    ap.add_argument("--concede-turn", type=int, default=6,
                    help="concede once the game reaches this turn (default 6 — enough "
                         "for the LLM seat to take several real decisions)")
    ap.add_argument("--require-decisions", type=int, default=0,
                    help="require at least N LLM decision posts (=== AVAILABLE ACTIONS === "
                         "plus target/combat classes)")
    args = ap.parse_args()

    t0 = time.monotonic()
    run_dir = os.path.join(args.evidence_dir, args.run_name)
    os.makedirs(run_dir, exist_ok=True)
    checks = Checks()
    result = {"run": args.run_name, "expect": args.expect, "shapes": args.shapes,
              "started": time.strftime("%Y-%m-%dT%H:%M:%S")}

    print(f"=== ASK-REASON LIVE DRIVER ({args.expect} shapes='{args.shapes or 'off'}') "
          f"proxy={args.host}:{args.proxy} xmage={args.host}:{args.xmage} ===", flush=True)

    pre_mock_lines = count_lines(args.mock_log)
    pre_tx = set(glob.glob(os.path.join(args.gamelogs, "game_*", "*_transcript-*.txt")))
    pre_dumps = set(glob.glob(os.path.join(args.dump_dir, "*.json")))
    print(f"[info] pre-existing: mock-log lines {pre_mock_lines}, transcripts {len(pre_tx)}, "
          f"dumps {len(pre_dumps)}", flush=True)

    try:
        owner = Client(args.host, args.proxy, args.xmage, OWNER_NAME, t0)
    except RuntimeError as e:
        print(f"[harness-error] owner connect: {e}")
        result["harness_error"] = str(e)
        write_result(run_dir, result, checks)
        return 3

    # ── create a single-game table: HUMAN owner + COMPUTER_LLM seat → mock ──
    owner.ws.send({
        "type": "create_table",
        "name": f"reasonverify-{args.run_name}"[:32],
        "gameType": "Two Player Duel",
        "deckType": "Constructed - Freeform",
        "winsNeeded": 1,
        "playerConfigs": [
            {"type": "HUMAN", "name": OWNER_NAME, "deck": DECK},
            {"type": "COMPUTER_LLM", "name": LLM_SEAT_NAME, "deck": DECK,
             "llmConfig": {
                 "model": LLM_MODEL,
                 "apiEndpoint": LLM_ENDPOINT,
                 "reasoningEffort": "default",
                 "thinkingEnabled": False,
                 "streamingEnabled": False,
             }},
        ],
    })
    msg = owner.wait(lambda m: m.get("type") in ("table_created", "error"), 60)
    if not checks.add("(1) table_created received",
                      msg is not None and msg.get("type") == "table_created",
                      json.dumps(msg)[:300] if msg else "timeout"):
        owner.close()
        write_result(run_dir, result, checks)
        return 3
    assert msg is not None
    print(f"[info] {stamp(t0)} table id: {msg['tableId']}", flush=True)

    sg = owner.wait(lambda m: m.get("type") in ("start_game", "error"), 180)
    if not checks.add("(2) match auto-started (start_game received)",
                      sg is not None and sg.get("type") == "start_game",
                      f"last frames: {[m[1].get('type') for m in owner.frames[-14:]]}"):
        owner.close()
        write_result(run_dir, result, checks)
        return 2
    assert sg is not None
    game_id = sg.get("gameId")
    print(f"[info] {stamp(t0)} game id={game_id}", flush=True)
    result["game_id"] = game_id

    # ── play: autopilot + pass_priority; concede once turn >= 2; wait game_over ──
    state = {"turn": 0, "over": None, "conceded": False}
    last_pass = 0.0
    deadline = time.time() + args.timeout_s
    while time.time() < deadline and state["over"] is None:
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
            t = state_turn(m)
            if t is not None and t != state["turn"]:
                state["turn"] = t
                print(f"[info] {stamp(t0)} state turn -> {t}", flush=True)
            if m.get("type") == "game_over":
                state["over"] = m
                print(f"[info] {stamp(t0)} game_over (turn={state['turn']})", flush=True)
        if state["over"] is None:
            if not state["conceded"] and state["turn"] >= args.concede_turn:
                owner.ws.send({"type": "concede"})
                state["conceded"] = True
                print(f"[info] {stamp(t0)} CONCEDE sent at turn {state['turn']}", flush=True)
            now = time.monotonic()
            if now - last_pass > 0.4:
                last_pass = now
                owner.ws.send({"type": "pass_priority"})
        else:
            time.sleep(0.05)

    checks.add("(3) game reached turn >= 2 before the concede",
               state["turn"] >= 2, f"turn={state['turn']}")
    checks.add("(4) game_over received", state["over"] is not None, "timeout")
    result["game"] = {"turn": state["turn"], "game_over": state["over"] is not None}

    # let post-game reflection run so its transcript writes land
    end = time.time() + 20
    while time.time() < end:
        try:
            owner.ws.sock.settimeout(0.5)
            m = owner.ws.recv()
        except (socket.timeout, TimeoutError):
            continue
        except RuntimeError:
            break
        owner.frames.append((time.monotonic(), m))
    owner.close()

    # ── mock-side counts (this run only: lines after the pre-run offset) ──
    all_mock = read_text(args.mock_log).splitlines()
    run_lines = all_mock[pre_mock_lines:]
    posts = [ln for ln in run_lines if ln.startswith("POST ")]
    suffix_yes = [ln for ln in posts if "has_suffix=yes" in ln]
    correction_yes = [ln for ln in posts if "is_correction=yes" in ln]
    classes = {}
    for ln in posts:
        # "prompt_class=<klass> reason_shape=..." — klass may itself start with
        # '===' so capture non-greedily up to the next labeled field.
        mm = re.search(r"prompt_class=(.*?) reason_shape=", ln)
        if mm:
            classes[mm.group(1)] = classes.get(mm.group(1), 0) + 1
    result["mock"] = {"offset": pre_mock_lines, "posts": len(posts),
                      "suffix_yes": len(suffix_yes), "correction_yes": len(correction_yes),
                      "classes": classes}
    checks.add("(5) the strict mock served >= 1 chat POST", len(posts) >= 1,
               f"posts={len(posts)}")
    decision_classes = ("=== AVAILABLE ACTIONS ===", "=== TARGET SELECTION ===",
                        "=== DECLARE ATTACKERS ===", "=== DECLARE BLOCKERS ===",
                        "=== CHOOSE ONE ===", "=== YES/NO DECISION ===")
    decisions = sum(n for k, n in classes.items() if k in decision_classes)
    result["mock"]["decision_posts"] = decisions
    if args.require_decisions:
        checks.add(f"(5b) >= {args.require_decisions} LLM decision posts observed",
                   decisions >= args.require_decisions,
                   f"decisions={decisions} classes={classes}")
    print(f"[info] mock posts={len(posts)} suffix_yes={len(suffix_yes)} "
          f"correction_yes={len(correction_yes)} decisions={decisions} classes={classes}",
          flush=True)

    # ── server-log checks since run start (approximate: whole-file scan of a fresh stack) ──
    slog = read_text(args.server_log)
    fatal_hits = [ln for ln in slog.splitlines()
                  if "[FATAL]" in ln or "GameWorker system error" in ln]
    invalid_action = [ln for ln in slog.splitlines()
                      if "invalid action index" in ln]
    fmt_validation = [ln for ln in slog.splitlines()
                      if "failed format validation" in ln]
    result["server"] = {"fatal": len(fatal_hits), "invalid_action": len(invalid_action),
                        "format_validation": len(fmt_validation)}
    checks.add("(6) no [FATAL] / GameWorker error in the server log",
               not fatal_hits, json.dumps(fatal_hits[:2])[:300])
    checks.add("(6b) no 'invalid action index' in the server log",
               not invalid_action, json.dumps(invalid_action[:2])[:300])

    # ── transcript: stated-reason sections ──
    tx_file = newest(os.path.join(args.gamelogs, "game_*", "*_transcript-*.txt"), pre_tx)
    result["transcript"] = {"path": tx_file}
    if tx_file:
        body = read_text(tx_file)
        shutil.copy2(tx_file, os.path.join(run_dir, os.path.basename(tx_file)))
        sr = body.count("--- LLM STATED REASON ---")
        result["transcript"]["stated_reason_sections"] = sr
        result["transcript"]["has_mock_reason_text"] = "mock stated reason." in body
        print(f"[info] transcript {os.path.basename(tx_file)}: "
              f"stated_reason_sections={sr}", flush=True)
        print("[info] stated-reason excerpts:", flush=True)
        excerpts = re.findall(r"--- LLM STATED REASON ---\n([^\n]*)", body)
        for x in excerpts[:6]:
            print(f"       > {x}", flush=True)
        checks.add("(9a) transcript file exists", True, "")
    else:
        checks.add("(9a) transcript file exists", False, "no new transcript")

    # ── copy deterministic bodies (mulligan + assessment) for cross-run diffing ──
    bodies_dir = os.path.join(run_dir, "bodies")
    os.makedirs(bodies_dir, exist_ok=True)
    mull = sorted(glob.glob(os.path.join(args.dump_dir, "*MULLIGAN_DECISION*.json")))
    asmt = sorted(glob.glob(os.path.join(args.dump_dir, "*PRE_GAME_DECK_ASSESSMENT*.json")))
    for label, hits in (("mulligan", mull), ("assessment", asmt)):
        if hits:
            shutil.copy2(hits[0], os.path.join(bodies_dir, f"{label}.json"))
    result["bodies"] = {lbl: (os.path.join(bodies_dir, f"{lbl}.json") if hits else None)
                        for lbl, hits in (("mulligan", mull), ("assessment", asmt))}
    checks.add("(10) deterministic bodies captured (mulligan + assessment)",
               bool(mull) and bool(asmt),
               f"mulligan={len(mull)} assessment={len(asmt)}")

    # ── write per-run mock log slice for the wrapper's suffix audit ──
    with open(os.path.join(run_dir, "mock-log-slice.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(run_lines) + ("\n" if run_lines else ""))

    write_result(run_dir, result, checks)
    failed = [k for k, v in checks.items.items() if not v["ok"]]
    print()
    if failed:
        print(f"RESULT: FAIL ({len(failed)}): " + "; ".join(failed))
        return 2
    print(f"RESULT: PASS (all checks, {args.expect} shapes='{args.shapes or 'off'}')")
    return 0


def write_result(run_dir, result, checks):
    result["checks"] = checks.items
    result["finished"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    with open(os.path.join(run_dir, "result.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, sort_keys=True)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (socket.timeout, TimeoutError) as e:
        print(f"[harness-error] timeout: {e}")
        sys.exit(3)
    except RuntimeError as e:
        print(f"[harness-error] {e}")
        sys.exit(3)
