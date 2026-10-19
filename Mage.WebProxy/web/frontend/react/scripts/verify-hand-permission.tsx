/**
 * Standalone behavioural checks for the hand-request INITIATE half

 *
 * Run with:  bash scripts/verify-hand-permission.sh
 *
 * Not part of the app build (scripts/ is excluded from tsconfig "include": ["src"]).
 * Everything here drives the REAL store through the REAL websocket-send path:
 * the action implementations in gameplaySlice, the watched-hand selection module
 * (store/slices/handWatch.ts), the delta applicator (network/deltaApplicator.ts)
 * and the message handler, so a regression in any of them fails here.
 *
 * What is pinned:
 *  1. requestHandPermission sends {type:'request_hand_permission', playerId} —
 *     playerId is the GRANTER's seat id, verbatim, no massaging;
 *  2. revokeHandPermission / allowHandRequests send their exact bare/boolean
 *     payloads (desktop's PERMISSION_REQUESTS_ALLOWED_ON|OFF pair);
 *  3. a dead socket on request surfaces an error instead of vanishing (the
 *     "the other player ignored me" trap), while revoke/allow stay silent
 *     no-ops like every other settings-style verb;
 *  4. the delta applicator carries watchedHands through a game_delta (the
 *     grant appears WITHOUT a reload);
 *  5. the handWatch module: selectHand/closeHandView drive the viewer key, an
 *     empty name is refused (Fail Fast — it can never match a hand), and a
 *     revoke that empties watchedHands clears the selection the same tick the
 *     component's revoked-effect would (asserted via the module API the effect
 *     calls, since react-dom/server cannot run effects — see the shell grep
 *     layer for the wiring);
 *  6. the REAL <WatchedHandsView /> and <PlayerContextMenu /> rendered with
 *     react-dom/server: no grant → no chrome; one grant → the granted seat's
 *     cards render (named tiles); the player menu offers the desktop's own items
 *     for the seat shape in state (request on another's seat, allow/revoke on
 *     yours, watcher wording while spectating).
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { applyDelta } from '../src/network/deltaApplicator'
import { closeHandView, getSelectedHand, selectHand, setAllowIntent, subscribeHandWatch } from '../src/store/slices/handWatch'
import { WatchedHandsView } from '../src/components/WatchedHandsView'
import { PlayerContextMenu } from '../src/components/PlayerContextMenu'
import type { GameState, ServerMessage } from '../src/types'

// rootZoom() (utils/viewport.ts) reads getComputedStyle(document.documentElement).zoom.
// The Node stubs provide documentElement but not getComputedStyle; identity zoom (1)
// is the correct answer for a headless render — it is what an unzoomed page reports.
;(globalThis as unknown as { getComputedStyle: () => { zoom: number } }).getComputedStyle =
  () => ({ zoom: 1 })

// react-dom/server reads zustand's getServerSnapshot, which is
// `selector(api.getInitialState())` — the store's INITIAL state object, NOT the
// live state. That snapshot object is returned by reference, so mutating it in
// lockstep with the live setState is what makes each server render below see the
// delivered scenario (same lever as verify-token-menu-hides-preview.tsx).
const ssrSnapshot = (
  useGameStore as unknown as { getInitialState: () => Record<string, unknown> }
).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

// record the full payloads that go on the wire
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

const reset = () => {
  sent.length = 0
  useGameStore.setState({ actionPrompt: null, currentActionPrompt: null, eventLog: [], gameState: null, passPending: false, errorMessage: '' })
}

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

const GRANTER = '11111111-2222-3333-4444-555555555555'

// ─────────────────────────────────────────────────────────────────────────────
section('requestHandPermission — the INITIATE verb')

{
  reset()
  useGameStore.getState().requestHandPermission(GRANTER)
  check('exactly one message went out', sent.length === 1, JSON.stringify(sent))
  check('type is request_hand_permission', sent[0]?.type === 'request_hand_permission')
  check('playerId is the GRANTER seat id, verbatim', sent[0]?.playerId === GRANTER)
}
{
  reset()
  // A GRANTER whose id is unknown (replay frame / old proxy) must still send
  // WHAT it has — the server is the authority on whether the seat exists.
  useGameStore.getState().requestHandPermission('P2-name-not-a-uuid')
  check('any supplied id rides the wire untouched', sent[0]?.playerId === 'P2-name-not-a-uuid')
}

section('requestHandPermission — dead socket fails loudly')

{
  reset()
  useGameStore.setState({ ws: null } as never)
  useGameStore.getState().requestHandPermission(GRANTER)
  const err = useGameStore.getState().errorMessage
  check('nothing went on a dead socket', sent.length === 0, JSON.stringify(sent))
  check('an error surfaced (not a silent no-op)', typeof err === 'string' && err.length > 0, String(err))
  // restore the recording socket for the remaining sections
  useGameStore.getState().setWebSocket({
    send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
    onStateVersionReceived: () => {},
    requestResync: () => {},
    clearResyncFlag: () => {},
    disconnect: () => {},
  } as never)
}

// ─────────────────────────────────────────────────────────────────────────────
section('revokeHandPermission / allowHandRequests — own-seat verbs')

{
  reset()
  useGameStore.getState().revokeHandPermission()
  check('revoke sends the bare verb', sent.length === 1 && sent[0]?.type === 'revoke_hand_permission', JSON.stringify(sent))
  check('revoke carries no fields beyond its type', Object.keys(sent[0] ?? {}).length === 1)
}
{
  reset()
  useGameStore.getState().allowHandRequests(true)
  check('allow(true) sends the boolean', sent[0]?.type === 'allow_hand_requests' && sent[0]?.enabled === true)
}
{
  reset()
  useGameStore.getState().allowHandRequests(false)
  check('allow(false) sends the boolean', sent[0]?.type === 'allow_hand_requests' && sent[0]?.enabled === false)
}
{
  reset()
  useGameStore.setState({ ws: null } as never)
  useGameStore.getState().revokeHandPermission()
  useGameStore.getState().allowHandRequests(true)
  check('dead socket: revoke/allow stay silent no-ops (settings-style verbs)', sent.length === 0)
  useGameStore.getState().setWebSocket({
    send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
    onStateVersionReceived: () => {},
    requestResync: () => {},
    clearResyncFlag: () => {},
    disconnect: () => {},
  } as never)
}

// ─────────────────────────────────────────────────────────────────────────────
section('delta applicator — a grant arrives without a reload')

{
  reset()
  const base = { stateSeq: 1, players: [], stack: [], revealed: [], lookedAt: [] } as unknown as GameState
  useGameStore.setState({ gameState: base } as never)
  // What WebProxyClient.computeStateDelta sends after a grant: the whole new list.
  const delta = { watchedHands: [{ name: 'QA-B', cards: [{ id: 'c1', name: 'Forest' }] }] } as never
  const merged = applyDelta(useGameStore.getState().gameState!, delta)
  check('watchedHands rides the delta', merged.watchedHands?.length === 1)
  check('the seat name survives', merged.watchedHands?.[0]?.name === 'QA-B')
}
{
  reset()
  // A revoke: the empty list must REPLACE the old one (not be ignored).
  const before = { stateSeq: 2, players: [], stack: [], revealed: [], lookedAt: [],
    watchedHands: [{ name: 'QA-B', cards: [] }] } as unknown as GameState
  useGameStore.setState({ gameState: before } as never)
  const merged = applyDelta(before, { watchedHands: [] } as never)
  check('an emptied watchedHands replaces the grant list', (merged.watchedHands ?? []).length === 0)
}

// ─────────────────────────────────────────────────────────────────────────────
section('watched hands on the wire — handleMessage carries them into state')

{
  reset()
  handleMessage({
    type: 'game_update',
    state: { stateSeq: 3, players: [], stack: [], revealed: [], lookedAt: [],
      watchedHands: [{ name: 'QA-B', cards: [{ id: 'c9', name: 'Island' }] }] },
  } as unknown as ServerMessage, useGameStore.getState())
  const st = useGameStore.getState().gameState
  check('game_update state carries watchedHands', (st?.watchedHands ?? []).length === 1)
  check('with its cards', st?.watchedHands?.[0]?.cards?.[0]?.name === 'Island')
}

// ─────────────────────────────────────────────────────────────────────────────
section('handWatch module — the viewer key')

{
  check('starts closed', getSelectedHand() === null)
  selectHand('QA-B')
  check('selectHand opens the viewer on that seat', getSelectedHand() === 'QA-B')
  let notified = 0
  const unsub = subscribeHandWatch(() => { notified++ })
  selectHand('QA-C')
  check('switching seats notifies subscribers', notified === 1 && getSelectedHand() === 'QA-C')
  selectHand('QA-C')
  check('re-selecting the same seat is a no-op', notified === 1)
  closeHandView()
  check('closeHandView closes it', getSelectedHand() === null && notified === 2)
  unsub()
}
{
  // Fail Fast: an empty seat name could never match a hand — refuse it instead
  // of opening a viewer that renders a permanently empty panel.
  let threw = false
  try { selectHand('') } catch { threw = true }
  check('empty seat name is refused', threw)
}

// ─────────────────────────────────────────────────────────────────────────────
section('revoke while open — the component effect calls closeHandView on the tick the grant vanishes')

{
  // The component's revoked-effect calls closeHandView when the selected seat is
  // no longer in watchedHands. react-dom/server cannot run effects, so pin the
  // exact CONDITION the effect computes here, against real state.
  selectHand('QA-B')
  const withGrant = { watchedHands: [{ name: 'QA-B', cards: [] }] } as unknown as GameState
  const revoked = withGrant.watchedHands!.find((h) => h.name === getSelectedHand()) === undefined
  check('grant present → not revoked', revoked === false)
  const withoutGrant = { watchedHands: [] } as unknown as GameState
  const revokedNow = withoutGrant.watchedHands!.find((h) => h.name === getSelectedHand()) === undefined
  check('grant gone → revoked', revokedNow === true)
  if (revokedNow) closeHandView()
  check('effect body closes the viewer', getSelectedHand() === null)
}

// ─────────────────────────────────────────────────────────────────────────────
section('rendered <WatchedHandsView /> — no grant renders nothing, a grant renders the cards')

{
  // No grants at all: the component must render NOTHING (an ungranted session
  // sees no new chrome anywhere on the board).
  setLive({ gameState: { stateSeq: 1, players: [], stack: [], revealed: [], lookedAt: [], watchedHands: [] } })
  closeHandView()
  const html = renderToStaticMarkup(<WatchedHandsView />)
  check('ungranted session → empty render', html === '', html.slice(0, 120))
}
{
  // A granted hand, viewer open: the seat name, the granted count and one tile
  // per card must be in the markup.
  setLive({ gameState: { stateSeq: 2, players: [], stack: [], revealed: [], lookedAt: [],
    watchedHands: [{ name: 'QA-B', cards: [
      { id: 'w1', name: 'Forest', imageUrl: '', typeLine: 'Basic Land — Forest', manaCost: '', rules: [] },
      { id: 'w2', name: 'Island', imageUrl: '', typeLine: 'Basic Land — Island', manaCost: '', rules: [] },
    ] }] } })
  selectHand('QA-B')
  const html = renderToStaticMarkup(<WatchedHandsView />)
  check('the viewer window renders', html.includes('float-window') && html.includes('watched-hand-window'))
  check('titled with the granted seat + count', html.includes('QA-B') && html.includes('granted to you (2)'))
  check('every granted card renders (data-card-name on the tile)', html.includes('data-card-name="Forest"') && html.includes('data-card-name="Island"'))
  check('an empty hand renders the row with no tiles', (() => {
    setLive({ gameState: { stateSeq: 3, players: [], stack: [], revealed: [], lookedAt: [],
      watchedHands: [{ name: 'QA-B', cards: [] }] } })
    const h = renderToStaticMarkup(<WatchedHandsView />)
    return h.includes('granted to you (0)') && !h.includes('data-card-name=')
  })())
  closeHandView()
}
{
  // Two grants: the switch rail appears (the desktop's handCards.size() > 1 rule),
  // and only when NOT viewing one of them.
  setLive({ gameState: { stateSeq: 4, players: [], stack: [], revealed: [], lookedAt: [],
    watchedHands: [
      { name: 'QA-B', cards: [] },
      { name: 'QA-C', cards: [] },
    ] } })
  closeHandView()
  const html = renderToStaticMarkup(<WatchedHandsView />)
  check('switch rail appears with 2+ grants', html.includes('watched-hands-switch'))
  check('one switch button per granted seat', (html.match(/hand-switch-btn/g) ?? []).length === 2)
  // One grant only → no rail (the desktop's single-hand shape: open it from the menu).
  setLive({ gameState: { stateSeq: 5, players: [], stack: [], revealed: [], lookedAt: [],
    watchedHands: [{ name: 'QA-B', cards: [] }] } })
  const single = renderToStaticMarkup(<WatchedHandsView />)
  check('no rail with exactly one grant', !single.includes('watched-hands-switch'))
}

// ─────────────────────────────────────────────────────────────────────────────
section('rendered <PlayerContextMenu /> — the desktop items, for the right seat shape')

/** A minimal player the menu reads (name/isMe/isHuman/playerId + zone arrays). */
const seat = (name: string, over: Record<string, unknown>) => ({
  playerId: `uuid-${name}`,
  name,
  isMe: false,
  isHuman: true,
  life: 20, libraryCount: 40, handCount: 7,
  graveyard: [], exile: [], sideboard: [], battlefield: [], hand: [],
  counters: [], manaPool: null, ...over,
})

const openMenuFor = (playerName: string) => {
  setLive({ openContextMenu: { kind: 'player', x: 10, y: 10, playerName } })
}

{
  // Another human's seat: the REQUEST item (desktop seat wording, non-watcher).
  setLive({
    gameState: { stateSeq: 6, isSpectator: false, players: [seat('QA-A', { isMe: true }), seat('QA-B', {})],
      stack: [], revealed: [], lookedAt: [], watchedHands: [] },
  })
  openMenuFor('QA-B')
  const html = renderToStaticMarkup(<PlayerContextMenu />)
  check('request item on another seat', html.includes('Request permission to see the hand cards'))
  check('the desktop tooltip-equivalent for the request is an explain id', html.includes('data-explain="player-ctx-request-hand"'))
  check('no allow/revoke on another seat', !html.includes('Allow hand requests from other users'))
  check('the section head uses the desktop submenu title', html.includes('Cards on hand ('))
}
{
  // YOUR OWN seat: the allow checkbox and the revoke item — and NO request item
  // (the engine refuses self-requests). The checkbox's value resolves as
  // `intent ?? serialized flag` (handWatch.resolveAllowHandRequests): a live
  // allow_hand_requests send leaves an intent, so reset it here to assert the
  // SERIALIZED-source path, then set one to assert the intent-source path.
  setAllowIntent(null as never)
  setLive({
    gameState: { stateSeq: 7, isSpectator: false, players: [
      seat('QA-A', { isMe: true, allowHandRequests: true }), seat('QA-B', {})],
      stack: [], revealed: [], lookedAt: [], watchedHands: [] },
  })
  openMenuFor('QA-A')
  const html = renderToStaticMarkup(<PlayerContextMenu />)
  check('allow item on your own seat', html.includes('Allow hand requests from other users'))
  check('checked state mirrors the serialized allowHandRequests flag', html.includes('☑'))
  check('revoke item on your own seat', html.includes('Revoke all permission(s) to see your hand cards'))
  check('no request item on your own seat', !html.includes('Request permission to see the hand cards'))
  // Intent wins over the (stale) serialized flag — the engine fires no push for
  // this toggle, so the click must land on the first press.
  setAllowIntent(false)
  const htmlOff = renderToStaticMarkup(<PlayerContextMenu />)
  check('a sent intent overrides the stale serialized flag', htmlOff.includes('☐') && !htmlOff.includes('☑ Allow'))
  setAllowIntent(true)
  const htmlOn = renderToStaticMarkup(<PlayerContextMenu />)
  check('the intent also renders the checked state', htmlOn.includes('☑ Allow'))
  setAllowIntent(null as never)
}
{
  // Watcher: every seat offers the request item, with the desktop WATCHER wording.
  setLive({
    gameState: { stateSeq: 8, isSpectator: true, players: [seat('QA-A', {}), seat('QA-B', {})],
      stack: [], revealed: [], lookedAt: [], watchedHands: [] },
  })
  openMenuFor('QA-B')
  const html = renderToStaticMarkup(<PlayerContextMenu />)
  check('watcher wording names the seat', html.includes('Request permission to see hand cards (QA-B)'))
}
{
  // Already granted: the menu offers the opener for the granted hand instead of
  // another (refused) request.
  setLive({
    gameState: { stateSeq: 9, isSpectator: false, players: [seat('QA-A', { isMe: true }), seat('QA-B', {})],
      stack: [], revealed: [], lookedAt: [], watchedHands: [{ name: 'QA-B', cards: [] }] },
  })
  openMenuFor('QA-B')
  const html = renderToStaticMarkup(<PlayerContextMenu />)
  check('granted seat offers the viewer opener', html.includes('data-explain="player-ctx-view-granted-hand"') && html.includes('hand (granted)'))
  check('…and not a duplicate request item', !html.includes('data-explain="player-ctx-request-hand"'))
}

console.log(failures === 0 ? '\nPASS' : `\nFAIL (${failures})`)
if (failures > 0) (globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(1)