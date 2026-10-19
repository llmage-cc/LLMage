/**
 * Behavioural checks for the lobby leave-table flow.
 *
 * Run with:  bash scripts/verify-leave-table.sh
 *
 * (Agent unit: this harness is WRITTEN but deliberately NOT RUN here — the
 * the parent change runs it first. Same rolldown-bundle + react-dom/server
 * pattern as verify-logout.sh / verify-endgame-presentation.sh.)
 *
 * WHAT THIS ADDS. The waiting panel kept a manual Refresh because there was NO
 * `leave_table` request anywhere (WebProxySession had no such case). The desktop
 * client's TableWaitingDialog has a Cancel → SessionHandler.leaveTable →
 * Session.leaveTable → engine roomLeaveTableOrTournament. This adds the proxy
 * `leave_table` verb (PLAYER-ONLY, Fail-Fast, seat-checked) + `table_left`
 * answer, the store `leaveTable`/`clearSeatState` actions, the `table_left`
 * handler, and the LobbyView Leave button — alongside (not replacing) Refresh.
 *
 * Assertions:
 *  A. store.leaveTable sends `{type:'leave_table'}` with NO tableId (the proxy
 *     resolves it from its own watch); no socket / dead socket -> no send.
 *  B. store.clearSeatState resets the five seat fields.
 *  C. the `table_left` handler clears the seat state (waiting panel gone) and
 *     re-requests the table list.
 *  D. The REAL LobbyView waiting panel renders BOTH the Refresh and the Leave
 *     buttons, Leave carrying the lobby-leave-table explain id.
 *  E. Source-scan: the proxy has the `leave_table` case, PLAYER_ONLY membership,
 *     the `table_left` frame, a seat check, and calls xmageSession.leaveTable.
 */
import { readFileSync } from 'node:fs'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import LobbyView from '../src/components/LobbyView'
import { handleMessage } from '../src/network/messageHandlers'

declare const process: { exit(code: number): void; env: Record<string, string | undefined> }

let failures = 0
let checks = 0
function check(name: string, ok: boolean, detail = '') {
  checks++
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

// ── a recording WS stub ──
const sent: Record<string, unknown>[] = []
const fakeWs = {
  send: (m: Record<string, unknown>) => { sent.push(m) },
  connect: () => {},
  disconnect: () => {},
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  pendingConnectData: null,
}
useGameStore.setState({
  ws: fakeWs as never,
  connected: true,
  connecting: false,
  connStatus: '',
  proxyUsername: 'Seated',
})

// ── A. leaveTable ──
sent.length = 0
useGameStore.getState().leaveTable()
check('A1: leaveTable sends a leave_table frame with NO tableId (proxy resolves it)',
  sent.length === 1 && sent[0]!.type === 'leave_table' && !('tableId' in sent[0]!),
  JSON.stringify(sent))

sent.length = 0
useGameStore.setState({ ws: null })
useGameStore.getState().leaveTable()
check('A2: no socket -> no send (never a silent throw)', sent.length === 0)
useGameStore.setState({ ws: fakeWs as never })

sent.length = 0
useGameStore.setState({ connected: false, connStatus: 'connection_lost' })
useGameStore.getState().leaveTable()
check('A3: dead socket -> surfaced (no send)', sent.length === 0)
useGameStore.setState({ connected: true, connStatus: '' })

// ── B. clearSeatState ──
useGameStore.setState({
  joinWaiting: true, pendingJoinTableId: 't1', myTableId: 't1', isTableOwner: true, mySeatNames: ['A'],
})
useGameStore.getState().clearSeatState()
check('B1: clearSeatState resets all five seat fields',
  useGameStore.getState().joinWaiting === false
    && useGameStore.getState().pendingJoinTableId === null
    && useGameStore.getState().myTableId === null
    && useGameStore.getState().isTableOwner === false
    && useGameStore.getState().mySeatNames.length === 0)

// ── C. the table_left handler ──
useGameStore.setState({
  joinWaiting: true, pendingJoinTableId: 't1', myTableId: 't1', isTableOwner: true, mySeatNames: ['A'],
})
sent.length = 0
handleMessage({ type: 'table_left', tableId: 't1' }, useGameStore.getState())
check('C1: table_left clears the waiting/seat state (panel gone)',
  useGameStore.getState().joinWaiting === false && useGameStore.getState().myTableId === null)
check('C2: table_left re-requests the table list',
  sent.some((m) => m.type === 'get_tables'), JSON.stringify(sent))

// ── D. the real LobbyView waiting panel ──
useGameStore.setState({
  joinWaiting: true, myTableId: 't1', isTableOwner: true, tables: [], finishedMatches: [], replayFiles: [],
})
const html = renderToStaticMarkup(createElement(LobbyView))
check('D1: the waiting panel offers BOTH Refresh and Leave',
  html.includes('data-explain="lobby-refresh-tables"') && html.includes('data-explain="lobby-leave-table"'), html)
check('D2: the Leave button label is present', html.includes('>Leave<') || />\s*Leave\s*</.test(html), html)
check('Refresh still exists (leaving is additive, not a replacement)',
  html.includes('Refresh Tables'), html)

// ── E. proxy source-scan ──
const SRC = process.env.XMAGE_REACT_SRC
if (!SRC) throw new Error('XMAGE_REACT_SRC is not set — run via scripts/verify-leave-table.sh')
const proxySrc = readFileSync(`${SRC}/../../../../src/main/java/mage/webproxy/WebProxySession.java`, 'utf8')
check('E1: the proxy has the leave_table case', proxySrc.includes('case "leave_table"'))
check('E2: leave_table is PLAYER-ONLY', /PLAYER_ONLY_ACTIONS[\s\S]*?"leave_table"/.test(proxySrc))
check('E3: the handler emits a table_left frame', proxySrc.includes('msg.put("type", "table_left")'))
check('E4: the handler performs the seat check (isTableSeatOfUser)',
  /handleLeaveTable[\s\S]*?isTableSeatOfUser\(tableId\)/.test(proxySrc))
check('E5: the handler calls xmageSession.leaveTable',
  /handleLeaveTable[\s\S]*?xmageSession\.leaveTable\(/.test(proxySrc))
check('E6: no tableId falls back to the watched table',
  /handleLeaveTable[\s\S]*?watchedTableId/.test(proxySrc))

const boardSrc = readFileSync(`${SRC}/components/LobbyView.tsx`, 'utf8')
check('E7: LobbyView calls the store leaveTable action', boardSrc.includes('.leaveTable()'))

console.log(`\n${checks - failures}/${checks} checks passed`)
if (failures > 0) process.exit(1)
