#!/usr/bin/env python3
"""Early-sideboard submit_deck repro + regression gate (stdlib only).

Reproduces the early-sideboard silent-reject defect:

  * Two raw-WS clients (owner + friend) drive a Bo3 table through
    game 1 -> SIDEBOARDING on an isolated web proxy (default 127.0.0.1:8101,
    XMage server 127.0.0.1:17181).
  * Game 1 ends by a GAME-scoped CONCEDE from seat B (the current game only —
    the match stays alive with both seats seated).

Scenarios, in order:

  CONTROL A (live-game refusal): a submit while the game is still being played
    must be REFUSED. Pre-fix the refusal reason was EMPTY ("Failed to submit
    deck: "); post-fix it must carry a real reason.

  REPRO B (the issue): seat B fires submit_deck IMMEDIATELY on receiving its
    `game_over` frame — i.e. in the gap between the game ending (clients told)
    and the table actually entering SIDEBOARDING. 3 more submits follow at
    +10/+25/+50 ms. Pre-fix these are rejected ("table state Dueling") with an
    EMPTY reason; post-fix they are HELD and applied once the sideboard phase
    is entered, so no error frame arrives.

  LATE C: a submit 3.5 s later must be accepted (the 1-2 s vs 3 s discriminator).

  FLOW D: both seats submit -> game 2 must start (no regression to the normal
    sideboard flow).

  CONTROL E: a submit for a foreign table id must still be rejected with a
    non-empty reason (proxy seat gate).

Usage:
  python3 verify-early-submit-repro.py [--host 127.0.0.1] [--proxy 8101]
                                      [--xmage 17181] [--tag TAG]
                                      [--expect-fixed | --expect-base]

Exit: 0 all checks for the requested mode passed; 2 a check failed;
      3 harness/setup error. Without --expect-* the run is informational
      and exits 0 unless a scenario could not be executed at all.
"""

import argparse
import base64
import json
import os
import socket
import struct
import sys
import time

DECK_PATH = os.environ.get("EARLY_SUBMIT_DECK", "/tmp/early-submit-e2e/deck.dck")
DECK_TEXT_DEFAULT = "20 [M11:245] Mountain\n20 [3ED:201] Grizzly Bears\n"

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
    """Minimal RFC6455 client (stdlib only) — same shape as the other
    Mage.WebProxy/scripts/verify-*.py drivers."""

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
        self.frames = []          # (monotonic, msg) for every frame since connect
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

    def wait_type(self, types, timeout, note=None, autopilot=True):
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
            mt = msg.get("type")
            if autopilot:
                self.autopilot(msg)
            if mt in types:
                if note:
                    print(f"[info] {stamp(self.t0)} {self.user} <- "
                          f"{json.dumps(msg)[:200]}   ({note})", flush=True)
                return msg
        return None

    def autopilot(self, msg):
        """Answer benign engine prompts (mulligan keep / pick starting player)
        so the game keeps moving. Returns True when it answered."""
        if msg.get("type") != "action_prompt":
            return False
        at = msg.get("action_type")
        if at == "boolean":
            self.ws.send({"type": "boolean_choice", "value": False})  # Keep hand
            print(f"[info] {stamp(self.t0)} {self.user} answered boolean ask: "
                  f"{(msg.get('message') or '')[:70]!r}", flush=True)
            return True
        if at == "target":
            targets = msg.get("targets") or []
            me = next((t for t in targets if t.get("name") == self.user), None)
            pick = me or (targets[0] if targets else None)
            if pick is not None:
                self.ws.send({"type": "select_target", "targetId": pick.get("id")})
                print(f"[info] {stamp(self.t0)} {self.user} answered target ask "
                      f"(picked {pick.get('name')})", flush=True)
                return True
        return False

    def drain(self, seconds=1.0, autopilot=True):
        """Collect frames for a fixed window."""
        end = time.time() + seconds
        out = []
        while time.time() < end:
            try:
                self.ws.sock.settimeout(max(0.2, end - time.time()))
                msg = self.ws.recv()
            except (socket.timeout, TimeoutError):
                continue
            self.frames.append((time.monotonic(), msg))
            out.append(msg)
            if autopilot:
                self.autopilot(msg)
        return out

    def errors_since(self, idx=0):
        return [(ts, m) for ts, m in self.frames[idx:] if m.get("type") == "error"]

    def close(self):
        self.ws.close()


def deck_text():
    if os.path.exists(DECK_PATH):
        with open(DECK_PATH) as f:
            return f.read()
    print(f"[warn] deck file {DECK_PATH} missing — using inline default", flush=True)
    return DECK_TEXT_DEFAULT


def deck_payload_from_text(dtext):
    """Build the submit_deck wire shape from the [SET:NUM] deck file."""
    sub = {"cards": [], "sideboard": []}
    for line in dtext.splitlines():
        line = line.strip()
        if not line:
            continue
        qty, code, name = line.split(None, 2)
        setc, num = code.strip("[]").split(":")
        sub["cards"].append({"name": name, "setCode": setc,
                             "cardNumber": num, "count": int(qty)})
    return sub


def prompt_payload(msg):
    """sideboard_prompt cards/sideboard entries are already the submit wire
    shape {name,setCode,cardNumber,count}."""
    cards = [dict(c, count=c.get("count", 1)) for c in (msg.get("cards") or [])]
    side = [dict(c, count=c.get("count", 1)) for c in (msg.get("sideboard") or [])]
    return {"cards": cards, "sideboard": side}


REFUSAL_PREFIX = "Failed to submit deck:"


def refusal_reason(msg):
    """The REASON segment of a refusal frame's message.

    The proxy wraps every submit_deck refusal as 'Failed to submit deck: <x>'.
    The reported bug is an EMPTY <x> (the frame read exactly
    'Failed to submit deck: ', telling the player nothing). Returns the <x>
    part stripped, so callers can assert on its emptiness directly.
    """
    text = msg.get("message") or ""
    if text.startswith(REFUSAL_PREFIX):
        text = text[len(REFUSAL_PREFIX):]
    return text.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--proxy", type=int, default=8101)
    ap.add_argument("--xmage", type=int, default=17181)
    ap.add_argument("--late-delay", type=float, default=3.5,
                    help="seconds between the repro submits and the LATE retry")
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--expect-fixed", action="store_true",
                      help="post-fix gate: early submits accepted; refusals carry reasons")
    mode.add_argument("--expect-base", action="store_true",
                      help="base gate: the sync-on-game_over submit is refused with an EMPTY reason")
    ap.add_argument("--tag", default=str(int(time.time())))
    args = ap.parse_args()

    t0 = time.monotonic()
    tail = args.tag[-4:]
    owner_user = f"e3o{tail}"
    friend_user = f"e3f{tail}"
    table_name = f"early-submit-{tail}"

    dtext = deck_text()
    live_submit = deck_payload_from_text(dtext)
    print(f"=== EARLY SUBMIT REPRO ({'expect-fixed' if args.expect_fixed else 'expect-base' if args.expect_base else 'info'}) "
          f"proxy={args.host}:{args.proxy} xmage={args.host}:{args.xmage} ===")
    print(f"    table={table_name} owner={owner_user} friend={friend_user}", flush=True)

    try:
        owner = Client(args.host, args.proxy, args.xmage, owner_user, t0)
    except RuntimeError as e:
        print(f"[harness-error] owner connect: {e}")
        return 3

    # ── (1) create Bo3 table, 2 human seats; seat 0 auto-joined under the
    #        owner's account (deck text), seat 1 left open for the friend. ──
    owner.ws.send({
        "type": "create_table",
        "name": table_name,
        "gameType": "Two Player Duel",
        "deckType": "Constructed - Freeform",
        "winsNeeded": 2,
        "playerConfigs": [
            {"type": "HUMAN", "name": owner_user, "deck": dtext},
            {"type": "HUMAN", "name": "open-seat", "deck": ""},
        ],
    })
    msg = owner.wait_type(("table_created", "error"), 60, "create_table")
    if not check("(1) table_created received",
                 msg is not None and msg.get("type") == "table_created",
                 json.dumps(msg)[:300] if msg else "timeout"):
        return 3
    table_id = msg["tableId"]
    print(f"[info] {stamp(t0)} table id: {table_id}", flush=True)
    owner.drain(2.0)  # let the auto-join task seat the owner

    # ── (2) friend joins the open seat with its own deck ──
    try:
        friend = Client(args.host, args.proxy, args.xmage, friend_user, t0)
    except RuntimeError as e:
        print(f"[harness-error] friend connect: {e}")
        return 3
    friend.ws.send({"type": "join_table", "tableId": table_id, "deck": dtext})
    msg = friend.wait_type(("table_joined", "error"), 45, "join_table")
    if not check("(2) friend table_joined",
                 msg is not None and msg.get("type") == "table_joined",
                 json.dumps(msg)[:300] if msg else "timeout"):
        return 3

    # Both-human tables do NOT auto-start (proxy only auto-starts when a seat
    # is AI); the seated owner issues start_match explicitly, as the browser's
    # Start Match button does.
    owner.drain(3.0)  # let the seat-1 fill land (table -> READY_TO_START)
    owner.ws.send({"type": "start_match", "tableId": table_id})
    print(f"[info] {stamp(t0)} owner start_match sent", flush=True)

    # ── (3) both seats must reach the game and the match must start ──
    sg_o = owner.wait_type(("start_game", "error"), 90, "waiting start_game")
    sg_f = friend.wait_type(("start_game", "error"), 90, "waiting start_game")
    if not check("(3) match started (start_game on both seats)",
                 sg_o is not None and sg_o.get("type") == "start_game"
                 and sg_f is not None and sg_f.get("type") == "start_game",
                 f"owner={sg_o and sg_o.get('type')} friend={sg_f and sg_f.get('type')}"):
        return 2
    print(f"[info] {stamp(t0)} game 1 id={sg_f.get('gameId')}", flush=True)

    # ── CONTROL A: refusal while the game is still being played ──
    # The engine may still be at the starting-player choice / mulligan asks.
    # Answer benign prompts for a moment so the game has real priority.
    for _ in range(6):
        owner.drain(0.25)
        friend.drain(0.25)
    n_before_a = len(friend.frames)
    friend.ws.send({"type": "submit_deck", "tableId": table_id, "deck": live_submit})
    print(f"[info] {stamp(t0)} CONTROL A: live-game submit_deck sent", flush=True)
    friend.drain(2.0)
    ctrl_a = [(ts, m) for ts, m in friend.frames[n_before_a:] if m.get("type") == "error"]
    if ctrl_a:
        print(f"[CONTROL-A] live-game submit REFUSED; raw frame: {json.dumps(ctrl_a[0][1])}", flush=True)
        print(f"[CONTROL-A] reason text: {ctrl_a[0][1].get('message')!r}", flush=True)
    else:
        print("[CONTROL-A] live-game submit got NO error frame (unexpected)", flush=True)

    # ── REPRO B: game 1 ends; submit synchronously on the game_over frame ──
    print(f"[info] {stamp(t0)} conceding game 1 from seat B ({friend_user})", flush=True)
    friend.ws.send({"type": "concede"})

    game_over_at = None
    prompt_owner = None
    prompt_friend = None
    deadline = time.time() + 150
    sync_fired = False
    sync_send_times = []
    last_pass = 0.0
    while time.time() < deadline and not (prompt_owner and prompt_friend):
        progress = False
        for cli, label in ((owner, "owner"), (friend, "friend")):
            try:
                cli.ws.sock.settimeout(0.5)
                m = cli.ws.recv()
            except (socket.timeout, TimeoutError):
                continue
            except RuntimeError:
                continue
            progress = True
            cli.frames.append((time.monotonic(), m))
            mt = m.get("type")
            cli.autopilot(m)
            if mt == "error":
                print(f"[info] {stamp(t0)} {label} <- error frame: {json.dumps(m)}", flush=True)
            if mt == "game_over" and label == "friend" and not sync_fired:
                # THE REPRO: submit the moment the game-over frame lands —
                # inside the window between "clients told" and the table
                # entering SIDEBOARDING.
                game_over_at = time.monotonic()
                print(f"[info] {stamp(t0)} friend <- game_over", flush=True)
                for off in (0.0, 0.010, 0.025, 0.050):
                    if off > 0:
                        time.sleep(off - (time.monotonic() - game_over_at))
                    friend.ws.send({"type": "submit_deck", "tableId": table_id,
                                    "deck": live_submit})
                    sync_send_times.append(time.monotonic())
                    print(f"[info] {stamp(t0)} REPRO submit sent "
                          f"(+{(sync_send_times[-1] - game_over_at) * 1000:.0f} ms after game_over)",
                          flush=True)
                sync_fired = True
            if mt == "sideboard_prompt":
                print(f"[info] {stamp(t0)} {label} <- sideboard_prompt "
                      f"(tableId={m.get('tableId')} time={m.get('time')} "
                      f"cards={len(m.get('cards') or [])} sb={len(m.get('sideboard') or [])})",
                      flush=True)
                if label == "owner":
                    prompt_owner = (time.monotonic(), m)
                else:
                    prompt_friend = (time.monotonic(), m)
        # A conceded game only ENDS when the engine next reaches a priority
        # checkpoint (GameImpl.setConcedingPlayer queues the request and
        # checkConcede() runs on the next priority change). With no GUI the
        # game would otherwise stall at the priority holder forever, so both
        # seats keep passing priority — the same flow the browser produces —
        # until the game-over frame arrives.
        if game_over_at is None:
            now = time.monotonic()
            if now - last_pass > 0.4:
                last_pass = now
                for cli in (owner, friend):
                    cli.ws.send({"type": "pass_priority"})
        if not progress:
            time.sleep(0.02)

    if not check("(4) both seats reached SIDEBOARDING (sideboard_prompt received)",
                 prompt_owner is not None and prompt_friend is not None,
                 f"owner={prompt_owner is not None} friend={prompt_friend is not None}"):
        print(f"      owner last frames: {[m[1].get('type') for m in owner.frames[-12:]]}", flush=True)
        print(f"      friend last frames: {[m[1].get('type') for m in friend.frames[-12:]]}", flush=True)
        return 2

    # Collect the outcome of the REPRO submits: any error frame arriving after
    # the first repro submit and before the late retry belongs to scenario B.
    if game_over_at:
        print(f"[info] {stamp(t0)} prompt - game_over = "
              f"{(prompt_friend[0] - game_over_at) * 1000:+.0f} ms", flush=True)
    sync_errors = []
    sync_window_end = sync_send_times[0] + 2.5 if sync_send_times else time.monotonic()
    for ts, m in friend.frames:
        if sync_send_times and sync_send_times[0] <= ts <= sync_window_end and m.get("type") == "error":
            sync_errors.append((ts, m))
    if sync_errors:
        for ts, m in sync_errors[:3]:
            print(f"[REPRO-B] early submit REFUSED; raw frame: {json.dumps(m)}  "
                  f"(+{(ts - sync_send_times[0]) * 1000:.0f} ms after first repro submit)",
                  flush=True)
    else:
        print("[REPRO-B] no refusal for the early submits (held/accepted)", flush=True)

    submit_deck = prompt_payload(prompt_friend[1])
    if not submit_deck["cards"] and not submit_deck["sideboard"]:
        submit_deck = live_submit

    # ── LATE C: the retry after the phase is fully entered must be accepted ──
    time.sleep(max(0.0, args.late_delay))
    n_before_late = len(friend.frames)
    friend.ws.send({"type": "submit_deck", "tableId": table_id, "deck": submit_deck})
    print(f"[info] {stamp(t0)} LATE submit_deck sent "
          f"({(time.monotonic() - prompt_friend[0]) * 1000:.0f} ms after prompt receipt)", flush=True)
    friend.drain(3.0)
    late_err = [(ts, m) for ts, m in friend.frames[n_before_late:] if m.get("type") == "error"]
    if late_err:
        print(f"[REPRO] LATE submit REJECTED: {json.dumps(late_err[0][1])}", flush=True)

    # ── FLOW D: owner submits too; game 2 must start ──
    owner_deck = prompt_payload(prompt_owner[1])
    n_before_owner = len(owner.frames)
    owner.ws.send({"type": "submit_deck", "tableId": table_id,
                   "deck": owner_deck if owner_deck["cards"] else submit_deck})
    print(f"[info] {stamp(t0)} owner submit_deck sent", flush=True)
    owner.drain(2.0)
    owner_err = [(ts, m) for ts, m in owner.frames[n_before_owner:] if m.get("type") == "error"]
    # Game 2's start_game may already have arrived during the drain above —
    # scan the frames captured since the owner's submit BEFORE falling back to
    # a blocking read (wait_type only ever looks at NEW frames).
    game2 = next((m for _, m in owner.frames[n_before_owner:]
                  if m.get("type") == "start_game"), None)
    if game2 is None:
        game2 = owner.wait_type("start_game", 90, "waiting for game 2")
    check("(D) game 2 starts after both seats submit",
          game2 is not None and game2.get("type") == "start_game",
          json.dumps(game2)[:200] if game2 else "timeout")
    if game2 and game2.get("type") == "start_game":
        print(f"[info] {stamp(t0)} game 2 gameId={game2.get('gameId')}", flush=True)

    # ── CONTROL E: foreign-table submit refused with a real reason ──
    n_before_bad = len(friend.frames)
    friend.ws.send({"type": "submit_deck",
                    "tableId": "00000000-0000-0000-0000-000000000000",
                    "deck": submit_deck})
    friend.drain(2.0)
    bad_err = [(ts, m) for ts, m in friend.frames[n_before_bad:] if m.get("type") == "error"]

    if args.expect_fixed:
        check("(B) no refusal for the sync-on-game_over submits (post-fix: held/accepted)",
              not sync_errors, json.dumps(sync_errors[0][1]) if sync_errors else "")
        check("(A) live-game refusal carries a NON-EMPTY reason",
              bool(ctrl_a) and bool(refusal_reason(ctrl_a[0][1])),
              f"frame={json.dumps(ctrl_a[0][1]) if ctrl_a else 'none'}")
        check("(C) LATE submit accepted (1-2s-vs-3s discriminator)", not late_err,
              json.dumps(late_err[0][1]) if late_err else "")
        check("(E) foreign-table submit refused with a non-empty reason",
              bool(bad_err) and bool(refusal_reason(bad_err[0][1])),
              json.dumps(bad_err[0][1]) if bad_err else "no error frame")
    elif args.expect_base:
        check("(B) the sync-on-game_over submit was refused (pre-fix failure mode)",
              bool(sync_errors), "no refusal captured — window not hit")
        check("(B2) that refusal carries an EMPTY reason (the reported bug)",
              bool(sync_errors) and refusal_reason(sync_errors[0][1]) == "",
              f"message={sync_errors[0][1].get('message')!r}" if sync_errors else "no error frame")
        check("(A) live-game refusal carries an EMPTY reason (pre-fix)",
              bool(ctrl_a) and refusal_reason(ctrl_a[0][1]) == "",
              f"message={ctrl_a[0][1].get('message')!r}" if ctrl_a else "no error frame")
        check("(C) LATE submit accepted (1-2s-vs-3s discriminator)", not late_err,
              json.dumps(late_err[0][1]) if late_err else "")
        check("(E) foreign-table submit refused with a non-empty reason (proxy seat gate)",
              bool(bad_err) and bool(refusal_reason(bad_err[0][1])),
              json.dumps(bad_err[0][1]) if bad_err else "no error frame")
    else:
        print("[info] informational run — no mode assertions applied", flush=True)

    for cli in (owner, friend):
        cli.close()

    print()
    if failures:
        print(f"RESULT: FAIL ({len(failures)}): " + "; ".join(failures))
        return 2
    print("RESULT: PASS (all checks)")
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
