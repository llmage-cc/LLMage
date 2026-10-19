/**
 * Hand switch — which hand the in-game hand area displays.
 *
 * Desktop parity: GamePanel.updateGame builds a `handCards` map — "Your hand"
 * (gameView.getMyHand()) plus one entry per player whose TURN this session
 * controls (getOpponentHands — GameSessionPlayer.processControlledPlayers) and per
 * player who granted this user permission to see their hand (getWatchedHands —
 * GameSessionWatcher.processWatchedHands) — and its btnSwitchHands is visible only
 * when handCards.size() > 1 ("Your hand" is always in the map for a player).
 * Picking an entry calls handContainer.loadCards(handCards.get(key)) and
 * chosenHandKey falls back to "Your hand" whenever the key disappears
 * (GamePanel.java:1108-1115) — which is how the desktop collapses the view when
 * control ends.
 *
 * State lives in module scope — the viewDeck/autoTapRun/tokenArt pattern: the
 * selection is one nullable string and the store's declared shape is a shared
 * file other workstreams edit.
 *
 * The selection is deliberately NOT auto-corrected by a side effect. Callers
 * derive the EFFECTIVE key at render time (`effectiveHandKey`), so a stale key
 * (control lost while it was selected, or a replay frame that predates the
 * selection) can never render a hand the engine no longer sends. That is also
 * what makes the "control lost → UI collapses back" behaviour provable in an
 * SSR harness, which runs no effects.
 */
import type { GameState } from '@/types'

/** The desktop's own label for the player's own hand (GamePanel.YOUR_HAND). */
export const YOUR_HAND_KEY = 'You'

/**
 * Selectable hand entries for this viewer, in the desktop's order: own hand
 * first, then controlled hands, then watched hands (the serializer emits
 * opponentHands before watchedHands).
 *
 * A spectator (no `isMe` player) has no own hand — the desktop then offers only
 * the watched hands (GamePanel's `else` branch: chosenHandKey starts at the first
 * key). Returns [] when the payload predates `controlledHands` or nothing is
 * visible, in which case the hand area keeps its single-hand rendering.
 */
export interface HandOption {
  /** Selection key — the engine's player name, or YOUR_HAND_KEY for your own. */
  key: string
  /** 'own' | 'controlled' | 'watched' — drives the item label/icon. */
  kind: 'own' | 'controlled' | 'watched'
  /** The engine's player name for the hand (`key` for foreign hands; your own
   *  serialized seat name for your own — display only). */
  name: string
  /** The hand's cards as the serializer sent them (own = the player's `hand`). */
  cards: import('@/types').Card[]
}

export function handOptions(gameState: GameState | null): HandOption[] {
  if (!gameState) return []
  const me = gameState.players.find((p) => p.isMe)
  const options: HandOption[] = []
  if (me) {
    options.push({ key: YOUR_HAND_KEY, kind: 'own', name: me.name, cards: me.hand })
  }
  for (const hand of gameState.controlledHands ?? []) {
    options.push({
      key: hand.name,
      kind: hand.kind === 'watched' ? 'watched' : 'controlled',
      name: hand.name,
      cards: hand.cards,
    })
  }
  return options
}

/**
 * True when the switch affordance should be offered at all.
 *
 * Desktop gate: a PLAYER sees the button when handCards.size() > 1 — i.e. at
 * least one foreign hand alongside their own. A SPECTATOR sees it when they
 * watch any hand at all (`btnSwitchHands.setVisible(!handCards.isEmpty())`), so
 * their single watched hand is what the container shows. The web client mirrors
 * both: >1 option for a player, >=1 option for a spectator with no own hand.
 */
export function showHandSwitch(options: HandOption[]): boolean {
  if (options.length === 0) return false
  const hasOwn = options.some((o) => o.kind === 'own')
  return hasOwn ? options.length > 1 : true
}

/**
 * The key whose cards should actually render, given the current options.
 * Falls back to your own hand (or the first available option for a spectator)
 * whenever the requested key is not — or is no longer — among the options.
 */
export function effectiveHandKey(options: HandOption[], requested: string | null): string | null {
  if (options.length === 0) return null
  if (requested != null && options.some((o) => o.key === requested)) return requested
  const own = options.find((o) => o.kind === 'own')
  return own ? own.key : options[0]!.key
}

// ── module-level selection (useSyncExternalStore contract) ──

let requestedKey: string | null = null
const listeners = new Set<() => void>()

function notify(): void {
  for (const l of listeners) l()
}

/** Subscribe to selection changes (module-level so it survives board remounts). */
export function subscribeHandSwitch(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Current requested key (stable between changes — safe as a snapshot getter). */
export function getHandSwitchKey(): string | null {
  return requestedKey
}

/** Select a hand by key. Unknown keys are harmless (effectiveHandKey ignores them). */
export function setHandSwitchKey(key: string | null): void {
  if (requestedKey === key) return
  requestedKey = key
  notify()
}

/** Forget the selection (leaving the game / new game). */
export function clearHandSwitch(): void {
  if (requestedKey === null) return
  requestedKey = null
  notify()
}

/** The option for one key, for callers that already have the options list. */
export function handFor(options: HandOption[], key: string | null): HandOption | null {
  if (key == null) return null
  return options.find((o) => o.key === key) ?? null
}

/**
 * Everything the board render needs for the hand switch, derived in ONE place.
 *
 * GameView computes this per render and the verification harness calls it with
 * the same inputs — so "control lost → the view collapses" is provable without a
 * browser, and cannot drift from what ships.
 *
 *   options    — every hand this viewer may look at (own + controlled + watched)
 *   visible    — whether the switch affordance is offered (see showHandSwitch)
 *   key        — the EFFECTIVE selection (stale keys fall back; see effectiveHandKey)
 *   current    — the option for `key`
 *   ownerName  — the seat whose hand area carries the control and renders a
 *                foreign hand: your own seat for a player, and for a spectator
 *                (no own hand) the seat currently displayed
 *   foreignHand— `current`'s cards when a FOREIGN hand is selected, else null;
 *                the seat in `ownerName` renders these INSTEAD of player.hand
 */
export interface HandView {
  options: HandOption[]
  visible: boolean
  key: string | null
  current: HandOption | null
  ownerName: string | null
  foreignHand: import('@/types').Card[] | null
}

export function handView(gameState: GameState | null, requested: string | null): HandView {
  const options = handOptions(gameState)
  const visible = showHandSwitch(options)
  const key = effectiveHandKey(options, requested)
  const current = handFor(options, key)
  const own = options.find((o) => o.kind === 'own') ?? null
  const ownerName = !visible ? null : own ? own.name : current?.name ?? null
  const foreignHand = current != null && current.kind !== 'own' ? current.cards : null
  return { options, visible, key, current, ownerName, foreignHand }
}
