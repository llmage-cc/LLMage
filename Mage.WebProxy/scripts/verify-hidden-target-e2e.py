#!/usr/bin/env python3
"""Live E2E for the hideServerTarget deployment toggle (scratch proxy, no Docker).

Drives a freshly built proxy JAR on a scratch port and asserts the real
observable behavior of the feature over HTTP + WebSocket:

  1. unpinned proxy            -> GET /config {"hideServerTarget":false};
                                  connect WITH server/port reaches XMage
  2. pinned + hide=true        -> GET /config {"hideServerTarget":true};
                                  connect WITHOUT server/port reaches XMage
                                  (the exact message the hidden form sends)
  3. pinned + hide=true, no pin -> the proxy REFUSES TO START (fail fast)

Run:  python3 verify-hidden-target-e2e.py --jar <path> --xmage 127.0.0.1:17171
Requires a running XMage server (the connect must actually succeed) and an
account: --user/--pass (defaults to the local dev convention).
"""
import argparse
import base64
import json
import os
import signal
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request

FAILURES = []


def check(name, cond, detail=''):
    if cond:
        print(f'  PASS  {name}')
    else:
        FAILURES.append(name)
        print(f'  FAIL  {name} {detail}')


def start_proxy(jar, port, props):
    # --add-opens: same list as start-proxy.sh — JBoss Remoting (bisocket)
    # deserialization on JDK 21 requires them on BOTH ends of the connection,
    # otherwise login fails with "Wrong java version - check your client
    # running scripts and params."
    opens = [
        'java.base/java.lang', 'java.base/java.util', 'java.base/java.io',
        'java.base/java.lang.reflect', 'java.base/java.text', 'java.base/java.net',
        'java.base/java.security', 'java.base/sun.security.action',
        'java.base/sun.nio.ch', 'java.base/sun.reflect',
        'java.base/jdk.internal.access', 'java.base/jdk.internal.ref',
        'java.base/jdk.internal.misc',
    ]
    cmd = ['java', '-Djava.net.preferIPv4Stack=true']
    for o in opens:
        cmd.append(f'--add-opens={o}=ALL-UNNAMED')
    cmd.append('--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED')
    for p in props:
        cmd.append(f'-D{p}')
    cmd += ['-jar', jar, '--bind', '127.0.0.1', '--port', str(port)]

    log = open(f'/tmp/proxy-toggle-{port}.log', 'w')
    proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT,
                            preexec_fn=os.setsid)
    # wait for the port to accept
    deadline = time.time() + 30
    while time.time() < deadline:
        with socket.socket() as s:
            s.settimeout(1)
            if s.connect_ex(('127.0.0.1', port)) == 0:
                return proc, log
        if proc.poll() is not None:
            return proc, log
        time.sleep(0.5)
    return proc, log


def stop_proxy(proc):
    if proc.poll() is not None:
        return
    os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        os.killpg(os.getpgid(proc.pid), signal.SIGKILL)


def get_config(port):
    req = urllib.request.Request(f'http://127.0.0.1:{port}/config')
    with urllib.request.urlopen(req, timeout=10) as r:
        return r.status, json.loads(r.read().decode())


def ws_connect(port):
    raw = socket.create_connection(('127.0.0.1', port), timeout=10)
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        f'GET /ws HTTP/1.1\r\n'
        f'Host: 127.0.0.1:{port}\r\n'
        f'Origin: http://127.0.0.1:{port}\r\n'
        f'Upgrade: websocket\r\n'
        f'Connection: Upgrade\r\n'
        f'Sec-WebSocket-Key: {key}\r\n'
        f'Sec-WebSocket-Version: 13\r\n\r\n'
    )
    raw.sendall(req.encode())
    buf = b''
    while b'\r\n\r\n' not in buf:
        chunk = raw.recv(4096)
        if not chunk:
            raise ConnectionError('closed during handshake: ' + buf.decode(errors='replace'))
        buf += chunk
    head, rest = buf.split(b'\r\n\r\n', 1)
    return raw, head.split(b'\r\n')[0].decode(), rest


def send_text(s, payload):
    data = payload.encode()
    mask = os.urandom(4)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    if len(data) < 126:
        hdr = bytes([0x81, 0x80 | len(data)])
    elif len(data) < 65536:
        hdr = bytes([0x81, 0x80 | 126]) + struct.pack('>H', len(data))
    else:
        hdr = bytes([0x81, 0x80 | 127]) + struct.pack('>Q', len(data))
    s.sendall(hdr + mask + masked)


def read_until(s, rest, want_type=None, max_frames=40, timeout_secs=20):
    def need(buf, n):
        while len(buf) < n:
            chunk = s.recv(4096)
            if not chunk:
                raise ConnectionError('closed mid-frame')
            buf += chunk
        return buf

    s.settimeout(timeout_secs)
    pending = rest
    for _ in range(max_frames):
        buf = pending
        buf = need(buf, 2)
        b1, b2 = buf[0], buf[1]
        op = b1 & 0x0F
        ln = b2 & 0x7F
        off = 2
        if ln == 126:
            buf = need(buf, 4)
            ln = struct.unpack('>H', buf[2:4])[0]
            off = 4
        elif ln == 127:
            buf = need(buf, 10)
            ln = struct.unpack('>Q', buf[2:10])[0]
            off = 10
        buf = need(buf, off + ln)
        payload = buf[off:off + ln]
        pending = b''
        if op == 8:
            return {'_closed': payload.decode(errors='replace')}
        if op != 1:
            continue
        try:
            msg = json.loads(payload.decode(errors='replace'))
        except json.JSONDecodeError:
            continue
        if want_type is None or msg.get('type') == want_type:
            return msg
    return {'_timeout': True}


def connect_case(port, connect_msg, want='connected'):
    s, status, rest = ws_connect(port)
    try:
        if ' 101 ' not in status:
            return {'_handshake': status}
        send_text(s, json.dumps(connect_msg))
        return read_until(s, rest, want_type=want)
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jar', required=True)
    ap.add_argument('--xmage', default='127.0.0.1:17171')
    ap.add_argument('--user', default='CityOfBronze')
    ap.add_argument('--pass', dest='password', default='')
    args = ap.parse_args()

    xm_host, xm_port = args.xmage.split(':')
    base_port = 18210
    # XMage refuses a second login for an account that still holds a session, and
    # a closed socket does not release the seat immediately. Every connect in
    # this script therefore uses its own account name (XMage caps names at 14).
    base_user = args.user[:13]
    user_seq = iter(range(1, 20))

    def next_user():
        return f'{base_user}{next(user_seq)}'


    # ── 1. Unpinned proxy: fields are the client's, /config says false ──
    print('\n## unpinned proxy (the local/LAN default)')
    proc, log = start_proxy(args.jar, base_port, [])
    try:
        if proc.poll() is not None:
            log.flush()
            print(open(f'/tmp/proxy-toggle-{base_port}.log').read()[-2000:])
            check('unpinned proxy started', False, 'process exited')
        else:
            status, cfg = get_config(base_port)
            check('GET /config is 200', status == 200, str(status))
            check('/config reports hideServerTarget=false', cfg.get('hideServerTarget') is False, json.dumps(cfg))
            msg = connect_case(base_port, {
                'type': 'connect', 'server': xm_host, 'port': int(xm_port),
                'username': next_user(), 'password': args.password,
            })
            check('connect WITH server/port succeeds', msg.get('type') == 'connected', json.dumps(msg)[:300])
            # And the unpinned case still requires them.
            msg2 = connect_case(base_port, {
                'type': 'connect', 'username': next_user(), 'password': args.password,
            }, want='connect_error')
            check('connect WITHOUT server/port is refused when unpinned',
                  msg2.get('type') == 'connect_error', json.dumps(msg2)[:300])
    finally:
        stop_proxy(proc)

    # ── 2. Pinned + hidden: the hidden form's message must work ──
    print('\n## pinned + hideServerTarget=true (the public deployment)')
    proc, log = start_proxy(args.jar, base_port + 1,
                            ['webproxy.pinnedXmageTarget=' + args.xmage,
                             'webproxy.hideServerTarget=true'])
    try:
        if proc.poll() is not None:
            print(open(f'/tmp/proxy-toggle-{base_port + 1}.log').read()[-2000:])
            check('pinned proxy started', False, 'process exited')
        else:
            status, cfg = get_config(base_port + 1)
            check('GET /config is 200', status == 200, str(status))
            check('/config reports hideServerTarget=true', cfg.get('hideServerTarget') is True, json.dumps(cfg))
            # The EXACT payload the hidden form builds: no server, no port.
            msg = connect_case(base_port + 1, {
                'type': 'connect', 'username': next_user(), 'password': args.password,
            })
            check('connect WITHOUT server/port succeeds (hidden-form payload)',
                  msg.get('type') == 'connected', json.dumps(msg)[:300])
            # A client that still sends a target must be ignored, not honored.
            msg2 = connect_case(base_port + 1, {
                'type': 'connect', 'server': '203.0.113.9', 'port': 1,
                'username': next_user(), 'password': args.password,
            })
            check('a client-supplied target is ignored (still connects to the pin)',
                  msg2.get('type') == 'connected', json.dumps(msg2)[:300])
    finally:
        stop_proxy(proc)

    # ── 3. Contradictory config must refuse to boot ──
    print('\n## hideServerTarget=true with NO pinned target (contradictory)')
    proc, log = start_proxy(args.jar, base_port + 2, ['webproxy.hideServerTarget=true'])
    time.sleep(2)
    exited = proc.poll() is not None
    check('proxy exits instead of booting a broken deployment', exited,
          f'still running after 2s (poll={proc.poll()})')
    stop_proxy(proc)
    log.flush()
    boot_log = open(f'/tmp/proxy-toggle-{base_port + 2}.log').read()
    check('boot log names the missing pinned target',
          'hideServerTarget' in boot_log and 'pinnedXmageTarget' in boot_log,
          boot_log[-800:])
    # The port must not be serving anything.
    with socket.socket() as s:
        s.settimeout(1)
        check('nothing is listening on the refused port',
              s.connect_ex(('127.0.0.1', base_port + 2)) != 0)

    print(f"\n{'ALL CHECKS PASSED' if not FAILURES else str(len(FAILURES)) + ' CHECK(S) FAILED: ' + ', '.join(FAILURES)}")
    return 0 if not FAILURES else 1


if __name__ == '__main__':
    sys.exit(main())
