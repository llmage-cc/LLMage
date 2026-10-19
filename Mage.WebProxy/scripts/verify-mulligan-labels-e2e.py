#!/usr/bin/env python3
"""Live E2E for the mulligan/boolean prompt button labels.

Drives a freshly built proxy JAR (scratch port) against the dev XMage server and
asserts the REAL wire behavior of WebProxyClient.handleGameAsk:

  1. mulligan ask  -> action_prompt(boolean, context=mulligan) carries the
                      ENGINE's option texts: yes_label="Mulligan",
                      no_label="Keep" (HumanPlayer.chooseMulligan:403-405) —
                      never the old hardcoded Yes/No;
  2. the right button answers FALSE: answering no_label ("Keep") keeps the hand
                      — the game must advance (turn >= 1 game state arrives);
  3. no error frames surface during the flow.

Run:  python3 scripts/verify-mulligan-labels-e2e.py --jar target/mage-web-proxy-1.4.62.jar \
        --xmage 127.0.0.1:17171 [--user <base>] [--pass <pw>]
Requires a running XMage server. Every run logs in under its own throwaway
account (XMage refuses a second login while a session is alive; names cap at 14
chars).
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

FAILURES = []


def check(name, cond, detail=''):
    if cond:
        print(f'  PASS  {name}')
    else:
        FAILURES.append(name)
        print(f'  FAIL  {name} {detail}')


def start_proxy(jar, port, props):
    # --add-opens: same list as start-proxy.sh — JBoss Remoting (bisocket)
    # deserialization on JDK 21 requires them on BOTH ends.
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

    log = open(f'/tmp/proxy-mulligan-{port}.log', 'w')
    proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT,
                            preexec_fn=os.setsid)
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


def ws_connect(port):
    raw = socket.create_connection(('127.0.0.1', port), timeout=15)
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


def send_text(sock, payload):
    data = payload.encode()
    mask = os.urandom(4)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    n = len(data)
    if n < 126:
        hdr = bytes([0x81, 0x80 | n])
    elif n < 65536:
        hdr = bytes([0x81, 0x80 | 126]) + struct.pack('>H', n)
    else:
        hdr = bytes([0x81, 0x80 | 127]) + struct.pack('>Q', n)
    sock.sendall(hdr + mask + masked)


class WsReader:
    """Incrementing websocket reader."""

    def __init__(self, sock, initial):
        self.sock = sock
        self.buf = initial

    def _need(self, n, timeout):
        self.sock.settimeout(timeout)
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise ConnectionError('closed mid-frame')
            self.buf += chunk

    def next_frame(self, timeout=60):
        self._need(2, timeout)
        b1, b2 = self.buf[0], self.buf[1]
        op = b1 & 0x0F
        ln = b2 & 0x7F
        off = 2
        if ln == 126:
            self._need(4, timeout)
            ln = struct.unpack('>H', self.buf[2:4])[0]
            off = 4
        elif ln == 127:
            self._need(10, timeout)
            ln = struct.unpack('>Q', self.buf[2:10])[0]
            off = 10
        self._need(off + ln, timeout)
        payload = self.buf[off:off + ln]
        self.buf = self.buf[off + ln:]
        if op == 8:
            return {'_closed': payload.decode(errors='replace')}
        if op != 1:
            return None
        try:
            return json.loads(payload.decode(errors='replace'))
        except json.JSONDecodeError:
            return None


DECK = "\n".join(
    ["4 " + c for c in
     ["Grizzly Bears", "Hill Giant", "Air Elemental", "Earth Elemental",
      "Savannah Lions", "Merfolk of the Pearl Trident", "Scathe Zombies",
      "Hurloon Minotaur", "Goblin Balloon Brigade", "War Mammoth"]] +
    ["4 " + c for c in
     ["Lightning Bolt", "Giant Growth", "Counterspell", "Dark Ritual",
      "Healing Salve", "Terror", "Unsummon", "Disenchant", "Fireball",
      "Stone Rain"]] +
    ["4 Mountain", "4 Forest", "4 Island", "4 Swamp", "4 Plains"]
)


def is_ask(msg):
    return (isinstance(msg, dict) and msg.get('type') == 'action_prompt'
            and msg.get('action_type') == 'boolean')


def run_game_flow(port, user, password, xmage):
    """Drives one full connect -> create -> mulligan flow. Returns a result dict."""
    parts = xmage.split(':')
    host, xmport = parts[0], int(parts[1])
    sock, status, rest = ws_connect(port)
    reader = WsReader(sock, rest)
    if ' 101 ' not in status:
        return {'_handshake': status}
    send_text(sock, json.dumps({
        'type': 'connect', 'server': host, 'port': xmport,
        'username': user, 'password': password,
    }))
    connected = read_until(reader, lambda m: m.get('type') in ('connected', 'connect_error'), 30)
    if connected.get('type') != 'connected':
        return {'_connect': connected}

    # Two-player duel vs the MAD AI (proxy auto-joins our seat + auto-starts),
    # freeform deck.
    send_text(sock, json.dumps({
        'type': 'create_table',
        'name': 'mulligan-labels-e2e',
        'gameType': 'Two Player Duel',
        'deckType': 'Constructed - Freeform',
        'winsNeeded': 1,
        'vsAI': True,
        'deck': DECK,
    }))
    created = read_until(reader, lambda m: m.get('type') in ('table_created', 'error'), 30)
    if created.get('type') != 'table_created':
        return {'_create': created}

    # The game MAY open with "Select a starting player" — a required GAME_TARGET
    # only the seat the engine picked as chooser receives (GameImpl.startGame:
    # pickChoosingPlayer is random; when the AI is the chooser the human seat
    # sees NO target prompt and goes straight to its mulligan ask). Wait for
    # EITHER prompt and answer the target only when it is ours.
    first = read_until(reader, lambda m: m.get('type') == 'action_prompt'
                       and m.get('action_type') in ('target', 'boolean'), 150)
    if first.get('type') == 'action_prompt' and first.get('action_type') == 'target':
        start_choices = first.get('targets') or []
        me = next((t for t in start_choices if t.get('name') == user), None)
        if me is None:
            return {'_start_target': {'_no_self_target': first}}
        send_text(sock, json.dumps({'type': 'select_target', 'targetId': me.get('id')}))
        # Now the mulligan asks. Wait for OUR first boolean ask.
        first_ask = read_until(reader, is_ask, 150)
    elif is_ask(first):
        first_ask = first
    else:
        return {'_ask': first}
    if not is_ask(first_ask):
        return {'_ask': first_ask}

    out = {'first_ask': first_ask}
    message = first_ask.get('message') or ''
    out['is_mulligan'] = 'mulligan' in message.lower()
    if out['is_mulligan']:
        out['labels_correct'] = (first_ask.get('yes_label') == 'Mulligan'
                                 and first_ask.get('no_label') == 'Keep'
                                 and first_ask.get('yes_label') != 'Yes')
    else:
        # A non-mulligan boolean ask still proves the label passthrough works:
        # the engine always sends real texts (e.g. "Heads"/"Tails").
        out['labels_correct'] = (isinstance(first_ask.get('yes_label'), str)
                                 and isinstance(first_ask.get('no_label'), str)
                                 and bool(first_ask.get('yes_label').strip())
                                 and bool(first_ask.get('no_label').strip()))

    # Answer with the RIGHT button ("Keep" -> boolean false). Semantics check:
    # keeping the hand must advance the game to turn 1.
    send_text(sock, json.dumps({'type': 'boolean_choice', 'value': False}))

    asks_after = []
    turn_after = None
    errors = []
    deadline = time.time() + 120
    while time.time() < deadline:
        msg = read_until(
            reader,
            lambda m: isinstance(m, dict) and m.get('type') in (
                'action_prompt', 'game_update', 'error', 'game_over'),
            45)
        if not isinstance(msg, dict) or msg.get('type') not in (
                'action_prompt', 'game_update', 'error', 'game_over'):
            break
        mtype = msg.get('type')
        if mtype == 'action_prompt':
            if is_ask(msg):
                asks_after.append(msg)
                # Still our turn to answer something: decline again (Keep on a
                # re-ask, No on any opening-hand action) and keep collecting.
                send_text(sock, json.dumps({'type': 'boolean_choice', 'value': False}))
        elif mtype == 'game_update':
            st = msg.get('state') or {}
            turn = st.get('turn')
            if isinstance(turn, int) and turn >= 1:
                turn_after = turn
                break
        elif mtype == 'error':
            errors.append(msg.get('message'))
        elif mtype == 'game_over':
            break

    out['asks_after_keep'] = [
        {'message': a.get('message'), 'yes_label': a.get('yes_label'),
         'no_label': a.get('no_label')} for a in asks_after]
    out['turn_after_keep'] = turn_after
    out['errors'] = errors
    try:
        sock.close()
    except OSError:
        pass
    return out


def read_until(reader, pred, timeout_secs):
    deadline = time.time() + timeout_secs
    while time.time() < deadline:
        try:
            msg = reader.next_frame(timeout=max(1, deadline - time.time()))
        except (ConnectionError, socket.timeout) as exc:
            return {'_error': str(exc)}
        if msg is None:
            continue
        if pred(msg):
            return msg
    return {'_timeout': True}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jar', required=True)
    ap.add_argument('--xmage', default='127.0.0.1:17171')
    ap.add_argument('--user', default='MulliLabels')
    ap.add_argument('--pass', dest='password', default='')
    args = ap.parse_args()

    port = 18240
    user = args.user[:13] + '1'  # own throwaway account per run

    print('\n## proxy delivers the engine button labels on GAME_ASK')
    proc, log = start_proxy(args.jar, port, [])
    try:
        if proc.poll() is not None:
            log.flush()
            print(open(f'/tmp/proxy-mulligan-{port}.log').read()[-2000:])
            check('proxy started', False, 'process exited')
            return 1
        out = run_game_flow(port, user, args.password, args.xmage)
        if any(k in out for k in ('_handshake', '_connect', '_create', '_ask', '_error', '_timeout')):
            check('game flow reached the first ask', False, json.dumps(out)[:400])
            return 1
        first = out['first_ask']
        check('first boolean ask is the mulligan question', out.get('is_mulligan') is True,
              json.dumps(first)[:300])
        check('mulligan labels are the engine texts (Mulligan/Keep), not Yes/No',
              out.get('labels_correct') is True,
              json.dumps({'yes_label': first.get('yes_label'),
                          'no_label': first.get('no_label')}))
        check('answer with the right button (Keep/false) advances the game',
              out.get('turn_after_keep') is not None,
              'asks after keep: ' + json.dumps(out.get('asks_after_keep'))[:300])
        check('no proxy error surfaced during the game',
              not out.get('errors'), json.dumps(out.get('errors'))[:300])
    finally:
        stop_proxy(proc)

    print(f"\n{'ALL CHECKS PASSED' if not FAILURES else str(len(FAILURES)) + ' CHECK(S) FAILED: ' + ', '.join(FAILURES)}")
    return 0 if not FAILURES else 1


if __name__ == '__main__':
    sys.exit(main())
