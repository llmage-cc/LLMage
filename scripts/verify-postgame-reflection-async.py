#!/usr/bin/env python3
"""Live E2E driver — async post-game reflection.

One LEG per invocation; the wrapper (verify-postgame-reflection-async.sh) runs
both legs against the same stack:

  control — mock answers the '=== POST-GAME REFLECTION ===' prompt instantly.
            Asserts the behavior KEPT WHEN HEALTHY: postgame files ARE written
            (game 1 and game 2) with the reflection content and the committed
            non-draw result, results recorded correctly, sideboard ran, match
            completed.

  stall   — mock HOLDS the '=== POST-GAME REFLECTION ===' response for
            MOCK_STALL_REFLECTION_SECONDS (wrapper default 240s). Asserts the
            MATCH LIFECYCLE PROCEEDS WITHOUT WAITING for the hung call:
              (a) game-1 result recorded correctly (rich game_over, NOT a draw),
              (b) sideboarding ran (owner sideboard_prompt + LLM sideboard file),
              (c) game 2 STARTED while the reflection was still held (delta from
                  game-1 over <= --game2-max-delta),
              (d) the match completed (game-2 game_over, matchInfo names winner),
              (e) the stall actually engaged (mock log carries REFLECTION-STALL;
                  without it this leg would be vacuous).

Context: pre-fix
the reflection ran ON the game worker thread inside won()/lostForced()/quit();
a >600s provider hang propagated through checkIfGameIsOver, the match was
recorded as "Game is a draw" and game 2 never started (zombie table). Post-fix
(ruling C, verbatim: "move reflection OFF the game thread (async): match +
sideboard proceed immediately; failure = no postgame file, loud log") the
reflection is handed to a bounded executor and the match proceeds immediately.

Shape (adapted from the committed verify-llm-sideboard-live.py — same raw-WS
protocol): owner (HUMAN) creates a Bo3 table with one COMPUTER_LLM seat
pointing at the local strict mock; the proxy auto-joins the AI seat and
auto-starts the match. The owner plays to turn >= 2, concedes (GAME-scoped —
the match stays alive for sideboarding), submits a deck at the sideboard
prompt, and game 2 runs to a second concession, completing the match 2-0 to
the LLM seat.

Every check prints PASS/FAIL with the observed evidence on failure.
Exit: 0 all checks passed; 2 a check failed; 3 harness/setup error.
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

LLM_SEAT_NAME = os.environ.get("LLM_SEAT_NAME", "llm-seat")
LLM_MODEL = os.environ.get("LLM_MODEL", "stub-model")

DECK_LLM = ("20 [M11:245] Mountain\n"
            "20 [3ED:201] Grizzly Bears\n"
            "SB: 2 [M11:245] Mountain\n")
DECK_HUMAN = ("20 [M11:245] Mountain\n"
              "20 [3ED:201] Grizzly Bears\n")

# Failure lines that must NOT appear in the server log during either leg: the
# ruling-C contract is that reflection trouble degrades LOUDLY but never breaks
# the match — and the sideboard/postgame write paths must not fail either.
SERVER_FAILURE_PATTERNS = (
    "Failed to generate post-game reflection",
    "Error during LLM sideboarding",
    "Failed to write sideboard summary",
    "Failed to write post-game summary",
)

# The proxy ships TWO game_over frames per game end: the partial
# GAME_OVER/GameClientMessage first (result text only, complete=false, no
# hasWon/matchInfo), then the rich END_GAME_INFO/GameEndView
# (complete=true, hasWon, matchInfo) a few ms later (proxy log: both
# "End game info received" and the partial-payload WARN). The driver settles
# on the rich frame — the partial one is not a failure, it is just less data.
GAME_OVER_SETTLE_S = 3.0


def game_over_rich(m):
    """True when a game_over frame carries the rich END_GAME_INFO payload."""
    return (bool(m.get("complete")) or m.get("hasWon") is not None
            or bool(m.get("matchInfo")))


failures = []


def check(name, cond, detail=""):
    tag = "PASS" if cond else "FAIL"
    print(f"[{tag}] {name}" + (f"  -- {detail}" if detail and not cond else ""), flush=True)
    if not cond:
        failures.append(name)
    return cond


def info(msg):
    print(f"[info] {msg}", flush=True)


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
        self.frames = []          # (monotonic, msg)
        self.pending = []         # frames a settle window read without consuming
        self.ws = Ws(host, port)
        self.ws.send({"type": "connect", "server": host, "port": xmage_port,
                      "username": user, "password": ""})
        msg = self.wait(lambda m: m.get("type") in ("connected", "connect_error"), 30)
        if msg is None:
            raise RuntimeError(f"{user}: no connected frame within 30s")
        if msg["type"] == "connect_error":
            raise RuntimeError(f"{user}: connect_error: {msg.get('message')}")
        info(f"{stamp(t0)} {self.user} connected")

    def _recv_frame(self, timeout):
        """Next frame for the driver's read loops: the pending queue first
        (frames a settle window read but did not consume, drained in order so
        no waiter ever observes the stream out of sequence), then the socket.
        Returns (monotonic read time, msg); socket errors propagate like a
        bare ws.recv()."""
        if self.pending:
            return self.pending.pop(0)
        self.ws.sock.settimeout(timeout)
        return (time.monotonic(), self.ws.recv())

    def wait(self, pred, timeout):
        end = time.time() + timeout
        while time.time() < end:
            try:
                ts, msg = self._recv_frame(max(0.5, end - time.time()))
            except (socket.timeout, TimeoutError):
                continue
            self.frames.append((ts, msg))
            if pred(msg):
                return msg
        return None

    def wait_type(self, types, timeout, note=None, autopilot=True):
        if isinstance(types, str):
            types = (types,)
        end = time.time() + timeout
        while time.time() < end:
            try:
                ts, msg = self._recv_frame(max(0.5, end - time.time()))
            except (socket.timeout, TimeoutError):
                continue
            self.frames.append((ts, msg))
            mt = msg.get("type")
            if autopilot:
                self.autopilot(msg)
            if mt in types:
                if note:
                    info(f"{stamp(self.t0)} {self.user} <- "
                         f"{json.dumps(msg)[:200]}   ({note})")
                return msg
        return None

    def autopilot(self, msg):
        """Answer benign engine prompts (mulligan keep / pick starting player)
        so the game keeps moving."""
        if msg.get("type") != "action_prompt":
            return False
        at = msg.get("action_type")
        if at == "boolean":
            self.ws.send({"type": "boolean_choice", "value": False})  # Keep hand
            info(f"{stamp(self.t0)} {self.user} answered boolean ask: "
                 f"{(msg.get('message') or '')[:70]!r}")
            return True
        if at == "target":
            targets = msg.get("targets") or []
            me = next((t for t in targets if t.get("name") == self.user), None)
            pick = me or (targets[0] if targets else None)
            if pick is not None:
                self.ws.send({"type": "select_target", "targetId": pick.get("id")})
                info(f"{stamp(self.t0)} {self.user} answered target ask "
                     f"(picked {pick.get('name')})")
                return True
        return False

    def close(self):
        self.ws.close()


def deck_payload_from_text(dtext):
    sub = {"cards": [], "sideboard": []}
    for line in dtext.splitlines():
        line = line.strip()
        if not line:
            continue
        sb = line.startswith("SB:")
        if sb:
            line = line[3:].strip()
        qty, code, name = line.split(None, 2)
        setc, num = code.strip("[]").split(":")
        (sub["sideboard"] if sb else sub["cards"]).append(
            {"name": name, "setCode": setc, "cardNumber": num, "count": int(qty)})
    return sub


def prompt_payload(msg):
    cards = [dict(c, count=c.get("count", 1)) for c in (msg.get("cards") or [])]
    side = [dict(c, count=c.get("count", 1)) for c in (msg.get("sideboard") or [])]
    return {"cards": cards, "sideboard": side}


def state_turn(msg):
    """Turn number from a state-carrying frame (game_init/game_update/game_delta)."""
    st = msg.get("state") or {}
    if "turn" in st:
        return st.get("turn")
    d = msg.get("delta") or {}
    if "turn" in d:
        return d.get("turn")
    return None


def newest(globpat):
    hits = sorted(glob.glob(globpat), key=os.path.getmtime)
    return hits[-1] if hits else None


def read_head(path, n=2500):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read(n)


def wait_new(globpat, before, timeout_s):
    end = time.time() + timeout_s
    while time.time() < end:
        now = glob.glob(globpat)
        new = [p for p in now if p not in before]
        if new:
            return sorted(new, key=os.path.getmtime)[-1]
        time.sleep(0.4)
    return None


def read_file(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError as e:
        return f"<<unreadable: {e}>>"


def play_until_turn(cli, min_turn, deadline_s, label, concede=True):
    """Pass priority until the state reports turn >= min_turn, then concede;
    keep passing until game_over. Returns the state dict (turn, over frame,
    over_at monotonic, conceded flag)."""
    state = {"turn": 0, "over": None, "over_at": None, "conceded": False, "last_pass": 0.0}
    end = time.time() + deadline_s
    while time.time() < end and state["over"] is None:
        ts = time.monotonic()
        try:
            ts, m = cli._recv_frame(0.5)
        except (socket.timeout, TimeoutError):
            m = None
        except RuntimeError:
            break
        if m is not None:
            cli.frames.append((ts, m))
            mt = m.get("type")
            cli.autopilot(m)
            t = state_turn(m)
            if t is not None and t != state["turn"]:
                state["turn"] = t
                info(f"{stamp(cli.t0)} {label}: state turn -> {t}")
            if mt == "error":
                info(f"{stamp(cli.t0)} {label} <- error frame: {json.dumps(m)[:300]}")
            if mt == "game_over":
                state["over_at"] = ts
                state["over"] = m
                info(f"{stamp(cli.t0)} {label} <- game_over (turn={state['turn']})"
                     f"{' [partial]' if not game_over_rich(m) else ''}")
                # Settle: wait briefly for the rich END_GAME_INFO variant and
                # keep it over the partial frame. Frames read meanwhile (the
                # sideboard prompt lands ~40ms later) are stashed on
                # cli.pending so the next waiter still sees them in order.
                settle_end = time.time() + GAME_OVER_SETTLE_S
                while time.time() < settle_end and not game_over_rich(state["over"]):
                    try:
                        ts2, m2 = cli._recv_frame(max(0.2, settle_end - time.time()))
                    except (socket.timeout, TimeoutError):
                        continue
                    except RuntimeError:
                        break
                    if m2.get("type") == "game_over":
                        cli.frames.append((ts2, m2))
                        state["over_at"] = ts2
                        state["over"] = m2
                        if game_over_rich(m2):
                            info(f"{stamp(cli.t0)} {label} <- rich game_over "
                                 f"(complete={m2.get('complete')})")
                    else:
                        cli.pending.append((ts2, m2))
        if state["over"] is None:
            now = time.monotonic()
            if concede and not state["conceded"] and state["turn"] >= min_turn:
                cli.ws.send({"type": "concede"})
                state["conceded"] = True
                info(f"{stamp(cli.t0)} {label}: CONCEDE sent at turn "
                     f"{state['turn']} (>= {min_turn})")
            # Keep the game moving: the conceded game only ends when the engine
            # next reaches a priority checkpoint (checkConcede runs on a
            # priority change) — same pass loop the browser produces.
            if now - state["last_pass"] > 0.4:
                state["last_pass"] = now
                cli.ws.send({"type": "pass_priority"})
        if m is None:
            time.sleep(0.02)
    return state


def wait_next_start_game(cli, timeout_s):
    """Next start_game frame (game 2), returning (frame, monotonic) or (None, None)."""
    end = time.time() + timeout_s
    while time.time() < end:
        try:
            ts, m = cli._recv_frame(max(0.5, end - time.time()))
        except (socket.timeout, TimeoutError):
            continue
        except RuntimeError:
            return None, None
        cli.frames.append((ts, m))
        cli.autopilot(m)
        if m.get("type") == "start_game":
            return m, time.monotonic()
    return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--proxy", type=int, default=8181)
    ap.add_argument("--xmage", type=int, default=17281)
    ap.add_argument("--mock", type=int, default=18281)
    ap.add_argument("--gamelogs", default=os.environ.get("LLM_GAMELOGS", "/tmp/llm-reflection-stage/gamelogs"))
    ap.add_argument("--server-log", default=os.environ.get("SERVER_LOG", "/tmp/llm-reflection-stage/server.log"))
    ap.add_argument("--mock-log", default=os.environ.get("MOCK_LOG", "/tmp/llm-reflection-stage/mock.log"))
    ap.add_argument("--tag", default=str(int(time.time())))
    ap.add_argument("--leg", required=True, choices=("control", "stall"))
    ap.add_argument("--stall-seconds", type=float, default=0.0,
                    help="informational: the mock's MOCK_STALL_REFLECTION_SECONDS for this leg")
    ap.add_argument("--game2-max-delta", type=float, default=120.0,
                    help="stall leg: max seconds from game-1 game_over to game-2 start")
    args = ap.parse_args()

    t0 = time.monotonic()
    tail = args.tag[-4:]
    leg = args.leg
    owner_user = f"pr{leg[0]}{tail}"
    table_name = f"pgr-{leg}-{tail}"
    endpoint = f"http://{args.host}:{args.mock}/v1/chat/completions"

    print(f"=== POSTGAME-REFLECTION LEG: {leg.upper()}  "
          f"proxy={args.host}:{args.proxy} xmage={args.host}:{args.xmage} "
          f"mock={endpoint} ===", flush=True)
    print(f"    table={table_name} owner={owner_user} llmSeat={LLM_SEAT_NAME} "
          f"model={LLM_MODEL} stall={args.stall_seconds or 'off'}s", flush=True)

    # Snapshot the artifact dirs BEFORE the table exists (files can be written
    # within ms of a prompt; a post-prompt snapshot cannot distinguish runs).
    side_glob = os.path.join(args.gamelogs, "game_*",
                             f"{LLM_SEAT_NAME}_{LLM_MODEL}_sideboard-*.txt")
    pg_glob = os.path.join(args.gamelogs, "game_*",
                           f"{LLM_SEAT_NAME}_{LLM_MODEL}_postgame-*.txt")
    pre_side = set(glob.glob(side_glob))
    pre_pg = set(glob.glob(pg_glob))
    info(f"pre-existing sideboard files: {len(pre_side)}, postgame files: {len(pre_pg)}")

    try:
        owner = Client(args.host, args.proxy, args.xmage, owner_user, t0)
    except RuntimeError as e:
        print(f"[harness-error] owner connect: {e}")
        return 3

    try:
        # ── (1) create Bo3 table: HUMAN owner + COMPUTER_LLM seat ──
        owner.ws.send({
            "type": "create_table",
            "name": table_name,
            "gameType": "Two Player Duel",
            "deckType": "Constructed - Freeform",
            "winsNeeded": 2,
            "playerConfigs": [
                {"type": "HUMAN", "name": owner_user, "deck": DECK_HUMAN},
                {"type": "COMPUTER_LLM", "name": LLM_SEAT_NAME, "deck": DECK_LLM,
                 "llmConfig": {
                     "model": LLM_MODEL,
                     "apiEndpoint": endpoint,
                     "reasoningEffort": "default",
                     "thinkingEnabled": False,
                     "streamingEnabled": False,
                 }},
            ],
        })
        msg = owner.wait_type(("table_created", "error"), 60, "create_table")
        if not check("(1) table_created received",
                     msg is not None and msg.get("type") == "table_created",
                     json.dumps(msg)[:300] if msg else "timeout"):
            return 3
        table_id = msg["tableId"]
        info(f"{stamp(t0)} table id: {table_id}")

        # ── (2) the proxy auto-joins the AI seat and auto-starts the match ──
        sg = owner.wait_type("start_game", 180, "waiting start_game (auto-start)")
        if not check("(2) match auto-started (start_game on the owner seat)",
                     sg is not None and sg.get("type") == "start_game",
                     f"last frames: {[m[1].get('type') for m in owner.frames[-14:]]}"):
            for _, m in owner.frames:
                if m.get("type") == "error":
                    info(f"error frame: {json.dumps(m)[:300]}")
            return 2
        game1_id = sg.get("gameId")
        info(f"{stamp(t0)} game 1 id={game1_id}")

        # ── (3)+(4) game 1: play to turn >= 2, concede, wait game_over ──
        g1 = play_until_turn(owner, 2, 300, "game1")
        check("(3) game 1 reached turn >= 2 before the concede",
              g1["turn"] >= 2 and g1["conceded"],
              f"turn={g1['turn']} conceded={g1['conceded']}")
        if not check("(4) game 1 game_over received", g1["over"] is not None, "timeout"):
            info(f"last frames: {[m[1].get('type') for m in owner.frames[-14:]]}")
            return 2

        # ── (4b) THE central regression assertion: the recorded result is a
        # proper win/loss, NOT the pre-fix "Game is a draw". Accepts both frame
        # variants: the partial GAME_OVER text ("Player X is the winner" /
        # "Player X is the loser") and the rich END_GAME_INFO gameInfo
        # ("You won/lost the game on turn N."). ──
        rtext = (g1["over"].get("result") or "").strip()
        rlow = rtext.lower()
        check("(4b) game-1 RESULT IS NOT A DRAW (pre-fix the hung reflection "
              "flipped the record to 'Game is a draw')",
              rtext != "" and "draw" not in rlow
              and ("won" in rlow or "lost" in rlow or "winner" in rlow),
              f"game_over result={rtext!r} hasWon={g1['over'].get('hasWon')} "
              f"complete={g1['over'].get('complete')} "
              f"frame={json.dumps(g1['over'])[:300]}")
        info(f"game-1 result text: {rtext!r} (hasWon={g1['over'].get('hasWon')}, "
             f"complete={g1['over'].get('complete')})")

        # ── (5) sideboard prompt on the human seat: sideboarding ran while
        # (stall leg) the reflection call is hung ──
        sp = owner.wait_type("sideboard_prompt", 120, "waiting sideboard_prompt")
        check("(5) sideboard_prompt received (sideboarding ran)",
              sp is not None and sp.get("type") == "sideboard_prompt",
              f"last frames: {[m[1].get('type') for m in owner.frames[-14:]]}")

        # ── (6) the LLM seat's sideboard file (its sideboard() completed and
        # wrote its file — while, in the stall leg, the reflection is hung) ──
        side_file = wait_new(side_glob, pre_side, 90)
        if check("(6) LLM sideboard file written for game 1", side_file is not None,
                 f"no new file matching {side_glob}"):
            side_head = read_head(side_file)
            info(f"sideboard file: {side_file}")
            check("(6b) sideboard file header is '=== SIDEBOARDING ==='",
                  "=== SIDEBOARDING ===" in side_head, side_head[:200])
            expected_dir = "game_" + (game1_id or "")[-8:]
            actual_dir = os.path.basename(os.path.dirname(str(side_file)))
            check("(6c) sideboard file lands in the game-1 transcript dir",
                  actual_dir == expected_dir, f"{actual_dir} != {expected_dir}")

        # ── (7) owner submits deck; game 2 must start ──
        if sp is not None:
            sub = prompt_payload(sp)
            if not sub["cards"] and not sub["sideboard"]:
                sub = deck_payload_from_text(DECK_HUMAN)
            owner.ws.send({"type": "submit_deck", "tableId": table_id, "deck": sub})
            info(f"{stamp(t0)} owner submit_deck sent ({len(sub['cards'])} card entries)")

        g2_frame, g2_at = wait_next_start_game(owner, 150)
        if not check("(7) game 2 STARTED (pre-fix: the hung reflection killed the "
                     "match here — zombie table, game 2 never started)",
                     g2_frame is not None and g2_frame.get("gameId") != game1_id,
                     f"no distinct start_game within 150s; last frames: "
                     f"{[m[1].get('type') for m in owner.frames[-14:]]}"):
            return 2
        game2_id = g2_frame.get("gameId")
        info(f"{stamp(t0)} game 2 id={game2_id}")

        if leg == "stall":
            delta = (g2_at - (g1["over_at"] or g2_at))
            check("(7b) game 2 started while the reflection was STILL HELD "
                  f"(reflection stall {args.stall_seconds or '?'}s; game 2 must not "
                  f"wait for it)",
                  delta <= args.game2_max_delta,
                  f"game 2 started {delta:.1f}s after game-1 game_over "
                  f"(limit {args.game2_max_delta}s, stall {args.stall_seconds}s) "
                  f"— if the limit is exceeded the match may have waited on the "
                  f"hung reflection")
            info(f"stall leg: game-1 over -> game 2 start delta = {delta:.1f}s")

        # ── (8)+(9) game 2: play to turn >= 2, concede, match ends ──
        g2s = play_until_turn(owner, 2, 300, "game2")
        check("(8) game 2 reached turn >= 2 before the concede",
              g2s["turn"] >= 2 and g2s["conceded"],
              f"turn={g2s['turn']} conceded={g2s['conceded']}")
        check("(9) game 2 game_over received", g2s["over"] is not None, "timeout")
        if g2s["over"] is not None:
            match_info = (g2s["over"].get("matchInfo") or "").strip()
            check("(10) match completed (game-2 game_over names the match winner)",
                  "won the match" in match_info.lower(),
                  f"matchInfo={match_info!r} frame={json.dumps(g2s['over'])[:300]}")
            info(f"match result text: {match_info!r}")

        # ── (11)+(12) control leg: postgame files ARE written (healthy path) ──
        if leg == "control":
            g1_dir = os.path.join(args.gamelogs, "game_" + (game1_id or "")[-8:])
            g2_dir = os.path.join(args.gamelogs, "game_" + (game2_id or "")[-8:])

            pg1 = None
            for _ in range(150):
                hits = [p for p in glob.glob(os.path.join(g1_dir, "*_postgame-*.txt"))
                        if p not in pre_pg and "=== POST-GAME REFLECTION ===" in read_head(p)]
                if hits:
                    pg1 = sorted(hits, key=os.path.getmtime)[-1]
                    break
                time.sleep(0.4)
            if check("(11) game-1 postgame REFLECTION file written (behavior kept "
                     "when healthy)",
                     pg1 is not None, f"no reflection file in {g1_dir}"):
                head1 = read_head(pg1)
                info(f"game-1 postgame file: {pg1}")
                print("---- game-1 postgame file (head) ----")
                print(head1)
                print("-------------------------------------", flush=True)
                check("(11b) game-1 file carries the '=== POST-GAME REFLECTION ===' header",
                      "=== POST-GAME REFLECTION ===" in head1, head1[:200])
                check("(11c) game-1 file carries the mock reflection content",
                      "MOCK REFLECTION" in head1, head1[:400])
                check("(11d) game-1 file records the committed NON-DRAW result",
                      "Result: " in head1 and "Result: Draw" not in head1 and "WON" in head1,
                      head1[:400])

            pg2 = None
            for _ in range(150):
                hits = [p for p in glob.glob(os.path.join(g2_dir, "*_postgame-*.txt"))
                        if p not in pre_pg and "=== POST-GAME REFLECTION ===" in read_head(p)]
                if hits:
                    pg2 = sorted(hits, key=os.path.getmtime)[-1]
                    break
                time.sleep(0.4)
            if check("(12) game-2 postgame REFLECTION file written",
                     pg2 is not None, f"no reflection file in {g2_dir}"):
                head2 = read_head(pg2)
                info(f"game-2 postgame file: {pg2}")
                check("(12b) game-2 file carries the '=== POST-GAME REFLECTION ===' header",
                      "=== POST-GAME REFLECTION ===" in head2, head2[:200])

        # ── (13) stall leg: the stall actually engaged (else the leg is vacuous) ──
        if leg == "stall":
            mock_text = read_file(args.mock_log)
            check("(13) mock stall ENGAGED on the reflection prompt (without this the "
                  "leg proves nothing)",
                  "REFLECTION-STALL" in mock_text,
                  f"no 'REFLECTION-STALL' line in {args.mock_log}")
            for ln in mock_text.splitlines():
                if "REFLECTION-STALL" in ln:
                    info(f"mock log: {ln.strip()[:200]}")

            g1_dir = os.path.join(args.gamelogs, "game_" + (game1_id or "")[-8:])
            g1_refl = [p for p in glob.glob(os.path.join(g1_dir, "*_postgame-*.txt"))
                       if "=== POST-GAME REFLECTION ===" in read_head(p)]
            info(f"(i) game-1 reflection file present at leg end? "
                 f"{'yes: ' + str(g1_refl) if g1_refl else 'no (still held by the mock stall — expected)'}")

        # ── (14) server log: no failure lines; the write paths fired ──
        log_text = read_file(args.server_log)
        if log_text.startswith("<<unreadable"):
            check("(14) server log readable", False, args.server_log)
        else:
            check("(14a) server log carries 'Sideboard summary written to:'",
                  "Sideboard summary written to:" in log_text, args.server_log)
            if leg == "control":
                check("(14b) server log carries 'Post-game summary written to:'",
                      "Post-game summary written to:" in log_text, args.server_log)
            hits = []
            for pat in SERVER_FAILURE_PATTERNS:
                for line in log_text.splitlines():
                    if pat in line:
                        hits.append((pat, line.strip()[:220]))
            check("(14c) NO failure lines in the server log during this leg",
                  not hits, json.dumps(hits[:4]))
        for ln in log_text.splitlines():
            if "Post-game summary written to:" in ln:
                info(f"server log: {ln.strip()[:200]}")

        print()
        if failures:
            print(f"RESULT: FAIL ({len(failures)}): " + "; ".join(failures))
            return 2
        print(f"RESULT: PASS (all checks, leg={leg})")
        return 0
    finally:
        owner.close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (socket.timeout, TimeoutError) as e:
        print(f"[harness-error] timeout: {e}")
        sys.exit(3)
    except RuntimeError as e:
        print(f"[harness-error] {e}")
        sys.exit(3)
