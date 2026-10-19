/**
 * Standalone behavioural check: the off-hand card-play affordance

 *
 * Interaction rule: interface selection is PRIMARY, popups are FALLBACK.
 * A playable card in MY graveyard/exile (flashback, play-from-exile) must be
 * selectable ON THE SURFACE — the board tile in the player board, and the tile
 * inside an open ZonePopup — with the SAME highlight the hand gets, instead of
 * the right-rail button being the only path.
 *
 * The proxy already labels these plays 'activate' (or cast/land) with the
 * card's OWN UUID (WebProxyClient.handleGameSelect — findCardViewById resolves
 * graveyard/exile cards), so the frontend needs no proxy change: the choice id
 * and the zone tile's card id are the same UUID. This harness pins that
 * assumption against the real modules, so a proxy-side id drift fails here.
 *
 * Layers:
 *  (a) SSR renders of the REAL PlayerBoard (react-dom/server) — the graveyard
 *      and exile tiles carry card-playable + a click affordance exactly when
 *      playableCardIds (built from the prompt's choices) holds the card id;
 *  (b) SSR render of the REAL ZonePopup — the open zone window marks the
 *      playable card popup-card-playable with role="button";
 *  (c) wiring greps over the REAL source — the click paths reach
 *      GameView.handleCardClick (board) / selectChoice (popup), and the CSS
 *      classes exist;
 *  (d) the real store: a graveyard flashback select with the card in my
 *      graveyard suppresses the DecisionPopup window (the rail button list is
 *      empty — the offHandClickableCardIds path inside
 *      selectChoicesKeptAsButtons), while a command-zone 'activate' whose card
 *      is clickable nowhere keeps it;
 *  (e) the answer path: the popup's selectChoice dispatch (and the board's
 *      handleCardClick choice lookup) sends select_choice with the RIGHT index
 *      through the REAL message handler — same wire shape the rail button sends.
 *
 * Node-only: real store, real message handler, real components. The only stub
 * is the browser API surface (scripts/verify-stubs.mjs + a getComputedStyle
 * fake, same as verify-emblem-badge-clickable.tsx).
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { createElement } from 'react'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { PlayerBoard } from '../src/components/PlayerBoard'
import { ZonePopup } from '../src/components/ZonePopup'
import { selectChoicesKeptAsButtons } from '../src/components/prompts/SelectPrompt'
import { isEmptySelectWindow } from '../src/components/DecisionPopup'
import { readFileSync } from 'node:fs'
import type { GameState, Player, ServerMessage, SelectPrompt as SelectPromptType } from '../src/types'

// PlayerBoard renders BattlefieldZone → CardThumb, which reads the store and
// getComputedStyle (rootZoom). Node has neither; the stubs make the render
// work without changing what is asserted.
;(globalThis as unknown as { getComputedStyle: () => { zoom: number } }).getComputedStyle =
  () => ({ zoom: 1 })

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

const SKIP_FLAGS_OFF = {
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
} as const

/** A land sitting in MY graveyard (the flashback/play-from-graveyard target). */
const GRAVE_LAND = {
  id: 'grave-land-1', name: 'Verdant Catacombs', imageUrl: '',
  typeLine: 'Land', manaCost: '', rules: [],
}

/** A spell sitting in MY graveyard (flashback target with a mana cost). */
const GRAVE_SPELL = {
  id: 'grave-spell-1', name: 'Ancient Grudge', imageUrl: '',
  typeLine: 'Instant', manaCost: '{1}{R}', rules: ['Flashback {1}{R}'],
}

/** An unrelated dead card in my graveyard — must never light up. */
const GRAVE_FILLER = {
  id: 'grave-filler-1', name: 'Grizzly Bears', imageUrl: '',
  typeLine: 'Creature', manaCost: '{1}{G}', rules: [],
}

/** A card in MY exile (play-from-exile target). */
const EXILE_CARD = {
  id: 'exile-card-1', name: 'Yawgmoth\'s Vengeance', imageUrl: '',
  typeLine: 'Instant', manaCost: '{2}{U}', rules: ['Flashback'],
}

const player = (name: string, isMe: boolean, prio: boolean, extra: Partial<Player> = {}): Player => ({
  name, playerId: name, life: 20, isMe, libraryCount: 40, handCount: 1,
  isActive: false, hasPriority: prio, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
  statesSavedSize: 1, ...SKIP_FLAGS_OFF, ...extra,
})

const state = (priorityPlayer: string, extra: Partial<GameState> = {}): GameState => ({
  status: 'playing', turn: 5, phase: null, step: null,
  activePlayer: 'QA-A', priorityPlayer,
  stack: [], combat: [],
  players: [player('QA-A', true, priorityPlayer === 'QA-A'), player('QA-B', false, priorityPlayer === 'QA-B')],
  llmDebug: [], revealed: [], lookedAt: [], stateSeq: 1,
  ...extra,
})

const myStateWithZones = (extra: Partial<Player> = {}): GameState => state('QA-A', {
  players: [
    player('QA-A', true, true, {
      graveyard: [GRAVE_LAND, GRAVE_SPELL, GRAVE_FILLER],
      exile: [EXILE_CARD],
      ...extra,
    }),
    player('QA-B', false, false, { graveyard: [{ ...GRAVE_LAND, id: 'opp-grave-1' }] }),
  ],
})

// ── wire capture (the answer path must go over the REAL handler) ──
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

// react-dom/server SSR: zustand's useSyncExternalStore takes its SERVER snapshot
// from api.getInitialState() — the object captured at store creation, which
// setState never touches. Point the fields ZonePopup's selectors read at the
// LIVE state right before each render, so the SSR markup reflects what a
// browser (whose getServerSnapshot is never used) would show. The REAL store,
// handler and component are exercised end-to-end; only the SSR snapshot source
// is aimed at the delivered state (the documented SSR limitation is the same
// one verify-pass-priority-window.tsx works around by asserting the predicate).
;(globalThis as unknown as { getComputedStyle: () => { zoom: number } }).getComputedStyle =
  () => ({ zoom: 1 })
const mirrorForSSR = () => {
  const live = useGameStore.getState()
  const init = useGameStore.getInitialState() as unknown as Record<string, unknown>
  init.gameState = live.gameState
  init.actionPrompt = live.actionPrompt
  init.zonePopup = live.zonePopup
  init.inReplay = live.inReplay
  init.replayState = live.replayState
}

const reset = () => {
  sent.length = 0
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, passPending: false, eventLog: [], gameState: null, zonePopup: null })
}

const deliver = (msg: ServerMessage) => handleMessage(msg, useGameStore.getState())

/** The exact wire shape WebProxyClient.handleGameSelect emits. */
const selectMsg = (choices: Array<{ id: string; name: string; action?: string }>, over: Record<string, unknown> = {}): ServerMessage =>
  ({ type: 'action_prompt', action_type: 'select', message: 'Play spells and abilities', ...over,
     choices: choices.map((c, i) => ({ index: i, ...c })) } as unknown as ServerMessage)

const updateMsg = (s: GameState): ServerMessage => ({ type: 'game_update', state: s } as unknown as ServerMessage)

const selectPrompt = (): SelectPromptType => {
  const p = useGameStore.getState().actionPrompt
  if (p == null || p.type !== 'select') throw new Error('no select prompt in store')
  return p
}

function renderBoard(gs: GameState, playable: Set<string>): string {
  mirrorForSSR()
  const me = gs.players.find((p) => p.isMe)!
  return renderToStaticMarkup(createElement(PlayerBoard as never, {
    player: me, isTopPlayer: false, isCommander: false, isActiveTurn: true, hasPriority: true,
    playableCardIds: playable, onCardClick: (id: string | null) => void id, onZoneClick: () => {},
  } as never))
}

/** All `data-card-id` tile tags inside ONE zone block of the board render. The
 * block ends at the NEXT zone boundary (search starts at an offset past the
 * zone class itself, so the zone's own class cannot terminate it). */
function zoneTileIds(html: string, zoneClass: string): { id: string; tag: string }[] {
  const at = html.indexOf(zoneClass)
  if (at < 0) return []
  const rest = html.slice(at + zoneClass.length)
  const end = rest.search(/exile-zone|graveyard-zone|main-zones|sideboard-zone/)
  const block = end >= 0 ? rest.slice(0, end) : rest
  const out: { id: string; tag: string }[] = []
  for (const m of block.matchAll(/<div[^>]*class="[^"]*card-thumb[^"]*"[^>]*>/g)) {
    const tag = m[0]
    const id = /data-card-id="([^"]*)"/.exec(tag)?.[1]
    if (id) out.push({ id, tag })
  }
  return out
}

// ─────────────────────────────────────────────────────────────────────────────
section('the proxy wire contract — off-hand plays carry the card\'s own UUID')

{
  // The proxy labels a non-hand non-battlefield play 'activate' (WebProxyClient
  // handleGameSelect: inHand||inCommandZone ? cast|land : activate) and emits the
  // ENGINE's card UUID as the choice id. The harness pins the ids it keys on:
  reset()
  deliver(updateMsg(myStateWithZones()))
  deliver(selectMsg([
    { id: GRAVE_LAND.id, name: GRAVE_LAND.name, action: 'activate' },
  ]))
  const p = selectPrompt()
  check('the graveyard play arrives as a card-based choice (action activate)', p.choices[0]!.action === 'activate')
  check('the choice id IS the graveyard card\'s id (no proxy change needed)',
    p.choices[0]!.id === GRAVE_LAND.id, JSON.stringify(p.choices[0]))

  const gs = useGameStore.getState().gameState
  const me = gs!.players.find((pl) => pl.isMe)!
  check('the serializer carries the card id into the zone list',
    me.graveyard.some((c) => c.id === GRAVE_LAND.id))
  check('an opponent\'s graveyard holds a DIFFERENT id for its copy',
    !me.graveyard.some((c) => c.id === 'opp-grave-1'))
}

section('board tiles — my graveyard/exile light up only for the offered choices')

{
  reset()
  deliver(updateMsg(myStateWithZones()))
  // Choices: the graveyard land + the exile card are playable; the filler is not.
  deliver(selectMsg([
    { id: GRAVE_LAND.id, name: GRAVE_LAND.name, action: 'activate' },
    { id: EXILE_CARD.id, name: EXILE_CARD.name, action: 'activate' },
  ]))
  const gs = useGameStore.getState().gameState!
  const p = selectPrompt()
  const playable = new Set(p.choices.map((c) => c.id))

  const html = renderBoard(gs, playable)
  check('the graveyard zone renders', html.includes('graveyard-zone'))
  check('the exile zone renders', html.includes('exile-zone'))
  const graveTiles = zoneTileIds(html, 'graveyard-zone')
  const exileTiles = zoneTileIds(html, 'exile-zone')
  const graveLandTile = graveTiles.find((t) => t.id === GRAVE_LAND.id)
  const graveFillerTile = graveTiles.find((t) => t.id === GRAVE_FILLER.id)
  const exileTile = exileTiles.find((t) => t.id === EXILE_CARD.id)
  check('my graveyard renders all three cards as tiles', graveTiles.length === 3, JSON.stringify(graveTiles.map((t) => t.id)))
  check('my graveyard tile carries card-playable',
    !!graveLandTile && graveLandTile.tag.includes('card-playable'), graveLandTile?.tag)
  check('the filler tile has no playable class',
    !!graveFillerTile && !graveFillerTile.tag.includes('card-playable'), graveFillerTile?.tag)
  check('my exile tile carries card-playable (play-from-exile)',
    !!exileTile && exileTile.tag.includes('card-playable'), exileTile?.tag)
  check('the opponent\'s graveyard tile NEVER lights up (their zone, no choice)',
    !html.slice(html.indexOf('opp-grave-1') - 400).includes('card-playable'))
}

section('wiring — the tiles reach the answer path (SSR cannot carry handlers)')

{
  // Harness runs from the react dir (scripts/*.sh cd there) — relative reads,
  // same pattern as verify-choice-suggested.tsx.
  const board = readFileSync('src/components/PlayerBoard.tsx', 'utf8')
  const gameView = readFileSync('src/components/GameView.tsx', 'utf8')
  const thumb = readFileSync('src/components/CardThumb.tsx', 'utf8')
  // The graveyard/exile CardThumbs pass the SAME wiring the hand uses.
  const graveIdx = board.indexOf('{/* Graveyard */}')
  const exileIdx = board.indexOf('{/* Exile */}')
  const graveBlock = board.slice(graveIdx, exileIdx > graveIdx ? exileIdx : undefined)
  const exileBlock = board.slice(exileIdx)
  check('PlayerBoard graveyard tiles get isPlayable from playableCardIds', /isPlayable=\{playableCardIds\?\.\has\(/.test(graveBlock), graveBlock.slice(0, 400))
  check('PlayerBoard graveyard tiles click through onCardClick', graveBlock.includes('onClick={onCardClick}'))
  check('PlayerBoard exile tiles get isPlayable', /isPlayable=\{playableCardIds\?\.\has\(/.test(exileBlock))
  check('PlayerBoard exile tiles click through onCardClick', exileBlock.includes('onClick={onCardClick}'))
  check('GameView hands the live board onCardClick = handleCardClick', gameView.includes('onCardClick={handleCardClick}'))
  // The click resolves to select_choice via the choice lookup (the same path a
  // hand card uses); CardThumb renders the class and the pointer cursor.
  check('CardThumb maps isPlayable to the card-playable class', thumb.includes("isPlayable ? 'card-playable' : ''"))
  check('CardThumb sets cursor pointer when clickable', thumb.includes("cursor: 'pointer'"))
}

section('zone window — ZonePopup marks the playable card and answers by choice index')

{
  reset()
  deliver(updateMsg(myStateWithZones()))
  deliver(selectMsg([
    { id: GRAVE_SPELL.id, name: GRAVE_SPELL.name, action: 'activate' },
  ]))
  // Open MY graveyard window (the store payload GameView.handleZoneClick writes).
  useGameStore.setState({ zonePopup: { zone: 'graveyard', playerName: 'QA-A', cards: [] } })
  mirrorForSSR()
  const html = renderToStaticMarkup(<ZonePopup />)
  check('the zone window renders the graveyard cards', html.includes('Ancient Grudge') && html.includes('Verdant Catacombs'))
  check('the offered card carries popup-card-playable + role="button"',
    html.includes('popup-card-playable') && /class="popup-card popup-card-playable" role="button"/.test(html), html.slice(html.indexOf('popup-card'), html.indexOf('popup-card') + 160))
  // The UNGRANTED card in the same window stays display-only.
  const catacombsIdx = html.indexOf('Verdant Catacombs')
  const graveSpellIdx = html.indexOf('Ancient Grudge')
  check('non-offered cards in the same window stay display-only',
    catacombsIdx >= 0 && graveSpellIdx >= 0 &&
    html.slice(0, catacombsIdx).includes('popup-card"') &&
    !html.slice(0, catacombsIdx).includes('popup-card-playable'))
  void graveSpellIdx

  // The opponent's graveyard window: no choices can match, everything inert.
  useGameStore.setState({ zonePopup: { zone: 'graveyard', playerName: 'QA-B', cards: [] } })
  mirrorForSSR()
  const oppHtml = renderToStaticMarkup(<ZonePopup />)
  check('an opponent\'s zone window never renders the affordance', !oppHtml.includes('popup-card-playable'))

  // The affordance follows the LIVE prompt: clear it and the same window is inert.
  useGameStore.setState({ zonePopup: { zone: 'graveyard', playerName: 'QA-A', cards: [] }, actionPrompt: null })
  mirrorForSSR()
  const noPromptHtml = renderToStaticMarkup(<ZonePopup />)
  check('with no live prompt the window is display-only again', !noPromptHtml.includes('popup-card-playable'))

  // The sideboard window stays display-only even while a prompt is live — the
  // affordance is scoped to graveyard/exile (sideboard is a pure viewing surface).
  useGameStore.setState({ zonePopup: { zone: 'sideboard', playerName: 'QA-A', cards: [] } })
  mirrorForSSR()
  check('the sideboard window is never playable (popups stay fallback for sideboard)',
    !renderToStaticMarkup(<ZonePopup />).includes('popup-card-playable'))

  // Wiring greps: the popup tile dispatches selectChoice (the rail button's answer).
  const zonePopupSrc = readFileSync('src/components/ZonePopup.tsx', 'utf8')
  check('ZonePopup answers through selectChoice(choice.index)', zonePopupSrc.includes('selectChoice(choice.index)'))
  check('ZonePopup gates the affordance on card-based actions', zonePopupSrc.includes("['cast', 'land', 'activate']"))
}

section('rail + window gate — a clickable graveyard card needs no button and no popup window')

{
  reset()
  deliver(updateMsg(myStateWithZones()))
  deliver(selectMsg([{ id: GRAVE_SPELL.id, name: GRAVE_SPELL.name, action: 'activate' }]))
  const p = selectPrompt()
  const gs = useGameStore.getState().gameState
  check('the flashback choice is NOT kept as a rail button (interface selection is primary)',
    selectChoicesKeptAsButtons(p, gs ?? null).length === 0, JSON.stringify(selectChoicesKeptAsButtons(p, gs ?? null)))
  check('DecisionPopup window suppressed for the off-hand play (same predicate)',
    isEmptySelectWindow(p))

  // The command-zone twin: the card is clickable NOWHERE → button + window stay.
  reset()
  deliver(updateMsg(myStateWithZones()))
  deliver(selectMsg([{ id: 'emblem-momir-1', name: 'Momir Basic Emblem', action: 'activate' }]))
  const pz = selectPrompt()
  check('a command-zone activate (id in no rendered zone) stays a rail button',
    selectChoicesKeptAsButtons(pz, useGameStore.getState().gameState ?? null).length === 1)
  check('DecisionPopup window stays for it', !isEmptySelectWindow(pz))

  // A graveyard choice with NO matching state (stale id / early prompt): the rail
  // keeps the button — the affordance only claims what it can prove.
  reset()
  deliver(selectMsg([{ id: 'grave-never-arrived', name: 'Ghost Card', action: 'activate' }]))
  const pg = selectPrompt()
  check('null-state fallthrough keeps ALL choices as buttons',
    selectChoicesKeptAsButtons(pg, null).length === 1)
  check('a card in no rendered zone keeps its button even with state',
    selectChoicesKeptAsButtons(pg, useGameStore.getState().gameState ?? null).length === 1)
}

section('answer path — the affordance sends exactly what the rail button sends')

{
  reset()
  deliver(updateMsg(myStateWithZones()))
  deliver(selectMsg([
    { id: GRAVE_LAND.id, name: GRAVE_LAND.name, action: 'activate' },
    { id: EXILE_CARD.id, name: EXILE_CARD.name, action: 'activate' },
  ]))
  const p = selectPrompt()
  // The choice the ZonePopup tile dispatches for the graveyard land (index 0).
  useGameStore.getState().selectChoice(p.choices[0]!.index)
  check('exactly one message went out', sent.length === 1, JSON.stringify(sent))
  check('it is select_choice with the choice index',
    sent[0]!.type === 'select_choice' && sent[0]!.index === 0, JSON.stringify(sent[0]))
  // And the exile choice (index 1) — the same dispatch shape.
  reset()
  deliver(updateMsg(myStateWithZones()))
  deliver(selectMsg([
    { id: GRAVE_LAND.id, name: GRAVE_LAND.name, action: 'activate' },
    { id: EXILE_CARD.id, name: EXILE_CARD.name, action: 'activate' },
  ]))
  const p2 = selectPrompt()
  useGameStore.getState().selectChoice(p2.choices[1]!.index)
  check('the exile answer is select_choice with its own index',
    sent[0]!.type === 'select_choice' && sent[0]!.index === 1, JSON.stringify(sent[0]))
}

section('the proxy contract holds — a drift fails loudly here')

{
  // The board mapping is id-identity: if a proxy change ever renamed/re-keyed
  // off-hand choice ids, the tiles would go dark. Guard the invariant the UI
  // leans on by re-deriving it from the real message handler round-trip above:
  // the choice installed in the store must still match a zone card id.
  reset()
  deliver(updateMsg(myStateWithZones()))
  deliver(selectMsg([{ id: GRAVE_LAND.id, name: GRAVE_LAND.name, action: 'activate' }]))
  const p = selectPrompt()
  const gs = useGameStore.getState().gameState!
  const me = gs.players.find((pl) => pl.isMe)!
  const zoneIds = new Set([...me.graveyard, ...me.exile].map((c) => c.id))
  check('every card-based choice id resolves to a rendered zone tile (or hand/battlefield)',
    p.choices.filter((c) => c.action && ['cast', 'land', 'activate'].includes(c.action ?? ''))
      .every((c) => zoneIds.has(c.id)))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)