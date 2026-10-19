#!/usr/bin/env python3
"""token-art-replay-e2e.py — token art DURING A REPLAY, end to end.

While reviewing a replay, picking token art changed the token on
screen (the preference is applied locally) but the proxy answered
  "token_art_preference requires an active game"
(servererror-9-24-26.txt). Two contracts to pin, both name-based (no live game
needed):

  1. A replay viewer has NO table to relay to, so `token_art_preference` is
     not-applicable while a replay is loaded — the proxy must not error on it.
  2. The token art RESOLVER (`token_versions`) works while a replay is loaded:
     the right-click menu and the hover preview both resolve tokens through it.

Run (dev rig):  python3 token-art-replay-e2e.py --host 127.0.0.1 --port 8090
Run (public):   WEBPROXY_TOKEN=<from .env> python3 token-art-replay-e2e.py \
                    --host play.llmage.cc --port 443 --tls \
                    --origin https://play.llmage.cc
Exit: 0 when every assertion passes, 1 otherwise.
"""
import argparse
import importlib.util
import json
import os
import socket
import ssl
import sys
import time
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("ta", os.path.join(HERE, "token-art-e2e.py"))
ta = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ta)

USER = "TRpl" + uuid.uuid4().hex[:5]

failures, passes = [], []


def ok(name, cond, detail=""):
    if cond:
        passes.append(name)
        print("PASS  %s%s" % (name, ("  (" + detail + ")") if detail else ""))
    else:
        failures.append(name)
        print("FAIL  %s  %s" % (name, detail))


def wait_for(ws, want, timeout=30, sink=None):
    """Read frames until one of type `want` (a set) arrives; returns it.

    Every frame read is appended to `sink` (the caller's list) so later
    assertions can inspect what ELSE was on the wire.
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            m = ws.recv(timeout=3)
        except (socket.timeout, TimeoutError):
            continue
        except Exception as e:
            print("  socket ended while waiting for %s: %r" % (want, e))
            return None
        if sink is not None:
            sink.append(m)
        if m.get("type") in want:
            return m
    return None


def connect_ws(args):
    """Open the WS (TLS when asked) and run the connect handshake."""
    if args.tls:
        ctx = ssl.create_default_context()
        orig = socket.create_connection

        def tls_conn(addr, timeout=25):
            s = orig(addr, timeout=timeout)
            return ctx.wrap_socket(s, server_hostname=addr[0])

        socket.create_connection = tls_conn
        try:
            ws = ta.WS(args.host, args.port, origin=args.origin)
        finally:
            socket.create_connection = orig
    else:
        ws = ta.WS(args.host, args.port, origin=args.origin)

    connect = {"type": "connect", "server": "127.0.0.1", "port": 17171,
               "username": USER, "password": ""}
    token = args.token or os.environ.get("WEBPROXY_TOKEN")
    if token:
        connect["authToken"] = token
    ws.send(connect)
    seen = []
    m = wait_for(ws, {"connected", "connect_error"}, timeout=15, sink=seen)
    if m is None or m.get("type") != "connected":
        print("FATAL: connect failed: %s" % json.dumps(seen[-3:])[:300])
        return None
    print("connected as %s" % m.get("username"))
    return ws


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8090)
    ap.add_argument("--tls", action="store_true")
    ap.add_argument("--origin", default=None)
    ap.add_argument("--token", default=None)
    ap.add_argument("--game-id", default=None,
                    help="replay to load; default = smallest file in the listing")
    args = ap.parse_args()
    if args.origin is None:
        scheme = "https" if args.tls else "http"
        args.origin = "%s://%s:%d" % (scheme, args.host, args.port)

    print("=== token-art during REPLAY: %s:%d ===" % (args.host, args.port))
    ws = connect_ws(args)
    if ws is None:
        return 1

    # ── 1. List the replay files; pick the smallest (fastest load) ──
    ws.send({"type": "get_replay_files"})
    listing = wait_for(ws, {"replay_files"}, timeout=20)
    ok("replay file listing arrived", listing is not None)
    if listing is None:
        return finish()
    game_ids = listing.get("gameIds") or []
    ok("replay listing has files", len(game_ids) > 0, "n=%d" % len(game_ids))
    if not game_ids:
        return finish()
    meta = listing.get("fileMeta") or {}

    def size_of(gid):
        m = meta.get(gid) or {}
        return m.get("bytes") if isinstance(m, dict) else None

    gid = args.game_id or sorted(
        game_ids, key=lambda g: (size_of(g) is None, size_of(g) or 0))[0]
    print("replay: %s (bytes=%s of %d files)" % (gid, size_of(gid), len(game_ids)))

    # ── 2. Load + start it ──
    ws.send({"type": "replay_load", "gameId": gid})
    loaded = wait_for(ws, {"replay_game", "replay_done"}, timeout=45)
    ok("replay_load answered with replay_game", loaded is not None and loaded.get("type") == "replay_game",
       json.dumps(loaded)[:160] if loaded else "no answer")
    if loaded is None or loaded.get("type") != "replay_game":
        return finish()

    ws.send({"type": "replay_start"})
    first = wait_for(ws, {"replay_init", "replay_done"}, timeout=90)
    ok("first replay frame arrived", first is not None and first.get("type") == "replay_init",
       json.dumps(first)[:160] if first else "no frame")
    if first is None or first.get("type") != "replay_init":
        return finish()

    # ── 3. The resolver works while a replay is loaded (menu + hover need it) ──
    ws.send({"type": "token_versions", "tokenName": "Soldier",
             "requestId": "replay-resolve"})
    res = wait_for(ws, {"token_versions_result", "error"}, timeout=25)
    ok("token_versions answered during replay", res is not None and res.get("type") == "token_versions_result",
       json.dumps(res)[:200] if res else "no answer")
    versions = ((res or {}).get("result") or {}).get("versions") or []
    default_url = ((res or {}).get("result") or {}).get("defaultUrl")
    ok("resolver returns printings during replay", len(versions) > 0, "n=%d" % len(versions))
    ok("resolver returns a default art", bool(default_url), default_url or "")

    # ── 4. THE REPORTED DEFECT: a preference during a replay must NOT error ──
    # A replay has no table to relay to; the browser applies the choice locally.
    # The old proxy answered "token_art_preference requires an active game".
    pick = default_url or (versions[0]["url"] if versions else None)
    ws.send({"type": "token_art_preference", "tokenName": "Soldier", "url": pick})
    seen = []
    time.sleep(6)  # a short window; the answer/error would be immediate
    try:
        ws.drain(3, seen)
    except Exception:
        pass
    def is_pref_error(m):
        if m.get("type") not in ("error", "server_error"):
            return False
        return "token_art_preference" in json.dumps(m) or "active game" in json.dumps(m)

    errs = [m for m in seen if is_pref_error(m)]
    ok("no error for token_art_preference during replay", len(errs) == 0,
       json.dumps(errs[0])[:200] if errs else "clean")
    relays = [m for m in seen if m.get("type") == "token_art_preferences"]
    ok("no relay attempted during replay (nothing to relay to)", len(relays) == 0,
       json.dumps(relays[0])[:160] if relays else "clean")

    # ── 5. Connection still healthy after that exchange ──
    ws.send({"type": "token_versions", "tokenName": "Treasure",
             "requestId": "replay-after"})
    res2 = wait_for(ws, {"token_versions_result", "error"}, timeout=20)
    ok("connection healthy after the preference message",
       res2 is not None and res2.get("type") == "token_versions_result",
       json.dumps(res2)[:160] if res2 else "no answer")

    # ── 6. Clean stop ──
    ws.send({"type": "replay_stop"})
    done = wait_for(ws, {"replay_done"}, timeout=25)
    ok("replay_stop answered with replay_done", done is not None,
       json.dumps(done)[:160] if done else "no answer")

    return finish()


def finish():
    print()
    print("passed %d, failed %d" % (len(passes), len(failures)))
    for f in failures:
        print("  FAILED: %s" % f)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
