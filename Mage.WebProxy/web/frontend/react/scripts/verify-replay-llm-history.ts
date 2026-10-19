/**
 * Behavioural check: the REPLAY LLM tab's cumulative chat history.

 *
 * Run with:  flock /tmp/xmage-npm.lock npx tsx scripts/verify-replay-llm-history.ts
 * Drives the REAL store through the REAL message handler and asserts:
 *  (a) the accumulated list grows with forward steps and shrinks on backward
 *      steps (history as of the cursor, no duplicates),
 *  (b) a frame with NO LLM bucket keeps the prior history (the "totally blank"
 *      case is gone) and does NOT add an empty bucket,
 *  (c) the toggle OFF returns the rail to single-frame behavior and the setting
 *      persists (xmage-settings registry + Reset to Default coverage),
 *  (d) replayLLMHistoryFor merge semantics: per-player message concatenation in
 *      frame order, idempotent re-delivery, empty input yields empty output.
 */
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { replayLLMHistoryFor, type ReplayLLMFrame } from '../src/store/slices/replaySlice'
import { emptyLlmCopyFor, RightRail } from '../src/components/RightRail'
import { resetSettingsToDefaults } from '../src/utils/settingsDefaults'
import type { GameState, LLMDebugEntry, LLMMessage, Player, ServerMessage } from '../src/types'

declare const process: { exit(code: number): void }

// ── fixtures ──
const player = (name: string): Player => ({
  name, playerId: name, life: 20, isMe: false, libraryCount: 40, handCount: 0,
  isActive: false, hasPriority: false, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
} as unknown as Player)

/** An LLM exchange bucket as one frame carries it (the serializer's 1:1 pass-through). */
const llmEntry = (playerName: string, messages: LLMMessage[]): LLMDebugEntry => ({
  playerName,
  modelName: 'test-model',
  exchangeCount: 1,
  totalContextChars: 0,
  modelContextWindow: 0,
  streaming: false,
  messages,
})

/** A replay frame whose llmDebug carries the given entries (undefined = no bucket). */
const frame = (turn: number, llm?: LLMDebugEntry[]): GameState => ({
  status: 'playing', turn, phase: null, step: null,
  activePlayer: 'A', priorityPlayer: '',
  stack: [], combat: [], players: [player('A'), player('B')],
  llmDebug: llm ?? [],
  revealed: [], lookedAt: [], stateSeq: turn,
} as unknown as GameState)

// ── wire capture ──
useGameStore.getState().setWebSocket({
  send: () => {},
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  noteConnectAccepted: () => {},
  disconnect: () => {},
} as never)

const feed = (m: ServerMessage) => handleMessage(m, useGameStore.getState())

let failures = 0
function check(name: string, ok: boolean, detail = '') {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

// The exchange sequence:
// frames: empty → exchange A → empty → exchange B.
const EX1: LLMMessage[] = [
  { role: 'user', content: 'Prompt one?' },
  { role: 'assistant', content: 'Answer one.' },
]
const EX2: LLMMessage[] = [
  { role: 'user', content: 'Prompt two?' },
  { role: 'assistant', content: 'Answer two.' },
]

// ── (d) pure accumulator ──
{
  const frames: ReplayLLMFrame[] = [
    { stateIndex: 0, entries: [llmEntry('LLM-1', EX1)] },
    { stateIndex: 5, entries: [llmEntry('LLM-1', EX2)] },
  ]
  const at0 = replayLLMHistoryFor(frames, 0)
  // Each frame's bucket carries that frame's own exchange — EX1 is one
  // user+assistant pair, i.e. 2 messages (the per-frame shape).
  check('cursor 0: only the first frame’s messages', at0.length === 1 && at0[0]!.messages.length === EX1.length, JSON.stringify(at0))
  const at5 = replayLLMHistoryFor(frames, 5)
  check('cursor 5: both frames merged in order', at5.length === 1 && at5[0]!.messages.length === EX1.length + EX2.length &&
    at5[0]!.messages[0]!.content === 'Prompt one?' && at5[0]!.messages[EX1.length]!.content === 'Prompt two?', JSON.stringify(at5))
  const at3 = replayLLMHistoryFor(frames, 3)
  check('scrubbing BACK drops the later exchange', at3.length === 1 && at3[0]!.messages.length === EX1.length)
  // The pure merger has no dedupe to do: each frame's bucket
  // carries only that frame's exchanges, so production never hands it a
  // duplicate frame — frame-level idempotency ("a frame that arrives twice
  // writes the same key", ) lives in
  // setReplayState and is covered through the REAL store below.
  const two = replayLLMHistoryFor(
    [
      { stateIndex: 0, entries: [llmEntry('LLM-1', EX1), llmEntry('LLM-2', EX1)] },
      { stateIndex: 5, entries: [llmEntry('LLM-2', EX2)] },
    ],
    5,
  )
  const p1 = two.find((e) => e.playerName === 'LLM-1')!
  const p2 = two.find((e) => e.playerName === 'LLM-2')!
  check('per-player merge keeps both seats',
    !!p1 && !!p2 && p1.messages.length === EX1.length && p2.messages.length === EX1.length + EX2.length)
  check('empty bucket list yields empty history', replayLLMHistoryFor([], 99).length === 0)
}

// ── (a)+(b) through the REAL store ──
feed({ type: 'connected', username: 'qa' } as ServerMessage)
useGameStore.getState().onReplayGame('hist-game')
check('buckets start empty', useGameStore.getState().replayLLMFrames.length === 0)

// Frame 1: empty phase — no bucket written.
feed({ type: 'replay_init', gameId: 'hist-game', state: frame(1), replayStateIndex: 0, replayStateCount: 9 } as ServerMessage)
check('an empty frame writes no bucket', useGameStore.getState().replayLLMFrames.length === 0)
check('...and the cumulative view is empty at cursor 0', replayLLMHistoryFor(useGameStore.getState().replayLLMFrames, 0).length === 0)

// Frame 2: exchange A (the LLM acted at state index 2).
feed({ type: 'replay_update', gameId: 'hist-game', state: frame(2, [llmEntry('LLM-1', EX1)]), replayStateIndex: 2, replayStateCount: 9 } as ServerMessage)
check('a decision frame retains its bucket', useGameStore.getState().replayLLMFrames.length === 1)
check('the view shows exchange 1', replayLLMHistoryFor(useGameStore.getState().replayLLMFrames, 2).length === 1)

// Frame 3: empty again — prior history stays visible.
feed({ type: 'replay_update', gameId: 'hist-game', state: frame(3), replayStateIndex: 3, replayStateCount: 9 } as ServerMessage)
check('an empty frame between decisions keeps the prior bucket', useGameStore.getState().replayLLMFrames.length === 1)
check('...and the view still shows exchange 1 at cursor 3', replayLLMHistoryFor(useGameStore.getState().replayLLMFrames, 3).length === 1)

// Frame 4: exchange B (at state index 5 — a seek forward past frame 3).
feed({ type: 'replay_update', gameId: 'hist-game', state: frame(4, [llmEntry('LLM-1', EX2)]), replayStateIndex: 5, replayStateCount: 9 } as ServerMessage)
{
  const hist = replayLLMHistoryFor(useGameStore.getState().replayLLMFrames, 5)
  check('stepping forward shows both exchanges', hist.length === 1 && hist[0]!.messages.length === EX1.length + EX2.length, JSON.stringify(hist.map((e) => e.messages.length)))
}
// Scrub BACK to frame 3: exchange B disappears.
feed({ type: 'replay_update', gameId: 'hist-game', state: frame(3), replayStateIndex: 3, replayStateCount: 9 } as ServerMessage)
{
  const hist = replayLLMHistoryFor(useGameStore.getState().replayLLMFrames, 3)
  check('scrubbing BACK drops the later exchange (history as of the cursor)', hist.length === 1 && hist[0]!.messages.length === EX1.length, JSON.stringify(hist.map((e) => e.messages.length)))
}

// A new replay resets the buckets.
useGameStore.getState().onReplayGame('hist-game-2')
check('a different replay resets the buckets', useGameStore.getState().replayLLMFrames.length === 0 && useGameStore.getState().replayLLMGameId === null)
useGameStore.getState().stopReplay()

// ── (c) the toggle: default ON, persisted through the registry ──
check('the toggle defaults ON', useGameStore.getInitialState().replayLLMFullHistory === true)
useGameStore.getState().setReplayLLMFullHistory(false)
check('the setter flips the field', useGameStore.getState().replayLLMFullHistory === false)
check('...and persists to localStorage', JSON.parse(localStorage.getItem('xmage-settings')!).replayLLMFullHistory === false)
useGameStore.getState().setReplayLLMFullHistory(true)
check('flipping back re-persists', JSON.parse(localStorage.getItem('xmage-settings')!).replayLLMFullHistory === true)

// Reset-to-default covers the field.
useGameStore.getState().setReplayLLMFullHistory(false)
resetSettingsToDefaults(useGameStore.getState())
check('Reset to Default restores replayLLMFullHistory=true', useGameStore.getState().replayLLMFullHistory === true)

// ── (e) skipped-range backfill: jump delivery ──
// Live repro: with the toggle ON, Skip 10 over a bucket at index 7 lost it
// (4/16 msgs after a seek); the server now rides the skipped indices' buckets
// on the jumping frame (state.replayLLMBuckets, keyed by TRUE state index).
{
  useGameStore.getState().onReplayGame('jump-game')
  // Frame at index 1: no bucket of its own.
  feed({ type: 'replay_update', gameId: 'jump-game', state: frame(1), replayStateIndex: 1, replayStateCount: 40 } as ServerMessage)
  check('(e1) jump target frame alone carries nothing', replayLLMHistoryFor(useGameStore.getState().replayLLMFrames, 1).length === 0)

  // SKIP: lands on index 11 and rides the bucket recorded at index 7.
  const skipState = {
    ...frame(11, [llmEntry('LLM-1', EX2)]),
    replayLLMBuckets: [{ stateIndex: 7, entries: [llmEntry('LLM-1', EX1)] }],
  } as unknown as GameState
  feed({ type: 'replay_update', gameId: 'jump-game', state: skipState, replayStateIndex: 11, replayStateCount: 40 } as ServerMessage)
  {
    const st = useGameStore.getState()
    check('(e2) the skipped-range bucket was merged into the per-index store',
      st.replayLLMFrames.some((f) => f.stateIndex === 7), JSON.stringify(st.replayLLMFrames.map((f) => f.stateIndex)))
    const hist = replayLLMHistoryFor(st.replayLLMFrames, 11)
    check('(e3) cumulative view after Skip = skipped bucket + target bucket, in order',
      hist.length === 1 && hist[0]!.messages.length === EX1.length + EX2.length &&
      hist[0]!.messages[0]!.content === 'Prompt one?',
      JSON.stringify(hist.map((e) => e.messages.length)))
    check('(e4) history BEHIND the skipped bucket stays trimmed (cursor 3)',
      replayLLMHistoryFor(st.replayLLMFrames, 3).length === 0)
  }

  // Re-delivery of the same frame (a seek back onto it, or resync) is idempotent:
  // same buckets, same keys, no duplicate messages.
  feed({ type: 'replay_update', gameId: 'jump-game', state: skipState, replayStateIndex: 11, replayStateCount: 40 } as ServerMessage)
  {
    const st = useGameStore.getState()
    const hist = replayLLMHistoryFor(st.replayLLMFrames, 11)
    check('(e5) idempotent re-delivery: still 2 exchanges, no duplicates',
      st.replayLLMFrames.length === 2 && hist.length === 1 && hist[0]!.messages.length === EX1.length + EX2.length,
      JSON.stringify({ frames: st.replayLLMFrames.map((f) => f.stateIndex), msgs: hist.map((e) => e.messages.length) }))
  }

  // A later SEEK over several buckets: 11 -> 30, with buckets at 15 and 22 plus
  // a re-delivered 7 (the merge must overwrite that key, not duplicate it).
  const seekState = {
    ...frame(30, [llmEntry('LLM-1', EX2)]),
    replayLLMBuckets: [
      { stateIndex: 7, entries: [llmEntry('LLM-1', EX1)] },
      { stateIndex: 15, entries: [llmEntry('LLM-1', EX2)] },
      { stateIndex: 22, entries: [llmEntry('LLM-1', EX1)] },
    ],
  } as unknown as GameState
  feed({ type: 'replay_update', gameId: 'jump-game', state: seekState, replayStateIndex: 30, replayStateCount: 40 } as ServerMessage)
  {
    const st = useGameStore.getState()
    const hist = replayLLMHistoryFor(st.replayLLMFrames, 30)
    // Unique indices after this delivery: {7, 11, 15, 22, 30} — the frame's own
    // bucket (30), the two earlier own buckets (11 from the skip, 7 carried by
    // the skip), and the two new backfill buckets (15, 22). Index 7 was already
    // present and must NOT be duplicated.
    check('(e6) a multi-bucket seek merges every skipped index exactly once',
      st.replayLLMFrames.length === 5 && st.replayLLMFrames.filter((f) => f.stateIndex === 7).length === 1,
      JSON.stringify(st.replayLLMFrames.map((f) => f.stateIndex)))
    check('(e7) cumulative view at 30 = all five exchanges up to the cursor',
      hist.length === 1 && hist[0]!.messages.length === EX1.length * 5,
      JSON.stringify(hist.map((e) => e.messages.length)))
  }

  // Backward scrub still trims: at cursor 16 only buckets 7, 11 and 15 show
  // (3 buckets × 2 messages — EX1 and EX2 are both 2-message exchanges).
  {
    const hist = replayLLMHistoryFor(useGameStore.getState().replayLLMFrames, 16)
    check('(e8) scrub backward: only buckets ≤ 16 render', hist[0]!.messages.length === 6,
      JSON.stringify(hist.map((e) => e.messages.length)))
  }

  // A NEW replay resets everything (no bucket bleed across games).
  useGameStore.getState().onReplayGame('jump-game-2')
  check('(e9) a different replay resets the jumped buckets', useGameStore.getState().replayLLMFrames.length === 0)
  useGameStore.getState().stopReplay()
}

// ── (f) empty-state copy rule ──
{
  check('(f1) LIVE board keeps the original no-LLM wording',
    emptyLlmCopyFor(false, undefined, true) === 'No LLM players in this game.')
  check('(f2) replay of a game with NO LLM seat → no-LLM wording (accurate there)',
    emptyLlmCopyFor(true, false, true) === 'No LLM players in this game.')
  check('(f3) replay of an LLM game, full history ON → "up to this frame" (never the live claim)',
    emptyLlmCopyFor(true, true, true) === 'No LLM exchanges recorded up to this frame.' &&
    !emptyLlmCopyFor(true, true, true).includes('No LLM players'))
  check('(f4) replay of an LLM game, single-frame mode → "at this frame"',
    emptyLlmCopyFor(true, true, false) === 'No LLM exchanges recorded at this frame.')
  check('(f5) flag ABSENT (old proxy) → exchange wording, never the live claim',
    emptyLlmCopyFor(true, undefined, true) === 'No LLM exchanges recorded up to this frame.')
  // (f6) 2026-10-04 bug-hunt #6: on a LIVE board the LLM tab exists only when
  // the root hasLlmPlayers flag is true, yet the per-viewer filtered llmDebug
  // can be empty (spectator / private chat). The copy must not claim there are
  // no LLM players when there demonstrably ARE seats.
  check('(f6) LIVE board WITH LLM seats → viewer-visibility wording, never the no-LLM claim',
    emptyLlmCopyFor(false, undefined, true, true) === 'No LLM exchanges are visible to you in this game yet.' &&
    !emptyLlmCopyFor(false, undefined, true, true).includes('No LLM players'))
  check('(f7) LIVE board with the flag false/absent → original wording (back-compat)',
    emptyLlmCopyFor(false, undefined, true, false) === 'No LLM players in this game.')
}

// ── (g) toggle-visibility rule through the REAL component (SSR fixture) ──
// react-dom/server renders through zustand's getServerSnapshot, which reads the
// object captured at store creation — copy the live fields onto it (the
// verify-chat-labels.tsx technique).
{
  const ssrState = useGameStore.getInitialState() as unknown as Record<string, unknown>
  const syncSsrState = () => { Object.assign(ssrState, useGameStore.getState()) }

  const replayFrame = (idx: number, llm?: LLMDebugEntry[], hasLlm?: boolean): GameState => ({
    ...frame(1, llm),
    replayHasLlmPlayers: hasLlm,
  } as unknown as GameState)

  // Frame with a bucket → panel non-empty; toggle must render (pre-existing).
  useGameStore.getState().onReplayGame('ssr-game')
  feed({ type: 'replay_update', gameId: 'ssr-game', state: replayFrame(0, [llmEntry('LLM-1', EX1)], true), replayStateIndex: 0, replayStateCount: 9 } as ServerMessage)
  useGameStore.getState().setActiveRightTab('llm')
  useGameStore.getState().setReplayLLMFullHistory(true)
  syncSsrState()
  const htmlBucket = renderToStaticMarkup(createElement(RightRail))
  check('(g1) non-empty panel: toggle row + messages render',
    htmlBucket.includes('replay-llm-history-toggle') && htmlBucket.includes('Show entire history') && htmlBucket.includes('llm-message'))
  check('(g2) non-empty panel: no empty-state copy leaks',
    !htmlBucket.includes('No LLM exchanges') && !htmlBucket.includes('No LLM players'))

  // EMPTY frame, toggle OFF → THE bug-1 fixture: panel empty, toggle MUST exist.
  useGameStore.getState().setReplayLLMFullHistory(false)
  feed({ type: 'replay_update', gameId: 'ssr-game', state: replayFrame(4, undefined, true), replayStateIndex: 4, replayStateCount: 9 } as ServerMessage)
  syncSsrState()
  const htmlEmpty = renderToStaticMarkup(createElement(RightRail))
  check('(g3) EMPTY panel (toggle OFF): the "Show entire history" row IS rendered — bug 1 fix',
    htmlEmpty.includes('replay-llm-history-toggle') && htmlEmpty.includes('Show entire history'))
  check('(g4) EMPTY panel: single-frame copy, not the live-only wording',
    htmlEmpty.includes('No LLM exchanges recorded at this frame.') && !htmlEmpty.includes('No LLM players in this game.'))

  // EMPTY frame with full history ON but the cursor BEFORE any recorded bucket
  // (an LLM game whose first exchanges came later): the cumulative view IS empty,
  // and the copy must say "up to this frame" — not the live-only claim. (When a
  // prior bucket exists the panel is non-empty and no copy renders at all.)
  useGameStore.getState().onReplayGame('ssr-game-early')
  useGameStore.getState().setReplayLLMFullHistory(true)
  feed({ type: 'replay_update', gameId: 'ssr-game-early', state: replayFrame(3, undefined, true), replayStateIndex: 3, replayStateCount: 9 } as ServerMessage)
  syncSsrState()
  const htmlEmptyFull = renderToStaticMarkup(createElement(RightRail))
  check('(g5) EMPTY panel (full history ON, cursor before the first bucket): "up to this frame" copy + toggle present',
    htmlEmptyFull.includes('No LLM exchanges recorded up to this frame.') && htmlEmptyFull.includes('replay-llm-history-toggle'))

  // A replay whose game has NO LLM seat still gets the honest no-LLM wording.
  useGameStore.getState().onReplayGame('ssr-game-nollm')
  feed({ type: 'replay_update', gameId: 'ssr-game-nollm', state: replayFrame(0, undefined, false), replayStateIndex: 0, replayStateCount: 3 } as ServerMessage)
  syncSsrState()
  const htmlNoLlm = renderToStaticMarkup(createElement(RightRail))
  check('(g6) replay without an LLM seat: no-LLM copy, toggle still rendered',
    htmlNoLlm.includes('No LLM players in this game.') && htmlNoLlm.includes('replay-llm-history-toggle'))
  useGameStore.getState().stopReplay()
  resetSettingsToDefaults(useGameStore.getState())
}

// ── (h) system-prompt dedupe in the cumulative merge (2026-09-30) ──
// buildReplayViews attaches the game's system prompt as the LEADING message of
// every bucket (single-frame mode needs each frame self-sufficient). The
// cumulative merge must keep only the FIRST system message per player — the
// pre-fix merge interleaved the same system prompt between every exchange.
{
  const SYS = 'You are an LLM playing Magic. === YOUR DECKLIST === 4x Lightning Bolt'
  const bucket = (idx: number, withSys: boolean): ReplayLLMFrame => ({
    stateIndex: idx,
    entries: [llmEntry('LLM-1', ([
      ...(withSys ? [{ role: 'system', content: SYS }] : []),
      { role: 'user', content: `[priority | Turn ${idx} | Main]\\nstate ${idx}` },
      { role: 'assistant', content: `answer ${idx}` },
    ] as LLMMessage[]))],
  })
  const frames = [bucket(0, true), bucket(4, true), bucket(9, true)]
  const hist = replayLLMHistoryFor(frames, 9)
  const sysCount = hist[0]!.messages.filter((m) => m.role === 'system').length
  check('(h1) merged view carries the system prompt exactly ONCE',
    sysCount === 1, `system messages: ${sysCount}`)
  check('(h2) the system prompt leads the merged view',
    hist[0]!.messages[0]!.role === 'system' && hist[0]!.messages[0]!.content === SYS)
  check('(h3) all exchanges survive the dedupe',
    hist[0]!.messages.filter((m) => m.role === 'user').length === 3,
    JSON.stringify(hist[0]!.messages.map((m) => m.role)))
  // Mid-history scrub: same rule at any cursor.
  const at4 = replayLLMHistoryFor(frames, 4)
  check('(h4) scrub back keeps the same single system prompt',
    at4[0]!.messages.filter((m) => m.role === 'system').length === 1)
  // Two seats: dedupe is per player, both keep their own one.
  const twoSeats: ReplayLLMFrame[] = [
    { stateIndex: 0, entries: [llmEntry('LLM-1', [{ role: 'system', content: SYS }, ...EX1]), llmEntry('LLM-2', [{ role: 'system', content: SYS }, ...EX1])] },
    { stateIndex: 5, entries: [llmEntry('LLM-1', [{ role: 'system', content: SYS }, ...EX2]), llmEntry('LLM-2', [{ role: 'system', content: SYS }, ...EX2])] },
  ]
  const two = replayLLMHistoryFor(twoSeats, 5)
  check('(h5) dedupe is per player — each seat keeps exactly one system prompt',
    two.length === 2 && two.every((e) => e.messages.filter((m) => m.role === 'system').length === 1),
    JSON.stringify(two.map((e) => e.messages.filter((m) => m.role === 'system').length)))
  // A frame with NO system message (legacy replays / buckets from before the
  // pre-game commit) must be untouched by the filter.
  const legacy = [bucket(0, false), bucket(3, false)]
  const legacyHist = replayLLMHistoryFor(legacy, 3)
  check('(h6) no system messages → merge unchanged',
    legacyHist[0]!.messages.length === 4 && legacyHist[0]!.messages.every((m) => m.role !== 'system'))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)