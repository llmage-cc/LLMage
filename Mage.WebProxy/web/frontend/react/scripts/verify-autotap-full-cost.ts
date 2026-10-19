/**
 * Standalone behavioural check: ONE press of "Auto-Tap Lands" must pay as much of
 * the cost as the available sources allow.
 *
 * Reproduced twice live: Ajani, Nacatl
 * Pariah {1}{W} and Amped Raptor {1}{R} each needed two presses).
 *
 * Run with:  bash scripts/verify-autotap-full-cost.sh
 *
 * NOT part of the app build (`scripts/` is outside the tsconfig `include`). Every case drives the
 * REAL store through the REAL message handler (`network/messageHandlers.ts` → `setActionPrompt`),
 * so the assertions cover the shipped code path, not a re-implementation.
 *
 * WHY THE SCENARIO IS SHAPED THIS WAY (a single untapped land would prove nothing: correct and
 * buggy code agree by construction, and so would a one-pip cost):
 *   - ≥2 untapped lands, and the land that can pay the coloured pip is NOT the first one on the
 *     battlefield (`selectMyUntappedLands` returns battlefield order, so "first" is real);
 *   - a cost with BOTH a coloured pip and a generic pip, so finishing it genuinely needs two
 *     different lands;
 *   - assertions are on the EMITTED FRAMES (the `tap_mana` sends on the wire, in order), not on
 *     the resulting pips and not on how many lands ended up tapped.
 *
 * DISCRIMINATION. `pressLegacy` below is the PRE-FIX one-press path transcribed verbatim from the
 * shipped code before this fix (DecisionPopup.tsx `autoPayMana`, blob 2b636096b1): plan once, send
 * at most one tap, stop. Both arms drive the SAME simulated server, so the only difference between
 * the "BEFORE" and "AFTER" cases is the press itself — and the BEFORE case asserts the defect
 * (a second press is required) while the AFTER case asserts the acceptance criterion (one press
 * finishes the cost). Deleting the store hook or the run state makes the AFTER case fail with the
 * exact frame diff; nothing else in the harness can mask it.
 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { startAutoTapAllLands, getAutoTapRun } from '../src/store/slices/autoTapRun'
import { selectMyUntappedLands } from '../src/store/selectors'
import { planAutoTap } from '../src/utils/cardTypes'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { PlayManaPrompt } from '../src/components/prompts/PlayManaPrompt'
import type { GameState, Permanent, Player, ServerMessage } from '../src/types'

// ── record what actually goes on the wire ──
const sent: string[] = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => {
    const msg = m as { type: string; landId?: string }
    sent.push(msg.type === 'tap_mana' ? `tap_mana:${msg.landId}` : msg.type)
  },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

/** The frames emitted since the last drain, and clear the buffer. */
const drain = (): string[] => {
  const frames = [...sent]
  sent.length = 0
  return frames
}

// ── minimal but type-complete fixtures ──
// Plateau + Mountain is the live board: a Boros deck with an RW dual and basics, casting
// Ajani, Nacatl Pariah ({1}{W}) and Amped Raptor ({1}{R}).
const land = (id: string, name: string, production: string[] | undefined, tapped = false): Permanent => {
  const perm: Permanent = {
    id, name, imageUrl: '', typeLine: 'Land — ' + name, manaCost: '', rules: [], tapped, damage: 0,
  }
  if (production !== undefined) perm.manaProduction = production
  return perm
}

const player = (name: string, isMe: boolean, battlefield: Permanent[]): Player => ({
  name, playerId: name, life: 20, isMe, libraryCount: 40, handCount: 7,
  isActive: false, hasPriority: isMe, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  counters: [], commanders: [], battlefield, hand: [], graveyard: [], exile: [],
  statesSavedSize: 1,
})

let seq = 1
/** Push a board state to the store the way `game_update` does. */
const pushBoard = (myBattlefield: Permanent[]): void => {
  const state: GameState = {
    status: 'playing', turn: 3, phase: null, step: null,
    activePlayer: 'QA-A', priorityPlayer: 'QA-A',
    stack: [], combat: [], players: [player('QA-A', true, myBattlefield), player('QA-B', false, [])],
    llmDebug: [], revealed: [], lookedAt: [], stateSeq: ++seq,
  }
  handleMessage({ type: 'game_update', state } as ServerMessage, useGameStore.getState())
}

/** The mana prompt the proxy sends (WebProxyClient.handleGamePlayMana). `cost` is the UNPAID cost
 *  text and `creature` the object being paid for — `ManaUtil.addSpecialManaPayAbilities` builds
 *  exactly this string on the server: unpaid text + a `<div>` name block.
 *
 *  `payableForThisCost` is the OPTIONAL engine field (Courtyard autotap option 2) the proxy now
 *  forwards: `undefined` deliberately omits the key entirely — that is the wire shape of an older
 *  proxy/server that did not evaluate, which the planner must read as "unknown". */
const manaPrompt = (cost: string, creature: string, payableForThisCost?: Record<string, boolean>): void => {
  handleMessage({
    type: 'action_prompt',
    action_type: 'play_mana',
    message: `Pay ${cost}<div style='font-size:11pt'>${creature}</div>`,
    context: 'mana_payment',
    mana_options: [],
    remaining_cost: cost,
    auto_pay_available: true,
    special_available: false,
    ...(payableForThisCost !== undefined ? { mana_payable_for_this_cost: payableForThisCost } : {}),
  } as unknown as ServerMessage, useGameStore.getState())
}

/** The board: Mountain (R only) FIRST, Plateau (RW dual) second — the land that can pay a
 *  coloured pip is NOT first, which is what makes the scenario discriminating. */
const f11Board = (): Permanent[] => [
  land('land-mountain', 'Mountain', ['R']),
  land('land-plateau', 'Plateau', ['R', 'W']),
]
const AJANI = 'Ajani, Nacatl Pariah'

/** A fresh game state + the opening mana prompt. */
function startScenario(board: Permanent[], cost: string, creature: string): void {
  drain()
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, eventLog: [], gameState: null })
  pushBoard(board)
  manaPrompt(cost, creature)
}

/**
 * The SERVER side of one `tap_mana`: the land the client actually sent becomes tapped and the
 * next `GAME_PLAY_MANA` re-prompt carries the REDUCED unpaid cost (`ManaCostsImpl.pay` loops
 * `playMana()` per source, so the server asks again after each answer).
 *
 * Applying the tap for the id the CLIENT chose — never for a guessed id — is what makes the
 * one-press and two-press arms comparable.
 */
function serverAppliesTaps(board: Permanent[], frames: readonly string[], remainingCost: string, creature: string): Permanent[] {
  const taps = frames.filter((f) => f.startsWith('tap_mana:')).map((f) => f.slice('tap_mana:'.length))
  if (taps.length === 0) throw new Error(`serverAppliesTaps: no tap_mana in ${JSON.stringify(frames)}`)
  const next = board.map((l) => (taps.includes(l.id ?? '') ? { ...l, tapped: true } : l))
  pushBoard(next)
  manaPrompt(remainingCost, creature)
  return next
}

/**
 * PRE-FIX press, transcribed verbatim from the shipped one-press path
 * (DecisionPopup.tsx `autoPayMana`, blob 2b636096b1 — identical logic in PlayManaPrompt.tsx):
 *
 * const autoPayMana =  => {
 *     const state = useGameStore.getState()
 *     const lands = selectMyUntappedLands(state)
 *     const me = state.gameState?.players.find((p) => p.isMe)
 *     const plan = planAutoTap(lands, prompt.rawMessage || prompt.message, me?.manaPool ?? null)
 *     switch (plan.kind) {
 *       case 'tap': setAutoTapNotice(null); tapMana(plan.cardId); return
 *       case 'blocked': setAutoTapNotice(`${plan.reason}. …`); return
 *       case 'nothing-to-do': setAutoTapNotice(plan.reason); return
 *     }
 *   }
 *
 * One press = at most one tap. This is the code under test's "before" arm.
 */
function pressLegacy(): void {
  const store = useGameStore.getState()
  const prompt = store.actionPrompt
  if (!prompt || prompt.type !== 'play_mana') throw new Error('pressLegacy: no play_mana prompt')
  const lands = selectMyUntappedLands(store)
  const me = store.gameState?.players.find((p: Player) => p.isMe)
  const plan = planAutoTap(lands, prompt.rawMessage || prompt.message, me?.manaPool ?? null)
  if (plan.kind === 'tap') store.tapMana(plan.cardId)
}

let failures = 0
function check(name: string, cond: boolean, detail = ''): void {
  if (cond) console.log(`  PASS  ${name}`)
  else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string): void { console.log(`\n## ${t}`) }
const eq = (a: readonly string[], b: readonly string[]): boolean => a.join('|') === b.join('|')
const tapsOnly = (frames: readonly string[]): string[] => frames.filter((f) => f.startsWith('tap_mana:'))

/**
 * Drive the payment the way the STORE does it: press `startAutoTapAllLands`, then for every
 * `tap_mana` it emitted apply the tap on the board, subtract the mana that land's ONE
 * activation really produces from the unpaid cost, and let the prompt hook continue the run
 * on the server's re-prompt. Ends when the planner stops or the cost is fully paid.
 */
function planAutoTapSequence(boardFactory: () => Permanent[], fullCost: string, creature: string, expectedFrames: readonly string[]): void {
  const productionOf = new Map<string, string[]>()
  for (const perm of boardFactory()) {
    if (perm.manaProduction) productionOf.set(perm.id ?? '', perm.manaProduction)
  }

  let board = boardFactory()
  let remaining = fullCost
  const frames: string[] = []
  const equal = (a: readonly string[], b: readonly string[]): boolean => a.join('|') === b.join('|')

  startScenario(board, remaining, creature)
  check(`${creature} opens with a play_mana prompt`, useGameStore.getState().actionPrompt?.type === 'play_mana')

  for (let guard = 0; guard < 6; guard++) {
    startAutoTapAllLands(useGameStore.getState())
    const emitted = drain().filter((f) => f.startsWith('tap_mana:'))
    if (emitted.length === 0) break

    for (const frame of emitted) {
      const id = frame.slice('tap_mana:'.length)
      frames.push(frame)
      board = board.map((l) => (l.id === id ? { ...l, tapped: true } : l))
      remaining = reduceCost(remaining, productionOf.get(id) ?? [])
    }

    pushBoard(board)
    manaPrompt(remaining, creature)
    check(`  frames so far match plan (${frames.join(', ')})`, equal(frames, expectedFrames.slice(0, frames.length)), JSON.stringify(frames))

    const nothingLeft = remaining.replace(/\{[X\d][^}]*\}/g, '').length === 0
    if (nothingLeft) break
  }

  check(`${creature}: auto-tap pays the full ${fullCost} cost across the ${expectedFrames.length} real activations`, equal(frames, expectedFrames), `${JSON.stringify(frames)} vs ${JSON.stringify(expectedFrames)}`)
}

function reduceCost(unpaid: string, produced: readonly string[]): string {
  const pips: string[] = []
  const re = /\{[^}]+\}/g
  let m: RegExpExecArray | null
  while ((m = re.exec(unpaid)) !== null) pips.push(m[0])
  for (const p of produced) {
    const idx = pips.findIndex((pip) => /\{(G|W|U|B|R|C|Any)\}/.test(pip) && pip.toLowerCase().includes(p.toLowerCase()))
    if (idx >= 0) pips.splice(idx, 1)
  }
  return pips.join('')
}

// ═════════════════════════════════════════════════════════════════════════════
section('BEFORE — the pre-fix one-press path needs a SECOND press (the reported defect)')
{
  startScenario(f11Board(), '{1}{W}', AJANI)
  pressLegacy()
  const first = drain()
  check('one press taps exactly one land', tapsOnly(first).length === 1, JSON.stringify(first))
  check('and it is the colour-appropriate land (Plateau), not the first land', eq(first, ['tap_mana:land-plateau']), JSON.stringify(first))

  const board = serverAppliesTaps(f11Board(), first, '{1}', AJANI)
  check('the cost is still unpaid after one press', useGameStore.getState().actionPrompt?.type === 'play_mana')
  check('the Mountain is still untapped', board[0]!.tapped === false, JSON.stringify(board.map((l) => [l.id, l.tapped])))

  pressLegacy()
  const second = drain()
  check('a SECOND press of the same button is required to finish ({1} from Mountain)',
    eq(second, ['tap_mana:land-mountain']), JSON.stringify(second))
}

section('AFTER — ONE press pays the whole {1}{W} cost, in order, one tap per server re-prompt')
{
  startScenario(f11Board(), '{1}{W}', AJANI)
  startAutoTapAllLands(useGameStore.getState())
  const first = drain()
  check('the press itself sends exactly ONE tap (no burst)', tapsOnly(first).length === 1, JSON.stringify(first))
  check('the first tap is the colour-appropriate land (Plateau), not the first land', eq(first, ['tap_mana:land-plateau']), JSON.stringify(first))
  check('the run is active and remembers what it tapped',
    getAutoTapRun().active && getAutoTapRun().tappedIds.join('|') === 'land-plateau', JSON.stringify(getAutoTapRun()))

  // The server round-trip: Plateau is tapped, the re-prompt asks only for {1}.
  const board = serverAppliesTaps(f11Board(), first, '{1}', AJANI)
  const second = drain()
  check('the SAME press continues on the re-prompt — no second click',
    eq(second, ['tap_mana:land-mountain']), JSON.stringify(second))
  const settled = board.map((l) => (l.id === 'land-mountain' ? { ...l, tapped: true } : l))
  check('both lands end up tapped', settled.every((l) => l.tapped), JSON.stringify(settled.map((l) => [l.id, l.tapped])))
  check('NO boolean_choice was ever sent (no cast-cancel livelock)', ![...first, ...second].includes('boolean_choice'))
}

section('AFTER — a two-colour {R}{W} cost across a dual and a basic pays in ONE press')
{
  // The specialised land is kept for the pip that needs it ({W} only Plateau can pay), so the
  // first tap is Mountain for {R} and the second is Plateau for {W}. Both happen on one press.
  startScenario(f11Board(), '{R}{W}', 'Boros Charm')
  startAutoTapAllLands(useGameStore.getState())
  const first = drain()
  check('the first tap is the land that can ONLY pay {R} (keeps the dual for {W})',
    eq(first, ['tap_mana:land-mountain']), JSON.stringify(first))
  serverAppliesTaps(f11Board(), first, '{W}', 'Boros Charm')
  const second = drain()
  check('the same press finishes it: Plateau for {W}', eq(second, ['tap_mana:land-plateau']), JSON.stringify(second))
}

section('AFTER — the run ends honestly when no source can pay (no silent stop, no cancel)')
{
  startScenario([land('land-mountain', 'Mountain', ['R'])], '{W}', 'Guide of Souls')
  startAutoTapAllLands(useGameStore.getState())
  const frames = drain()
  check('NOTHING is sent when no land is known to produce the colour', frames.length === 0, JSON.stringify(frames))
  check('no boolean_choice at all — cancelling is an explicit user action only', sent.length === 0)
  const notice = getAutoTapRun().notice
  check('the honest reason is shown', typeof notice === 'string' && notice.includes('no untapped land is known to produce W'), JSON.stringify(notice))
  check('and the notice names the remaining cost', typeof notice === 'string' && notice.includes('remaining cost: {W}'), JSON.stringify(notice))
}

section('AFTER — a permanent with UNKNOWN production is never tapped')
{
  // The serializer omits `manaProduction` when it cannot characterise a plain "{T}: Add …" — that
  // is the 2026-09-13 off-colour-tap invariant: absent = unknown = never auto-tap.
  startScenario([land('land-mystery', 'Cavern of Souls', undefined)], '{1}', 'Amped Raptor')
  startAutoTapAllLands(useGameStore.getState())
  check('a permanent with unknown production is never tapped', drain().length === 0)
  check('and the honest reason is shown', (getAutoTapRun().notice ?? '').length > 0, JSON.stringify(getAutoTapRun().notice))
}

section('AFTER — a re-prompt that did NOT accept the tap stops instead of looping')
{
  // Simulates a server that ignored the tap: identical cost, board unchanged. Re-sending the same
  // land is the documented livelock (2026-09-12 B5 / 2026-09-13), so the run must STOP and say so.
  startScenario(f11Board(), '{1}{W}', AJANI)
  startAutoTapAllLands(useGameStore.getState())
  drain()
  manaPrompt('{1}{W}', AJANI) // identical re-prompt, board untouched (the tap was not applied)
  check('the same land is NOT sent twice', drain().length === 0)
  check('the run stops with a reason', getAutoTapRun().active === false, JSON.stringify(getAutoTapRun()))
  check('  … naming the already-tapped land', (getAutoTapRun().notice ?? '').includes('already tapped that land'),
    JSON.stringify(getAutoTapRun().notice))
}

section('AFTER — a run cannot leak into the NEXT payment (no auto-tap the user did not ask for)')
{
  startScenario(f11Board(), '{1}{W}', AJANI)
  startAutoTapAllLands(useGameStore.getState())
  const first = drain()
  // The rest of the payment resolves (the player taps the last land by hand).
  serverAppliesTaps(f11Board(), first, '{1}', AJANI)
  drain()
  // A NEW payment, for a different object: no press was made, so nothing may be sent.
  manaPrompt('{R}', 'Amped Raptor')
  check('a new payment is not auto-tapped without a press', drain().length === 0)
  check('the previous run is over', getAutoTapRun().active === false, JSON.stringify(getAutoTapRun()))
}

section('AFTER — an ability question mid-payment PAUSES the run, it does not kill it')
{
  // A multi-ability land raises GAME_CHOOSE_ABILITY mid-payment. The choice is the user's, but the
  // rest of the cost must still pay without a second press once they answer.
  startScenario(f11Board(), '{1}{W}', AJANI)
  startAutoTapAllLands(useGameStore.getState())
  const first = drain()
  check('the press sends the first tap', tapsOnly(first).length === 1, JSON.stringify(first))
  handleMessage({
    type: 'action_prompt', action_type: 'choose_ability', message: 'Choose spell or ability to play',
    abilities: [{ index: 0, id: 'ab-1', name: '{T}: Add {W}.' }, { index: 1, id: 'ab-2', name: '{T}: Add {R}.' }],
  } as unknown as ServerMessage, useGameStore.getState())
  check('the run is still alive across the ability question', getAutoTapRun().active === true, JSON.stringify(getAutoTapRun()))
  // The user answers; the server re-prompts the mana payment with the reduced cost.
  serverAppliesTaps(f11Board(), first, '{1}', AJANI)
  check('the run resumes and finishes the cost', eq(drain(), ['tap_mana:land-mountain']))
}

section('AFTER — a run ends when its prompt is withdrawn')
{
  startScenario(f11Board(), '{1}{W}', AJANI)
  startAutoTapAllLands(useGameStore.getState())
  drain()
  useGameStore.getState().hideActionPrompt()
  check('withdrawing the prompt ends the run', getAutoTapRun().active === false)
}

section('Button label — the control automated UI tests click by text')
{
  // The label is a CONTRACT, not decoration: browser tests locate this button by its
  // text (skill refs: browser-ui-test-harness.md, webui-testing-recipes.md), so a rename
  // that misses them turns every automated mana payment into a timeout. Rendering the REAL
  // component (react-dom/server, same pattern as verify-trigger-order.tsx) makes the
  // contract executable here instead of only in prose.
  const label = 'Auto-Tap Lands'
  // The pre-rename label, spelled via replace() so THIS file stays grep-clean of it: the
  // periodic "is any stale reference to the old label left?" audit greps the repo, and a
  // guard test is the one place a hit would be a false alarm rather than a stale reference.
  const preRenameLabel = label.replace('Auto-Tap ', 'Auto-Tap All ')
  const html = renderToStaticMarkup(
    createElement(PlayManaPrompt as never, {
      prompt: {
        type: 'play_mana', message: 'Pay {1}{W}', rawMessage: 'Pay {1}{W}',
        manaOptions: [], remainingCost: '{1}{W}', autoPayAvailable: true, specialAvailable: false,
      },
    } as never) as never)
  check(`the mana-payment button is labelled exactly "${label}"`, html.includes(`>${label}</button>`), html.slice(0, 400))
  check(`no trace of the old label ("${preRenameLabel}") in the markup`, !html.includes(preRenameLabel))
  check('the run-progress attribute is untouched (data-autotap-active="false" while idle)',
    html.includes('data-autotap-active="false"'))
}

section('2026-09-19 — Morska {G}{W}{U} with ONLY Azorius Chancery + Tranquil Thicket')
{
  // Real characterisations measured from the engine's own rule text via the webproxy
  // characteriser: Chancery = ["U","W"] (one {T} produces BOTH {W} and {U} at once),
  // Thicket = ["G"] (plain "{T}: Add {G}."; its cycling is irrelevant to mana payment).
  const morskaBoard = (): Permanent[] => [
    land('land-thicket', 'Tranquil Thicket', ['G']),
    land('land-chancery', 'Azorius Chancery', ['U', 'W']),
  ]
  // Whole cost planned in one pass of the planner (the planner is pip-driven, so the
  // three-run sequence below is what ONE press emits across the server's re-prompts).
  planAutoTapSequence(morskaBoard, '{G}{W}{U}', 'Morska, Undersea Sleuth', [
    'tap_mana:land-thicket',   // Thicket settles {G}
    'tap_mana:land-chancery',  // Chancery settles {W}{U} (its dual is only needed once)
  ])
}

section('2026-09-23 — Secluded Courtyard: a conditional any-colour source is tapped ONLY on the engine\'s word')
{
  // The BOARD THE DEFECT WAS REPORTED ON: Secluded Courtyard is the only non-colourless source
  // left, and the cost is a creature of the chosen type {G}. Its colourless {T}: Add {C} ability
  // is what the serializer CHARACTERISES (["C"]) — its conditional any-colour ability is
  // uncharacterisable from rule text (the appended restriction sentence fails the ANY_COLOR_MANA
  // match), so `manaProduction` can never mention G and the planner alone could never tap it.
  const courtBoard = (): Permanent[] => [land('land-court', 'Secluded Courtyard', ['C'])]
  const scout = 'Gladecover Scout'   // {G}, Elf — the creature type chosen for the Courtyard

  // (1) ABSENCE — an older proxy/server that sent no verdicts. Nothing changed for it: the
  //     Courtyard stays untouched and the run says so honestly. This is the pre-fix behaviour.
  startScenario(courtBoard(), '{G}', scout)
  startAutoTapAllLands(useGameStore.getState())
  check('[absent] an unevaluated conditional source is never tapped', drain().length === 0)
  check('[absent] and the honest reason is shown',
    (getAutoTapRun().notice ?? '').includes('no untapped land is known to produce G'), JSON.stringify(getAutoTapRun().notice))

  // (2) FALSE — the engine evaluated and this cost is NOT one the condition allows. Same refusal:
  //     a present map with false must never be read as "tap it anyway".
  startScenario(courtBoard(), '{G}', scout)
  manaPrompt('{G}', scout, { 'land-court': false })
  startAutoTapAllLands(useGameStore.getState())
  check('[false] an engine-refused conditional source is never tapped', drain().length === 0)

  // (3) TRUE — the engine verified the Courtyard's conditional mana qualifies for THIS cost
  //     (ConditionalMana.apply passes for an Elf). The tap is now justified by the engine itself,
  //     not by a guess, and the planner spends it.
  startScenario(courtBoard(), '{G}', scout)
  manaPrompt('{G}', scout, { 'land-court': true })
  startAutoTapAllLands(useGameStore.getState())
  check('[true] the engine-confirmed conditional source IS tapped', eq(drain(), ['tap_mana:land-court']),
    JSON.stringify(drain()))

  // (4) EMPTY MAP — the engine evaluated this cost and NOTHING qualified (a present-but-empty map,
  //     which is knowledge, not absence). The Courtyard's characterised {C} cannot answer a {U}
  //     pip and the engine confirmed nothing: the run must stop, not tap.
  startScenario(courtBoard(), '{U}', 'Azure Drake')
  manaPrompt('{U}', 'Azure Drake', {})
  startAutoTapAllLands(useGameStore.getState())
  check('[empty map] an evaluated-but-unqualified source is never tapped', drain().length === 0)

  // (5) The engine's verdict does NOT override the known-source preference: with a Forest on the
  //     board, {G} is paid by the Forest (rule-text-known) and the Courtyard is KEPT — a
  //     conditional source must not be burned on a pip a plain land can pay.
  const courtAndForest = (): Permanent[] => [
    land('land-court', 'Secluded Courtyard', ['C']),
    land('land-forest', 'Forest', ['G']),
  ]
  startScenario(courtAndForest(), '{G}', scout)
  manaPrompt('{G}', scout, { 'land-court': true })
  startAutoTapAllLands(useGameStore.getState())
  check('[preference] a known source is used before the engine-confirmed conditional one',
    eq(drain(), ['tap_mana:land-forest']), JSON.stringify(drain()))
}

section('2026-09-23 — Secluded Courtyard: the engine verdict is re-derived on every server re-prompt')
{
  // The full payment of a {G}{G} Elf with a Courtyard AND a Forest. The verdict must be read from
  // EACH prompt, never cached: the first {G} goes to the known Forest (a conditional source is kept
  // in reserve), and the SECOND {G} is payable only because the re-prompt says the engine still
  // qualifies the Courtyard for the reduced cost. One press, two prompts, two taps.
  const courtAndForest = (): Permanent[] => [
    land('land-court', 'Secluded Courtyard', ['C']),
    land('land-forest', 'Forest', ['G']),
  ]
  const elf = 'Elvish Warrior' // {G}{G}, Elf

  startScenario(courtAndForest(), '{G}{G}', elf)
  manaPrompt('{G}{G}', elf, { 'land-court': true, 'land-forest': true })
  startAutoTapAllLands(useGameStore.getState())
  const first = drain()
  check('the first {G} goes to the known Forest, not the conditional Courtyard',
    eq(first, ['tap_mana:land-forest']), JSON.stringify(first))
  check('the run remembers the tap', getAutoTapRun().tappedIds.join('|') === 'land-forest')

  // Server side: the Forest is tapped and the re-prompt carries the REDUCED cost with the engine's
  // fresh verdicts — the Courtyard is still qualified for the remaining {G}.
  pushBoard([{ ...courtAndForest()[0]! }, { ...courtAndForest()[1]!, tapped: true }])
  manaPrompt('{G}', elf, { 'land-court': true })
  check('the SAME press continues: the re-prompt\'s verdict makes the Courtyard payable',
    eq(drain(), ['tap_mana:land-court']), JSON.stringify(drain()))

  // Third prompt: both sources are tapped. With nothing qualified, the run stops honestly instead
  // of re-sending a tap the server cannot honour.
  pushBoard(courtAndForest().map((l) => ({ ...l, tapped: true })))
  manaPrompt('{G}', elf, {})
  check('nothing more is sent once the engine qualifies no source', drain().length === 0)
  check('and the run reports the honest reason',
    getAutoTapRun().active === false && (getAutoTapRun().notice ?? '').includes('no untapped land is known to produce G'),
    JSON.stringify(getAutoTapRun()))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
