/**
 * Harness: whisper-direction labels on BOTH web chat surfaces.
 *
 * Run: bash scripts/verify-chat-labels.sh
 *
 * The desktop prefixes every private message with its direction
 * (ChatPanelBasic.java:245-259 — `"Whisper from " + username` for
 * WHISPER_FROM, `"Whisper to " + username` for WHISPER_TO) and the server
 * populates that field per direction (ChatSession.broadcastWhisperToUser:129-140
 * — the SENDER's name for whisper_from, the RECIPIENT's for whisper_to). The
 * web client rendered the bare name on both surfaces. This harness pins the
 * parity so it cannot silently regress:
 *
 *  0     JAVA CROSS-CHECK — the desktop wording and the server's per-direction
 *        `from` population are read from the REAL sources (repo root passed by
 *        the .sh wrapper): if either side of the contract moves, this fails
 *        instead of quietly drifting. (Same technique as verify-slash-commands'
 *        MAX_CHAT_LENGTH cross-check.)
 *  1-9   chatFromLabel (utils/chatLabels): both directions map to the desktop
 *        wording; talk/unknown/absent/alias chatType → the plain name, no
 *        invented prefix (fail-neutral);
 * 10-13  store wiring through the REAL message handler: a delivered `event`
 *        frame carries chatType to BOTH lists (chatMessages + lobbyTalkMessages)
 *        and a talk/whisper line never lands in eventLog (no double-render);
 * 14-19  the REAL LobbyChat Talk tab rendered through react-dom/server from the
 *        live store state: both directions labelled, plain talk unlabelled,
 *        whisper classes present;
 * 20-24  the REAL RightRail Chat tab rendered the same way: both directions
 *        labelled, plain talk unlabelled, `chat-whisper_from` class present.
 *
 * (rolldown-bundled like verify-lobby-chat-users.sh; scripts/ is outside
 * tsconfig "include", so this is not part of the vite build.)
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { readFileSync } from 'node:fs'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { chatFromLabel } from '../src/utils/chatLabels'
import LobbyChat from '../src/components/lobby/LobbyChat'
import { RightRail } from '../src/components/RightRail'

// scripts/ is outside the app tsconfig's `include`, so @types/node's globals are
// not in scope here (same declaration as verify-choice-suggested.tsx).
declare const process: { env: Record<string, string | undefined>; exit(code: number): void }

// react-dom/server renders through zustand's `getServerSnapshot`, which reads
// the object captured at store creation (`useGameStore.getInitialState()`), NOT
// the live state. Overriding the exported `getInitialState` does not reach it —
// zustand's useStore closes over its internal api — so the SSR visible state is
// synced by copying the live fields onto that captured object before each render
// (the technique verify-phase-color.ts established).
const ssrState = useGameStore.getInitialState() as unknown as Record<string, unknown>
const syncSsrState = () => {
  Object.assign(ssrState, useGameStore.getState())
}

let failures = 0
let passed = 0
function check(name: string, ok: boolean, detail = '') {
  if (ok) {
    passed++
    console.log(`PASS  ${name}`)
  } else {
    failures++
    console.log(`FAIL  ${name}${detail ? '  — ' + detail : ''}`)
  }
}
function section(t: string) {
  console.log(`\n## ${t}`)
}

// ── 0. Java source cross-check (exact desktop wording + per-direction `from`) ──
const repoRoot = process.env.XMAGE_REPO_ROOT
if (!repoRoot) {
  throw new Error('XMAGE_REPO_ROOT is not set — run via scripts/verify-chat-labels.sh')
}
{
  const panel = readFileSync(
    `${repoRoot}/Mage.Client/src/main/java/mage/client/chat/ChatPanelBasic.java`,
    'utf8',
  )
  check(
    'desktop ChatPanelBasic prefixes WHISPER_FROM with "Whisper from "',
    panel.includes('username = "Whisper from " + username;'),
  )
  check(
    'desktop ChatPanelBasic prefixes WHISPER_TO with "Whisper to "',
    panel.includes('username = "Whisper to " + username;'),
  )
  const session = readFileSync(
    `${repoRoot}/Mage.Server/src/main/java/mage/server/ChatSession.java`,
    'utf8',
  )
  // The `from` the desktop prefixes is the ChatMessage username field, set per
  // direction in broadcastWhisperToUser: sender for WHISPER_FROM, recipient for
  // WHISPER_TO. Pin both constructions so a server-side flip is caught here.
  check(
    'server whisper_from carries the SENDER (fromUser.getName)',
    /new ChatMessage\(fromUser\.getName\(\),[\s\S]{0,200}?MessageType\.WHISPER_FROM/.test(session),
  )
  check(
    'server whisper_to carries the RECIPIENT (toUser.getName)',
    /new ChatMessage\(toUser\.getName\(\),[\s\S]{0,200}?MessageType\.WHISPER_TO/.test(session),
  )
}

// ── 1. chatFromLabel: pure mapping ──
section('chatFromLabel — direction labels (utils/chatLabels)')
check(
  'whisper_from → "Whisper from <from>"',
  chatFromLabel({ chatType: 'whisper_from', from: 'Ket' }) === 'Whisper from Ket',
  chatFromLabel({ chatType: 'whisper_from', from: 'Ket' }),
)
check(
  'whisper_to → "Whisper to <from>" (from = recipient per ChatSession)',
  chatFromLabel({ chatType: 'whisper_to', from: 'Ket' }) === 'Whisper to Ket',
  chatFromLabel({ chatType: 'whisper_to', from: 'Ket' }),
)
check(
  'talk → bare name, no prefix',
  chatFromLabel({ chatType: 'talk', from: 'Ket' }) === 'Ket',
  chatFromLabel({ chatType: 'talk', from: 'Ket' }),
)
check(
  'case-insensitive chatType (WHISPER_FROM)',
  chatFromLabel({ chatType: 'WHISPER_FROM', from: 'Ket' }) === 'Whisper from Ket',
)
check(
  'unknown chatType fails neutral (no invented direction)',
  chatFromLabel({ chatType: 'super_new_type', from: 'Ket' }) === 'Ket',
  chatFromLabel({ chatType: 'super_new_type', from: 'Ket' }),
)
check(
  "routeChat's 'whisper' alias carries no direction → bare name",
  chatFromLabel({ chatType: 'whisper', from: 'Ket' }) === 'Ket',
)
check(
  'absent chatType → bare name (pre-chatType events)',
  chatFromLabel({ from: 'Ket' }) === 'Ket' && chatFromLabel({ chatType: null, from: 'Ket' }) === 'Ket',
)
check(
  "missing/empty from → '?' (the pre-existing placeholder)",
  chatFromLabel({ chatType: 'whisper_from' }) === 'Whisper from ?' &&
    chatFromLabel({ chatType: 'talk', from: '' }) === '?',
)
check(
  'label text carries no separator (the caller adds ": " like the desktop)',
  !chatFromLabel({ chatType: 'whisper_from', from: 'Ket' }).includes(':'),
)

// ── 2. store wiring through the REAL message handler ──
section('store wiring — one wire frame, two lists, no log duplication')
const sent: Record<string, unknown>[] = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  noteConnectAccepted: () => {},
  disconnect: () => {},
} as never)
const feed = (m: unknown) => handleMessage(m as never, useGameStore.getState())
feed({ type: 'connected', username: 'qa18a' } as never)

const WHISPER_FROM_EVENT = { seq: 101, type: 'talk', chatType: 'whisper_from', from: 'qa18a', message: 'hello-from-A' }
const WHISPER_TO_EVENT = { seq: 102, type: 'talk', chatType: 'whisper_to', from: 'qa18b', message: 'hello-from-A' }
const TALK_EVENT = { seq: 103, type: 'talk', chatType: 'talk', from: 'qa18c', message: 'hi all' }
for (const event of [WHISPER_FROM_EVENT, WHISPER_TO_EVENT, TALK_EVENT]) {
  feed({ type: 'event', event } as never)
}
const st = useGameStore.getState()
check(
  'incoming whisper lands in chatMessages (rail Chat tab)',
  st.chatMessages.some((e) => e.seq === 101 && e.chatType === 'whisper_from'),
)
check(
  'outgoing whisper echoes into chatMessages with chatType whisper_to intact',
  st.chatMessages.some((e) => e.seq === 102 && e.chatType === 'whisper_to'),
)
check(
  'both whisper directions route into the lobby Talk list',
  st.lobbyTalkMessages.some((e) => e.seq === 101) && st.lobbyTalkMessages.some((e) => e.seq === 102),
)
check(
  'a chat line never lands in the game log (eventLog) — rendered exactly once',
  !st.eventLog.some((e) => e.seq === 101 || e.seq === 102 || e.seq === 103),
)
check(
  'each whisper line appears EXACTLY ONCE in chatMessages (no duplicate append)',
  st.chatMessages.filter((e) => e.seq === 101).length === 1 &&
    st.chatMessages.filter((e) => e.seq === 102).length === 1,
)

// ── 3. the REAL LobbyChat Talk tab renders the labels ──
section('LobbyChat (lobby Talk tab) — rendered markup')
useGameStore.getState().setLobbyChatTab('talk')
syncSsrState()
const lobbyHtml = renderToStaticMarkup(<LobbyChat />)
check(
  'renders "Whisper from qa18a: hello-from-A"',
  lobbyHtml.includes('Whisper from qa18a:') && lobbyHtml.includes('hello-from-A'),
)
check(
  'renders "Whisper to qa18b: hello-from-A"',
  lobbyHtml.includes('Whisper to qa18b:'),
)
check(
  'plain talk stays bare ("qa18c:")',
  lobbyHtml.includes('qa18c:') && !lobbyHtml.includes('Whisper from qa18c'),
)
check(
  'whisper rows keep their direction classes (lobby-chat.css styling hook)',
  lobbyHtml.includes('lobby-chat-entry lobby-chat-whisper_from') &&
    lobbyHtml.includes('lobby-chat-entry lobby-chat-whisper_to'),
)
check(
  'Talk tab is the rendered list (System content absent)',
  lobbyHtml.includes('lobby-chat-tab-talk'),
)

// ── 4. the REAL RightRail Chat tab renders the same labels ──
section('RightRail (in-game rail Chat tab) — rendered markup')
useGameStore.getState().setActiveRightTab('chat')
syncSsrState()
const railHtml = renderToStaticMarkup(<RightRail />)
check(
  'rail chat renders "Whisper from qa18a:"',
  railHtml.includes('Whisper from qa18a:'),
)
check(
  'rail chat renders "Whisper to qa18b:"',
  railHtml.includes('Whisper to qa18b:'),
)
check(
  'rail chat plain talk stays bare',
  railHtml.includes('qa18c:') && !railHtml.includes('Whisper from qa18c'),
)
check(
  'rail chat rows carry the chat-<chatType> class',
  railHtml.includes('chat-entry chat-whisper_from') && railHtml.includes('chat-entry chat-whisper_to'),
)
check(
  'the rail renders the Chat tab of the SAME chatMessages list (one line per whisper)',
  (railHtml.match(/Whisper from qa18a:/g) ?? []).length === 1 &&
    (railHtml.match(/Whisper to qa18b:/g) ?? []).length === 1,
)

console.log(`\n${passed} passed, ${failures} failed`)
if (failures > 0) {
  process.exit(1)
}
console.log('verify-chat-labels: ALL CHECKS PASS')
