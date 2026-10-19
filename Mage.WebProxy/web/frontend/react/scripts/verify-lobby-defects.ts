/**
 * Behavioural checks for three lobby/proxy defects (2026-09-14 batch):
 *
 *   (A) hidden-tab timer throttling made the owner's Start Match button appear 40–60 s late.
 *
 *   (C) stripServerHtml ate <br> with no separator, gluing words.
 *
 *
 * (B) duplicate start_game idempotency lives in WebProxyClient (Java) and is asserted by
 * Mage.WebProxy/scripts/verify-lobby-proxy-units.sh.
 *
 * Run with:  bash scripts/verify-lobby-defects.sh
 *
 * Every case drives the REAL store through the REAL message handler and the REAL
 * text util with browser stubs, so the assertions cover shipped code paths.
 */
import { useGameStore } from '../src/store/gameStore'
import { selectCanStartMatch } from '../src/store/selectors'
import { handleMessage } from '../src/network/messageHandlers'
import { stripServerHtml, colorizePlayerNames } from '../src/utils/textFormat'
import { GRID_POLL_MS, shouldPollGrid } from '../src/utils/lobbyPoll'
import { readFileSync } from 'node:fs'
import type { ServerMessage, TableInfo } from '../src/types'

const sent: string[] = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push((m as { type: string }).type) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

const table = (over: Partial<TableInfo> = {}): TableInfo => ({
  id: 'T-1', name: 'Web Game', gameType: 'Two Player Duel',
  playerCount: 1, maxPlayers: 2, state: 'WAITING', ...over,
})

const tableList = (tables: TableInfo[]) => ({ type: 'table_list', tables } as ServerMessage)

// ═════════════════════════════════════════════════════════════════════════════
// (A) Hidden-tab throttling — the Start Match gate must be a pure function of a
//     table_list that a SERVER PUSH can update (a WS message is not a timer).
// ═════════════════════════════════════════════════════════════════════════════

section('A — the Start Match gate is driven by pushed table_list frames, not by a timer')
{
  useGameStore.setState({ myTableId: null, isTableOwner: false, tables: [] })
  check('no seat recorded -> no start control', selectCanStartMatch(useGameStore.getState()) === false)

  // Creator seats: table_created records ownership (existing behaviour).
  handleMessage({ type: 'table_created', tableId: 'T-1', tableName: 'Web Game' } as ServerMessage, useGameStore.getState())
  check('creator is recorded as owner', useGameStore.getState().isTableOwner === true && useGameStore.getState().myTableId === 'T-1')

  // 1/2 seats: the button must NOT be offered yet.
  handleMessage(tableList([table({ playerCount: 1, state: 'WAITING' })]), useGameStore.getState())
  check('1/2 seats -> no Start Match', selectCanStartMatch(useGameStore.getState()) === false)

  // THE DEFECT: this is the frame the proxy pushes when the second seat fills. It arrives
  // over the WebSocket, so a hidden/throttled tab still receives it immediately. Before the
  // fix the owner got no such frame without a page timer firing, and the control appeared
  // 40-60 s late.
  handleMessage(tableList([table({ playerCount: 2, state: 'READY_TO_START' })]), useGameStore.getState())
  check('pushed table_list alone makes Start Match appear', selectCanStartMatch(useGameStore.getState()) === true)

  // A joiner must never be offered the owner-only control, whatever the push says.
  useGameStore.setState({ isTableOwner: false })
  check('joiner never offered Start Match', selectCanStartMatch(useGameStore.getState()) === false)

  // A table that already started must not offer it either.
  useGameStore.setState({ isTableOwner: true })
  handleMessage(tableList([table({ playerCount: 2, state: 'DUELING' })]), useGameStore.getState())
  check('DUELING table -> no Start Match', selectCanStartMatch(useGameStore.getState()) === false)
}

section('A — the gate survives the exact live frame shape (READY_TO_START, 2/2, owner seated)')
{
  // Verbatim shape from the creator's WS transcript:
  // {"tables":[{"…","playerCount":2,"state":"READY_TO_START","seatNames":["QA-M1","QA-M2"]}],
  //  "type":"table_list"}
  useGameStore.setState({ myTableId: 'e54d14f8-0000-0000-0000-000000000001', isTableOwner: true, tables: [] })
  // The merge gate: the socket-down guard (isSocketDown) refuses sends while the
  // store reads disconnected. This scene exercises the SEND path, so it must hold a live
  // socket (the sections above the gate live in earlier scenes; nothing here is "down").
  useGameStore.setState({ connected: true, connStatus: 'connecting' })
  handleMessage(tableList([{
    id: 'e54d14f8-0000-0000-0000-000000000001', name: 'SMOKE-1', gameType: 'Two Player Duel',
    playerCount: 2, maxPlayers: 2, state: 'READY_TO_START',
  }]), useGameStore.getState())
  check('live-shaped push yields the control', selectCanStartMatch(useGameStore.getState()) === true)

  // The button's own handler sends start_match for the seated table.
  sent.length = 0
  const st = useGameStore.getState()
  st.startMatch(st.myTableId!)
  check('Start Match sends start_match', sent.join(',') === 'start_match', sent.join(','))
}

section('A — LobbyView polls remain a backstop (no reliance on the timer interval)')
{
  // The store's requestTables() is what both the backstop interval and the visibility
  // catch-up use. Assert it emits the same get_tables the rig workaround used.
  sent.length = 0
  useGameStore.getState().requestTables()
  check('requestTables sends get_tables', sent.join(',') === 'get_tables', sent.join(','))
}

// ═════════════════════════════════════════════════════════════════════════════
// (C) stripServerHtml — break/block tags become a separator, inline tags do not
// ═════════════════════════════════════════════════════════════════════════════

section('C — <br> is a separator (the word-gluing defect)')
{
  // MatchImpl.java:429 — "Match is <not >rated<br/>" then the score; both halves are
  // concatenated by the engine and used to render "0Match"/"ratedYou"/"matchGame".
  check('<br/> mid-string becomes a space',
    stripServerHtml('rated<br/>You won the game') === 'rated You won the game',
    JSON.stringify(stripServerHtml('rated<br/>You won the game')))
  check('leading <br> does not leave a leading space',
    stripServerHtml('<br>Seats: 2/2') === 'Seats: 2/2',
    JSON.stringify(stripServerHtml('<br>Seats: 2/2')))
  check('<br> with no surrounding whitespace still separates',
    stripServerHtml('0<br>Match score: 1-0') === '0 Match score: 1-0',
    JSON.stringify(stripServerHtml('0<br>Match score: 1-0')))
  check('trailing <br> does not leave a trailing space',
    stripServerHtml('game over<br>') === 'game over',
    JSON.stringify(stripServerHtml('game over<br>')))
  check('bare <br> (attribute-less, no slash) separates',
    stripServerHtml('a<br>b') === 'a b',
    JSON.stringify(stripServerHtml('a<br>b')))
  check('<BR> uppercase separates',
    stripServerHtml('a<BR>b') === 'a b',
    JSON.stringify(stripServerHtml('a<BR>b')))
  check('a run of breaks collapses to one space',
    stripServerHtml('a<br><br>b') === 'a b',
    JSON.stringify(stripServerHtml('a<br><br>b')))
  check('whitespace already around a break is not doubled',
    stripServerHtml('a <br> b') === 'a b',
    JSON.stringify(stripServerHtml('a <br> b')))
  check('<br /> with a space separates',
    stripServerHtml('a<br />b') === 'a b',
    JSON.stringify(stripServerHtml('a<br />b')))
}

section('C — block tags separate too; inline tags still strip to NOTHING')
{
  check('<p> separates', stripServerHtml('a<p>b</p>') === 'a b', JSON.stringify(stripServerHtml('a<p>b</p>')))
  check('</div> separates', stripServerHtml('a</div><div>b') === 'a b', JSON.stringify(stripServerHtml('a</div><div>b')))
  check('<li> separates', stripServerHtml('first<li>second') === 'first second', JSON.stringify(stripServerHtml('first<li>second')))

  // INLINE tags must NOT become separators: <font> wraps runs of text the server never
  // split, so inserting a space here would corrupt words.
  check('<font> strips with no separator',
    stripServerHtml('<font color=orange>QA-A</font> casts') === 'QA-A casts',
    JSON.stringify(stripServerHtml('<font color=orange>QA-A</font> casts')))
  check('adjacent inline tags do not split a word',
    stripServerHtml('<b>x</b><i>y</i>') === 'xy',
    JSON.stringify(stripServerHtml('<b>x</b><i>y</i>')))
  check('<font color=...> mid-word is not split',
    stripServerHtml('Elf<font color=green></font>') === 'Elf',
    JSON.stringify(stripServerHtml('Elf<font color=green></font>')))
}

section('C — before/after: the pre-fix implementation is what glued the words')
{
  // Verbatim pre-fix implementation (textFormat.tsx at commit 3dc79f9138:
  //   .replace(/<[^>]+>/g, '').replace(/\s\[[a-z0-9]{2,4}\]/gi, '')
  // kept here so the harness proves BOTH halves: the defect was real, and the shipped
  // fix removes it — not merely that the current output looks reasonable.
  const preFix = (s: string) => String(s || '').replace(/<[^>]+>/g, '').replace(/\s\[[a-z0-9]{2,4}\]/gi, '')
  // The engine concatenates "Match is not rated<br/>" (MatchImpl.java:429) with the score.
  const defectText = 'Match is not rated<br/>0 Match score: 1-0'
  check('pre-fix output glues "rated" and "0"',
    preFix(defectText) === 'Match is not rated0 Match score: 1-0',
    JSON.stringify(preFix(defectText)))
  check('shipped fix separates them',
    stripServerHtml(defectText) === 'Match is not rated 0 Match score: 1-0',
    JSON.stringify(stripServerHtml(defectText)))
  console.log(`        before: ${JSON.stringify(preFix(defectText))}`)
  console.log(`        after:  ${JSON.stringify(stripServerHtml(defectText))}`)
}

section('C — existing behaviour is unchanged (regression guard)')
{
  // [SET] abbreviation removal (the other half of this util).
  check('[set] abbreviations still stripped',
    stripServerHtml("Lightning Bolt [M10]") === 'Lightning Bolt',
    JSON.stringify(stripServerHtml('Lightning Bolt [M10]')))
  check('[set] after a <br> still stripped',
    stripServerHtml('Bolt<br>Forest [M10]') === 'Bolt Forest',
    JSON.stringify(stripServerHtml('Bolt<br>Forest [M10]')))

  // Chat arrives entity-escaped (MageServerImpl.sendChatMessage escapes it), so raw tags
  // must survive as decoded TEXT, not be eaten as markup — the documented order guarantee.
  check('escaped chat markup survives as text',
    stripServerHtml('&lt;b&gt;bold&lt;/b&gt;') === '<b>bold</b>',
    JSON.stringify(stripServerHtml('&lt;b&gt;bold&lt;/b&gt;')))
  check('escaped &amp;lt; is not double-decoded',
    stripServerHtml('amp &amp;lt; b') === 'amp &lt; b',
    JSON.stringify(stripServerHtml('amp &amp;lt; b')))
  check('numeric entity decoded',
    stripServerHtml('turn &#39;3&#39;') === "turn '3'",
    JSON.stringify(stripServerHtml('turn &#39;3&#39;')))
  check('non-ASCII round-trips',
    stripServerHtml('QÅ-B has joined') === 'QÅ-B has joined',
    JSON.stringify(stripServerHtml('QÅ-B has joined')))
  check('empty input stays empty', stripServerHtml('') === '', JSON.stringify(stripServerHtml('')))

  // colorizePlayerNames consumes stripServerHtml and slices by index — a separator change
  // must not shift name offsets.
  const nodes = colorizePlayerNames('QA-A casts a spell', { 'QA-A': 0 })
  check('colorizePlayerNames still finds the name', nodes.length === 2 && nodes[1] !== undefined)
  const brNodes = colorizePlayerNames('QA-A plays<br>QA-B responds', { 'QA-A': 0, 'QA-B': 1 })
  // The two player names become <span> nodes; render them as a marker so the string
  // segments (and the break separator between them) can be asserted.
  const flat = brNodes.map((n) => (typeof n === 'string' ? n : '§')).join('')
  check('colorize keeps the break separator', flat === '§ plays § responds', JSON.stringify(flat))
}

section('D — grid auto-refresh: the pure gate and the shipped wiring (half 2 of the lobby todo)')
{
  // The gate is a pure predicate (src/utils/lobbyPoll.ts) so it can be pinned here
  // without a DOM. Grid visible = connected + unseated + no dialog.
  check('grid case polls (connected, not seated, no dialogs)',
    shouldPollGrid({ connected: true, joinWaiting: false, showSetup: false, showTournamentSetup: false }) === true)
  check('disconnected does not poll',
    shouldPollGrid({ connected: false, joinWaiting: false, showSetup: false, showTournamentSetup: false }) === false)
  check('seated does not poll (the 2 s backstop covers that case)',
    shouldPollGrid({ connected: true, joinWaiting: true, showSetup: false, showTournamentSetup: false }) === false)
  check('setup dialog does not poll',
    shouldPollGrid({ connected: true, joinWaiting: false, showSetup: true, showTournamentSetup: false }) === false)
  check('tournament dialog does not poll',
    shouldPollGrid({ connected: true, joinWaiting: false, showSetup: false, showTournamentSetup: true }) === false)
  check('cadence is 15 s', GRID_POLL_MS === 15000, String(GRID_POLL_MS))

  // Source structure of the shipped component: the poll must use the shared
  // constants (not a re-typed literal), keep the visibility catch-up, and leave
  // the seated 2 s backstop alone.
  const lobbySrc = readFileSync('src/components/LobbyView.tsx', 'utf8')
  check('poll interval uses GRID_POLL_MS',
    lobbySrc.includes('setInterval(() => requestTables(), GRID_POLL_MS)'))
  check('poll is gated by shouldPollGrid on the component state',
    lobbySrc.includes('shouldPollGrid({ connected, joinWaiting, showSetup, showTournamentSetup })'))
  check('visibility catch-up registered in the same effect',
    /setInterval\(\(\) => requestTables\(\), GRID_POLL_MS\)[\s\S]{0,600}visibilitychange/.test(lobbySrc))
  check('seated 2 s backstop untouched',
    lobbySrc.includes('setInterval(() => requestTables(), 2000)'))
  check('the gate fields are in the effect deps (restarts/stopping on change)',
    /shouldPollGrid\(\{ connected, joinWaiting, showSetup, showTournamentSetup \}\)[\s\S]{0,700}\[connected, joinWaiting, showSetup, showTournamentSetup, requestTables\]/.test(lobbySrc))

  // …and the util is the single source of the cadence (no second literal elsewhere).
  const utilSrc = readFileSync('src/utils/lobbyPoll.ts', 'utf8')
  check('lobbyPoll.ts declares the cadence once', utilSrc.includes('export const GRID_POLL_MS = 15000'))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
