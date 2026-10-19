/**
 * Harness: the web LLM tab renders BOTH assistant-message shapes.
 *
 * Run: bash scripts/verify-llm-reasoning-split.sh
 *
 * The LLM plugin's stored assistant message changed
 * (LLMClient.buildAssistantMessage) from
 *   `Decision: <answer>\nReasoning: <thinking>`
 * to a bare answer followed by a `[model_reasoning]` … `[/model_reasoning]`
 * fence. Old saves and old replays keep the legacy shape, so the rail's split
 * (RightRail.splitAssistantMessage) must read BOTH. This harness pins:
 *
 *  ── pure split ──
 *   new fence: answer = everything before the token; reasoning = the content
 *   between the tokens; a truncation marker rides the reasoning; a missing
 *   closing fence keeps the rest of the text as reasoning (graceful); a fence
 *   with no answer yields an empty decision; an exact-token discipline check
 *   (prose like `[model_reasoning_x]` is not a fence); text after the closing
 *   fence is not reasoning;
 *  ── legacy (unchanged behavior) ──
 *   `Decision: N` + a line starting with `Reasoning:` — decision and reasoning
 *   byte-identical to the previous split, incl. case-insensitivity and
 *   multi-line reasoning; a plain answer has no reasoning part;
 *  ── role gate ── a user/system message is never split;
 *  ── rendered through the REAL RightRail (react-dom/server, live board) ──
 *   a NEW-format and a LEGACY-format assistant message both render their answer
 *   plus a 💭 Reasoning block, the fence tokens never leak into the markup, and
 *   the message/content class names are unchanged;
 *  ── the replay path ── the cumulative history view (replayLLMHistoryFor) is
 *   rendered by the same LLMDebugPanel, so a replay frame carrying a
 *   new-format message renders split there too — ReplayView needs no separate
 *   parser (it imports RightRail directly).
 *
 * (rolldown-bundled like verify-chat-labels.sh / verify-replay-llm-history.sh;
 * scripts/ is outside tsconfig "include", so this is not part of the vite build.)
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { RightRail, splitAssistantMessage } from '../src/components/RightRail'
import { replayLLMHistoryFor, type ReplayLLMFrame } from '../src/store/slices/replaySlice'
import type { GameState, LLMDebugEntry, LLMMessage, Player } from '../src/types'

// scripts/ is outside the app tsconfig's `include`, so @types/node's globals are
// not in scope here (same declaration as verify-chat-labels.tsx).
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

// ── fixtures: the two stored shapes, copied from the writers' exact output ──
// NEW: answer.trim() + "\n[model_reasoning]\n"
// + thinking + "\n[/model_reasoning]".
const NEW_MSG =
  'Cast Lightning Bolt at the opponent.\n[model_reasoning]\nThey are at 3 life and tapped out.\n[/model_reasoning]'
const NEW_TRUNCATED =
  'Pass the turn.\n[model_reasoning]\nHolding the counterspell... [reasoning truncated]\n[/model_reasoning]'
// A truncated write / mid-stream message can lack the closing token.
const NEW_UNCLOSED = 'Swing with everything.\n[model_reasoning]\nNo blockers left, so'
// LEGACY (old saves / replays): "Decision: " + answer + "\nReasoning: " + thinking.
const LEGACY_MSG = 'Decision: 3\nReasoning: attack with the 3/3 — they have no blockers'

section('splitAssistantMessage — new fence format')
{
  const r = splitAssistantMessage(NEW_MSG, 'assistant')
  check(
    'the answer is everything before the [model_reasoning] marker',
    r.decision === 'Cast Lightning Bolt at the opponent.',
    JSON.stringify(r),
  )
  check(
    'the reasoning is the content between the fence tokens',
    r.reasoning === 'They are at 3 life and tapped out.',
    JSON.stringify(r),
  )
  check(
    'neither part carries the fence tokens',
    !r.decision.includes('[model_reasoning]') && !r.reasoning.includes('[model_reasoning]'),
    JSON.stringify(r),
  )
  const t = splitAssistantMessage(NEW_TRUNCATED, 'assistant')
  check(
    'a truncation marker rides the reasoning block',
    t.decision === 'Pass the turn.' && t.reasoning === 'Holding the counterspell... [reasoning truncated]',
    JSON.stringify(t),
  )
  const u = splitAssistantMessage(NEW_UNCLOSED, 'assistant')
  check(
    'missing closing fence → the rest of the text is reasoning (graceful)',
    u.decision === 'Swing with everything.' && u.reasoning === 'No blockers left, so',
    JSON.stringify(u),
  )
  const e = splitAssistantMessage('[model_reasoning]\nno answer line\n[/model_reasoning]', 'assistant')
  check(
    'a fence with no answer before it yields an empty decision',
    e.decision === '' && e.reasoning === 'no answer line',
    JSON.stringify(e),
  )
  const x = splitAssistantMessage(
    'Answer mentions [model_reasoning_x] as prose.\nReasoning: still legacy',
    'assistant',
  )
  check(
    'a similar-but-not-exact token is not a fence (falls through to legacy)',
    x.decision === 'Answer mentions [model_reasoning_x] as prose.' && x.reasoning === 'Reasoning: still legacy',
    JSON.stringify(x),
  )
  const tr = splitAssistantMessage(
    'Answer.\n[model_reasoning]\nThought.\n[/model_reasoning]\ntrailing junk',
    'assistant',
  )
  check(
    'the reasoning block ends at the closing fence (trailing text is not reasoning)',
    tr.decision === 'Answer.' && tr.reasoning === 'Thought.',
    JSON.stringify(tr),
  )
}

section('splitAssistantMessage — legacy format (previous behavior, unchanged)')
{
  const r = splitAssistantMessage(LEGACY_MSG, 'assistant')
  check('legacy decision keeps the "Decision: N" line verbatim', r.decision === 'Decision: 3', JSON.stringify(r))
  check(
    'legacy reasoning keeps the "Reasoning:" prefix line (as before)',
    r.reasoning === 'Reasoning: attack with the 3/3 — they have no blockers',
    JSON.stringify(r),
  )
  const ml = splitAssistantMessage('Decision: 1\nReasoning: first line\nsecond line', 'assistant')
  check(
    'legacy multi-line reasoning keeps every line',
    ml.decision === 'Decision: 1' && ml.reasoning === 'Reasoning: first line\nsecond line',
    JSON.stringify(ml),
  )
  const lc = splitAssistantMessage('Decision: 2\nreasoning: lowercase still matched', 'assistant')
  check(
    'lowercase "reasoning:" is matched (case-insensitive parity)',
    lc.decision === 'Decision: 2' && lc.reasoning === 'reasoning: lowercase still matched',
    JSON.stringify(lc),
  )
  const p = splitAssistantMessage('Just an answer with no reasoning.', 'assistant')
  check(
    'a plain answer renders whole with no reasoning block',
    p.decision === 'Just an answer with no reasoning.' && p.reasoning === '',
    JSON.stringify(p),
  )
}

section('splitAssistantMessage — role gate')
{
  const u = splitAssistantMessage(NEW_MSG, 'user')
  check('a user message is never split (fence tokens stay raw)', u.decision === NEW_MSG && u.reasoning === '', JSON.stringify(u))
  const s = splitAssistantMessage('Decision: 9\nReasoning: n/a', 'system')
  check(
    'non-assistant roles are not split on the legacy marker either',
    s.decision === 'Decision: 9\nReasoning: n/a' && s.reasoning === '',
    JSON.stringify(s),
  )
}

// ── store-shaped fixtures for the rendered checks ──
const player = (name: string): Player => ({
  name, playerId: name, life: 20, isMe: false, libraryCount: 40, handCount: 0,
  isActive: false, hasPriority: false, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
} as unknown as Player)

const entry = (playerName: string, messages: LLMMessage[]): LLMDebugEntry => ({
  playerName,
  modelName: 'test-model',
  exchangeCount: messages.length,
  totalContextChars: 0,
  modelContextWindow: 0,
  streaming: false,
  messages,
})

const board = (llm: LLMDebugEntry[]): GameState => ({
  status: 'playing', turn: 3, phase: null, step: null,
  activePlayer: 'A', priorityPlayer: '',
  stack: [], combat: [], players: [player('A'), player('B')], llmDebug: llm,
  revealed: [], lookedAt: [], stateSeq: 5, hasLlmPlayers: true,
} as unknown as GameState)

// react-dom/server renders through zustand's `getServerSnapshot`, which reads the
// object captured at store creation (useGameStore.getInitialState()) — copy the
// live fields onto it before each render (the technique verify-chat-labels.tsx
// established).
const ssrState = useGameStore.getInitialState() as unknown as Record<string, unknown>
const syncSsrState = () => {
  Object.assign(ssrState, useGameStore.getState())
}

section('RightRail LLM tab (live board) — rendered markup, both formats')
useGameStore.setState({
  gameState: board([
    entry('LLM-1', [
      { role: 'user', content: 'Prompt one?' },
      { role: 'assistant', content: NEW_MSG },
      { role: 'assistant', content: LEGACY_MSG },
    ]),
  ]),
  activeRightTab: 'llm',
  activeLLMPlayerIdx: 0,
  inReplay: false,
} as never)
syncSsrState()
const html = renderToStaticMarkup(<RightRail />)
check('the new-format answer renders', html.includes('Cast Lightning Bolt at the opponent.'))
check(
  'the new-format reasoning renders inside the collapsible block',
  html.includes('They are at 3 life and tapped out.'),
)
check('the legacy decision renders verbatim', html.includes('Decision: 3'))
check('the legacy reasoning renders', html.includes('attack with the 3/3'))
check(
  'both assistant messages render a 💭 Reasoning block',
  (html.match(/💭 Reasoning/g) ?? []).length === 2,
  String((html.match(/💭 Reasoning/g) ?? []).length),
)
check(
  'the fence tokens never leak into the markup',
  !html.includes('[model_reasoning]') && !html.includes('[/model_reasoning]'),
)
check(
  'message rows keep their classes (llm-message llm-<role>)',
  html.includes('llm-message llm-assistant') && html.includes('llm-message llm-user'),
)
check('the content wrapper class is unchanged', html.includes('llm-content'))

section('RightRail LLM tab (replay path) — the same split through the cumulative view')
{
  const frames: ReplayLLMFrame[] = [
    {
      stateIndex: 7,
      entries: [
        entry('LLM-1', [
          { role: 'user', content: 'Prompt two?' },
          { role: 'assistant', content: NEW_MSG },
        ]),
      ],
    },
  ]
  check(
    'the cumulative history view carries the new-format message',
    replayLLMHistoryFor(frames, 7).length === 1 &&
      replayLLMHistoryFor(frames, 7)[0]!.messages.length === 2,
  )
  useGameStore.setState({
    inReplay: true,
    replayState: board([]),
    replayLLMFrames: frames,
    replayLLMGameId: 'legacy-split-replay',
    replayStateIndex: 7,
    replayLLMFullHistory: true,
    activeRightTab: 'llm',
    activeLLMPlayerIdx: 0,
  } as never)
  syncSsrState()
  const replayHtml = renderToStaticMarkup(<RightRail />)
  check('the replay rail renders the new-format answer', replayHtml.includes('Cast Lightning Bolt at the opponent.'))
  check('...and its reasoning block', replayHtml.includes('They are at 3 life and tapped out.'))
  check(
    '...with no fence tokens in the markup',
    !replayHtml.includes('[model_reasoning]') && !replayHtml.includes('[/model_reasoning]'),
  )
}

console.log(`\n${passed} passed, ${failures} failed`)
if (failures > 0) {
  process.exit(1)
}
console.log('verify-llm-reasoning-split: ALL CHECKS PASS')
