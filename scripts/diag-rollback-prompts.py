#!/usr/bin/env python3
"""Diagnose: join a quick human-vs-MAD table and dump every action_prompt + error for 90s."""
import os, json, socket, struct, base64, time, sys
env = {}
with open(os.environ.get('XMAGE_TEST_ENV', os.path.expanduser('~/.xmage-test.env'))) as f:
    for line in f:
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            env[k] = v
HOST = (os.environ.get('XMAGE_PROXY_HOST', '127.0.0.1'), int(os.environ.get('XMAGE_PROXY_PORT', '8090')))
USER = env['TEST_USER']
TABLE_NAME = 'rb-diag-' + str(int(time.time()))
DECK_A = "40 Forest\n"
import socket as s
class WS:
    def __init__(self, user, pw):
        self.user = user
        raw = s.create_connection(HOST, timeout=15)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (f'GET /ws HTTP/1.1\r\nHost: {HOST[0]}:8090\r\nUpgrade: websocket\r\n'
               f'Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n'
               f'Origin: http://{HOST[0]}:8090\r\n\r\n')
        raw.sendall(req.encode())
        buf = b''
        while b'\r\n\r\n' not in buf:
            c = raw.recv(4096)
            if not c: raise ConnectionError('hs fail')
            buf += c
        head = buf.split(b'\r\n\r\n', 1)[0]
        assert ' 101 ' in head.split(b'\r\n')[0].decode()
        self.s = raw
        self.send({'type': 'connect', 'server': '127.0.0.1', 'port': 17171,
                   'username': user, 'password': pw})
        self.wait(['connected'], 30)
        print(f'[{USER}] connected', flush=True)
    def send(self, obj):
        data = json.dumps(obj).encode(); mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        if len(data) < 126:
            hdr = bytes([0x81, 0x80 | len(data)])
        else:
            hdr = bytes([0x81, 0x80 | 126]) + struct.pack('>H', len(data))
        self.s.sendall(hdr + mask + masked)
    def _recv(self):
        buf = b''
        def need(n):
            nonlocal buf
            while len(buf) < n:
                c = self.s.recv(65536)
                if not c: raise ConnectionError('closed')
                buf += c
        need(2)
        b1, b2 = buf[0], buf[1]; op = b1 & 0x0F; ln = b2 & 0x7F; off = 2
        if ln == 126: need(4); ln = struct.unpack('>H', buf[2:4])[0]; off = 4
        elif ln == 127: need(10); ln = struct.unpack('>Q', buf[2:10])[0]; off = 10
        need(off + ln)
        return op, buf[off:off+ln].decode(errors='replace')
    def wait(self, wants, timeout_secs):
        deadline = time.time() + timeout_secs
        while True:
            rem = deadline - time.time()
            if rem <= 0: raise TimeoutError(str(wants))
            self.s.settimeout(min(rem, 5))
            try: op, txt = self._recv()
            except s.timeout: continue
            if op == 8: raise ConnectionError(txt[:200])
            if op in (9, 10): continue
            try: msg = json.loads(txt)
            except json.JSONDecodeError: continue
            if msg.get('type', '') in wants: return msg

ws = WS(USER, env['TEST_PASS'])
ws.send({'type': 'create_table', 'name': TABLE_NAME,
         'gameType': 'Two Player Duel', 'deckType': 'Constructed - Freeform',
         'winsNeeded': 1, 'attackOption': 'LEFT', 'range': 'ALL',
         'skillLevel': 'CASUAL', 'timeLimit': 'NONE', 'bufferTime': 'NONE',
         'mulliganType': 'GAME_DEFAULT', 'freeMulligans': 0,
         'playerConfigs': [
             {'type': 'HUMAN', 'deck': DECK_A.strip(), 'isYou': True},
             {'type': 'COMPUTER_MAD', 'deck': '', 'isYou': False},
         ]})
msg = ws.wait(['table_created', 'error'], 30)
print('create ->', msg.get('type'), flush=True)
deadline = time.time() + 90
while time.time() < deadline:
    ws.s.settimeout(min(deadline - time.time(), 10))
    try: op, txt = ws._recv()
    except s.timeout: continue
    try: msg = json.loads(txt)
    except Exception: continue
    t = msg.get('type')
    if t == 'action_prompt':
        print('PROMPT:', json.dumps(msg)[:400], flush=True)
    elif t == 'error':
        print('ERROR:', msg.get('message'), flush=True)
    elif t in ('game_init', 'game_update', 'game_delta'):
        st = msg.get('state') or {}; d = msg.get('delta') or {}
        print('STATE type=%s turn=%s prompt=%s' % (t, st.get('turn') or d.get('turn'), str(msg.get('prompt'))[:100]), flush=True)
    elif t in ('game_over', 'game_end'):
        print('OVER:', str(msg)[:150], flush=True)
        break
print('DIAG DONE', flush=True)