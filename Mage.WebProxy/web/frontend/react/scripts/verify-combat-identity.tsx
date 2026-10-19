/**
 * Standalone regression check for COMBAT CLICK IDENTITY routing.
 *
 * Run with:  bash scripts/verify-combat-identity.sh
 *
 * "selecting/unselecting attackers/blockers is bugged
 * especially with lots of tokens."
 *
 * WHAT WAS MEASURED (live two-seat session, 12+ Goblin Tokens):
 *
 *  * The engine's declare loops (HumanPlayer.selectAttackers / selectBlockers)
 *    re-fire GAME_SELECT after EVERY answer, and each re-prompt carries a
 *    RENUMBERED choice list — the declared creature is dropped from
 *    possibleAttackers/possibleBlockers, shifting every later creature down.
 *    Live trace: pc=73 listed 4 attackers [idx0..3]; one declare later
 *    (pc=74) the SAME creatures were [0..2]; next (pc=75) [0..1].
 *
 *  * A click's `select_choice` carries only a POSITION into that list, computed
 *    from the RENDER when the tile was wired. On a heavy token board the render
 *    can lag the wire by a prompt, so:
 *      - 15 ms after a prompt bump: the tile closure sent index 2/3 while the
 *        LIVE prompt held those creatures at 0/1 -> proxy "Invalid choice
 *        index: 2" toast, both clicks died.
 *      - 30 ms gap, two different creatures: clicked 48154915…, engine
 *        declared 01e9234a… — the WRONG creature.
 *      - 0 ms burst: the 2nd select_choice reached the proxy after the answer
 *        was consumed — silently dropped (proxy stale guard).
 *
 * THE FIX: combat choices (action attack/block) answer by creature UUID (via
 * play_card), never by index. The engine dereferences a responded UUID by
 * IDENTITY (selectDefender / removeAttackerIfPossible for attackers;
 * selectCombatGroup / combat.removeBlocker for blockers), so a renumbered list
 * cannot misdeliver it — this is exactly the desktop client's
 * DefaultActionCallback behavior. Non-combat choices keep the index path.
 *
 * This harness encodes:
 *  (1) planCardClick — the pure routing function the click sites use:
 *      combat choice -> UUID; declared combat creature -> UUID (undeclare);
 *      non-combat choice -> index; non-combat unlisted -> UUID (play attempt);
 *      combat unlisted+undeclared (opponent's creature) -> null (no send).
 *  (2) the store round-trip: playCard(uuid) reaches the wire as
 *      { type: 'play_card', cardId } — the single answer the engine consumes.
 *  (3) a RENUMBER SIMULATION: the same physical creature at index 0 in one
 *      prompt and index 3 in the next — both clicks route to the same UUID,
 *      so neither can misdeliver (the regression the index path had).
 *  (4) source pins: the click sites (GameView, SelectPrompt rail buttons, F2)
 *      route combat through the UUID path, not raw selectChoice(index).
 *
 * Node-only: real store, real message handler (no browser).
 */
import { readFileSync } from 'node:fs'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { planCardClick, isCombatChoice } from '../src/utils/combatClick'
import type { ServerMessage } from '../src/types'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(name: string) { console.log(`\n## ${name}`) }

// ── wire capture ──
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)
const reset = () => { sent.length = 0 }

const player = (id: string, isMe: boolean) => ({
  name: id, isMe, isAI: false, isConnected: true, score: 0, life: 20,
  isActive: false, hasPriority: false, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [], statesSavedSize: 1,
}) as never

const combatState = (attackers: string[], blockers: string[]) => ({
  type: 'game_update',
  state: { battlefield: { cards: [] }, zones: [], library: [], hand: [],
    status: 'playing', turn: 1, phase: null, step: null,
    activePlayer: 'me-1', priorityPlayer: 'me-1',
    stack: [], combat: [
      { defenderId: 'opp-1', defenderName: 'Opponent', blocked: blockers.length > 0,
        attackers: attackers.map((id) => ({ id })), blockers: blockers.map((id) => ({ id })) } as never,
    ],
    players: [player('me-1', true) as never, player('opp-1', false) as never],
    revealed: [], lookedAt: [], stateSeq: 1 },
} as unknown as ServerMessage)

/** The exact wire shape WebProxyClient.handleGameSelect emits with combat context. */
const declarePrompt = (context: 'declare_attackers' | 'declare_blockers', choices: string[]): ServerMessage => ({
  type: 'action_prompt', action_type: 'select',
  message: context === 'declare_attackers' ? 'Select attackers' : 'Select blockers',
  context, required: false,
  choices: choices.map((id, index) => ({ id, index, name: 'Goblin Token', action: context === 'declare_attackers' ? 'attack' : 'block' })),
} as unknown as ServerMessage)

const prioPrompt = (choices: Array<{ id: string; index: number; name: string; action?: string }>): ServerMessage => ({
  type: 'action_prompt', action_type: 'select', message: 'Play spells and abilities',
  context: 'select_card', required: false, choices,
} as unknown as ServerMessage)

const deliver = (msg: ServerMessage) => handleMessage(msg, useGameStore.getState())

// ── (1) planCardClick — the pure routing function ──
section('planCardClick: combat choices answer by UUID, everything else unchanged')

{
  const combat = { type: 'select', context: 'declare_attackers', isCombat: true,
    choices: [{ id: 'tok-1', index: 0, name: 'Goblin Token', action: 'attack' },
              { id: 'tok-2', index: 1, name: 'Goblin Token', action: 'attack' }] } as never
  const p1 = planCardClick(combat, 'tok-2', new Set())
  check('combat choice -> uuid', !!p1 && p1.kind === 'uuid' && p1.cardId === 'tok-2', JSON.stringify(p1))

  const nonCombat = { type: 'select', context: 'select_card', isCombat: false,
    choices: [{ id: 'spell-1', index: 0, name: 'Lightning Bolt', action: 'cast' },
              { id: 'land-1', index: 1, name: 'Mountain', action: 'land' }] } as never
  const p2 = planCardClick(nonCombat, 'spell-1', new Set())
  check('non-combat choice keeps the index path', !!p2 && p2.kind === 'index' && p2.index === 0, JSON.stringify(p2))

  const p3 = planCardClick(combat, 'tok-9', new Set(['tok-9']))
  check('declared combat creature (not in list) -> uuid undeclare', !!p3 && p3.kind === 'uuid' && p3.cardId === 'tok-9', JSON.stringify(p3))

  const p4 = planCardClick(nonCombat, 'offers-2', new Set())
  check('non-combat unlisted card -> uuid play attempt', !!p4 && p4.kind === 'uuid' && p4.cardId === 'offers-2', JSON.stringify(p4))

  const p5 = planCardClick(combat, 'opp-creature', new Set())
  check('combat unlisted + undeclared (opponent creature) -> null (no send)', p5 === null, JSON.stringify(p5))

  check('isCombatChoice only matches attack/block',
    isCombatChoice({ id: 'x', index: 0, name: 'x', action: 'attack' }) &&
    isCombatChoice({ id: 'x', index: 0, name: 'x', action: 'block' }) &&
    !isCombatChoice({ id: 'x', index: 0, name: 'x', action: 'cast' }) &&
    !isCombatChoice(undefined))
}

// ── (2) store round-trip: playCard -> play_card wire message ──
section('store round-trip: combat declare reaches the wire as play_card{uuid}')

{
  reset()
  deliver(combatState([], []))
  deliver(declarePrompt('declare_attackers', ['7b1b7a99-0000-0000-0000-000000000001']))
  useGameStore.getState().playCard('7b1b7a99-0000-0000-0000-000000000001')
  check('exactly one message went out', sent.length === 1, JSON.stringify(sent))
  check('it is play_card with the creature UUID',
    sent[0]!.type === 'play_card' && sent[0]!.cardId === '7b1b7a99-0000-0000-0000-000000000001',
    JSON.stringify(sent[0]))
}

// ── (3) renumber simulation — the exact regression the index path had ──
section('renumber simulation: same creature at index 0 then index 3 must route identically')

{
  const TARGET = 'aaaa1111-0000-0000-0000-000000000009'
  // pc=73 style list: TARGET first.
  const promptA = { type: 'select', context: 'declare_attackers', isCombat: true,
    choices: [{ id: TARGET, index: 0, name: 'Goblin Token', action: 'attack' }] } as never
  // pc=76 style list: TARGET moved to index 3 (three other creatures ahead).
  const promptB = { type: 'select', context: 'declare_attackers', isCombat: true,
    choices: [{ id: 'other-1', index: 0, name: 'Goblin Token', action: 'attack' },
              { id: 'other-2', index: 1, name: 'Goblin Token', action: 'attack' },
              { id: 'other-3', index: 2, name: 'Goblin Token', action: 'attack' },
              { id: TARGET, index: 3, name: 'Goblin Token', action: 'attack' }] } as never
  const a = planCardClick(promptA, TARGET, new Set())
  const b = planCardClick(promptB, TARGET, new Set())
  check('clicking TARGET under prompt A routes to its UUID', !!a && a.kind === 'uuid' && a.cardId === TARGET)
  check('clicking TARGET under prompt B routes to the SAME UUID (index 3 is irrelevant)', !!b && b.kind === 'uuid' && b.cardId === TARGET)
  check('neither plan can send a positional index',
    (!!a && a.kind !== 'index') && (!!b && b.kind !== 'index'))
}

// ── (4) click-site source pins ──
section('click sites route combat through the UUID path (source pins)')

{
  const gameView = readFileSync('src/components/GameView.tsx', 'utf8')
  const rail = readFileSync('src/components/prompts/SelectPrompt.tsx', 'utf8')
  const bar = readFileSync('src/components/ActionPromptBar.tsx', 'utf8')
  check('GameView handleCardClick uses planCardClick', /planCardClick\(actionPrompt, cardId, combatDeclaredIds\)/.test(gameView))
  check('GameView no longer sends a raw index for a listed combat choice',
    !/if \(choice\) \{\s*selectChoice\(choice\.index\)/.test(gameView))
  check('rail SelectPrompt routes combat choices to playCard(choice.id)',
    /isCombatChoice\(choice\) \? playCard\(choice\.id\) : selectChoice\(choice\.index\)/.test(rail))
  check('F2 hotkey routes combat choices to playCard(first.id)',
    /if \(isCombatChoice\(first\)\) playCard\(first\.id\)/.test(bar))
}

if (failures > 0) {
  console.log(`\nFAILURES: ${failures}`)
  process.exit(1)
}
console.log('\nAll combat-identity checks passed.')
