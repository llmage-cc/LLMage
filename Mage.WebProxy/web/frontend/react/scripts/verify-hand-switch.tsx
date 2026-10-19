/**
 * Standalone behavioural check for the switch-hands flow (the desktop client's
 * btnSwitchHands — GamePanel.java:1094-1134:2968-2983).
 *
 * WHAT IS BEING VERIFIED
 *
 * The engine lets a session look at hands other than its own in exactly two
 * ways: it CONTROLS another player's turn (GameSessionPlayer.processControlledPlayers
 * → GameView.getOpponentHands) or a player GRANTED it permission to see their hand
 * (GameSessionWatcher.processWatchedHands → GameView.getWatchedHands). The
 * desktop client collects those into `handCards` ("Your hand" + one entry per
 * foreign hand) and its Switch-Hand button — visible only when
 * handCards.size() > 1 — swaps which one the hand container renders.
 *
 * The web wire now carries them additively as the top-level `controlledHands`
 * list; this harness pins every layer that makes the feature work:
 *
 *  (a) the DELTA applicator forwards `controlledHands` (control starts and ends
 *      mid-game, so the key flips between pushes — without it the browser keeps
 *      a stale affordance after control is lost);
 *  (b) the gate: ONE hand (my own, no foreign hands) → no switch UI at all —
 *      the normal single-hand view is untouched;
 *  (c) two hands → the toggle lists BOTH, and SSR of the REAL PlayerBoard shows
 *      the control in the hand area's title row;
 *  (d) switching swaps the rendered cards (SSR shows the controlled player's
 *      card names where my hand's were);
 *  (e) control lost → `controlledHands` empties, the selection is stale, and the
 *      view collapses back to my own hand WITH NO switch UI (the desktop's
 *      chosenHandKey fallback);
 *  (f) a watched hand is a display surface only (handCardsPlayable);
 *  (g) the wire shape the Java serializer emits (field names + kinds) matches
 *      exactly what this frontend reads — a rename on either side fails here.
 *
 * Real store, real message handler, real PlayerBoard/handSwitch modules, and the
 * REAL explanation resolver over the control's real markup. The only stubs are
 * the browser API surface (scripts/verify-stubs.mjs).
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { createElement } from 'react'
import { readFileSync } from 'node:fs'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { PlayerBoard } from '../src/components/PlayerBoard'
import { HandSwitch, handOptionExplanation, handOptionLabel, handCardsPlayable } from '../src/components/HandSwitch'
import {
  YOUR_HAND_KEY,
  clearHandSwitch,
  getHandSwitchKey,
  handView,
  setHandSwitchKey,
} from '../src/store/slices/handSwitch'
import { applyDelta } from '../src/network/deltaApplicator'
import { EXPLAIN, resolveExplanation } from '../src/hints/explain'
import type { Card, GameState, Player, ServerMessage } from '../src/types'

declare const process: { env: Record<string, string | undefined>; cwd(): string; exit(code: number): void }

// The harness bundles to /tmp and runs from there, so source reads must resolve
// against the react dir the .sh wrapper passes in (same pattern as
// verify-shared-layout-parity.ts).
const HERE = process.env.XMAGE_VERIFY_DIR ?? process.cwd()
const readSource = (rel: string): string => readFileSync(`${HERE}/${rel}`, 'utf8')

// The real store + real message handler need a socket to answer on: capture what
// the client sends so a resync request or a stray verb is visible in the run.
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
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

// ── fixtures ──

const card = (id: string, name: string): Card => ({
  id, name, imageUrl: '', typeLine: 'Instant', manaCost: '{R}', rules: [],
})

/** My hand — what the normal, unchanged view renders. */
const MY_CARDS = [card('me-1', 'Grizzly Bears'), card('me-2', 'Forest')]
/** The hand of a player whose turn I control (arrives only in `controlledHands`). */
const CONTROLLED_CARDS = [card('ctl-1', 'Lightning Bolt'), card('ctl-2', 'Mountain'), card('ctl-3', 'Shock')]
/** The hand a player granted me permission to look at. */
const WATCHED_CARDS = [card('watch-1', 'Counterspell')]

const SKIP_FLAGS_OFF = {
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndOfStepBeforeMyTurn: false,
} as const

const player = (name: string, isMe: boolean, extra: Partial<Player> = {}): Player => ({
  name, playerId: name, life: 20, isMe, libraryCount: 40, handCount: 2,
  isActive: false, hasPriority: false, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  counters: [], commanders: [], battlefield: [], hand: isMe ? MY_CARDS : [] as Card[],
  graveyard: [], exile: [], statesSavedSize: 1, ...SKIP_FLAGS_OFF, ...extra,
} as Player)

const state = (extra: Partial<GameState> = {}): GameState => ({
  status: 'playing', turn: 5, phase: null, step: null,
  activePlayer: 'QA-B', priorityPlayer: 'QA-A',
  stack: [], combat: [],
  players: [player('QA-A', true), player('QA-B', false), player('QA-C', false)],
  llmDebug: [], revealed: [], lookedAt: [], stateSeq: 1,
  ...extra,
})

/** The serializer's wire shape for a foreign hand (GameStateSerializer.serializeForeignHand). */
const foreignHand = (name: string, kind: 'controlled' | 'watched', cards: Card[]) =>
  ({ name, kind, cards })

const ONE_HAND = state()
const CONTROLLED_TURN = state({
  controlledHands: [foreignHand('QA-B', 'controlled', CONTROLLED_CARDS)],
})
const CONTROLLED_AND_WATCHED = state({
  controlledHands: [
    foreignHand('QA-B', 'controlled', CONTROLLED_CARDS),
    foreignHand('QA-C', 'watched', WATCHED_CARDS),
  ],
})

/** SSR render of one seat's board, mirroring GameView's props for that seat. */
function renderSeat(gs: GameState, seat: Player, view = handView(gs, getHandSwitchKey())): string {
  const isOwner = view.visible && view.ownerName === seat.name
  return renderToStaticMarkup(createElement(PlayerBoard as never, {
    player: seat, isTopPlayer: !seat.isMe, isCommander: false,
    isActiveTurn: false, hasPriority: false,
    displayedHand: isOwner ? view.foreignHand ?? undefined : undefined,
    handIsForeign: isOwner && view.foreignHand != null,
    handSwitchControl: isOwner
      ? createElement(HandSwitch as never, { options: view.options, currentKey: view.key ?? '' })
      : undefined,
    onCardClick: () => {}, onZoneClick: () => {},
  } as never))
}

const cardNamesIn = (html: string): string[] =>
  [...html.matchAll(/data-card-name="([^"]*)"/g)].map((m) => m[1]!)

// ─────────────────────────────────────────────────────────────────────────────
section('(a) the delta path carries the new field')

{
  // Through the REAL message handler: a full state, then a game_delta that
  // starts control, then one that ends it — the exact sequence the proxy emits.
  useGameStore.setState({ gameState: null, actionPrompt: null, currentActionPrompt: null, passPending: false })
  sent.length = 0

  handleMessage({ type: 'game_update', state: state({ controlledHands: [] }) } as unknown as ServerMessage,
    useGameStore.getState())
  const before = useGameStore.getState().gameState!
  check('the full state installs an empty list', (before.controlledHands ?? []).length === 0)

  handleMessage({
    type: 'game_delta',
    stateSeq: 2,
    delta: { controlledHands: [foreignHand('QA-B', 'controlled', CONTROLLED_CARDS)] },
  } as unknown as ServerMessage, useGameStore.getState())
  const during = useGameStore.getState().gameState!
  check('a delta with controlledHands replaces the list (control started)',
    (during.controlledHands ?? []).length === 1
      && during.controlledHands![0]!.name === 'QA-B' && during.controlledHands![0]!.kind === 'controlled')
  check('the delta did not touch my own hand',
    during.players.find((p: Player) => p.isMe)!.hand.length === MY_CARDS.length)

  handleMessage({
    type: 'game_delta',
    stateSeq: 3,
    delta: { controlledHands: [] },
  } as unknown as ServerMessage, useGameStore.getState())
  const after = useGameStore.getState().gameState!
  check('control lost forwards on the same key (list back to empty)',
    (after.controlledHands ?? []).length === 0)
  check('no resync was requested by these pushes', !sent.some((m) => m.type === 'request_resync'))
}

section('(b) one hand → the normal single-hand view, no switch UI')

{
  clearHandSwitch()
  const v = handView(ONE_HAND, getHandSwitchKey())
  check('only my own hand is offered', v.options.length === 1 && v.options[0]!.key === YOUR_HAND_KEY)
  check('no switch affordance', v.visible === false, JSON.stringify(v.options))
  check('no foreign hand is rendered anywhere', v.foreignHand === null)

  const html = renderSeat(ONE_HAND, ONE_HAND.players[0]!)
  check('my hand renders my cards', cardNamesIn(html).includes('Grizzly Bears'))
  check('the rendered hand section carries NO switch control', !html.includes('hand-switch'))
  check('the hand title is the untouched single-hand shape', /<div class="zone-title">Hand \(2\)<\/div>/.test(html))
}

section('(c) two hands → the toggle lists both')

{
  clearHandSwitch()
  const v = handView(CONTROLLED_AND_WATCHED, getHandSwitchKey())
  check('three hands offered (own + controlled + watched)', v.options.length === 3)
  check('affordance visible', v.visible === true)
  check('your own hand is the default selection', v.key === YOUR_HAND_KEY && v.current!.kind === 'own')
  check('still no foreign hand rendered while your own is selected', v.foreignHand === null)
  check('controlled hand carries the engine name + kind',
    v.options[1]!.key === 'QA-B' && v.options[1]!.kind === 'controlled' && v.options[1]!.cards === CONTROLLED_CARDS)
  check('watched hand carries its own name + kind',
    v.options[2]!.key === 'QA-C' && v.options[2]!.kind === 'watched')
  check('menu labels name the reason each hand is visible',
    handOptionLabel(v.options[1]!).includes('QA-B') && handOptionLabel(v.options[1]!).includes('control')
      && handOptionLabel(v.options[2]!).includes('QA-C') && handOptionLabel(v.options[2]!).includes('see their hand'))

  const html = renderSeat(CONTROLLED_AND_WATCHED, CONTROLLED_AND_WATCHED.players[0]!)
  check('the control renders in MY hand section', html.includes('hand-switch') && html.includes('hand-title-row'))
  check('the switch button carries its explain id', html.includes('data-explain="hand-switch"'))
  check('the button reports the current selection', html.includes('data-hand-switch-current="You"'))
}

section('(d) switching swaps the rendered hand')

{
  clearHandSwitch()
  const seat = CONTROLLED_AND_WATCHED.players[0]!
  renderSeat(CONTROLLED_AND_WATCHED, seat)
  setHandSwitchKey('QA-B')
  const v = handView(CONTROLLED_AND_WATCHED, getHandSwitchKey())
  check('the selection is the controlled player', v.key === 'QA-B' && v.current!.kind === 'controlled')
  check('their hand is the rendered one', v.foreignHand === CONTROLLED_CARDS)

  const html = renderSeat(CONTROLLED_AND_WATCHED, seat)
  const names = cardNamesIn(html)
  check('the controlled cards render where mine were', names.includes('Lightning Bolt') && names.includes('Shock'))
  check('my own cards are gone from the hand area', !names.includes('Grizzly Bears'))
  check('the title row says whose hand this is', /Hand \(3\) — controlled\/visible hand/.test(html))
  check('the button now names the other player', html.includes("QA-B&#x27;s hand") || html.includes("QA-B's hand"))
  check('the button reports QA-B selected', html.includes('data-hand-switch-current="QA-B"'))
  check('a controlled hand is playable', handCardsPlayable('controlled') === true)

  // And back.
  setHandSwitchKey(YOUR_HAND_KEY)
  const back = renderSeat(CONTROLLED_AND_WATCHED, seat)
  check('switching back restores my own cards', cardNamesIn(back).includes('Grizzly Bears'))
  check('and drops theirs', !cardNamesIn(back).includes('Lightning Bolt'))

  // A WATCHED hand is a look-only surface.
  setHandSwitchKey('QA-C')
  const watched = renderSeat(CONTROLLED_AND_WATCHED, seat)
  check('the watched hand renders on selection', cardNamesIn(watched).includes('Counterspell'))
  check('a watched hand is NOT playable (permission to look is not permission to play)',
    handCardsPlayable('watched') === false)
}

section('(e) control lost → the view collapses back')

{
  // Selected QA-B, then the engine stops sending that hand (turn control ended).
  setHandSwitchKey('QA-B')
  const gone = state({ controlledHands: [] })
  const v = handView(gone, getHandSwitchKey())
  check('the stale selection falls back to your own hand', v.key === YOUR_HAND_KEY)
  check('no foreign hand is rendered', v.foreignHand === null)
  check('the affordance is gone (only one hand left)', v.visible === false)

  const seat = gone.players[0]!
  const html = renderSeat(gone, seat)
  check('my own cards are back', cardNamesIn(html).includes('Grizzly Bears'))
  check('the switch UI is gone', !html.includes('hand-switch'))
  check('the hand title is back to the single-hand shape', /Hand \(2\)<\/div>/.test(html))

  // Permission revoked / a different foreign hand still present: the selection
  // falls back only when ITS key disappears, and the affordance stays otherwise.
  setHandSwitchKey('QA-B')
  const onlyWatched = state({ controlledHands: [foreignHand('QA-C', 'watched', WATCHED_CARDS)] })
  const v2 = handView(onlyWatched, getHandSwitchKey())
  check('a different foreign hand keeps the affordance alive', v2.visible === true && v2.options.length === 2)
  check('but the vanished key still falls back to your own hand', v2.key === YOUR_HAND_KEY)

  clearHandSwitch()
}

section('(f) the wire contract the Java serializer emits')

{
  // The harness fixtures above deliberately spell the fields the way
  // GameStateSerializer.serializeForeignHand writes them; this check pins both
  // ends of that contract against the real serializer source. The harness runs
  // from the react/ dir, so the module is one level up and back along the repo.
  const src = readSource('../../../src/main/java/mage/webproxy/GameStateSerializer.java')
  check('serializer emits the top-level controlledHands key', src.includes('state.put("controlledHands", controlledHands)'))
  check('serializer emits the per-hand fields', src.includes('hand.put("name", playerName)')
    && src.includes('hand.put("kind", kind)') && src.includes('hand.put("cards", cards)'))
  check('serializer emits both grant kinds', src.includes('"controlled"') && src.includes('"watched"'))
  check('the existing hand contract is untouched in the serializer',
    src.includes('p.put("hand", hand)') && src.includes('p.put("hand", new ArrayList<>())'))
  check('the serializer reads the two engine sources by their desktop names',
    src.includes('gameView.getOpponentHands()') && src.includes('gameView.getWatchedHands()'))
}

section('(g) the control explains itself')

{
  // SSR markup cannot carry the delegated-tooltip walk (no real DOM), so resolve
  // the explanation for the control's OWN markup shape through the real
  // registry: the button's data-explain id must exist and read as an explanation.
  setHandSwitchKey('QA-B')
  const html = renderSeat(CONTROLLED_AND_WATCHED, CONTROLLED_AND_WATCHED.players[0]!)
  check('the control carries a data-explain id', html.includes('data-explain="hand-switch"'))
  const src = readSource('src/hints/explain.ts')
  check('the id is in the EXPLAIN registry', src.includes("'hand-switch':"))
  // The dropdown only renders while open (useState), so SSR shows the closed
  // button; the per-item literal explanations are checked through the REAL
  // helper plus the component's own markup marker.
  const switchSrc = readSource('src/components/HandSwitch.tsx')
  check('menu items carry the literal (`@`) explanation form', switchSrc.includes('data-explain={`@${handOptionExplanation(option)}`}'))
  const v = handView(CONTROLLED_AND_WATCHED, getHandSwitchKey())
  check('the controlled-hand item explanation names the player and the reason',
    handOptionExplanation(v.options[1]!).includes('QA-B') && handOptionExplanation(v.options[1]!).includes('you control their turn'))
  check('the watched-hand item explanation names the player and the reason',
    handOptionExplanation(v.options[2]!).includes('QA-C') && handOptionExplanation(v.options[2]!).includes('permission'))
  check('the own-hand item explanation says it is yours to play',
    handOptionExplanation(v.options[0]!).includes('your own hand'))
  // Resolve through the REAL registry (imported above): an id that is missing or
  // reworded to something unusable fails here, not in a user's tooltip.
  const text = EXPLAIN['hand-switch']
  check('the registry entry exists and explains both grant reasons',
    typeof text === 'string' && text.includes('Switch') && text.includes('turn')
      && text.includes('permission'), String(text))
  clearHandSwitch()
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
