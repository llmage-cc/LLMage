#!/usr/bin/env python3
"""
Capability-gating e2e.

Connects to the web proxy's WebSocket like the browser does, sends `connect`,
and asserts on the `connected` message's capabilities object. Optionally then
sends an LLM-carrying message that must be rejected when the server lacks
the capability.

Usage: verify-capability-gating.py <proxyHost> <proxyPort> <xmageHost> <xmagePort> <mode>
  mode = stock | llmage   (which XMage server the proxy is pointed at)
"""
import json
import sys
import urllib.request

try:
    import websocket  # websocket-client
except ImportError:
    print("FATAL: pip install websocket-client", file=sys.stderr)
    sys.exit(64)


def http_config(proxy):
    with urllib.request.urlopen(f"http://{proxy}/config", timeout=10) as r:
        return json.loads(r.read().decode())


def main():
    import websocket
    proxy_host, proxy_port, xmage_host, xmage_port, mode = (
        sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5])
    proxy = f"{proxy_host}:{proxy_port}"

    cfg = http_config(proxy)
    print(f"proxy /config: {cfg}")

    ws = websocket.create_connection(f"ws://{proxy}/ws", timeout=20)
    user = "captest1"
    ws.send(json.dumps({
        "type": "connect", "server": xmage_host, "port": int(xmage_port),
        "username": user, "password": "",
    }))
    capabilities = None
    connect_error = None
    # read frames until connected/connect_error (bounded)
    for _ in range(50):
        raw = ws.recv()
        if not raw:
            continue
        msg = json.loads(raw)
        t = msg.get("type")
        if t == "connected":
            capabilities = msg.get("capabilities")
            break
        if t == "connect_error":
            connect_error = msg.get("message")
            break
        # ignore tables/ping chatter
    if connect_error:
        print(f"FAIL: connect_error: {connect_error}")
        sys.exit(2)
    if capabilities is None:
        print("FAIL: no capabilities object in connected message")
        sys.exit(3)
    print(f"capabilities: {json.dumps(capabilities)}")

    llm = bool(capabilities.get("llm"))
    if mode == "stock" and llm:
        print("FAIL: stock server reported llm=true")
        sys.exit(4)
    if mode == "llmage" and not llm:
        print("FAIL: LLMage server reported llm=false")
        sys.exit(5)

    if not llm:
        # gated path: fetch_models must be REJECTED with a capability error
        ws.send(json.dumps({
            "type": "fetch_models",
            "endpoint": "http://127.0.0.1:9/v1/chat/completions",
            "apiKey": "x",
        }))
        rejected = False
        for _ in range(30):
            raw = ws.recv()
            if not raw:
                continue
            msg = json.loads(raw)
            if msg.get("type") == "error" and "does not support LLM features" in msg.get("message", ""):
                rejected = True
                break
            if msg.get("type") in ("models_result", "models_error"):
                print(f"FAIL: fetch_models reached the handler (got {msg.get('type')})")
                sys.exit(5)
        if not rejected:
            print("FAIL: fetch_models was not capability-rejected (no error within window)")
            sys.exit(6)
        print("PASS: fetch_models rejected on stock server")
    else:
        # LLMage: fetch_models must reach the model-fetch path (error about the
        # bogus endpoint is fine — the gate must NOT fire)
        ws.send(json.dumps({
            "type": "fetch_models",
            "endpoint": "http://127.0.0.1:9/v1/chat/completions",
            "apiKey": "x",
        }))
        got_model_path = False
        for _ in range(30):
            raw = ws.recv()
            if not raw:
                continue
            msg = json.loads(raw)
            if msg.get("type") in ("models_result", "models_error"):
                got_model_path = True
                break
            if msg.get("type") == "error" and "does not support LLM features" in msg.get("message", ""):
                break
        if not got_model_path:
            print("FAIL: fetch_models was capability-gated on an LLM server")
            sys.exit(7)
        print("PASS: fetch_models reached the model path on LLMage server")

    ws.close()
    print(f"PASS: mode={mode} llm={llm} serverVersion={capabilities.get('serverVersion')}")


if __name__ == "__main__":
    main()