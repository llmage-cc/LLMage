/**
 * Standalone behavioural check for the rejoin/reconnect history backfill

 *
 * Run with:  bash scripts/verify-rejoin-history.sh
 *
 * Not part of the app build (scripts/ is outside tsconfig "include": ["src"]).
 * Every case drives the REAL store through the REAL message handler, so the
 * assertions cover the shipped code path, not a re-implementation.
 *
 * The proxy half (bounded buffer, per-game scoping, seq assignment) has its own
 * Java harness: Mage.WebProxy/src/test/java/mage/webproxy/GameHistoryHarness.java
 *
 * What is proven here:
 *  (a) the proxy's `history_backfill` payload restores log + chat content;
 *  (b) a client that already holds part of the history gets NO duplicates
 *      (dedupe by seq) and the panel reads chronologically;
 *  (c) the between-games log reset still works, and a backfill of a new game
 *      cannot resurrect the previous game's log;
 *  (d) malformed backfills fail fast and surface (no silent skip);
 * (e) the symptom — reload → backfill → a live event must not wipe the log.
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import type { GameEvent, GameState, HistoryBackfillMessage, Player, ServerMessage } from '../src/types'

// ── minimal but type-complete fixtures (same shape as verify-prompt-lifecycle) ──
const player = (name: string, isMe: boolean, prio: boolean): Player => ({
  name, playerId: name, life: 20, isMe, libraryCount: 40, handCount: 7,
  isActive: false, hasPriority: prio, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
  statesSavedSize: 1,
})

const state = (priorityPlayer: string): GameState => ({
  status: 'playing', turn: 3, phase: null, step: null,
  activePlayer: 'QA-A', priorityPlayer,
  stack: [], combat: [],
  players: [player('QA-A', true, priorityPlayer === 'QA-A'), player('QA-B', false, priorityPlayer === 'QA-B')],
  llmDebug: [], revealed: [], lookedAt: [], stateSeq: 1,
})

useGameStore.getState().setWebSocket({
  send: () => {},
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

// ── helpers ──
const msg = (m: unknown) => handleMessage(m as ServerMessage, useGameStore.getState())

/** A game-log event as the proxy serializes it (WebProxyClient.sendGameEvent). */
const logEv = (seq: number, message: string, category?: string): GameEvent =>
  category !== undefined
    ? { seq, type: 'action', message, category: category as GameEvent['category'] }
    : { seq, type: 'action', message }

/** A chat line as the proxy serializes it (type 'talk', with `from`). */
const chatEv = (seq: number, from: string, message: string): GameEvent =>
  ({ seq, type: 'talk', message, from })

/** A `history_backfill` message as WebProxyClient.sendHistoryBackfill emits it. */
const backfill = (gameId: string, events: GameEvent[], chat: GameEvent[]): HistoryBackfillMessage =>
  ({ type: 'history_backfill', gameId, events, chat })

const backfillMsg = (gameId: string, events: GameEvent[], chat: GameEvent[]): ServerMessage =>
  backfill(gameId, events, chat) as ServerMessage

const resetStore = () => {
  useGameStore.setState({
    eventLog: [], chatMessages: [], gameState: null, actionPrompt: null,
    currentActionPrompt: null, gameEnd: null, inGame: false,
  })
}

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ─────────────────────────────────────────────────────────────────────────────
section('BACKFILL — a rejoin restores log + chat (the defect)')
{
  resetStore()
  // Live game: start_game, then a turn of log lines and one chat message.
  msg({ type: 'start_game', gameId: 'GAME-1' })
  msg({ type: 'event', event: logEv(1, 'QA-A plays Plateau') })
  msg({ type: 'event', event: logEv(2, 'QA-B casts Ponder') })
  msg({ type: 'event', event: chatEv(3, 'QA-B', 'nice topdeck') })
  msg({ type: 'event', event: logEv(4, 'QA-A attacks with Ajani') })
  check('live game logs its events', useGameStore.getState().eventLog.length === 3,
    JSON.stringify(useGameStore.getState().eventLog.map(e => e.message)))
  check('live game logs its chat', useGameStore.getState().chatMessages.length === 1)

  // Browser drops and rejoins: the proxy replays what it buffered, then the
  // rejoin's game_init. Simulate the WIPE a page reload causes first — the store
  // is destroyed, so the panels start empty while the proxy's buffer survives.
  resetStore()
  check('panels start empty after the reload (reproduces the defect)', useGameStore.getState().eventLog.length === 0)

  msg({ type: 'start_game', gameId: 'GAME-1' })
  msg(backfillMsg('GAME-1',
    [logEv(1, 'QA-A plays Plateau'), logEv(2, 'QA-B casts Ponder'), logEv(4, 'QA-A attacks with Ajani')],
    [chatEv(3, 'QA-B', 'nice topdeck')]))
  msg({ type: 'game_init', state: state('QA-A') })

  const log = useGameStore.getState().eventLog
  const chat = useGameStore.getState().chatMessages
  check('history restored after rejoin (log)', log.length === 3,
    JSON.stringify(log.map(e => e.message)))
  check('history restored after rejoin (chat)', chat.length === 1 && chat[0]!.from === 'QA-B',
    JSON.stringify(chat))
  check('log reads chronologically (sorted by seq)',
    log.map(e => e.seq).join(',') === '1,2,4', log.map(e => e.seq).join(','))
  check('the join-banner-only log is gone', log.length > 0 && log[0]!.message === 'QA-A plays Plateau')
}

section('BACKFILL — no duplicates for a client that kept its panels (WS reconnect)')
{
  resetStore()
  // A WS reconnect (not a reload): the store kept everything it had, and the
  // proxy replays a WINDOW that overlaps it, plus newer events.
  msg({ type: 'start_game', gameId: 'GAME-1' })
  msg({ type: 'event', event: logEv(10, 'QA-A plays Mountain') })
  msg({ type: 'event', event: logEv(11, 'QA-B plays Island') })
  msg({ type: 'event', event: chatEv(12, 'QA-A', 'thinking') })
  msg({ type: 'event', event: logEv(13, 'QA-A casts Lightning Bolt') })

  msg(backfillMsg('GAME-1',
    [logEv(10, 'QA-A plays Mountain'), logEv(11, 'QA-B plays Island'),
     logEv(13, 'QA-A casts Lightning Bolt'), logEv(14, 'QA-B at 17 life')],
    [chatEv(12, 'QA-A', 'thinking'), chatEv(15, 'QA-B', 'ouch')]))

  const log = useGameStore.getState().eventLog
  const chat = useGameStore.getState().chatMessages
  check('overlapping log events are not duplicated', log.length === 4,
    JSON.stringify(log.map(e => `${e.seq}:${e.message}`)))
  check('newer events from the backfill are appended', log[3]!.message === 'QA-B at 17 life')
  check('seq order is monotonic after the merge',
    log.every((e, i) => i === 0 || log[i - 1]!.seq < e.seq), log.map(e => e.seq).join(','))
  check('overlapping chat lines are not duplicated', chat.length === 2,
    JSON.stringify(chat.map(e => `${e.seq}:${e.message}`)))
  check('only one copy of the pre-drop message',
    log.filter(e => e.message === 'QA-A plays Mountain').length === 1)
}

section('BACKFILL — regression guard: a live event after the backfill keeps the log')
{
  resetStore()
  // Page-reload path: start_game arms a lazy log reset; the backfill restores the
  // history; the next live event must NOT wipe it (it used to — that is the defect).
  msg({ type: 'start_game', gameId: 'GAME-1' })
  msg(backfillMsg('GAME-1',
    [logEv(20, 'QA-A plays Plateau'), logEv(21, 'QA-B casts Ponder')],
    []))
  check('history restored before the next live event', useGameStore.getState().eventLog.length === 2)
  msg({ type: 'event', event: logEv(22, 'QA-A discards a card') })
  const log = useGameStore.getState().eventLog
  check('the live event does not wipe the restored history', log.length === 3,
    JSON.stringify(log.map(e => e.message)))
  check('the live event is appended at the end', log[2]!.message === 'QA-A discards a card')
}

section('BACKFILL — a rejoin whose game_id was already announced (re-announcement)')
{
  resetStore()
  msg({ type: 'start_game', gameId: 'GAME-7' })
  msg({ type: 'event', event: logEv(30, 'QA-A mulligans to 6') })
  // The browser re-runs the connect flow: start_game for the SAME game, backfill,
  // game_init. A start_game for the same id must not clear the log.
  msg({ type: 'start_game', gameId: 'GAME-7' })
  msg(backfillMsg('GAME-7', [logEv(30, 'QA-A mulligans to 6'), logEv(31, 'QA-B keeps 7')], []))
  check('re-announced start_game + backfill leaves exactly the union', useGameStore.getState().eventLog.length === 2,
    JSON.stringify(useGameStore.getState().eventLog.map(e => e.message)))
}

section('BACKFILL — between-games reset still works, and cannot be bypassed')
{
  resetStore()
  msg({ type: 'start_game', gameId: 'GAME-1' })
  msg(backfillMsg('GAME-1', [logEv(40, 'GAME 1: QA-A wins')], []))
  check('game 1 history present', useGameStore.getState().eventLog.length === 1)

  // Bo3 game 2: a NEW gameId. The proxy's buffer is reset for the new game, so its
  // backfill carries only the new game's events...
  msg({ type: 'start_game', gameId: 'GAME-2' })
  msg(backfillMsg('GAME-2', [logEv(41, 'GAME 2: QA-A plays Forest')], []))
  const log = useGameStore.getState().eventLog
  check('game 2 backfill replaces game 1 log (no cross-game contamination)',
    log.length === 1 && log[0]!.message === 'GAME 2: QA-A plays Forest',
    JSON.stringify(log.map(e => e.message)))

  // ...and the normal live-event reset path is unchanged.
  msg({ type: 'start_game', gameId: 'GAME-3' })
  msg({ type: 'event', event: logEv(42, 'GAME 3: QA-B plays Swamp') })
  const log3 = useGameStore.getState().eventLog
  check('a new game still starts from a clean live log',
    log3.length === 1 && log3[0]!.message === 'GAME 3: QA-B plays Swamp',
    JSON.stringify(log3.map(e => e.message)))
  msg({ type: 'event', event: logEv(43, 'GAME 3: QA-A plays Plains') })
  check('the new game keeps logging', useGameStore.getState().eventLog.length === 2)
}

section('BACKFILL — spam filtering + HTML stripping match the live path')
{
  resetStore()
  msg({ type: 'start_game', gameId: 'GAME-1' })
  msg(backfillMsg('GAME-1', [
    logEv(50, 'QA-A plays Island'),
    logEv(51, 'QA-A - Waiting for QA-B'),          // per-state-push spam
    logEv(52, 'Attacker: Ajani unblocked'),        // spam
    logEv(53, '<font color="red">QA-A takes 2 damage</font>'),
  ], [chatEv(54, 'QA-B', '<b>hey</b>')]))
  const log = useGameStore.getState().eventLog
  check('spam lines are filtered out of a backfill', log.length === 2,
    JSON.stringify(log.map(e => e.message)))
  check('server HTML is stripped in a backfill', log[1]!.message === 'QA-A takes 2 damage',
    JSON.stringify(log.map(e => e.message)))
  check('chat HTML is stripped in a backfill', useGameStore.getState().chatMessages[0]!.message === 'hey')
}

section('BACKFILL — malformed payloads fail fast and surface (no silent skip)')
{
  resetStore()
  msg({ type: 'start_game', gameId: 'GAME-1' })
  const before = useGameStore.getState().eventLog.length

  msg({ type: 'history_backfill', gameId: '', events: [], chat: [] } as ServerMessage)
  check('empty gameId surfaces an error toast',
    useGameStore.getState().errorToastVisible && /gameId/.test(useGameStore.getState().errorMessage),
    useGameStore.getState().errorMessage)

  useGameStore.setState({ errorToastVisible: false, errorMessage: '' })
  msg({ type: 'history_backfill', gameId: 'GAME-1' } as ServerMessage)
  check('missing events/chat arrays surfaces an error toast',
    useGameStore.getState().errorToastVisible && /events/.test(useGameStore.getState().errorMessage),
    useGameStore.getState().errorMessage)

  useGameStore.setState({ errorToastVisible: false, errorMessage: '' })
  msg({ type: 'history_backfill', gameId: 'GAME-1', events: [{ type: 'action', message: 'no seq' }], chat: [] } as unknown as ServerMessage)
  check('an event without a seq surfaces an error toast',
    useGameStore.getState().errorToastVisible && /seq/.test(useGameStore.getState().errorMessage),
    useGameStore.getState().errorMessage)

  check('a malformed backfill did not corrupt the panels', useGameStore.getState().eventLog.length === before)
}

section('BACKFILL — caps still hold (long-game memory guard)')
{
  resetStore()
  msg({ type: 'start_game', gameId: 'GAME-1' })
  const events: GameEvent[] = []
  for (let i = 0; i < 400; i++) events.push(logEv(1000 + i, `event ${i}`))
  msg(backfillMsg('GAME-1', events, []))
  const log = useGameStore.getState().eventLog
  check('a backfill larger than the panel cap is trimmed to 200', log.length === 200, String(log.length))
  check('the trim keeps the newest events (tail)',
    log[log.length - 1]!.message === 'event 399' && log[0]!.message === 'event 200',
    `${log[0]!.message} .. ${log[log.length - 1]!.message}`)
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
