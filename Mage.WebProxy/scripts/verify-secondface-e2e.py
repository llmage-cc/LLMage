#!/usr/bin/env python3
"""Live E2E for secondFace/transformed serialization (dev XMage, scratch proxy).

Drives the freshly built proxy JAR on a scratch port against the dev XMage
server, creates a HUMAN-vs-AI table whose human seat plays a Delver of
Secrets (transform DFC) deck, joins + starts, and asserts the REAL wire
state:

  1. /config reachable on the scratch proxy.
  2. connect -> connected (the dev convention: user/pass on XMage server).
  3. create_table (vsAI) auto-joins + auto-starts -> game flows to the seat.
  4. game_update for the human seat carries, for Delver of Secrets in hand:
       - secondFace { name: "Insectile Aberration", imageUrl: ... }
       - the card's own name stays "Delver of Secrets"
  5. Every serialized Delver anywhere (hand) has secondFace — the flip
     button's data contract.

Run:  python3 verify-secondface-e2e.py --jar <path> --xmage 127.0.0.1:17171 \
        --user WebQFlipA --pass ''
"""
import argparse
import base64
import json
import os
import socket
import struct
import subprocess
import sys
import time
import urllib.request

FAILURES = []


def check(name, cond, detail=''):
    if cond:
        print(f'  PASS  {name}')
    else:
        FAILURES.append(name)
        print(f'  FAIL  {name} {detail}')


DECK = (
    '4 [MID:47] Delver of Secrets\n'
    '10 [MID:371] Galvanic Iteration\n'
    '12 [MID:276] Forest\n'
    '12 [MID:270] Island\n'
    '6 [MID:268] Plains\n'
    '6 [MID:269] Plains\n'
)


def start_proxy(jar, port):
    opens = [
        'java.base/java.lang', 'java.base/java.util', 'java.base/java.io',
        'java.base/java.lang.reflect', 'java.base/java.text', 'java.base/java.net',
        'java.base/java.security', 'java.base/sun.security.action',
        'java.base/sun.nio.ch', 'java.base/sun.reflect',
        'java.base/jdk.internal.access', 'java.base/jdk.internal.ref',
        'java.base/jdk.internal.misc', 'java.rmi/sun.rmi.transport',
    ]
    cmd = ['java', '-Djava.net.preferIPv4Stack=true']
    for o in opens:
        cmd.append(f'--add-opens={o}=ALL-UNNAMED')
    cmd += ['-jar', jar, '--bind', '127.0.0.1', '--port', str(port)]
    log = open(f'/tmp/verify-secondface-{port}.log', 'w')
    proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(60):
        try:
            with socket.create_connection(('127.0.0.1', port), timeout=1):
                return proc, log
        except OSError:
            time.sleep(0.3)
    raise RuntimeError('scratch proxy did not start')


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
            raise ConnectionError('closed during handshake')
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


def recv_frame(s, buf):
    def need(b, n):
        while len(b) < n:
            chunk = s.recv(65536)
            if not chunk:
                raise ConnectionError('closed mid-frame')
            b += chunk
        return b
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
    return op, buf[off:off + ln], buf[off + ln:]


def drain(s, rest, seconds, sink):
    s.settimeout(1.0)
    deadline = time.time() + seconds
    pending = rest
    n = 0
    while time.time() < deadline:
        try:
            op, payload, pending = recv_frame(s, pending)
        except (socket.timeout, TimeoutError):
            continue
        except (ConnectionError, OSError):
            break
        if op == 8:
            break
        if op != 1:
            continue
        try:
            msg = json.loads(payload.decode(errors='replace'))
        except json.JSONDecodeError:
            continue
        n += 1
        sink.append(msg)
    return n


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--jar', required=True)
    ap.add_argument('--xmage', default='127.0.0.1:17171')
    ap.add_argument('--user', default='WebQFlipA')
    ap.add_argument('--pass', dest='password', default='')
    args = ap.parse_args()

    port = 18330
    print('\n## secondFace/transformed serialization — live XMage through scratch proxy')
    proc, log = start_proxy(args.jar, port)
    s = None
    try:
        with urllib.request.urlopen(f'http://127.0.0.1:{port}/config', timeout=10) as r:
            cfg = json.loads(r.read().decode())
        check('scratch proxy /config', isinstance(cfg, dict), str(cfg))

        s, status, rest = ws_connect(port)
        check('ws handshake', ' 101 ' in status, status)

        host, xport = args.xmage.split(':')
        send_text(s, json.dumps({
            'type': 'connect',
            'username': args.user,
            'password': args.password,
            'server': host,
            'port': int(xport),
        }))
        msgs = []
        drain(s, rest, 10, msgs)
        conn = next((m for m in msgs if m.get('type') == 'connected'), None)
        check('connected to XMage', conn is not None,
              f'first msgs: {[m.get("type") for m in msgs[:5]]}')
        if not conn:
            return

        send_text(s, json.dumps({
            'type': 'create_table',
            'name': 'flip-verify',
            'gameType': 'Two Player Duel',
            'deckType': 'Constructed - Freeform',
            'winsNeeded': 1,
            'vsAI': True,
            'deck': DECK,
            'spectatorsAllowed': True,
            'password': '',
        }))
        # Auto-player: keep the hand, pass priority whenever asked, answer
        # choose_ability with the FIRST ability (never happens for this deck),
        # cancel target prompts — enough for the AI opponent's turns to run and
        # for Delver to hit the battlefield and flip on an upkeep.
        msgs = []

        def autoanswer(m):
            t = m.get('type')
            if t == 'action_prompt':
                at = m.get('action_type')
                if at == 'boolean':
                    # The FIRST boolean is the mulligan question ("Mulligan down
                    # to N?") — true mulligans, we want to KEEP: answer false.
                    # Every later boolean (draw?, keep hand?) is fine either way.
                    value = not autoanswer.mulligan_asked
                    if m.get('context') == 'mulligan':
                        autoanswer.mulligan_asked = True
                    send_text(s, json.dumps({'type': 'boolean_choice', 'value': value}))
                elif at == 'select':
                    # Cast the first playable card (Delver / land), then pass on
                    # later identical prompts (the epoch guard is server-side).
                    choices = m.get('choices') or []
                    if choices and m.get('context') == 'select_card':
                        send_text(s, json.dumps({'type': 'select_choice', 'index': choices[0].get('index', 0)}))
                    else:
                        send_text(s, json.dumps({'type': 'pass_priority'}))
                elif at == 'target':
                    # Cancel an optional target; a required one picks the first.
                    if m.get('required'):
                        ts = m.get('targets') or []
                        if ts:
                            send_text(s, json.dumps({'type': 'select_target', 'targetId': ts[0].get('id')}))
                    else:
                        send_text(s, json.dumps({'type': 'pass_priority'}))
                elif at == 'play_mana':
                    # Auto-pay pool by sending boolean false? That cancels. Tap nothing; pass:
                    send_text(s, json.dumps({'type': 'pass_priority'}))
            elif t == 'game_over':
                pass

        # drain in slices so autoanswers fire during the window
        autoanswer.mulligan_asked = False
        import threading
        stop = time.time() + 75
        pending_rest = rest
        while time.time() < stop:
            got = []
            drain(s, pending_rest, 3, got)
            if not got:
                continue
            for m in got:
                msgs.append(m)
                autoanswer(m)
            pending_rest = b''  # drain() consumed frames; rest is fully read after first drain
        kinds = {}
        for m in msgs:
            kinds[m.get('type')] = kinds.get(m.get('type'), 0) + 1
        print('  note: message kinds after create/start:', dict(sorted(kinds.items())))

        updates = [m for m in msgs if m.get('type') in ('game_update', 'game_delta')]
        check('game state flowed to the seat', len(updates) > 0, str(kinds))

        # Deep-scan every state payload for Delver of Secrets
        delver = None
        transformed_seen = False
        secondfaces = 0
        for m in updates:
            state = m.get('state') or m.get('delta') or {}
            if not isinstance(state, dict):
                continue
            players = state.get('players') or []
            for p in players:
                for zone in ('hand', 'battlefield', 'graveyard', 'exile', 'sideboard'):
                    for card in p.get(zone, []) or []:
                        if card.get('name') == 'Delver of Secrets':
                            delver = card
                            if card.get('transformed'):
                                transformed_seen = True
                        if card.get('secondFace'):
                            secondfaces += 1
                for card in (p.get('topCard') or []) and [p.get('topCard')] or []:
                    pass
            stack = state.get('stack') or []
            for card in stack:
                if card.get('secondFace'):
                    secondfaces += 1

        check('Delver of Secrets rendered to the seat', delver is not None,
              'no Delver card in any state payload (mulligan may have kept it hidden)')
        if delver:
            sf = delver.get('secondFace')
            check('secondFace present on Delver', isinstance(sf, dict), str(delver.get('secondFace')))
            if isinstance(sf, dict):
                check('secondFace names the other face', sf.get('name') == 'Insectile Aberration', str(sf.get('name')))
                check('secondFace carries an image URL', bool(sf.get('imageUrl')), '')
        check('some card serialized a secondFace', secondfaces > 0, f'counted {secondfaces}')
        # Delver flips on an upkeep with an instant/sorcery on top (deck carries
        # Galvanic Iteration). Probabilistic within the 75 s window: the seat
        # must have cast Delver AND have an instant on top at an upkeep. The
        # flag itself is gated server-side on PermanentCard's own transformed
        # state (PermanentCard ctor: backSide -> transformed=true), so this is
        # informational, not a gate.
        print('  note: transformed flag seen:', transformed_seen,
              '(true only when a Delver actually flips in the window)')

        # Duplicate-choice suppression: a two-faced card's ability is
        # registered by the server under BOTH keys (source + main card); the
        # proxy must emit exactly ONE choice per ability set. The stray twin
        # shows up as a select choice whose id matches NO rendered card while
        # another choice with the SAME name DOES (distinct same-named
        # permanents — two Forests — are legitimate separate choices).
        sel_prompts = [m for m in msgs if m.get('type') == 'action_prompt' and m.get('action_type') == 'select']
        strays = {}
        for sp in sel_prompts:
            choices = sp.get('choices', [])
            rendered_ids = set()
            for m2 in msgs:
                st = m2.get('state') or m2.get('delta') or {}
                if not isinstance(st, dict):
                    continue
                for p in st.get('players') or []:
                    for card in (p.get('hand') or []) + (p.get('battlefield') or []):
                        if card.get('id'):
                            rendered_ids.add(card.get('id'))
            named_rendered = {c.get('name') for c in choices if c.get('id') in rendered_ids}
            for c in choices:
                cid, cname = c.get('id'), c.get('name')
                if cid and cid not in rendered_ids and cname in named_rendered:
                    strays[cname] = cid
        check('no stray duplicate select choices', not strays, str(strays))
        print(f'  note: {len(sel_prompts)} select prompts scanned')
    finally:
        if s:
            try:
                s.close()
            except OSError:
                pass
        if proc:
            proc.terminate()

    print()
    if FAILURES:
        print('FAIL —', FAILURES)
        sys.exit(1)
    print('PASS')


if __name__ == '__main__':
    main()