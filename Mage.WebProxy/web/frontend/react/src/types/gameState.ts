import type { Phase, Step } from './enums'

/**
 * Game state types — derived from GameStateSerializer.java field mappings.
 * Every field here corresponds to a `state.put(...)` or `p.put(...)` call
 * in the serializer.
 */

/** Mana pool — from GameStateSerializer mp.put(...) calls. */
export interface ManaPool {
  red: number
  blue: number
  green: number
  white: number
  black: number
  colorless: number
}

/** Counter on a permanent — from perm.getCounters() in serializePermanent. */
// Note: battlefield counters are a Map<string, number> (name → count),
// while player counters are an array of {name, count} objects.
export type PermanentCounters = Record<string, number>

/** Player counter — from player.getCounters() in serializePlayer. */
export interface PlayerCounter {
  name: string
  count: number
}

/** The other face of a two-faced card (transform/MDFC/meld/flip) — from
 *  GameStateSerializer.serializeSecondFace(). typeLine/manaCost/rules are
 *  absent when only the alternate name was available (flip cards in some
 *  zones); the image always resolves. */
export interface SecondFace {
  name: string
  imageUrl: string
  typeLine?: string | undefined
  manaCost?: string | undefined
  rules?: string[] | undefined
  power?: string | undefined
  toughness?: string | undefined
  loyalty?: string | undefined
  defense?: string | undefined
}

/** Card object — from serializeCard() in GameStateSerializer.java.
 *  Fields are optional because not all card types have all fields
 *  (e.g., lands have no manaCost, non-creatures have no power/toughness). */
export interface Card {
  id: string | null
  name: string
  imageUrl: string
  typeLine: string
  manaCost: string
  rules: string[]
  power?: string | undefined
  toughness?: string | undefined
  loyalty?: string | undefined
  defense?: string | undefined
  alternateName?: string | undefined
  originalCard?: string | undefined
  /** Other face (transform/MDFC/meld/flip) — presence gates the flip button. */
  secondFace?: SecondFace | undefined
  /** True when the permanent is on its back face (transformed). */
  transformed?: boolean | undefined
  copy?: boolean | undefined
  isAbility?: boolean | undefined
  source_card?: string | undefined
  ability_text?: string | undefined
  targets?: string[] | undefined
}

/** Permanent — extends Card with battlefield-only fields from serializePermanent(). */
export interface Permanent extends Card {
  tapped: boolean
  damage: number
  /**
   * Colours this permanent's tap-only mana abilities can produce, from the engine's
   * own ability text (`GameStateSerializer.manaProductionOf`). Sorted letters from
   * "WUBRGC".
   *
   * ABSENT means UNKNOWN — the serializer omits the field when it could not
   * characterise a plain "{T}: Add ..." ability (e.g. a land with no text, "{2}, {T}:
   * Add {B} for each Swamp", "{T}, Pay 1 life: Add one mana of any color"). Unknown
   * must be treated as "never auto-tap", NOT as "any colour" and NOT as "tap it last":
   * tapping an unknown permanent for a coloured cost is how the off-colour tap in
   * happened.
   *
   * An empty array never appears — the serializer returns null and omits the field.
   */
  manaProduction?: string[] | undefined
  counters?: Record<string, number> | undefined
  summoningSick?: boolean | undefined
  /**
   * True when the permanent is a token — the ENGINE's flag
   * (`PermanentView.isToken()`), not a guess from the name.
   *
   * Never infer tokenhood from the name: XMage has 116 token names without the
   * " Token" suffix ("Ajani's Pridemate", "Akroma, Angel of Wrath", "Black
   * Lotus", "Baneslayer Angel"…), so a name substring match misclassifies them
   * as real cards and their art loses the token fallback chain.
   */
  token?: boolean | undefined
  /**
   * The set code the engine picked for this token's art
   * (`TokenImpl.generateTokenInfo`, from tokens-database.txt). Real set codes
   * (10E, ALA, M21), while Scryfall mirrors them under a `t` prefix.
   */
  tokenSetCode?: string | undefined
  /**
   * The engine's image index within {@link tokenSetCode}; 0 means the engine did
   * not index this token's art ("unset", not "art 0"). Sent alongside
   * tokenSetCode and used to build `api.scryfall.com/cards/t<set>/<n>`.
   */
  tokenImageNumber?: number | undefined
  faceDown?: boolean | undefined
  attachedTo?: string | undefined
}

/** Commander object — from serializePlayer commanders list.
 *  CommandObjectView exposes only id/name/image/rules; battlefield-only fields
 *  (tapped, damage, counters) are NOT available here, so this is Card-shaped,
 *  not Permanent-shaped. */
export interface Commander {
  id: string | null
  name: string
  imageUrl: string
  typeLine: string
  manaCost?: string | undefined
  rules?: string[] | undefined
}

/** Player — from serializePlayer() in GameStateSerializer.java. */
export interface Player {
  name: string
  playerId: string | null
  life: number
  isMe: boolean
  /**
   * Seat is controlled by a human (PlayerView.isHuman() — GameStateSerializer.serializePlayer).
   * Gates the player menu's "View deck" item, exactly like the desktop client's
   * PlayAreaPanel.java:440 (`options.playerItself || !options.isHuman`): self and
   * computer seats are viewable, another human's deck is not.
   * Optional because replay/older-proxy payloads may predate the field.
   */
  isHuman?: boolean | undefined
  /**
   * Seat avatar id — PlayerView.getUserData().getAvatarId() via
   * GameStateSerializer.serializePlayer. The desktop renders /avatars/<id>.jpg
   * (PlayerPanelExt.java:359-377); computer seats carry 64 → i64.jpg. Optional
   * because replay/older-proxy payloads predate the field; `utils/avatars.ts`
   * resolves every unknown value to the default avatar.
   */
  avatarId?: number | undefined
  libraryCount: number
  handCount: number
  isActive: boolean
  hasPriority: boolean
  /** Undo bookmark availability from PlayerView.getStatesSavedSize() (>0 = undo possible). */
  statesSavedSize?: number | undefined
  hasLeft: boolean
  timerActive: boolean
  priorityTimeLeftSecs: number
  isMonarch: boolean
  hasInitiative: boolean
  /**
   * Does this seat currently ACCEPT hand requests? Mirrors
   * `PlayerView.getUserData().isAllowRequestHandToAll()` — the value the desktop
   * client's own-header checkbox reads (PlayAreaPanel.update:517). Gates the web
   * player menu's "Allow hand requests from other users" checkbox state; toggling
   * it sends `allow_hand_requests`. Optional: replay/older-proxy
   * payloads predate the field — absent means unknown, so the checkbox renders
   * unchecked but still togglable (the server state is authoritative on the next
   * push).
   */
  allowHandRequests?: boolean | undefined
  /** Auto-pass ("skip") flags, mirrored from PlayerView.isPassed* on every state
   *  push (GameStateSerializer.serializePlayer). The engine clears the flag when
   *  that skip fires, which is how the rail learns a skip is no longer armed —
   *  the same source the desktop client's skip buttons read
   *  (GamePanel.updatePressState). */
  passedTurn: boolean
  passedUntilEndOfTurn: boolean
  passedUntilNextMain: boolean
  passedAllTurns: boolean
  passedUntilStackResolved: boolean
  passedUntilEndStepBeforeMyTurn: boolean
  manaPool: ManaPool | null
  counters: PlayerCounter[]
  commanders: Commander[]
  emblems?: Commander[] | undefined
  planes?: Commander[] | undefined
  battlefield: Permanent[]
  hand: Card[]
  graveyard: Card[]
  exile: Card[]
  topCard?: Card | undefined
  sideboard?: Card[] | undefined
}

/** One foreign hand the viewing session may look at — from
 *  GameStateSerializer's top-level `controlledHands`. The engine
 *  only populates these views for the right viewer (turn control via
 *  GameSessionPlayer.processControlledPlayers, granted hand permission via
 *  GameSessionWatcher.processWatchedHands), so receiving one means the server
 *  decided this viewer may see that hand — the client never re-checks. */
export interface ControlledHand {
  /** The engine's player name — the desktop client's handCards key. */
  name: string
  /** Why this hand is visible: 'controlled' = you control that player's turn,
   *  'watched' = that player granted you permission to see their hand. */
  kind: 'controlled' | 'watched'
  cards: Card[]
}

/** Combat group — attacker + its blockers, from serializeCombat().
 *  Note: attackerId/attackerName are NOT serialized by the backend
 *  (CombatGroupView doesn't expose them); arrows use attackers[].id. */
export interface CombatGroup {
  defenderId: string | null
  defenderName: string
  blocked: boolean
  attackers: { id: string | null }[]
  blockers: { id: string | null }[]
}

/** Stack item — a card or ability on the stack. */
export interface StackItem extends Card {}

/** Revealed cards group — from state.put("revealed"...). */
export interface RevealedGroup {
  name: string
  cards: Card[]
}

/** Looked-at cards group — from state.put("lookedAt"...). */
export interface LookedAtGroup {
  name: string
  cards: Card[]
}

/**
 * One hand the viewer has permission to see — from
 * state.put("watchedHands"...) in GameStateSerializer. `name` is the seat's
 * player name (the server keys the watched-hands map by name, the same key the
 * desktop client's hand container uses); `cards` is the hand's contents in the
 * SAME per-card shape as a player's own `hand` list.
 */
export interface WatchedHand {
  name: string
  cards: Card[]
}

/** LLM debug info — from serializeLLMDebug(). */
export interface LLMMessage {
  role: string
  content: string
  promptType?: string
  turn?: number
  phaseStep?: string
  compactSummary?: string
}

export interface LLMDebugEntry {
  playerName: string
  modelName: string
  exchangeCount: number
  totalContextChars: number
  modelContextWindow: number
  streaming: boolean
  streamingReasoning?: string
  streamingContent?: string
  streamingStartedAt?: number
  gamePlan?: string
  postGameReflection?: string
  matchReview?: string
  messages: LLMMessage[]
}

/**
 * One skipped-range LLM bucket on the wire (GameState.replayLLMBuckets): the
 * exchanges recorded at ONE saved-state index that a replay jump (skip/seek)
 * delivered over. `entries` has the same shape as `GameState.llmDebug`.
 */
export interface ReplayLLMBucket {
  stateIndex: number
  entries: LLMDebugEntry[]
}

/** Full game state — top-level fields from serializeGameView(). */
export interface GameState {
  status: string
  turn: number
  isSpectator?: boolean | undefined
  phase: Phase | null
  step: Step | null
  activePlayer: string
  priorityPlayer: string
  stack: StackItem[]
  combat: CombatGroup[]
  players: Player[]
  llmDebug: LLMDebugEntry[]
  /**
   * Root-level, UNFILTERED: does this game have LLM debug data (i.e. at least
   * one LLM player seat)? Emitted by GameStateSerializer from
   * GameView.getLLMDebug() being non-empty and computed OUTSIDE the
   * serializer's per-viewer filter — so it stays true for an opponent whose own
   * `llmDebug` is filtered down to empty. It is the input for the rail's LIVE
   * LLM-tab gate: gating on `llmDebug` itself would hide the tab from a human
   * seat in a game that HAS LLM opponents

   *
   * Timing: the server attaches LLM debug data once an LLM player has
   * conversation history or a stream in progress, so this may be false for the
   * first moments of a game with an LLM seat; it flips true on the state push
   * that first carries data (and rides `game_delta` when it flips — see
   * network/deltaApplicator.ts).
   *
   * Optional and absent on older proxies — the rail then conservatively hides
   * the live LLM tab.
   */
  hasLlmPlayers?: boolean | undefined
  /**
   * Skipped-range LLM buckets — replay only.
   *
   * A replay frame's own `llmDebug` carries only the exchanges recorded at ITS
   * state index. Sequential playback delivers every index, but replay_skip /
   * replay_seek jump in ONE server step, so the skipped indices' buckets would
   * never reach the client and the cumulative view would miss exactly those
   * exchanges. ReplaySession now attaches them here, keyed by their TRUE state
   * index, shaped exactly like one llmDebug entry. The store merges them
   * idempotently into the per-index map (a re-delivered index overwrites its own
   * key). Absent on live frames, on jumps that skipped nothing, and on proxies
   * older than this field.
   */
  replayLLMBuckets?: ReplayLLMBucket[] | undefined
  /**
   * Does the replay's GAME have at least one LLM player seat? Frame-independent
   * (ReplaySession computes it once from the deserialized game, unlike the
   * per-frame `hasLlmPlayers` above). The replay viewer's LLM tab uses it to
   * pick its empty-state copy: a game WITH LLM seats gets "no exchanges up to
   * this frame" wording; a game with none gets the no-LLM wording — never the
   * live-only "No LLM players in this game." claim while looking at an LLM game.
   * Absent on live frames and older proxies — the copy then uses the exchange
   * wording.
   */
  replayHasLlmPlayers?: boolean | undefined
  /**
   * Foreign hands this viewer may look at: players whose TURN
   * you control (mind-control effects — Worst Fears / Mindslaver / Emrakul) and
   * players who granted you permission to see their hand. The desktop client
   * switches the rendered hand between these with its "Switch Hand" button
   * (GamePanel.updateGame: the button appears only when handCards.size() > 1).
   *
   * The engine fills the underlying views per-viewer, so an entry's presence is
   * the server's own permission decision. ALWAYS present on a fresh proxy
   * payload (empty when there is nothing to switch to; the desktop's gate for
   * an empty list is the same). Absent on older proxies / replay frames that
   * predate the field — the UI then shows no switch affordance.
   */
  controlledHands?: ControlledHand[] | undefined
  revealed: RevealedGroup[]
  lookedAt: LookedAtGroup[]
  /**
   * Hands this viewer was GRANTED permission to see — the
   * server's `GameView.getWatchedHands()` map, serialized by GameStateSerializer
   * as `[{name, cards}]`. Populated for a seated viewer AND for a spectator
   * (GameSessionWatcher.processWatchedHands keys grants by USER id), empty
   * otherwise. The desktop client renders these in its hand container exactly
   * like opponent hands (GamePanel.updateGame:1095-1099), which is what the
   * browser's hand switch mirrors.
   *
   * Optional and absent on older proxies / replay frames that predate the
   * field — treat absent as "no granted hands".
   */
  watchedHands?: WatchedHand[] | undefined
  stateSeq: number
  /**
   * Replay frames only (absent on live frames): the saved game's final game
   * log, sent once with every replay frame (flat capture — see
   * option A). The live channel
   * keeps using `event` messages instead.
   */
  gameLog?: string[] | undefined
  /** Replay frames only: how many of `gameLog`'s entries existed at THIS frame. */
  gameLogCount?: number | undefined
}

/** Partial game state for delta merges — all fields optional.
 *  The backend sends only changed top-level keys. */
export type GameStateDelta = Partial<GameState>