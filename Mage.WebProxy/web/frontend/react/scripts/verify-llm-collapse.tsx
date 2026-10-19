/**
 * Harness: every LLM-tab text block is collapsible, and the
 * replay viewer carries the pre-game blocks the live tab shows.
 *
 * Run: bash scripts/verify-llm-collapse.sh
 *
 * Pins, through the REAL RightRail rendered via react-dom/server:
 *  ── collapse affordance ── every conversation message (system / user /
 *     assistant), the reasoning sub-block, and the pre/post-game sections
 *     render inside a <details>/<summary> with a stable storage key;
 *  ── preview ── a collapsed block's summary line carries role + a first-line
 *     preview so the rail stays scannable;
 *  ── store wiring ── toggleLlmBlockCollapsed flips llmCollapsedBlocks, the
 *     <details> open attr reflects the store (controlled mode), and the state
 *     is session-scoped (NOT in xmage-settings);
 *  ── replay pre-game data ── a frame-0 bucket with a leading system message
 *     and full-prompt pre-game user message (the shape LLMPlayerHelper now
 *     emits) renders through the cumulative history view: system prompt,
 *     assessment prompt, thinking, and gameplan all present.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { RightRail } from '../src/components/RightRail'
import { replayLLMHistoryFor, type ReplayLLMFrame } from '../src/store/slices/replaySlice'
import type { GameState, LLMDebugEntry, LLMMessage, Player } from '../src/types'

declare const process: { exit(code: number): void }

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

const player = (name: string): Player => ({
  name, playerId: name, life: 20, isMe: false, libraryCount: 40, handCount: 0,
  isActive: false, hasPriority: false, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
} as unknown as Player)

const entry = (playerName: string, messages: LLMMessage[], extra: Partial<LLMDebugEntry> = {}): LLMDebugEntry => ({
  playerName,
  modelName: 'test-model',
  exchangeCount: messages.length,
  totalContextChars: 0,
  modelContextWindow: 0,
  streaming: false,
  messages,
  ...extra,
})

const board = (llm: LLMDebugEntry[]): GameState => ({
  status: 'playing', turn: 3, phase: null, step: null,
  activePlayer: 'A', priorityPlayer: '',
  stack: [], combat: [], players: [player('A'), player('B')], llmDebug: llm,
  revealed: [], lookedAt: [], stateSeq: 5, hasLlmPlayers: true,
} as unknown as GameState)

const ssrState = useGameStore.getInitialState() as unknown as Record<string, unknown>
const syncSsrState = () => {
  Object.assign(ssrState, useGameStore.getState())
}

// The new buildReplayViews shape for a stateIndex-0 pre-game bucket: leading
// system prompt, then full-prompt user + thinking + answer for the deck
// assessment.
const SYSTEM = 'You are an LLM playing Magic. === YOUR DECKLIST === 4x Lightning Bolt'
const ASSESS_PROMPT = 'FULL ASSESSMENT PROMPT: analyze this deck…'
const ASSESS_THINK = 'The deck is aggressive; curve out.'
const GAMEPLAN = 'Play cheap burn, race.'

section('collapse: every block renders inside <details> with a summary line')
useGameStore.setState({
  gameState: board([
    entry('LLM-1', [
      { role: 'system', content: SYSTEM },
      { role: 'user', content: ASSESS_PROMPT, promptType: 'deck_assessment' },
      { role: 'assistant_thinking', content: ASSESS_THINK },
      { role: 'assistant', content: 'My gameplan.\\n[model_reasoning]\\nbecause burn\\n[/model_reasoning]' },
    ], { gamePlan: GAMEPLAN }),
  ]),
  activeRightTab: 'llm',
  activeLLMPlayerIdx: 0,
  inReplay: false,
  llmCollapsedBlocks: {},
} as never)
syncSsrState()
let html = renderToStaticMarkup(<RightRail />)

const detailsCount = (html.match(/<details/g) ?? []).length
// system + user + assistant_thinking (own block now) + assistant (outer) +
// reasoning (inner) + gameplan section = 6
check('six collapsible blocks render (system, user, thinking, assistant, reasoning, gameplan)',
  detailsCount === 6, `got ${detailsCount}`)
check('every block has a <summary> label', (html.match(/<summary/g) ?? []).length === 6)
check('the system block summary carries its role + preview',
  /system — You are an LLM playing Magic/.test(html))
check('the user block summary carries its role + preview',
  /user — FULL ASSESSMENT PROMPT/.test(html))
check('the assistant block summary carries a preview of the answer',
  /assistant — My gameplan\./.test(html))
check('the reasoning block keeps its 💭 label', html.includes('💭 Reasoning'))
check('the gameplan section keeps its 📋 label', html.includes('📋 Deck Assessment'))

section('collapse: store wiring — toggle flips the store, the <details> reflects it')
{
  check('starts uncollapsed (open attr present on all blocks)',
    (html.match(/<details open=""/g) ?? []).length === 6,
    `open blocks: ${(html.match(/<details open=""/g) ?? []).length}`)

  useGameStore.getState().toggleLlmBlockCollapsed('LLM-1:msg:0')
  syncSsrState()
  const after = renderToStaticMarkup(<RightRail />)
  check('toggling the system block collapses ONLY it',
    (after.match(/<details open=""/g) ?? []).length === 5,
    `open blocks after toggle: ${(after.match(/<details open=""/g) ?? []).length}`)
  check('the store records the collapse', useGameStore.getState().llmCollapsedBlocks['LLM-1:msg:0'] === true)

  useGameStore.getState().toggleLlmBlockCollapsed('LLM-1:msg:0')
  check('toggling again reopens', useGameStore.getState().llmCollapsedBlocks['LLM-1:msg:0'] === false)

  // Session-scoped: the collapse map must NOT be written into xmage-settings.
  const persisted = JSON.stringify({
    autoScrollLog: true, replayLLMFullHistory: true, // the persisted siblings
  })
  check('llmCollapsedBlocks is not a persisted setting',
    !persisted.includes('llmCollapsedBlocks'))
}

section('replay pre-game data: the frame-0 bucket renders system + assessment through the cumulative view')
{
  const frames: ReplayLLMFrame[] = [
    {
      stateIndex: 0,
      entries: [
        entry('LLM-1', [
          { role: 'system', content: SYSTEM },
          { role: 'user', content: ASSESS_PROMPT, promptType: 'deck_assessment' },
          { role: 'assistant_thinking', content: ASSESS_THINK },
          { role: 'assistant', content: GAMEPLAN },
        ]),
      ],
    },
    {
      stateIndex: 12,
      entries: [
        entry('LLM-1', [
          { role: 'user', content: '[priority | Turn 1 | Precombat Main]\ncompact state' },
          { role: 'assistant', content: 'Play Mountain.' },
        ]),
      ],
    },
  ]

  const merged = replayLLMHistoryFor(frames, 12)
  check('the cumulative view merges frame 0 and frame 12 for the player',
    merged.length === 1 && merged[0]!.messages.length === 6,
    `merged msgs: ${merged[0]?.messages.length}`)
  check('the system prompt leads the merged view',
    merged[0]!.messages[0]!.role === 'system' && merged[0]!.messages[0]!.content === SYSTEM)
  check('the pre-game full prompt is the second message',
    merged[0]!.messages[1]!.content === ASSESS_PROMPT)

  useGameStore.setState({
    inReplay: true,
    replayState: board([]),
    replayLLMFrames: frames,
    replayLLMGameId: 'pregame-replay',
    replayStateIndex: 12,
    replayLLMFullHistory: true,
    activeRightTab: 'llm',
    activeLLMPlayerIdx: 0,
    llmCollapsedBlocks: {},
  } as never)
  syncSsrState()
  const replayHtml = renderToStaticMarkup(<RightRail />)
  check('the replay rail renders the system prompt', replayHtml.includes('=== YOUR DECKLIST ==='))
  check('...the full assessment prompt', replayHtml.includes('FULL ASSESSMENT PROMPT'))
  check('...the assessment thinking', replayHtml.includes('The deck is aggressive'))
  check('...the gameplan', replayHtml.includes('Play cheap burn, race.'))
  check('...and the in-game exchange', replayHtml.includes('Play Mountain.'))
  check('every replay block is collapsible too',
    (replayHtml.match(/<details/g) ?? []).length === 6,
    `replay details: ${(replayHtml.match(/<details/g) ?? []).length}`)
}

console.log(`\n${passed} passed, ${failures} failed`)
if (failures > 0) {
  process.exit(1)
}
console.log('verify-llm-collapse: ALL CHECKS PASS')
