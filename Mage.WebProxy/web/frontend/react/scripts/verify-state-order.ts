/**
 * verify-state-order — out-of-order / stale game-state frame handling
 * (the "header briefly shows an older turn/phase" symptom).
 *
 * Run with:  bash scripts/verify-state-order.sh
 *
 * THE SYMPTOM: "at
 * AI-1's turn 2 the header briefly read `AI-1's Turn 2 | Declare Attackers`
 * while the server had `TURN 3` for me with a Main-phase land playable; proxy
 * log showed out-of-order `game_update` frames (`turn=3 Beginning` then
 * `turn=2 End` then `turn=2 Combat`). Self-corrected on the next frame."
 *
 * ROOT CAUSE (measured live, the earlier test evidence): out-of-order
 * CALLBACKS arriving at the proxy (96 inverted messageId arrivals in one
 * spectator session). The proxy relays them in arrival order and stamps its own
 * monotonic stateSeq, so a stale GameView reached the browser under a NEWER
 * version — invisible to the version check below. Fixed at the proxy
 * (WebProxyClient.handleGameUpdate drops a stale callback; see
 * WebProxyStaleStateAdmissionTest). These cases pin the FRONTEND contract that
 * the fix relies on and that the frontend must keep even with the proxy fixed:
 *
 *  (a) GAP detection exists: a FORWARD version jump (N+2 when N+1 was due)
 *      raises the resync request (websocket.ts onStateVersionReceived).
 *  (b) The frontend does NOT itself drop lower/equal versions — the transport
 *      (proxy) is the single admission point for stale content, so a
 *      stale-drop here would double-guard and could eat the resync reply
 *      (whose application must stay unconditional). This harness pins that
 *      contract so a future frontend "fix" cannot silently change it.
 *  (c) game_init re-bases lastStateVersion (fresh attach / rejoin).
 *
 * Cases:
 *  1. in-order frames N, N+1, N+2 → applied in order; header follows the newest.
 *  2. FORWARD gap N → N+3 (N+1/N+2 lost) → resync requested.
 *  3. lower version = transport contract violation → the store still applies
 *     what the transport delivered (asserted so the proxy, not this layer, is
 *     the guard), and the resync path stays usable.
 *  4. equal version → applied (duplicate frames still render).
 *  5. game_init resets lastStateVersion (fresh attach semantics preserved).
 *  6. stateSeq absent → no version bookkeeping (older proxies).
 *
 * Every case drives the REAL store + REAL message handler + REAL GameWebSocket
 * version hook (the same object the store calls through). No re-implementation.
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { GameWebSocket } from '../src/network/websocket'
import type { GameState, Player, ServerMessage } from '../src/types'

// scripts/ is outside the app tsconfig "include" (same as every other harness):
// declare the Node binding locally so tsc/LSP noise stays out.
declare const process: { exit(code: number): void }

let failures = 0
function check(name: string, cond: boolean, detail = ''): void {
  if (!cond) failures++
  console.log(`  [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' :: ' + detail : ''}`)
}
function section(t: string): void {
  console.log(`\n## ${t}`)
}

// ── fixtures ────────────────────────────────────────────────────────────────

const player = (name: string, isMe: boolean): Player => ({
  name, playerId: name, life: 20, isMe, libraryCount: 40, handCount: 7,
  isActive: false, hasPriority: false, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
  statesSavedSize: 0,
})

const state = (over: Partial<GameState>): GameState => ({
  status: 'playing', turn: 1, phase: null, step: null,
  activePlayer: 'QA-A', priorityPlayer: 'QA-A',
  stack: [], combat: [], players: [player('QA-A', true), player('QA-B', false)],
  llmDebug: [], revealed: [], lookedAt: [], stateSeq: 1,
  ...over,
})

/** A frame as the proxy emits it (full-state game_update). */
const update = (st: GameState): ServerMessage => ({ type: 'game_update', state: st })
const init = (st: GameState): ServerMessage => ({ type: 'game_init', state: st })

// The wire side: a REAL GameWebSocket, with everything transport-touching
// replaced (no socket in Node). Its private lastStateVersion bookkeeping stays
// real — that is the code under test.
const sent: string[] = []
const wire = (() => {
  const ws = new GameWebSocket({
    url: 'ws://unused', onMessage: () => {}, onStatusChange: () => {}, onError: () => {},
  })
  // replace the socket object with a recording stand-in (OPEN so send() works)
  ;(ws as unknown as { ws: unknown }).ws = {
    readyState: 1, send: (d: string) => { sent.push(JSON.parse(d).type) }, close: () => {},
  }
  return ws
})()

// wire the store to the real manager, exactly like App.tsx does
useGameStore.getState().setWebSocket(wire as never)

const reset = () => {
  sent.length = 0
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, gameState: null })
  // fresh socket bookkeeping, as a page load / reconnect does
  ;(wire as unknown as { lastStateVersion: number | null }).lastStateVersion = null
  ;(wire as unknown as { resyncRequested: boolean }).resyncRequested = false
}

const header = (): string => {
  const st = useGameStore.getState().gameState
  if (!st) return '(no state)'
  return `${st.activePlayer}'s Turn ${st.turn} | ${st.step ?? st.phase ?? ''}`
}

// ── 1. in-order ─────────────────────────────────────────────────────────────

section('1. in-order frames are applied in order (no regression)')
{
  reset()
  handleMessage(update(state({ stateSeq: 1, turn: 2, phase: 'C1', step: 'DECLARE_ATTACKERS', activePlayer: 'AI-1' })), useGameStore.getState())
  check('N applied', header() === "AI-1's Turn 2 | DECLARE_ATTACKERS", header())
  handleMessage(update(state({ stateSeq: 2, turn: 2, phase: 'E1', step: 'END_TURN', activePlayer: 'AI-1' })), useGameStore.getState())
  handleMessage(update(state({ stateSeq: 3, turn: 3, phase: 'M1', step: 'PRECOMBAT_MAIN', activePlayer: 'QA-A' })), useGameStore.getState())
  check('N+1, N+2 applied', header() === "QA-A's Turn 3 | PRECOMBAT_MAIN", header())
  check('no resync requested on an in-order run', sent.filter((s) => s === 'request_resync').length === 0, sent.join(','))
}

// ── 2. forward gap → resync ─────────────────────────────────────────────────

section('2. FORWARD version gap requests a resync (existing guard)')
{
  reset()
  handleMessage(update(state({ stateSeq: 1, turn: 2, phase: 'C1', step: 'DECLARE_ATTACKERS', activePlayer: 'AI-1' })), useGameStore.getState())
  handleMessage(update(state({ stateSeq: 4, turn: 3, phase: 'M1', step: 'PRECOMBAT_MAIN', activePlayer: 'QA-A' })), useGameStore.getState())
  check('a forward jump N -> N+3 requests resync', sent.filter((s) => s === 'request_resync').length === 1, sent.join(','))
  check('the gapped frame IS applied on arrival (documented behaviour)', header() === "QA-A's Turn 3 | PRECOMBAT_MAIN", header())
}

// ── 3. lower version: transport contract, not the frontend's job ────────────

section('3. LOWER version is the TRANSPORT\'s contract (proxy drops it; this layer must not)')
{
  reset()
  // The proxy (WebProxyClient.handleGameUpdate) is the single admission point
  // for stale content: it drops an out-of-order callback BEFORE it is stamped
  // with a new stateSeq. A lower version therefore means a transport contract
  // violation (or a proxy that predates the fix) — the frontend deliberately
  // applies what it is handed rather than double-guarding, so the resync reply
  // stays unconditionally applicable.
  handleMessage(update(state({ stateSeq: 5, turn: 3, phase: 'M1', step: 'PRECOMBAT_MAIN', activePlayer: 'QA-A' })), useGameStore.getState())
  check('newest state applied', header() === "QA-A's Turn 3 | PRECOMBAT_MAIN", header())
  const resyncBefore = sent.filter((s) => s === 'request_resync').length
  handleMessage(update(state({ stateSeq: 3, turn: 2, phase: 'C1', step: 'DECLARE_ATTACKERS', activePlayer: 'AI-1' })), useGameStore.getState())
  check('a lower-version frame is applied (frontend is not the stale guard — proxy is)',
    header() === "AI-1's Turn 2 | DECLARE_ATTACKERS", header())
  check('a lower version does not ask for a resync (it is not a gap)', sent.filter((s) => s === 'request_resync').length === resyncBefore, sent.join(','))
  const last = (wire as unknown as { lastStateVersion: number | null }).lastStateVersion
  check('lastStateVersion follows the delivered frame', last === 3, String(last))
}

section('3b. the next frame restores server truth (recovery stays simple)')
{
  handleMessage(update(state({ stateSeq: 6, turn: 3, phase: 'M1', step: 'PRECOMBAT_MAIN', activePlayer: 'QA-A' })), useGameStore.getState())
  check('the following frame restores server truth', header() === "QA-A's Turn 3 | PRECOMBAT_MAIN", header())
}

// ── 3c. resync reply is applied unconditionally ─────────────────────────────

section('3c. resync reply (forceFullState game_update) is applied unconditionally')
{
  reset()
  handleMessage(update(state({ stateSeq: 40, turn: 9, phase: 'E1', step: 'END_TURN', activePlayer: 'AI-0' })), useGameStore.getState())
  // the proxy answers request_resync with the cached latest state as a FULL
  // game_update carrying the NEXT stateSeq — applied regardless of any
  // outstanding resync bookkeeping.
  handleMessage(update(state({ stateSeq: 41, turn: 9, phase: 'M2', step: 'POSTCOMBAT_MAIN', activePlayer: 'AI-0' })), useGameStore.getState())
  check('a late full-state reply applies', header() === "AI-0's Turn 9 | POSTCOMBAT_MAIN", header())
  check('no spurious resync loop', sent.filter((s) => s === 'request_resync').length === 0, sent.join(','))
}

// ── 4. equal version ────────────────────────────────────────────────────────

section('4. EQUAL stateSeq (duplicate frame, older content) is accepted')
{
  reset()
  handleMessage(update(state({ stateSeq: 7, turn: 4, phase: 'M1', step: 'PRECOMBAT_MAIN', activePlayer: 'QA-A' })), useGameStore.getState())
  handleMessage(update(state({ stateSeq: 7, turn: 3, phase: 'C1', step: 'DECLARE_ATTACKERS', activePlayer: 'QA-A' })), useGameStore.getState())
  check('CURRENT: an equal-version frame overwrites the store', header() === "QA-A's Turn 3 | DECLARE_ATTACKERS", header())
}

// ── 5. game_init reset semantics ────────────────────────────────────────────

section('5. game_init resets version bookkeeping (fresh attach / rejoin)')
{
  reset()
  handleMessage(update(state({ stateSeq: 9, turn: 5, phase: 'E1', step: 'END_TURN', activePlayer: 'QA-A' })), useGameStore.getState())
  // a NEW game's game_init starts its own counter (proxy stateSeq resets per client)
  handleMessage(init(state({ stateSeq: 1, turn: 1, phase: null, step: null, activePlayer: 'QA-A' })), useGameStore.getState())
  check('game_init applies (no stale-drop of an init)', header() === "QA-A's Turn 1 | ", header())
  // after the init, versions restart at 2 — a LOW number must not look stale
  handleMessage(update(state({ stateSeq: 2, turn: 1, phase: 'M1', step: 'PRECOMBAT_MAIN', activePlayer: 'QA-A' })), useGameStore.getState())
  check('post-init low versions still apply (init re-based the counter)', header() === "QA-A's Turn 1 | PRECOMBAT_MAIN", header())
  check('no resync from the init re-basing', sent.filter((s) => s === 'request_resync').length === 0, sent.join(','))
}

// ── 6. no stateSeq (older proxy) ────────────────────────────────────────────

section('6. frames WITHOUT stateSeq keep working (no bookkeeping)')
{
  reset()
  const st = state({ turn: 2, phase: 'C1', step: 'DECLARE_ATTACKERS', activePlayer: 'AI-1' })
  delete (st as { stateSeq?: number }).stateSeq
  handleMessage(update(st), useGameStore.getState())
  check('a versionless frame applies', header() === "AI-1's Turn 2 | DECLARE_ATTACKERS", header())
  check('no resync from a versionless frame', sent.filter((s) => s === 'request_resync').length === 0, sent.join(','))
}

// ── 7. game_delta path ──────────────────────────────────────────────────────

section('7. game_delta gap + stale')
{
  reset()
  handleMessage(update(state({ stateSeq: 1, turn: 2, phase: 'C1', step: 'DECLARE_ATTACKERS', activePlayer: 'AI-1' })), useGameStore.getState())
  // delta with a forward gap
  handleMessage({ type: 'game_delta', stateSeq: 5, delta: { turn: 3 } } as ServerMessage, useGameStore.getState())
  check('delta forward gap requests resync', sent.filter((s) => s === 'request_resync').length === 1, sent.join(','))
  // delta with a stale version
  const before = sent.filter((s) => s === 'request_resync').length
  handleMessage({ type: 'game_delta', stateSeq: 2, delta: { turn: 2 } } as ServerMessage, useGameStore.getState())
  check('CURRENT: delta stale version applied without resync',
    (useGameStore.getState().gameState as GameState).turn === 2 && sent.filter((s) => s === 'request_resync').length === before,
    `turn=${(useGameStore.getState().gameState as GameState).turn} sent=${sent.join(',')}`)
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
