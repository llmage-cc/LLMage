#!/usr/bin/env python3
"""
verify-capability-gating-legc.py — capability-gating matrix leg (c) e2e.

Local VARIANT of Mage.WebProxy/scripts/verify-capability-gating.py (that
script stays untouched; its committed semantics — mode stock|llmage asserting
the SERVER-side capability — cover legs (a)/(b)). Leg (c) is the INVERSE pair:
a STOCK BUILD (proxy jar -Pstock + VITE_LLMAGE=false bundle) pointed at an
LLMAGE SERVER, fully local. Its contract differs:

  1. connect must succeed (the stock proxy can talk to an LLMage server);
  2. the capability probe is honest by construction: it derives from the
     server's own ServerState.playerTypes, so an LLMage server reports
     llm=true EVEN to a stock proxy. The client-side suppression lives in the
     BUNDLE (compile-time __LLMAGE__=false tree-shake), verified by greps and
     the browser dogfood, not by the wire. So this script asserts
       - /config reports llmage:false   (stock artifact)
       - capabilities.llm == true       (LLMage server, probed honestly)
  3. get_finished_matches must return WITHOUT InvalidClassException — the
     proxy deserializes the server's MatchView records (suid pin);
  4. table_list must arrive (base lobby path works).

Usage: verify-capability-gating-legc.py <proxyHost> <proxyPort> <xmageHost> <xmagePort>
"""
import json
import socket
import struct
import sys
import urllib.request
import base64
import os
import hashlib


def http_config(proxy):
    with urllib.request.urlopen(f"http://{proxy}/config", timeout=10) as r:
        return json.loads(r.read().decode())


class Ws:
    """Minimal RFC6455 client (stdlib only) for ws://<host><path> — enough for
    the proxy's text frames. The committed script needs websocket-client, which
    is absent on this box; this keeps the leg runnable without new deps."""

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
        if op == 0x9:      # ping
            self.sock.sendall(b"\x8a\x80" + mask + b"" if False else b"\x8a\x80")
            return self.recv()
        if op == 0x8:      # close
            raise RuntimeError("WS closed by peer")
        return json.loads(payload.decode())


def main():
    proxy_host, proxy_port, xmage_host, xmage_port = (
        sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4]))
    proxy = f"{proxy_host}:{proxy_port}"

    cfg = http_config(proxy)
    print(f"proxy /config: {cfg}")
    if cfg.get("llmage") is not False:
        print("FAIL: /config must report llmage:false for a STOCK proxy")
        sys.exit(4)
    print("PASS: /config llmage:false (stock artifact)")

    ws = Ws(proxy_host, proxy_port)
    ws.send({
        "type": "connect", "server": xmage_host, "port": int(xmage_port),
        "username": "captestlegc", "password": "",
    })
    capabilities = None
    for _ in range(50):
        msg = ws.recv()
        t = msg.get("type")
        if t == "connected":
            capabilities = msg.get("capabilities")
            break
        if t == "connect_error":
            print(f"FAIL: connect_error: {msg.get('message')}")
            sys.exit(2)
    if capabilities is None:
        print("FAIL: no capabilities object in connected message")
        sys.exit(3)
    print(f"capabilities: {json.dumps(capabilities)}")

    llm = bool(capabilities.get("llm"))
    player_types = capabilities.get("playerTypes") or []
    if not llm:
        print("FAIL: LLMage server must report llm=true (probe derives from "
              "ServerState.playerTypes — an llm=false here means the probe or "
              "plugin load failed)")
        sys.exit(5)
    if "COMPUTER_LLM" not in player_types:
        print(f"FAIL: playerTypes {player_types} must contain COMPUTER_LLM")
        sys.exit(5)
    print(f"PASS: connect OK; probe reports llm=true from server playerTypes "
          f"{player_types} (LLMage server), version {capabilities.get('serverVersion')}")

    # base lobby: table_list must flow
    ws.send({"type": "get_tables"})
    got_tables = None
    for _ in range(30):
        msg = ws.recv()
        if msg.get("type") == "table_list":
            got_tables = msg.get("tables")
            break
    if got_tables is None:
        print("FAIL: no table_list response")
        sys.exit(6)
    print(f"PASS: table_list works ({len(got_tables)} tables)")

    # Finished-match MatchView records must deserialize
    # (InvalidClassException would surface as an error frame)
    ws.send({"type": "get_finished_matches"})
    got_matches = False
    invalid_class = False
    for _ in range(30):
        msg = ws.recv()
        if msg.get("type") == "finished_matches":
            got_matches = True
            break
        if msg.get("type") == "error":
            m = msg.get("message", "")
            if "InvalidClass" in m or "serial" in m.lower():
                invalid_class = True
            break
    if invalid_class or not got_matches:
        print(f"FAIL: get_finished_matches broken (got={got_matches} invalidClass={invalid_class})")
        sys.exit(7)
    print("PASS: get_finished_matches deserialized")

    # LLM-carrying message against the LLMage server THROUGH A STOCK PROXY:
    # the stock jar has the dispatch gates intact (requireServerLlmGate is
    # source-shared); the capability is present, so fetch_models reaches the
    # handler. What the stock build removes is the BROWSER-side bridge (bundle
    # compile-out) — asserted by the bundle greps + browser dogfood, not here.
    ws.send({
        "type": "fetch_models",
        "endpoint": "http://127.0.0.1:9/v1/chat/completions",
        "apiKey": "x",
    })
    reached = False
    for _ in range(30):
        msg = ws.recv()
        if msg.get("type") in ("models_result", "models_error"):
            reached = True
            break
        if msg.get("type") == "error":
            break
    if not reached:
        print("FAIL: fetch_models did not reach the model path (dispatch gate state wrong)")
        sys.exit(8)
    print("PASS: fetch_models dispatch reaches the model path "
          "(server has the capability; browser-side bridge is compile-time absent)")

    ws.close()
    print(f"RESULT: LEG-C PASS (stock build <-> LLMage server 127.0.0.1:{xmage_port}, proxy {proxy})")


if __name__ == "__main__":
    main()
