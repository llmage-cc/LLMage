/**
 * Store-level check for the REPLAY frontend slice.
 *
 * Drives the REAL store through the REAL message handler (same pattern as
 * verify-prompt-lifecycle.ts) and asserts the request/response state machine the
 * ReplayView + ReplayBrowser depend on:
 *   - finished_matches / replay_files populate the lobby lists
 *   - replay_game opens the viewer AND auto-sends replay_start
 *   - replay_init / replay_update advance replayState + replayFrame
 *   - replay_next / replay_previous / replay_skip send the right requests
 *   - replay_done 'boundary' KEEPS the viewer open (session still alive)
 *   - replay_done 'stopped' / 'error' close the viewer; 'error' surfaces a toast
 *   - selectCurrentView returns 'replay' while the viewer is open
 *   - the viewer is read-only: no game-action request can be produced from it
 *   - the Game Log tab is offered for live games and for replays that CARRY log
 *     data (flat capture), hidden for saves written before it
 *   - replayLogSlice renders entries 0..count-1 and grows/shrinks with stepping
 *
 * Run with:  flock /tmp/xmage-npm.lock npx tsx scripts/verify-replay-store.ts
 * (scripts/ is outside tsconfig "include": ["src"], so this is not part of the build.)
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { selectCurrentView, selectBoardState } from '../src/store/selectors'
import { effectiveRightTab, visibleRightTabs } from '../src/components/RightRail'
import { replayHasGameLog, replayLogSliceFor } from '../src/store/slices/replaySlice'
import { formatBytes } from '../src/utils/formatBytes'
import type { GameState, Player, ServerMessage } from '../src/types'

// ── fixtures ──
const player = (name: string): Player => ({
  name, playerId: name, life: 20, isMe: false, libraryCount: 40, handCount: 0,
  isActive: false, hasPriority: false, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
})

const frame = (turn: number): GameState => ({
  status: 'playing', turn, phase: null, step: null,
  activePlayer: 'A', priorityPlayer: '',
  stack: [], combat: [], players: [player('A'), player('B')],
  llmDebug: [], revealed: [], lookedAt: [], stateSeq: turn,
})

/** A replay frame carrying the flat log fields (post-capture save). */
const logFrame = (turn: number, log: string[], count: number): GameState => ({
  ...frame(turn), gameLog: log, gameLogCount: count,
})

// ── wire capture ──
const sent: Record<string, unknown>[] = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  noteConnectAccepted: () => {},
  disconnect: () => {},
} as never)

const feed = (m: ServerMessage) => handleMessage(m, useGameStore.getState())
const lastSent = () => sent[sent.length - 1]
const takeSent = () => sent.splice(0, sent.length)

// The store must look connected, otherwise selectCurrentView falls through to
// 'connect' and the view-routing assertions below would test nothing.
feed({ type: 'connected', username: 'qa' } as ServerMessage)
takeSent()

let failures = 0
function check(name: string, ok: boolean, detail = '') {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

// ── 1. lists ──
takeSent()
useGameStore.getState().requestFinishedMatches()
useGameStore.getState().requestReplayFiles()
check('requestFinishedMatches sends get_finished_matches', lastSent()?.type === 'get_finished_matches' || sent[0]?.type === 'get_finished_matches')
check('requestReplayFiles sends get_replay_files', sent.some((m) => m.type === 'get_replay_files'))

feed({ type: 'finished_matches', matches: [{ matchId: 'm1', tableId: 't1', name: 'G', gameType: 'Two Player Duel', deckType: 'D', players: 'A, B', result: 'A [1-0], B [0-1]', tournament: false, replayAvailable: true, startTime: 1727000000000, endTime: 1727000300000, rated: true, playerList: [{ name: 'A', wins: 1, quit: false, deckName: 'Burn' }, { name: 'B', wins: 0, quit: false, deckName: 'Control' }], games: ['g1', 'g2'] }] } as ServerMessage)
feed({ type: 'replay_files', directory: '/x/saved', gameIds: ['a', 'b'], fileMeta: { a: { bytes: 123, savedAtMs: 1727000900000, players: [{ name: 'C', wins: 2, quit: false, deck: 'Elves' }] } } } as ServerMessage)
check('finished_matches populates the list', useGameStore.getState().finishedMatches.length === 1)
check('replay_files populates the file list', useGameStore.getState().replayFiles.join(',') === 'a,b')
check('replay file metadata is stored', useGameStore.getState().replayFileMeta['a']?.players?.[0]?.name === 'C')

// ── 2. load → viewer opens + auto-start ──
takeSent()
useGameStore.getState().loadReplay('3b20f6c3-b752-4b87-8d46-cfc088908b4f')
check('loadReplay sends replay_load with the gameId', lastSent()?.type === 'replay_load' && lastSent()?.gameId === '3b20f6c3-b752-4b87-8d46-cfc088908b4f')
check('viewer is NOT open before the server ack', useGameStore.getState().inReplay === false)
// The load notice is raised optimistically on send: the server blocks for
// seconds, so the UI must be able to say "loading" before anything arrives.
check('loadReplay raises the load notice', useGameStore.getState().replayLoading === true)
check('load notice carries the game id', useGameStore.getState().replayLoadingGameId === '3b20f6c3-b752-4b87-8d46-cfc088908b4f')
check('load notice has no size yet', useGameStore.getState().replayLoadingBytes === null)

// replay_loading (the proxy's interim ack) supplies the size but must NOT open
// the viewer or send replay_start — replay_game is still the trigger for that.
feed({ type: 'replay_loading', gameId: '3b20f6c3-b752-4b87-8d46-cfc088908b4f', bytes: 5212020 } as ServerMessage)
check('replay_loading fills in the byte size', useGameStore.getState().replayLoadingBytes === 5212020)
check('replay_loading does NOT open the viewer', useGameStore.getState().inReplay === false)
check('replay_loading sends no replay_start', !sent.some((m) => m.type === 'replay_start'))
check('viewer still reported as not-open after the ack', selectCurrentView(useGameStore.getState()) === 'lobby')

// A late ack for a DIFFERENT game must be ignored (the user moved on).
feed({ type: 'replay_loading', gameId: 'some-other-game', bytes: 1 } as ServerMessage)
check('ack for another game does not change the size', useGameStore.getState().replayLoadingBytes === 5212020)

feed({ type: 'replay_game', gameId: '3b20f6c3-b752-4b87-8d46-cfc088908b4f' } as ServerMessage)
check('replay_game opens the viewer', useGameStore.getState().inReplay === true)
check('replay_game auto-sends replay_start', lastSent()?.type === 'replay_start', String(lastSent()?.type))
check('selectCurrentView is replay', selectCurrentView(useGameStore.getState()) === 'replay')
check('replayState is null until the first frame', useGameStore.getState().replayState === null)
// The notice persists through replay_game: the first frame has not arrived yet,
// so the viewer must keep showing load progress rather than an empty board.
check('load notice survives replay_game (no frame yet)', useGameStore.getState().replayLoading === true)

// ── 3. frames ──
feed({ type: 'replay_init', gameId: 'g', state: frame(1) } as ServerMessage)
check('replay_init sets replayState', useGameStore.getState().replayState?.turn === 1)
check('replay_init bumps replayFrame', useGameStore.getState().replayFrame === 1)
check('replay_init CLEARS the load notice', useGameStore.getState().replayLoading === false)
check('replay_init clears the load size', useGameStore.getState().replayLoadingBytes === null)
check('replay_init clears the loading game id', useGameStore.getState().replayLoadingGameId === null)
check('selectBoardState returns the replay frame', selectBoardState(useGameStore.getState())?.turn === 1)
feed({ type: 'replay_update', gameId: 'g', state: frame(2) } as ServerMessage)
check('replay_update replaces the frame', useGameStore.getState().replayState?.turn === 2)
check('replay_update bumps replayFrame', useGameStore.getState().replayFrame === 2)
check('gameState was NOT touched by replay frames', useGameStore.getState().gameState === null)

// ── 4. controls ──
takeSent()
useGameStore.getState().replayNext()
check('replayNext sends replay_next', lastSent()?.type === 'replay_next')
useGameStore.getState().replayPrevious()
check('replayPrevious sends replay_previous', lastSent()?.type === 'replay_previous')
useGameStore.getState().replaySkip(10)
check('replaySkip sends replay_skip moves=10', lastSent()?.type === 'replay_skip' && lastSent()?.moves === 10)
check('direction tracked as forward for skip', useGameStore.getState().replayLastDirection === 'forward')

// ── 4b. position control (scrubber) ──
// The count arrives with each frame; without it the control has no track length.
takeSent()
feed({ type: 'replay_update', gameId: 'g', state: frame(2), replayStateIndex: 40, replayStateCount: 573 } as ServerMessage)
check('state count is stored from the frame', useGameStore.getState().replayStateCount === 573)
check('state index is stored from the frame', useGameStore.getState().replayStateIndex === 40)
takeSent()
useGameStore.getState().replaySeek(137)
check('replaySeek sends replay_seek stateIndex=137', lastSent()?.type === 'replay_seek' && lastSent()?.stateIndex === 137)
check('a forward seek is tracked as forward', useGameStore.getState().replayLastDirection === 'forward')
useGameStore.getState().replaySeek(12)
check('a backward seek is tracked as backward', useGameStore.getState().replayLastDirection === 'backward')
// Fail Fast: an out-of-range index must not reach the wire.
takeSent()
useGameStore.getState().replaySeek(573)
check('a seek past the last frame is refused locally', sent.length === 0 && useGameStore.getState().errorToastVisible === true)
takeSent()
useGameStore.getState().replaySeek(-1)
check('a negative seek is refused locally', sent.length === 0)
takeSent()
useGameStore.getState().replaySeek(40)
check('a seek to the current frame sends nothing', sent.length === 0)
// A frame that carries no count must RETAIN the last known length rather than
// blanking the track (one field-less frame must not shrink the control).
takeSent()
feed({ type: 'replay_update', gameId: 'g', state: frame(3), replayStateIndex: 40 } as ServerMessage)
check('a frame without a count retains the last known length', useGameStore.getState().replayStateCount === 573)
// Opening a NEW replay resets the track length, so the previous save's length
// can never size the next replay's control.
useGameStore.getState().onReplayGame('another-game')
check('a new replay resets the track length', useGameStore.getState().replayStateCount === -1)

// ── 5. boundary keeps the viewer open ──
feed({ type: 'replay_done', gameId: 'g', reason: 'boundary', result: 'end of replay' } as ServerMessage)
check('boundary KEEPS the viewer open', useGameStore.getState().inReplay === true)
check('boundary is recorded for the notice', useGameStore.getState().replayDone?.reason === 'boundary')
check('boundary stops playback', useGameStore.getState().replayPlaying === false)

// a frame arriving after a boundary clears the notice
feed({ type: 'replay_update', gameId: 'g', state: frame(1) } as ServerMessage)
check('a frame after a boundary clears the notice', useGameStore.getState().replayDone === null)

// ── 6. stop ──
takeSent()
useGameStore.getState().stopReplay()
check('stopReplay closes the viewer locally', useGameStore.getState().inReplay === false)
check('stopReplay sends replay_stop', lastSent()?.type === 'replay_stop')
check('selectCurrentView returns to lobby', selectCurrentView(useGameStore.getState()) === 'lobby')
// the late REPLAY_DONE for our own stop must not resurrect anything
feed({ type: 'replay_done', gameId: 'g', reason: 'stopped', result: 'stopped replay' } as ServerMessage)
check('late stopped REPLAY_DONE is harmless', useGameStore.getState().inReplay === false && useGameStore.getState().replayDone === null)

// ── 7. error path surfaces to the user ──
useGameStore.getState().loadReplay('fbb390e8-e4d0-493d-9b90-db2a9696e6f7')
// The load notice is up while the server tries (and fails) to open the file.
check('load notice is up for the failing load', useGameStore.getState().replayLoading === true)
feed({ type: 'replay_game', gameId: 'fbb390e8-e4d0-493d-9b90-db2a9696e6f7' } as ServerMessage)
check('second replay opens the viewer', useGameStore.getState().inReplay === true)
// A missing/corrupt file fails ASYNC on the server: replay_game acks, then the
// error arrives. The notice must go away when it does, or the viewer keeps
// claiming to load something that will never arrive.
feed({ type: 'replay_done', gameId: 'fbb390e8-e4d0-493d-9b90-db2a9696e6f7', reason: 'error', result: 'Replay file not found or corrupt' } as ServerMessage)
check('error closes the viewer', useGameStore.getState().inReplay === false)
check('error clears the load notice', useGameStore.getState().replayLoading === false)
check('error is SURFACED as a toast', useGameStore.getState().errorToastVisible === true && /not found or corrupt/.test(useGameStore.getState().errorMessage))

// ── 7b. a REFUSED load must not stick at "⏳ loading…" ──
// The live-game guard in WebProxySession.handleReplayLoad refuses replay_load with a
// proxy `error` frame ("Cannot load a replay while in a game — stop watching/playing
// first"). That refusal is the ONE load outcome with no replay_game/replay_done after
// it, so without an explicit clear the clicked row renders "⏳ loading…" forever and
// the refusal is indistinguishable from a slow load

takeSent()
useGameStore.getState().loadReplay('refused-game-id')
check('refused load raises the notice', useGameStore.getState().replayLoading === true)
feed({ type: 'error', message: 'Cannot load a replay while in a game — stop watching/playing first' } as ServerMessage)
check('error clears the stuck load notice', useGameStore.getState().replayLoading === false)
check('error clears the loading game id', useGameStore.getState().replayLoadingGameId === null)
check('error clears the loading size', useGameStore.getState().replayLoadingBytes === null)
check('the refusal is still surfaced as a toast', useGameStore.getState().errorToastVisible === true)
check('a refused load does not open the viewer', useGameStore.getState().inReplay === false)
// An error with NO load pending must not disturb a replay being WATCHED: frames
// already arrived (the session is live), so dropping the notice must not close it.
feed({ type: 'replay_game', gameId: 'watched-game' } as ServerMessage)
feed({ type: 'replay_init', gameId: 'watched-game', state: frame(1) } as ServerMessage)
check('viewer is open with a frame before the error', useGameStore.getState().inReplay === true && useGameStore.getState().replayLoading === false)
feed({ type: 'error', message: 'some unrelated proxy error' } as ServerMessage)
check('an error while viewing does NOT close the viewer', useGameStore.getState().inReplay === true)
check('...and does not touch the frame', useGameStore.getState().replayState?.turn === 1)
useGameStore.getState().stopReplay()

// ── 8. read-only: no game action is reachable from the replay state ──
// The viewer passes no onCardClick/onManaClick, and gameplaySlice's action
// methods are never called by ReplayView. Assert the requests that WOULD be
// game actions are absent from everything the replay flow emitted.
const gameActionTypes = ['select_choice', 'play_card', 'select_target', 'tap_mana', 'pass_priority', 'concede', 'undo', 'use_mana']
const emitted = sent.map((m) => m.type)
check('no game-action request emitted by the replay flow', !emitted.some((t) => gameActionTypes.includes(String(t))), emitted.join(','))

// ── 10. Game Log tab in the replay viewer (flat capture) ──
// A replay frame carries the saved game's final log once (gameLog) plus the
// count of entries as of THAT frame (gameLogCount) — see
// option A. The tab is offered
// whenever the replay actually has log data, and hidden for saves written
// before the capture work (which carry neither field).
check('live game WITH an LLM player offers all three tabs', visibleRightTabs(false, false, true, true).join(',') === 'log,chat,llm', visibleRightTabs(false, false, true, true).join(','))
check('replay WITHOUT log data does not offer Game Log', !visibleRightTabs(true, false).includes('log'), visibleRightTabs(true, false).join(','))
check('replay without log still offers chat + llm', visibleRightTabs(true, false).join(',') === 'chat,llm', visibleRightTabs(true, false).join(','))
check('replay WITH log data offers all three tabs', visibleRightTabs(true, true).join(',') === 'log,chat,llm', visibleRightTabs(true, true).join(','))
check('stored log + live => log', effectiveRightTab('log', false) === 'log')
check('stored log + logless replay => chat (fallback, not an empty panel)', effectiveRightTab('log', true, false) === 'chat', effectiveRightTab('log', true, false))
check('stored log + log-bearing replay => log (the tab is back)', effectiveRightTab('log', true, true) === 'log', effectiveRightTab('log', true, true))
check('stored chat is unaffected by a replay', effectiveRightTab('chat', true, true) === 'chat')

// ── 10c. LLM tab gate: ROOT-LEVEL hasLlmPlayers (live only) ──
// The tab must follow the root-level UNFILTERED flag the serializer emits, not
// `llmDebug` itself: that list is per-viewer FILTERED, so an opponent in a live
// game with private LLM chat has an EMPTY llmDebug while LLM players ARE in the
// game — gating on it would hide the tab from exactly the games that have an
// LLM. The flag defaults
// to false, so a caller with no flag data (older proxy / no state) gets the
// conservative answer.
check('live WITHOUT LLM players does not offer the LLM tab', !visibleRightTabs(false, false, true, false).includes('llm'), visibleRightTabs(false, false, true, false).join(','))
check('...the live rail is log+chat then', visibleRightTabs(false, false, true, false).join(',') === 'log,chat', visibleRightTabs(false, false, true, false).join(','))
check('live default (no flag data) conservatively hides the LLM tab', visibleRightTabs(false).join(',') === 'log,chat', visibleRightTabs(false).join(','))
check('stock server never offers the LLM tab, even with LLM players', visibleRightTabs(false, false, false, true).join(',') === 'log,chat', visibleRightTabs(false, false, false, true).join(','))
check('replay offer is UNCHANGED by hasLlmPlayers (gates on the frame, not the flag)', visibleRightTabs(true, false, true, false).join(',') === 'chat,llm' && visibleRightTabs(true, false, true, true).join(',') === 'chat,llm', visibleRightTabs(true, false, true, false).join(','))
check('a stored LLM tab falls back when the flag is false (never an empty panel)', effectiveRightTab('llm', false, false, true, false) === 'log', effectiveRightTab('llm', false, false, true, false))
check('...and is restored verbatim once LLM players are in the game', effectiveRightTab('llm', false, false, true, true) === 'llm', effectiveRightTab('llm', false, false, true, true))
check('a stored LLM tab survives into a replay (unchanged semantics)', effectiveRightTab('llm', true, false, true, false) === 'llm', effectiveRightTab('llm', true, false, true, false))

// ── 10b. replayLogSliceFor — the per-frame slice of the flat log ──
const LOG = ['[1:UPKEEP] A plays Forest', '[1:DRAW] A draws a card', '[2:UPKEEP] B casts Shock', '[2:DRAW] B draws a card']
check('slice renders entries 0..count-1', replayLogSliceFor(logFrame(2, LOG, 2)).join('|') === LOG.slice(0, 2).join('|'), replayLogSliceFor(logFrame(2, LOG, 2)).join('|'))
check('later frame renders more entries (stepping forward grows)', replayLogSliceFor(logFrame(3, LOG, 4)).length === 4)
check('earlier frame renders fewer entries (stepping back shrinks)', replayLogSliceFor(logFrame(2, LOG, 2)).length === 2)
check('count is clamped to the log length (never fabricates lines)', replayLogSliceFor(logFrame(9, LOG, 99)).length === 4)
check('no log fields => empty slice (tab stays hidden)', replayLogSliceFor(frame(1)).length === 0)
check('null state => empty slice', replayLogSliceFor(null).length === 0)
check('server HTML in log lines is stripped', replayLogSliceFor(logFrame(1, ['<font color="red">A</font> plays Forest'], 1))[0] === 'A plays Forest', replayLogSliceFor(logFrame(1, ['<font color="red">A</font> plays Forest'], 1))[0])

// Through the REAL store: a log-bearing replay drives the slice, the user's
// stored tab choice survives the visit, and leaving restores the live tab.
useGameStore.getState().setActiveRightTab('log')
useGameStore.getState().onReplayGame('log-game')
check('slice starts empty while the replay loads', useGameStore.getState().replayLogSlice.length === 0)
feed({ type: 'replay_init', gameId: 'log-game', state: logFrame(1, LOG, 1) } as ServerMessage)
check('replay_init fills the slice from the frame', useGameStore.getState().replayLogSlice.join('|') === LOG.slice(0, 1).join('|'))
check('...and the rail offers the Game Log tab', effectiveRightTab(useGameStore.getState().activeRightTab, useGameStore.getState().inReplay, replayHasGameLog(useGameStore.getState().replayState)) === 'log')
feed({ type: 'replay_update', gameId: 'log-game', state: logFrame(2, LOG, 3) } as ServerMessage)
check('stepping forward grows the slice', useGameStore.getState().replayLogSlice.length === 3)
check('stored tab still log after the frames', useGameStore.getState().activeRightTab === 'log')
useGameStore.getState().stopReplay()
check('leaving the replay clears the slice', useGameStore.getState().replayLogSlice.length === 0)
check('leaving the replay restores the live Game Log tab', effectiveRightTab(useGameStore.getState().activeRightTab, useGameStore.getState().inReplay, false) === 'log')

// A logless replay (pre-capture save) must not offer the tab even through the store.
useGameStore.getState().setActiveRightTab('log')
useGameStore.getState().onReplayGame('logless-game')
feed({ type: 'replay_init', gameId: 'logless-game', state: frame(1) } as ServerMessage)
check('logless replay yields an empty slice', useGameStore.getState().replayLogSlice.length === 0)
check('...so the rail falls back to chat, not an empty log panel', effectiveRightTab(useGameStore.getState().activeRightTab, useGameStore.getState().inReplay, replayHasGameLog(useGameStore.getState().replayState)) === 'chat')

// The tab gate must be on the replay CARRYING a log, not on THIS frame's slice:
// frame 0's slice is legitimately empty, and gating there would flicker the tab
// bar (hidden on frame 1, popped in on frame 2).
check('frame 0 slice is empty', replayLogSliceFor(logFrame(1, LOG, 0)).length === 0)
check('...but the replay still COUNTS as carrying a log (no tab flicker)', replayHasGameLog(logFrame(1, LOG, 0)) === true)
check('a logless frame does not count as carrying a log', replayHasGameLog(frame(1)) === false)
check('null state does not count as carrying a log', replayHasGameLog(null) === false)
useGameStore.getState().stopReplay()

// ── 10d. the LLM tab gate through the REAL store ──
// `hasLlmPlayers` rides the game state payload (game_update / game_delta), so
// assert the gate is reachable from store state — RightRail computes it as
// `selectBoardState(s)?.hasLlmPlayers === true` and passes it (plus the other
// store inputs) to effectiveRightTab.
const railTab = () => {
  const s = useGameStore.getState()
  return effectiveRightTab(
    s.activeRightTab,
    s.inReplay,
    s.inReplay && replayHasGameLog(s.replayState),
    s.capabilities.llm,
    selectBoardState(s)?.hasLlmPlayers === true,
  )
}
useGameStore.getState().setActiveRightTab('llm')
feed({ type: 'game_update', state: { ...frame(2), hasLlmPlayers: false } } as ServerMessage)
check('live state WITHOUT LLM players: a stored llm tab falls back to log', railTab() === 'log', railTab())
feed({ type: 'game_update', state: { ...frame(3), hasLlmPlayers: true } } as ServerMessage)
check('live state WITH an LLM player: the stored llm tab is offered again', railTab() === 'llm', railTab())
// The backend's delta is a top-level key diff, so the flag flips arrive as a
// delta key — the applicator must carry it or the rail sees a stale gate.
feed({ type: 'game_delta', delta: { hasLlmPlayers: false }, stateSeq: 99 } as ServerMessage)
check('a game_delta flipping the flag back hides the tab again', railTab() === 'log', railTab())
// Replay boards are unchanged by this feature: their llm offer follows the
// FRAME (not the live flag), so a replay opened right after a no-LLM live game
// still offers the tab and a stored llm choice survives the visit.
useGameStore.getState().onReplayGame('llm-replay')
feed({ type: 'replay_init', gameId: 'llm-replay', state: frame(1) } as ServerMessage)
check('replay frames keep offering the LLM tab (live flag not consulted)', railTab() === 'llm', railTab())
useGameStore.getState().stopReplay()
check('leaving the replay re-applies the LIVE gate (flag false → fallback)', railTab() === 'log', railTab())

// ── 9. size formatting for the load notice ──
check('formatBytes: null yields no label', formatBytes(null) === '', `got ${JSON.stringify(formatBytes(null))}`)
check('formatBytes: bytes', formatBytes(512) === '512 B', formatBytes(512))
check('formatBytes: KB', formatBytes(40960) === '40 KB', formatBytes(40960))
check('formatBytes: MB', formatBytes(5212020) === '5.0 MB', formatBytes(5212020))

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)

