/**
 * Standalone behavioural check for the trigger-order prompt fixes.
 *
 * Run with:  bash scripts/verify-trigger-order.sh
 *
 * Covers the trigger-order prompt family:
 * the trigger-order prompt ("Pick triggered ability (goes to the stack first)")
 * used to render two unlabelled, DISABLED tiles and nothing in the UI could
 * answer or dismiss it, blocking a live game for 13 minutes.
 *
 * Everything here drives the REAL modules: the real message handler, the real
 * store, and the real popup components (rendered with react-dom/server, so no
 * browser is needed). The only stub is the browser API surface in
 * scripts/verify-stubs.mjs.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { TargetPrompt } from '../src/components/prompts/TargetPrompt'
import { TargetSelectBody, shouldShowCardSelectPopup } from '../src/components/CardSelectPopup'
import { candidateLabel, candidateGridHint, isCandidateSelectable, clickCandidate } from '../src/utils/targetCandidates'
import type { ServerMessage, TargetPrompt as TargetPromptType } from '../src/types'

// ── fixtures ──
//
// The payload shape below is what WebProxyClient.handleGameTarget now emits for
// the engine's PICK_ABILITY prompt (HumanPlayer.chooseTriggeredAbility →
// GameController `new CardsView(abilities, game)`). Verified against the raw
// observed evidence: candidates are the two abilities, id = ability UUID,
// name = source card, abilityText = engine-rendered trigger text. Before the fix
// `targets` was null and both entries were marked isValidTarget=false.
const ABILITY_A = '20b9361b-7f46-4031-842a-fb08de0454cf'
const ABILITY_B = '538055c9-c927-4d90-871c-cf498543c776'
const AJANI = 'Ajani, Nacatl Pariah'
const GUIDE = 'Guide of Souls'
const AJANI_RULE = 'When Ajani, Nacatl Pariah enters, create a 2/1 white Cat Warrior creature token.'
const GUIDE_RULE = 'Whenever another creature you control enters, you gain 1 life and get {E}.'

/** The exact message the proxy sends for trigger order. */
const triggerOrderMessage = (): ServerMessage => ({
  type: 'action_prompt',
  action_type: 'target',
  message: 'Pick triggered ability (goes to the stack first)',
  context: 'trigger_order',
  trigger_order: true,
  required: true,
  // One pick, two candidates — the proxy reports target_count=1.
  target_count: 1,
  min: 1,
  max: 1,
  targets: [
    { id: ABILITY_A, name: AJANI, type: 'permanent', abilityText: AJANI_RULE },
    { id: ABILITY_B, name: GUIDE, type: 'permanent', abilityText: GUIDE_RULE },
  ],
  // The proxy also mirrors the candidates into lookAtCards (cardsView1) — that is
  // how the CardSelectPopup floating window receives them.
  lookAtCards: [
    { name: AJANI, typeLine: '', manaCost: '', rules: [AJANI_RULE], id: ABILITY_A, imageUrl: '', isValidTarget: true, sourceName: AJANI, sourceImageUrl: 'https://api.scryfall.com/x?exact=Ajani', abilityText: AJANI_RULE },
    { name: GUIDE, typeLine: '', manaCost: '', rules: [GUIDE_RULE], id: ABILITY_B, imageUrl: '', isValidTarget: true, sourceName: GUIDE, sourceImageUrl: 'https://api.scryfall.com/x?exact=Guide', abilityText: GUIDE_RULE },
  ],
} as unknown as ServerMessage)

// ── wire capture: what actually goes on the socket ──
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

// react-dom/server reads zustand's `getServerSnapshot` (the INITIAL state), which
// would render every store-driven component as if nothing had arrived. Point that
// snapshot at the live state so the components under test see the delivered prompt.
;(useGameStore as unknown as { getInitialState: () => unknown }).getInitialState =
  () => useGameStore.getState()

const reset = () => {
  sent.length = 0
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, eventLog: [], gameState: null })
}
const deliver = (msg: ServerMessage) => handleMessage(msg, useGameStore.getState())
const prompt = (): TargetPromptType => {
  const p = useGameStore.getState().actionPrompt
  if (p == null || p.type !== 'target') throw new Error('no target prompt in store')
  return p
}

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ─────────────────────────────────────────────────────────────────────────────
section('the trigger-order payload reaches the popup LABELLED')

reset()
deliver(triggerOrderMessage())
{
  const p = prompt()
  check('prompt is recognised as trigger order', p.triggerOrder === true, JSON.stringify(p))
  check('both abilities are offered', p.targets.length === 2, String(p.targets.length))
  check('labels name the source cards, not "Ability"',
    p.targets[0]!.name === AJANI && p.targets[1]!.name === GUIDE,
    p.targets.map((t) => t.name).join('|'))
  check('trigger text is carried through',
    p.targets[0]!.abilityText === AJANI_RULE && p.targets[1]!.abilityText === GUIDE_RULE,
    p.targets.map((t) => t.abilityText).join('|'))
  check('the two options are DISTINGUISHABLE (the defect was two "Ability" tiles)',
    candidateLabel(p.lookAtCards![0]!) !== candidateLabel(p.lookAtCards![1]!),
    candidateLabel(p.lookAtCards![0]!) + ' vs ' + candidateLabel(p.lookAtCards![1]!))
  check('one click completes the prompt — not mis-framed as "Select 2 targets"',
    p.targetCount === 1 && !candidateGridHint(p).startsWith('Select 2'),
    `targetCount=${p.targetCount} hint=${candidateGridHint(p)}`)
}

section('the rendered tiles are ENABLED and labelled')

{
  const p = prompt()
  const html = renderToStaticMarkup(<TargetPrompt prompt={p} />)
  check('no tile is disabled', !html.includes('popup-card-disabled') && !html.includes('disabled'))
  for (const name of [AJANI, GUIDE]) {
    check(`tile text includes ${name}`, html.includes(name))
  }
  check('the popup asks for an order (not for N targets)',
    html.includes('goes on the stack first'), html)

  // The floating window is the component the live client renders (`.float-window .popup-card`):
  // CardSelectPopup's shell is store-driven (and react-dom/server reads zustand's
  // initial snapshot, not the delivered prompt), so assert the shell's own ownership
  // predicate and render its REAL body with the prompt the store received.
  check('the floating popup owns this prompt', shouldShowCardSelectPopup(p))
  const popupHtml = renderToStaticMarkup(<TargetSelectBody prompt={p} />)
  check('floating popup renders both candidates', popupHtml.includes(AJANI) && popupHtml.includes(GUIDE), popupHtml)
  check('floating popup marks both selectable (valid-target, never popup-card-disabled)',
    (popupHtml.match(/valid-target/g) ?? []).length >= 2 && !popupHtml.includes('popup-card-disabled'),
    popupHtml)
  check('floating popup uses the SOURCE card art, never a scryfall "Ability" lookup',
    !popupHtml.includes('exact=Ability'), popupHtml)
  check('floating popup labels the tiles with card + trigger text',
    popupHtml.includes(AJANI) && popupHtml.includes(GUIDE) && popupHtml.includes('Cat Warrior'), popupHtml)
}

section('clicking an option dispatches select_target with that ability id')

{
  reset()
  deliver(triggerOrderMessage())
  const p = prompt()
  // The exact click path of the floating popup's tile: shared clickCandidate()
  // (the same function the real onClick calls), driven through the store actions.
  const card = p.lookAtCards![1]!
  const actions = {
    selectTarget: (id: string) => useGameStore.getState().selectTarget(id),
    hideActionPrompt: () => useGameStore.getState().hideActionPrompt(),
  }
  check('candidate B is selectable', isCandidateSelectable(p, card))
  check('the click reports success', clickCandidate(p, card, actions))
  check('one select_target went out', sent.length === 1, JSON.stringify(sent))
  check('it carries the ability UUID (what HumanPlayer.chooseTriggeredAbility matches)',
    sent[0]!.type === 'select_target' && sent[0]!.targetId === ABILITY_B, JSON.stringify(sent[0]))
  // Re-activating the same answer before the server moves on must not reach the wire
  // a second time (the existing F1/F2 repeat-activation guard in the store).
  clickCandidate(p, card, actions)
  check('a repeat activation of the same tile sends nothing more', sent.length === 1, JSON.stringify(sent))
  // The other ability on a re-prompt (the engine asks again for the next one) works.
  reset()
  deliver(triggerOrderMessage())
  clickCandidate(prompt(), prompt().lookAtCards![0]!, actions)
  check('the next prompt accepts the other ability', sent.length === 1 && sent[0]!.targetId === ABILITY_A, JSON.stringify(sent))
}

section('no dead end: an optional trigger-order prompt can be declined')

{
  reset()
  const msg = triggerOrderMessage() as unknown as { required: boolean }
  msg.required = false
  deliver(msg as unknown as ServerMessage)
  const p = prompt()
  const html = renderToStaticMarkup(<TargetPrompt prompt={p} />)
  check('optional trigger-order prompt offers the dismiss button', html.includes('Skip'), html)
  // passPriority is the same decline path every other optional target prompt uses.
  useGameStore.getState().passPriority()
  check('dismissing sends pass_priority (the GAME_TARGET cancel path)',
    sent.length === 1 && sent[0]!.type === 'pass_priority', JSON.stringify(sent))
}

section('the prompt is answerable even when the payload marks candidates invalid')

{
  // Defensive: the whole pre-fix failure mode was isValidTarget=false everywhere.
  // The trigger-order gate must not depend on that flag.
  reset()
  const msg = triggerOrderMessage() as unknown as { lookAtCards: Array<Record<string, unknown>> }
  for (const c of msg.lookAtCards) c.isValidTarget = false
  deliver(msg as unknown as ServerMessage)
  const p = prompt()
  check('still trigger order', p.triggerOrder === true)
  check('still selectable despite isValidTarget=false', isCandidateSelectable(p, p.lookAtCards![0]!))
  const html = renderToStaticMarkup(<TargetPrompt prompt={p} />)
  check('rendered tiles are not disabled', !html.includes('popup-card-disabled'))
  useGameStore.getState().selectTarget(p.lookAtCards![0]!.id!)
  check('click still dispatches', sent.length === 1 && sent[0]!.targetId === ABILITY_A, JSON.stringify(sent))
}

section('No regression — ordinary target prompts are unchanged')

{
  // Fetchland / Surveil grid: validity gating and the "N targets" hint must stay.
  reset()
  deliver({
    type: 'action_prompt', action_type: 'target', message: 'Select a card',
    context: 'default', required: false, target_count: 1, min: 0, max: 1,
    targets: [],
    lookAtCards: [
      { name: 'Arid Mesa', typeLine: 'Land', manaCost: '', rules: [], id: 'land-1', imageUrl: '', isValidTarget: true },
      { name: 'Mountain', typeLine: 'Basic Land', manaCost: '', rules: [], id: 'land-2', imageUrl: '', isValidTarget: false },
    ],
  } as unknown as ServerMessage)
  {
    const p = prompt()
    check('not flagged as trigger order', p.triggerOrder === false)
    check('card validity still gates the click',
      isCandidateSelectable(p, p.lookAtCards![0]!) && !isCandidateSelectable(p, p.lookAtCards![1]!))
    check('ordinary cards label as their plain name (no rules text bleed)',
      candidateLabel(p.lookAtCards![0]!) === 'Arid Mesa' && candidateLabel(p.lookAtCards![1]!) === 'Mountain',
      candidateLabel(p.lookAtCards![0]!) + '|' + candidateLabel(p.lookAtCards![1]!))
    const html = renderToStaticMarkup(<TargetPrompt prompt={p} />)
    check('invalid card is not offered as a target (no valid-target marker)',
      (html.match(/valid-target/g) ?? []).length === 1, html)
    check('optional prompt still offers the skip button', html.includes("Skip"), html)
  }

  // Plain target list (spell targeting / declare-attackers style). The count is NOT
  // announced: the engine sends no target min/max (only the amount/multi-amount ctors
  // fill those), so the proxy reports target_count=0 and the header describes the action
  // instead of claiming a number. Asserting the ABSENCE of "Select N" here is the
  // regression guard — the old code worded this header from the candidate list.
  reset()
  deliver({
    type: 'action_prompt', action_type: 'target', message: 'Choose a target',
    context: 'default', required: true, target_count: 0, max_targets: 1,
    targets: [{ id: 't1', name: 'Bear', type: 'creature' }],
  } as unknown as ServerMessage)
  {
    const p = prompt()
    check('plain target prompt does not invent a count',
      p.targetCount === 0 && !/Select \d+ targets/.test(candidateGridHint(p)),
      `targetCount=${p.targetCount} hint=${candidateGridHint(p)}`)
    const html = renderToStaticMarkup(<TargetPrompt prompt={p} />)
    check('plain target button renders and is clickable', html.includes('Bear'), html)
    useGameStore.getState().selectTarget('t1')
    check('plain target click dispatches select_target',
      sent.length === 1 && sent[0]!.targetId === 't1', JSON.stringify(sent))
  }

  // Target-count regression — THE EXACT LIVE CASE (2026-09-17).
  //
  // Marsh Flats search: 13 legal candidates (6 Plains + 4 Plateau + 3 Savannah), NO
  // Done button in the options (the engine adds UI.right.btn.text only once its minimum
  // is already satisfied, and this prompt starts unsatisfied), required=false. The
  // shipped header read "Select 13 targets (click cards)" for a ONE-card effect.
  //
  // Two independent guards are needed because the defect had two halves: the proxy must
  // not send the candidate count as a claimed count (target_count === 0 — the old code's
  // `anyNumber ? 0 : candidateCount` fallback is what fed it 13), and the hint must not
  // word a header from target_count even if a large value arrives.
  reset()
  deliver({
    type: 'action_prompt', action_type: 'target', message: 'Select a Plains or Swamp card for Marsh Flats',
    context: 'library_search', required: false,
    target_count: 0, max_targets: 13,   // no done_button_text — the live shape
    lookAtCards: [
      { name: 'Plains', typeLine: 'Basic Land', id: 'c1', imageUrl: '', isValidTarget: true },
      { name: 'Plateau', typeLine: 'Land', id: 'c2', imageUrl: '', isValidTarget: true },
      { name: 'Lightning Bolt', typeLine: 'Instant', id: 'c3', imageUrl: '', isValidTarget: false },
    ],
  } as unknown as ServerMessage)
  {
    const p = prompt()
    check('no claimed count survives into the prompt (live Marsh Flats shape)',
      p.targetCount === 0, `targetCount=${p.targetCount}`)
    const hint = candidateGridHint(p)
    check('header never says "Select N targets" for a candidate-only prompt',
      !/Select \d+ targets/.test(hint), hint)
    check('candidate count is presented as choices, not as a requirement',
      hint.includes('13') && /legal choices/.test(hint), hint)
  }

  // A large target_count arriving from an older/other proxy must ALSO not be announced
  // as a "select N" instruction — the frontend half of the guard, independent of the proxy.
  {
    const hint = candidateGridHint({
      triggerOrder: false, targetCount: 13, maxTargets: 13,
    })
    check('frontend guard: a stray large targetCount is not rendered as an instruction',
      !/Select \d+ targets/.test(hint), hint)
  }

  // choose_pile (Fact or Fiction) is still owned by the same floating popup.
  reset()
  deliver({
    type: 'action_prompt', action_type: 'choose_pile', message: 'Fact or Fiction',
    pile1: { index: 0, cards: [] }, pile2: { index: 1, cards: [] },
  } as unknown as ServerMessage)
  {
    const p = useGameStore.getState().actionPrompt
    check('choose_pile still routes to the floating popup',
      shouldShowCardSelectPopup(p as never), JSON.stringify(p))
    // And the lookAtCards family still routes there too.
    reset()
    deliver({
      type: 'action_prompt', action_type: 'target', message: 'Select a card',
      required: false, target_count: 1, targets: [],
      lookAtCards: [{ name: 'Arid Mesa', typeLine: 'Land', manaCost: '', rules: [], id: 'land-1', imageUrl: '', isValidTarget: true }],
    } as unknown as ServerMessage)
    check('a lookAtCards target prompt still routes to the floating popup',
      shouldShowCardSelectPopup(prompt()), 'lookAtCards prompt did not route')
  }
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
