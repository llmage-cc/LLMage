#!/usr/bin/env python3
"""E2E for fix-replay-rollback-truncate: TWO HUMAN seats (also exercises the
rollback approval dialog). Creator = CityOfBronze (seat 0), joiner = RbTester2
(seat 1). Creator starts the match. Both auto-answer prompts (keep mulligan,
pick first target, pass). At turn >= 3 the creator requests rollback (0 =
start of current turn); the joiner ACCEPTS via user_request_response. After
the "Rolling back" inform is seen, keep passing until the game re-advances a
turn past the target, then creator concedes. Caller probes saved/<gameId>.game
(state[0] must be turn 1, turns non-decreasing) and the server log for the
truncate INFO line. Credentials come from scratch seed-rb/.creds (never printed).
"""
import os, json, socket, struct, base64, time, sys, threading

SCRATCH = os.environ.get('XMAGE_RB_CREDS', os.path.expanduser('~/.xmage-rb-e2e.creds'))
creds = {}
with open(SCRATCH) as f:
    for line in f:
        if '=' in line:
            k, v = line.strip().split('=', 1)
            creds[k] = v
USER1, PASS1 = 'CityOfBronze', creds['RB_E2E_PASS1']
USER2, PASS2 = 'RbTester2', creds['RB_E2E_PASS2']
HOST = (os.environ.get('XMAGE_PROXY_HOST', '127.0.0.1'), int(os.environ.get('XMAGE_PROXY_PORT', '8090')))
TABLE_NAME = 'rb-e2e-' + str(int(time.time()))
DECK_A = "36 Forest\n24 Grizzly Bears\n"
DECK_B = "36 Mountain\n24 Hill Giant\n"

lock = threading.Lock()
def log(who, *a):
    with lock:
        print(f'[{time.strftime("%H:%M:%S")}][{who}]', *a, flush=True)

class WS:
    def __init__(self, user, pw):
        self.user = user
        raw = socket.create_connection(HOST, timeout=15)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (f'GET /ws HTTP/1.1\r\nHost: {HOST[0]}:8090\r\nUpgrade: websocket\r\n'
               f'Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n'
               f'Origin: http://{HOST[0]}:8090\r\n\r\n')
        raw.sendall(req.encode())
        buf = b''
        while b'\r\n\r\n' not in buf:
            c = raw.recv(4096)
            if not c:
                raise ConnectionError('handshake fail')
            buf += c
        head = buf.split(b'\r\n\r\n', 1)[0]
        assert ' 101 ' in head.split(b'\r\n')[0].decode(), head[:200]
        self.s = raw
        self.send({'type': 'connect', 'server': '127.0.0.1', 'port': 17171,
                   'username': user, 'password': pw})
        self.wait(['connected'], 30)
        log(user, 'connected')

    def send(self, obj):
        data = json.dumps(obj).encode(); mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        if len(data) < 126:
            hdr = bytes([0x81, 0x80 | len(data)])
        else:
            hdr = bytes([0x81, 0x80 | 126]) + struct.pack('>H', len(data))
        self.s.sendall(hdr + mask + masked)

    def _recv(self):
        # NOTE: leftover bytes after a frame MUST be preserved — multiple frames
        # can arrive in a single TCP read (prompt + error back-to-back), and
        # dropping the remainder silently corrupts the stream (stalled the
        # 2026-09-20 E2E run8).
        buf = getattr(self, '_buf', b'')
        def need(n):
            nonlocal buf
            while len(buf) < n:
                c = self.s.recv(65536)
                if not c:
                    raise ConnectionError('closed mid-frame')
                buf += c
        need(2)
        b1, b2 = buf[0], buf[1]; op = b1 & 0x0F; ln = b2 & 0x7F; off = 2
        if ln == 126:
            need(4); ln = struct.unpack('>H', buf[2:4])[0]; off = 4
        elif ln == 127:
            need(10); ln = struct.unpack('>Q', buf[2:10])[0]; off = 10
        need(off + ln)
        self._buf = buf[off + ln:]
        return op, buf[off:off + ln].decode(errors='replace')

    def wait(self, wants, timeout_secs):
        deadline = time.time() + timeout_secs
        while True:
            rem = deadline - time.time()
            if rem <= 0:
                raise TimeoutError(f'timed out waiting for {wants}')
            self.s.settimeout(min(rem, 5))
            try:
                op, txt = self._recv()
            except socket.timeout:
                continue
            if op == 8:
                raise ConnectionError(f'server closed while waiting {wants}: {txt[:200]}')
            if op in (9, 10):
                continue
            try:
                msg = json.loads(txt)
            except json.JSONDecodeError:
                continue
            if msg.get('type', '') in wants:
                return msg

def handle_prompt(ws, msg):
    atype = msg.get('action_type')
    if atype == 'target':
        targets = msg.get('targets') or []
        if targets:
            ws.send({'type': 'select_target', 'targetId': targets[0]['id']})
        else:
            log(ws.user, 'target prompt with NO targets:', str(msg)[:120])
    elif atype == 'select':
        ws.send({'type': 'pass_priority'})
    else:
        ws.send({'type': 'boolean_choice', 'value': True})
        ws.send({'type': 'pass_priority'})

def run_player(ws, role):
    """Shared loop. Creator drives rollback; joiner approves user_request."""
    game_id = None
    rollback_sent = 0
    rollback_observed = False
    post_rollback_max = 0
    deadline = time.time() + 420
    while time.time() < deadline:
        try:
            msg = ws.wait(['game_init', 'game_update', 'game_delta', 'action_prompt',
                           'user_request', 'game_over', 'game_end', 'error'], 75)
        except TimeoutError:
            log(ws.user, 'no traffic in 75s')
            continue
        t = msg.get('type')
        raw = json.dumps(msg)
        if 'Rolling back to start of turn' in raw and not rollback_observed:
            log(ws.user, 'ROLLBACK OBSERVED (server inform)')
            rollback_observed = True
        if t == 'error':
            log(ws.user, 'ERROR:', msg.get('message'))
            continue
        if t == 'user_request':
            log(ws.user, 'USER REQUEST:', msg.get('message'), '| b1=', msg.get('button1Action'))
            ws.send({'type': 'user_request_response', 'action': msg.get('button1Action')})
            continue
        if t == 'action_prompt':
            log(ws.user, 'PROMPT:', msg.get('message'), '| type=', msg.get('action_type'),
                '| targets=', len(msg.get('targets') or []))
            handle_prompt(ws, msg)
            continue
        if t in ('game_over', 'game_end'):
            log(ws.user, 'game over:', str(msg)[:150])
            break
        st = msg.get('state') or {}
        delta = msg.get('delta') or {}
        turn = st.get('turn') or delta.get('turn')
        gid = st.get('gameId')
        if gid:
            game_id = gid
        if t in ('game_init', 'game_update', 'game_delta') and isinstance(turn, int):
            if ws.user == USER1:
                if rollback_observed:
                    if turn > post_rollback_max:
                        log(ws.user, f'post-rollback turn {turn}')
                        post_rollback_max = turn
                elif turn >= 3 and rollback_sent < 12:
                    ws.send({'type': 'rollback_turns'})
                    rollback_sent += 1
                    time.sleep(0.4)
                    continue
            if not rollback_observed or post_rollback_max < 4:
                ws.send({'type': 'pass_priority'})
        if rollback_observed and post_rollback_max >= 4 and ws.user == USER1:
            ws.send({'type': 'concede'})
            log(ws.user, f'conceded (re-advanced to turn {post_rollback_max})')
            break
    log(ws.user, f'gameId={game_id} rollback_sent={rollback_sent} observed={rollback_observed} post_max={post_rollback_max}')
    return game_id, rollback_observed

result = {}
def creator():
    try:
        ws = WS(USER1, PASS1)
        ws.send({'type': 'create_table', 'name': TABLE_NAME,
                 'gameType': 'Two Player Duel', 'deckType': 'Constructed - Freeform',
                 'winsNeeded': 1, 'attackOption': 'LEFT', 'range': 'ALL',
                 'skillLevel': 'CASUAL', 'timeLimit': 'NONE', 'bufferTime': 'NONE',
                 'mulliganType': 'GAME_DEFAULT', 'freeMulligans': 0,
                 'playerConfigs': [
                     {'type': 'HUMAN', 'deck': DECK_A.strip(), 'isYou': True},
                     {'type': 'HUMAN', 'deck': '', 'isYou': False},
                 ]})
        msg = ws.wait(['table_created', 'error'], 30)
        log('creator', 'create ->', msg.get('type'), str(msg)[:140])
        if msg.get('type') == 'error':
            result['creator'] = (None, False)
            return
        result['table_id'] = msg['tableId']
        table_id = msg['tableId']
        for _ in range(40):
            try:
                msg2 = ws.wait(['table_list'], 8)
            except TimeoutError:
                break
            for tb in msg2.get('tables', []):
                if tb.get('id') == table_id and tb.get('playerCount') == 2:
                    ws.send({'type': 'start_match', 'tableId': table_id})
                    log('creator', 'start_match sent')
                    break
            else:
                continue
            break
        result['creator'] = run_player(ws, 'creator')
    except Exception as e:
        log('creator', 'EXCEPTION:', repr(e))
        result['creator'] = (None, False)

def joiner():
    try:
        ws = WS(USER2, PASS2)
        table_id = None
        for _ in range(40):
            ws.send({'type': 'get_tables'})
            try:
                msg = ws.wait(['table_list'], 8)
            except TimeoutError:
                continue
            for tb in msg.get('tables', []):
                if tb.get('name') == TABLE_NAME:
                    table_id = tb.get('id')
                    break
            if table_id:
                break
            time.sleep(2)
        if not table_id:
            log('joiner', 'FATAL: table not found')
            return
        log('joiner', 'joining', table_id)
        ws.send({'type': 'join_table', 'tableId': table_id, 'deck': DECK_B.strip()})
        msg = ws.wait(['table_joined', 'error'], 30)
        log('joiner', 'join ->', msg.get('type'), str(msg)[:140])
        if msg.get('type') == 'error':
            return
        result['joiner'] = run_player(ws, 'joiner')
    except Exception as e:
        log('joiner', 'EXCEPTION:', repr(e))

tc = threading.Thread(target=creator); tc.start()
time.sleep(5)
tj = threading.Thread(target=joiner); tj.start()
tc.join(timeout=460)
tj.join(timeout=460)
time.sleep(10)  # allow saveGame + sidecar write
gid, obs = result.get('creator', (None, False))
if gid:
    print('GAME_ID=' + gid)
print('ROLLBACK_OBSERVED=' + str(obs))
sys.exit(0 if (obs and gid) else 2)