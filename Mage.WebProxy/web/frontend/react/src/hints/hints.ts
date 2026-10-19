/**
 * Onboarding hint system — registry, persistence and the tiny observable store.
 *
 * WHY THIS EXISTS: new players got stuck on the first steps of a game (how to
 * play a land, how to tap for mana, what "pass priority" means). The client shows
 * a short tip beside the real control until the player says "don't show again",
 * and any tip can switch the whole system off.
 *
 * ── SEMANTICS (exact, do not change without updating this comment) ──
 *
 * Persistence: ONE versioned localStorage key, `xmage-hints-v1`, holding
 *   { v: 1, off: boolean, dismissed: HintId[] }
 * — the same pattern the settings store uses (uiSlice.loadSettings / SETTINGS_KEY):
 * a tolerant read (a corrupt/missing key reads as "nothing dismissed yet"), a
 * whole-object write on every mutation.
 *
 * A tip is ELIGIBLE when ALL of these hold:
 *   1. `off === false`                     (the global switch is on), and
 *   2. its id is not in `dismissed`         (this tip was dismissed), and
 *   3. the current view matches `tip.view`  (game only — see below), and
 *   4. its anchor element exists, is visible and is inside the viewport.
 *
 * At most ONE tip is displayed at a time: the first eligible tip in HINTS order.
 * Dismissing it reveals the next one (if its anchor is on screen), so the tip
 * sequence walks the player through the basics instead of swarming the board.
 *
 * Dismissal is PER BROWSER, NOT PER GAME: a dismissed tip never comes back — not
 * on the next game, not on the next session. "First games only" is therefore
 * bounded by dismissal, not by a game counter: a player who never dismisses a tip
 * keeps seeing it, and the moment every tip is dismissed (or `off` is set)
 * NOTHING shows again on that browser. That is the whole exit condition.
 *
 * VIEW SCOPE: every tip in the registry is `view: 'game'` — the ten basics are all
 * game-board actions (there is nothing in the lobby or the replay viewer the hints
 * teach), so tips are structurally incapable of firing in the lobby or in a replay.
 * Adding a lobby tip later means adding `view: 'lobby'` and matching it in
 * HintLayer's view gate — the gate is a whitelist, not a blacklist.
 */

/** Stable ids — also the localStorage keys of dismissed tips. Never rename. */
export type HintId =
  | 'play-land'
  | 'tap-land'
  | 'pass-priority'
  | 'choose-target'
  | 'attack-block'
  | 'stack-area'
  | 'zone-click'
  | 'log-chat-rail'
  | 'skip-buttons'
  | 'settings-gear'

export interface HintDef {
  id: HintId
  /** Views this tip may appear in. Games only — see VIEW SCOPE above. */
  view: 'game'
  /**
   * Anchor candidates, most specific first: the first selector that matches a
   * VISIBLE element wins. Selectors are used where the target element belongs to
   * another surface (phase bar, hand, stack) so this feature needs no edits there;
   * `[data-explain=…]` is used for the two controls whose position in the DOM is
   * not addressable by a stable class.
   */
  anchor: string[]
  /** The tip body. Says what to DO, in game terms. */
  text: string
}

/**
 * The ten first-steps tips, in the order they are offered. Order matters: the
 * earliest stuck-points come first (play a land → pay for it → pass → respond).
 */
export const HINTS: readonly HintDef[] = [
  {
    id: 'play-land',
    view: 'game',
    anchor: ['.player-card:last-child .hand-section .hand-cards-row', '.hand-section .hand-cards-row'],
    text: 'Play a card: click a card in your hand. A land goes straight onto the battlefield; a spell is cast onto the stack and your opponent gets a chance to respond.',
  },
  {
    id: 'tap-land',
    view: 'game',
    anchor: ['.player-card:last-child .land-row .card-thumb', '.land-row .card-thumb'],
    text: 'Tap a land for mana: when a cost needs mana, click an untapped land (or the "Auto-Tap All Lands" button) to add it to your mana pool. A tapped land is sideways and cannot be used again until your next turn.',
  },
  {
    id: 'pass-priority',
    view: 'game',
    anchor: ['[data-explain="pass-priority"]', '.action-buttons .btn-primary', '.action-buttons .skip-btn'],
    text: 'Pass Priority: hands priority to the opponent. The step only advances after every player has passed in a row — if you have nothing to do, this is how the game moves on.',
  },
  {
    id: 'choose-target',
    view: 'game',
    anchor: ['.prompt-targets .btn-prompt', '.prompt-choices .btn-prompt', '.card-thumb.card-target'],
    text: 'Choose a target: cards and players outlined in red (or listed here) are the legal targets for the spell or ability waiting for your answer. Click one, then it resolves.',
  },
  {
    id: 'attack-block',
    view: 'game',
    anchor: ['.prompt-choices .btn-prompt-all-attack', '.btn-prompt-all-attack', '.phase-bar .phase-btn:nth-child(6)'],
    text: 'Combat: when the game asks you to declare attackers or blockers, click the highlighted creatures (or "All attack"). Creatures you have controlled since your last turn can attack; untapped creatures can block. Made a mistake? Click a declared creature AGAIN (orange ring) to undeclare it before combat is final.',
  },
  {
    id: 'stack-area',
    view: 'game',
    anchor: ['.stack-section'],
    text: 'The Stack: spells and abilities wait here to resolve, last one first. Anything on the stack can still be countered or answered, so a spell is not final until this row is empty.',
  },
  {
    id: 'zone-click',
    view: 'game',
    anchor: ['.player-card:last-child .clickable-zone-title', '.clickable-zone-title'],
    text: 'Graveyard, exile and sideboard: click a zone title to open the full list of cards in it. Cards in the graveyard are public information and can still be played by effects.',
  },
  {
    id: 'log-chat-rail',
    view: 'game',
    anchor: ['.right-tabs', '.game-log-list'],
    text: 'Log & chat: every game action is written to the Game Log tab, the Chat tab talks to your opponent, and the LLM tab shows the reasoning of an AI opponent when one is playing.',
  },
  {
    id: 'skip-buttons',
    view: 'game',
    anchor: ['.action-buttons .skip-btn', '[data-explain="skip-turn"]'],
    text: 'Skip buttons: tell the server you have nothing to do until a later moment — "Skip Turn" passes priority until your next turn, "Skip Main" until the next main phase. Click the armed skip again (or F3) to cancel it.',
  },
  {
    id: 'settings-gear',
    view: 'game',
    anchor: ['[data-explain="settings-gear"]'],
    text: 'Settings: fonts and card size, dark or light theme, and the highlight colours that mark playable, targetable and combat-selectable cards. Hover any button to see what it does.',
  },
]

export const HINT_IDS: readonly HintId[] = HINTS.map((h) => h.id)

/** Versioned key — bump `v` only with a migration; a fresh key resets dismissals. */
const LS_KEY = 'xmage-hints-v1'

interface HintStoreState {
  /** The global switch from every tip's "Turn off all hints" control. */
  off: boolean
  /** Tips the player dismissed individually. */
  dismissed: HintId[]
}

function load(): HintStoreState {
  // Tolerant read, matching uiSlice.loadSettings: storage may be unavailable
  // (private mode) or hold a value from another build. A bad value must never
  // keep the client from rendering; the worst case is re-showing tips.
  try {
    const raw = localStorage.getItem(LS_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<HintStoreState>
      const dismissed = Array.isArray(parsed.dismissed)
        ? parsed.dismissed.filter((id): id is HintId => (HINT_IDS as readonly string[]).includes(id))
        : []
      return { off: parsed.off === true, dismissed }
    }
  } catch { /* localStorage unavailable — treat as nothing dismissed */ }
  return { off: false, dismissed: [] }
}

let state: HintStoreState = load()

const listeners = new Set<() => void>()

function persist(next: HintStoreState) {
  try {
    localStorage.setItem(LS_KEY, JSON.stringify({ v: 1, off: next.off, dismissed: next.dismissed }))
  } catch { /* localStorage unavailable — the in-memory state still applies */ }
}

function set(next: HintStoreState) {
  state = next
  persist(next)
  for (const l of listeners) l()
}

/** Subscribe to hint-state changes (useSyncExternalStore contract). */
export function subscribeHints(listener: () => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

/** Current hint state (useSyncExternalStore contract — must be referentially stable). */
export function getHintsState(): HintStoreState {
  return state
}

/** "Don't show again" — dismiss exactly this tip. */
export function dismissHint(id: HintId) {
  if (state.dismissed.includes(id)) return
  set({ ...state, dismissed: [...state.dismissed, id] })
}

/** "Turn off all hints" — disable the whole system on this browser, for good. */
export function turnOffAllHints() {
  if (state.off) return
  set({ ...state, off: true })
}

/**
 * Re-arm every tip and switch hints back on.
 *
 * TEST/RESET ONLY — there is deliberately no UI for it (a "show hints again"
 * toggle would make the exit condition reversible for the player, which is not
 * what "don't show again" promised). The browser harness drives it through
 * `window.__xmageHints.reset()` to prove dismissals survive a reload.
 */
export function resetHints() {
  set({ off: false, dismissed: [] })
}

/** Whether a tip may be offered right now (rules 1–2 above; view/anchor are checked in HintLayer). */
export function isHintEligible(id: HintId): boolean {
  return !state.off && !state.dismissed.includes(id)
}
