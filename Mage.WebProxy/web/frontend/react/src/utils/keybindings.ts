/**
 * Keyboard shortcuts — the SINGLE registry of every keyboard binding the web
 * client owns, plus the rebindable user overrides on top of it (
 * Java reference: the desktop `PreferencesDialog` → "Game - Hotkeys" tab, whose
 * "Reset to default" only ever restores keys that already exist as bindings).
 *
 * ── WHAT IS IN HERE (and what deliberately is not) ──
 *
 * Every action below is a shortcut the web client already ships today —
 * nothing is invented: the nine game actions are exactly the `switch (e.key)`
 * cases `ActionPromptBar.tsx` used to carry hard-coded (F2–F11, minus the two
 * deliberately unmapped keys — see below), and the four replay rows mirror
 * `ReplayView.replayHotkey()`, which stays the replay viewer's own source of
 * truth (this registry only DOCUMENTS them and reserves their keys, see
 * "scopes" below).
 *
 * F8 and F12 are NOT rows here and stay unmapped in the default map: per the
 * original rail comment, desktop F8 toggles macro recording and F12 switches
 * chat panels — neither has a web equivalent (single chat panel, no macro
 * recorder), so inventing web actions for them is exactly what the todo row
 * warns against. A user MAY still rebind an existing action onto F8/F12 — the
 * rule is "no invented shortcut rows", not "these keys are forbidden".
 *
 * ── TWO SCOPES ──
 *
 * `scope: 'game'`  — the priority rail's actions (rebindable).
 * `scope: 'replay'` — the replay viewer's keys (FIXED: their keys are shared
 *   with typing, open menus and the settings dialog through the viewer's
 *   keyboard hand-back — `replayHotkey()` — so rebinding them would rewrite
 *   that contract. They are listed so the Settings → Hotkeys tab shows the
 *   complete keyboard map and so the conflict detector knows Space / arrows /
 *   Escape are taken).
 *
 * ── CONFLICT RULE ──
 *
 * One key, one meaning — across BOTH scopes. Two rows on one key is refused
 * with a message naming the other action (`rebindKeybinding` returns
 * `{ ok: false, reason: 'conflict' }`), never silently accepted; the rejected
 * rebind leaves the map and localStorage untouched. Strict across scopes on
 * purpose: the settings tab renders ONE row per action, and a key appearing
 * twice in that table means one of its two meanings is invisible from the
 * view where the other fires.
 *
 * ── KEY SPECS ──
 *
 * A binding is a canonical KeySpec string: `'F5'`, `'G'` (single chars are
 * uppercased), `'Space'`, `'ArrowLeft'`, `'PageDown'`. Matching stays on
 * `event.key` alone with modifiers IGNORED — that preserves the shipped
 * behaviour exactly (Ctrl+F5 skipping a turn is today's behaviour, not a new
 * promise), and it is why capture REFUSES modifier combinations rather than
 * pretending `Shift+G` is a different binding from `G`. Escape / Enter / Tab /
 * Backspace and F13+ are not bindable (they are entangled with dialog and
 * focus semantics).
 *
 * ── SUSPENSION (the replay hand-back's game-side twin) ──
 *
 * `matchHotkeyAction()` resolves nothing while any guard in its
 * HotkeyGuardContext is set: a rebind capture is armed anywhere in the app
 * (`beginCapture`, entered by the Settings → Hotkeys row being rebound), the
 * settings dialog is open, the keydown target is a typing target
 * (`isTypingTarget` — the SAME predicate the replay viewer's hand-back uses;
 * it lives here so the rail and the viewer share one definition), or a
 * context menu is open. The rebind capture itself also installs a
 * capture-phase window listener that stops propagation, so a captured key
 * never reaches ANY document-level hotkey handler while the rebind is pending.
 *
 * ── PERSISTENCE + RESET ──
 *
 * Overrides persist as JSON under their OWN localStorage key
 * ('xmage-keybindings', deliberately not inside xmage-settings — the same
 * posture as the priority-ding blob) holding ONLY non-default entries, so a
 * future change to a default key flows through to everyone who never touched
 * it. Reset to Default reaches this module through the settings registry
 * (`settingsDefaults.ts` MODULE_RESETS → `resetKeybindings()`), which wipes
 * the key and the in-memory overrides. NOT covered by Settings Presets yet:
 * presets capture the registry arrays plus the two hand-written module blobs,
 * and the preset validator rejects a blob missing a registry key, so adding
 * keybindings there needs a preset-schema decision (version bump / tolerant
 * migration) rather than a silent extension.
 *
 * ── FAIL-FAST POSTURE ON THE STORED BLOB ──
 *
 * `parseStoredOverrides()` never throws: this module is loaded at app start
 * (the rail imports it), and throwing on a corrupt blob would brick the client
 * before the Settings dialog that could fix it ever renders. Instead every
 * problem — unparseable JSON, unknown action id, unusable key, a collision in
 * the saved map — is REFUSED (that entry falls back to its default; nothing is
 * silently accepted) and collected in `getKeybindingLoadProblems()`, which the
 * Hotkeys tab renders. `rebindKeybinding()` itself is stricter: an unknown
 * action id or an attempt to rebind a fixed row THROWS (programming error).
 */

/** Canonical form of a binding: 'F5', 'G', 'Space', 'ArrowLeft', 'PageDown'. */
export type KeySpec = string

/** Which keyboard surface an action belongs to. */
export type KeybindScope = 'game' | 'replay'

/**
 * THE registry. Order = the Settings → Hotkeys render order (default keys
 * ascending, F2 → F11, which is also the rail's shipped order).
 */
const ACTIONS = [
  {
    id: 'confirm',
    label: 'Confirm (yes / first choice)',
    defaultKey: 'F2',
    scope: 'game',
    rebindable: true,
    note: null,
  },
  {
    id: 'cancel-skips',
    label: 'Cancel All Skips',
    defaultKey: 'F3',
    scope: 'game',
    rebindable: true,
    note: null,
  },
  {
    id: 'skip-turn',
    label: 'Skip Turn',
    defaultKey: 'F4',
    scope: 'game',
    rebindable: true,
    note: null,
  },
  {
    id: 'skip-eot',
    label: 'Skip to End of Turn',
    defaultKey: 'F5',
    scope: 'game',
    rebindable: true,
    note: null,
  },
  {
    id: 'skip-turn-stack',
    label: 'Skip to Next Turn, Skip Stack',
    defaultKey: 'F6',
    scope: 'game',
    rebindable: true,
    note: null,
  },
  {
    id: 'skip-main',
    label: 'Skip to Next Main Phase',
    defaultKey: 'F7',
    scope: 'game',
    rebindable: true,
    note: null,
  },
  {
    id: 'skip-my-turn',
    label: 'Skip to My Next Turn',
    defaultKey: 'F9',
    scope: 'game',
    rebindable: true,
    note: null,
  },
  {
    id: 'skip-stack',
    label: 'Skip Stack',
    defaultKey: 'F10',
    scope: 'game',
    rebindable: true,
    note: null,
  },
  {
    id: 'skip-end-step',
    label: 'Skip to End Step Before My Turn',
    defaultKey: 'F11',
    scope: 'game',
    rebindable: true,
    note: null,
  },
  {
    id: 'replay-play',
    label: 'Play / Pause',
    defaultKey: 'Space',
    scope: 'replay',
    rebindable: false,
    note: 'Replay viewer — the key is handed back to text fields, open menus and the settings dialog (see ReplayView.replayHotkey()).',
  },
  {
    id: 'replay-next',
    label: 'Next Play',
    defaultKey: 'ArrowRight',
    scope: 'replay',
    rebindable: false,
    note: 'Replay viewer — the key is handed back to text fields, open menus and the settings dialog (see ReplayView.replayHotkey()).',
  },
  {
    id: 'replay-previous',
    label: 'Previous Play',
    defaultKey: 'ArrowLeft',
    scope: 'replay',
    rebindable: false,
    note: 'Replay viewer — the key is handed back to text fields, open menus and the settings dialog (see ReplayView.replayHotkey()).',
  },
  {
    id: 'replay-exit',
    label: 'Exit Replay',
    defaultKey: 'Escape',
    scope: 'replay',
    rebindable: false,
    note: 'Replay viewer — Escape also dismisses the settings dialog and an open context menu, so it is fixed there.',
  },
] as const

export type KeybindingActionId = (typeof ACTIONS)[number]['id']
export type KeybindingAction = (typeof ACTIONS)[number]

/** Every registered action, in render order. */
export const KEYBINDING_ACTIONS: readonly KeybindingAction[] = ACTIONS

/** Action ids of one scope, so a consumer's switch is compile-checked against it. */
export type KeybindingActionIdOf<S extends KeybindScope> = Extract<KeybindingAction, { scope: S }>['id']
export type GameKeybindingActionId = KeybindingActionIdOf<'game'>
export type ReplayKeybindingActionId = KeybindingActionIdOf<'replay'>

/** localStorage key for the overrides blob (own key, not inside xmage-settings). */
export const KEYBINDINGS_STORAGE_KEY = 'xmage-keybindings'

/** Bump only alongside a migration of the stored blob. */
export const KEYBINDINGS_VERSION = 1 as const

// ── Key normalisation ──

const FUNCTION_KEY_RE = /^f([1-9]|1[0-2])$/i

/**
 * Named keys the registry accepts, lower-cased key/`event.key` → canonical spec.
 * Escape / Enter / Tab / Backspace are deliberately absent: they belong to
 * dialog dismissal, form submission and focus movement.
 */
const NAMED_KEYS = new Map<string, KeySpec>([
  [' ', 'Space'],
  ['space', 'Space'],
  ['spacebar', 'Space'],
  ['arrowleft', 'ArrowLeft'],
  ['arrowright', 'ArrowRight'],
  ['arrowup', 'ArrowUp'],
  ['arrowdown', 'ArrowDown'],
  ['home', 'Home'],
  ['end', 'End'],
  ['pageup', 'PageUp'],
  ['pagedown', 'PageDown'],
  ['insert', 'Insert'],
  ['delete', 'Delete'],
])

/**
 * Modifier keys themselves — a capture that sees one of these keeps waiting
 * for the real key instead of reporting an error.
 */
export const MODIFIER_ONLY_KEYS: ReadonlySet<string> = new Set([
  'Shift',
  'Control',
  'Alt',
  'Meta',
  'AltGraph',
  'CapsLock',
  'NumLock',
  'ScrollLock',
  'ContextMenu',
  'OS',
])

/**
 * Canonicalise a raw key (`event.key` or a hand-written string) into a KeySpec,
 * or null when it cannot be a binding. Accepts F1–F12, single printable
 * characters (letters uppercased), Space and the named navigation keys above.
 */
export function normalizeKeySpec(raw: string): KeySpec | null {
  if (typeof raw !== 'string') return null
  if (raw === ' ') return 'Space'
  const trimmed = raw.trim()
  if (trimmed.length === 0) return null
  const fn = FUNCTION_KEY_RE.exec(trimmed)
  if (fn) return `F${Number(fn[1])}`
  const named = NAMED_KEYS.get(trimmed.toLowerCase())
  if (named !== undefined) return named
  if (trimmed.length === 1) {
    const ch = trimmed
    if (/^[a-z]$/i.test(ch)) return ch.toUpperCase()
    if (/^[!-~]$/.test(ch)) return ch // digits and punctuation
  }
  return null
}

/** The KeySpec a keydown event maps to, or null (Escape/Enter/… never bind). */
export function keySpecFromEvent(event: { key: string }): KeySpec | null {
  return normalizeKeySpec(event.key)
}

/** Display form of a binding — the one place a spec becomes visible text. */
export function formatKeySpec(spec: KeySpec): string {
  return spec === 'Space' ? 'Space' : spec
}

// ── Live state ──

type KeybindingOverrides = Partial<Record<KeybindingActionId, KeySpec>>

function actionById(id: KeybindingActionId): KeybindingAction {
  const found = ACTIONS.find((a) => a.id === id)
  if (!found) throw new Error(`keybindings: unknown action '${id}' — known actions are ${ACTIONS.map((a) => a.id).join(', ')}`)
  return found
}

function isKnownId(id: string): id is KeybindingActionId {
  return ACTIONS.some((a) => a.id === id)
}

function isPlainRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

/**
 * Parse a stored overrides blob. Never throws (see the module header's
 * FAIL-FAST POSTURE): every accepted entry must be a known rebindable action
 * with a usable key that collides with nothing else in the map; anything else
 * is refused with a problem line and that action stays on its default.
 */
export function parseStoredOverrides(raw: string | null): {
  overrides: KeybindingOverrides
  problems: string[]
} {
  const problems: string[] = []
  if (raw === null || raw.trim() === '') return { overrides: {}, problems }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    return { overrides: {}, problems: ['saved shortcuts are not valid JSON — the default keys are in use'] }
  }
  if (!isPlainRecord(parsed)) {
    return { overrides: {}, problems: ['saved shortcuts are not an object — the default keys are in use'] }
  }
  if (parsed.version !== KEYBINDINGS_VERSION) {
    return {
      overrides: {},
      problems: [`saved shortcuts have unsupported version ${String(parsed.version)} — the default keys are in use`],
    }
  }
  const source = parsed.overrides
  if (!isPlainRecord(source)) {
    return { overrides: {}, problems: ['saved shortcuts carry no overrides object — the default keys are in use'] }
  }
  const accepted: KeybindingOverrides = {}
  // The map as it is resolved, defaults included: each accepted entry is
  // checked against everything already in it, so a saved collision is refused
  // instead of being applied as a double binding.
  const merged: Record<KeybindingActionId, KeySpec> = {} as Record<KeybindingActionId, KeySpec>
  for (const a of ACTIONS) merged[a.id] = a.defaultKey
  for (const [id, value] of Object.entries(source)) {
    if (!isKnownId(id)) {
      problems.push(`'${id}' is not a known shortcut — skipped`)
      continue
    }
    const action = actionById(id)
    if (!action.rebindable) {
      problems.push(`'${action.label}' is a fixed replay key and cannot be rebound — skipped`)
      continue
    }
    if (typeof value !== 'string') {
      problems.push(`the saved key for '${action.label}' is not text — skipped`)
      continue
    }
    const spec = normalizeKeySpec(value)
    if (spec === null) {
      problems.push(`'${value}' is not a usable key for '${action.label}' — skipped`)
      continue
    }
    if (spec === action.defaultKey) continue // a default written back is not an override
    const clash = ACTIONS.find((a) => a.id !== id && merged[a.id] === spec)
    if (clash) {
      problems.push(
        `${formatKeySpec(spec)} is saved for both '${action.label}' and '${clash.label}' — '${action.label}' stays on its default`,
      )
      continue
    }
    merged[id] = spec
    accepted[id] = spec
  }
  return { overrides: accepted, problems }
}

function loadStoredOverrides(): { overrides: KeybindingOverrides; problems: string[] } {
  let raw: string | null = null
  try {
    raw = localStorage.getItem(KEYBINDINGS_STORAGE_KEY)
  } catch {
    // localStorage unavailable (Node harness / privacy mode): defaults.
    return { overrides: {}, problems: [] }
  }
  return parseStoredOverrides(raw)
}

const initial = loadStoredOverrides()

let overrides: KeybindingOverrides = initial.overrides
let loadProblems: string[] = initial.problems

if (loadProblems.length > 0) {
  // Visible, not silent: the Hotkeys tab renders these too.
  console.error(`[keybindings] ${loadProblems.length} saved shortcut problem(s):`, loadProblems.join(' | '))
}

/** Problems found in the stored blob at load (empty when it was clean). */
export function getKeybindingLoadProblems(): readonly string[] {
  return loadProblems
}

// ── Change notification (the tokenArt pattern: one primitive revision) ──

const listeners = new Set<() => void>()
let revision = 0

/** Bumped on every change so `useSyncExternalStore` can detect one. */
export function keybindingsRevision(): number {
  return revision
}

/** Subscribe to binding changes. Returns an unsubscribe function. */
export function subscribeKeybindings(fn: () => void): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

function notify(): void {
  revision++
  for (const l of listeners) {
    try {
      l()
    } catch {
      /* a listener must not break the others */
    }
  }
}

// ── Capture suspension ──

/**
 * Token of the pending rebind capture, or null when none is armed. A token
 * (rather than a depth counter) makes release idempotent: StrictMode's
 * mount/cleanup/mount cycle, a toggle-off click and a dialog close can all
 * call `endCapture` with the same token without ever releasing a capture that
 * a later one owns.
 */
let captureToken: number | null = null
let nextCaptureToken = 1

/**
 * Arm a rebind capture: app hotkeys stand down until `endCapture(token)`.
 * Returns the token to release with. Arming again supersedes the previous
 * capture (only the Settings → Hotkeys row ever arms one).
 */
export function beginCapture(): number {
  const token = nextCaptureToken++
  captureToken = token
  return token
}

/** Release a pending rebind capture. Idempotent; a stale token is a no-op. */
export function endCapture(token: number): void {
  if (captureToken === token) captureToken = null
}

/** True while a rebind capture is armed anywhere in the app. */
export function isCaptureActive(): boolean {
  return captureToken !== null
}

/** What one captured keydown means to the armed rebind row. */
export type CaptureDecision =
  | { kind: 'ignore' } // a modifier alone — keep waiting for the real key
  | { kind: 'cancel' } // Escape — abandon the capture, keep the current binding
  | { kind: 'invalid'; raw: string } // usable-looking but not bindable — report it
  | { kind: 'bind'; key: KeySpec }

/**
 * Decide a captured keydown (pure — the DOM listener that calls it lives in
 * SettingsDialog). Escape cancels, modifier-only keys are ignored, everything
 * else either normalises to a KeySpec or is reported as unusable; the captured
 * row then runs it through `rebindKeybinding`, so a key another action owns is
 * refused with a conflict message rather than silently accepted.
 */
export function captureKeyDecision(rawKey: string): CaptureDecision {
  if (rawKey === 'Escape') return { kind: 'cancel' }
  if (MODIFIER_ONLY_KEYS.has(rawKey)) return { kind: 'ignore' }
  const spec = normalizeKeySpec(rawKey)
  if (spec === null) return { kind: 'invalid', raw: rawKey }
  return { kind: 'bind', key: spec }
}

// ── Resolution ──

/**
 * The full binding map: defaults with the user's overrides applied. Allocates —
 * callers that need it per render should read it once per render, not per row.
 */
export function getBindings(): Readonly<Record<KeybindingActionId, KeySpec>> {
  const out = {} as Record<KeybindingActionId, KeySpec>
  for (const a of ACTIONS) out[a.id] = overrides[a.id] ?? a.defaultKey
  return out
}

/** The key currently bound to one action. */
export function keyFor(id: KeybindingActionId): KeySpec {
  return overrides[id] ?? actionById(id).defaultKey
}

/** True when the action's key differs from its default (drives the row Reset). */
export function isKeybindingModified(id: KeybindingActionId): boolean {
  return overrides[id] !== undefined
}

/** The action's shipped default key. */
export function defaultKeyFor(id: KeybindingActionId): KeySpec {
  return actionById(id).defaultKey
}

/** Actions of one scope, in registry (= render) order. */
export function actionsInScope(scope: KeybindScope): readonly KeybindingAction[] {
  return ACTIONS.filter((a) => a.scope === scope)
}

/** The action (if any) already using `key`, ignoring `exceptId`. */
export function findConflict(key: KeySpec, exceptId: KeybindingActionId | null): KeybindingAction | null {
  const bindings = getBindings()
  for (const a of ACTIONS) {
    if (a.id === exceptId) continue
    if (bindings[a.id] === key) return a
  }
  return null
}

// ── Guard context + matching ──

/**
 * Everything that makes the app stand down for a keypress. Kept as plain data
 * so the harness can pin each guard without a DOM.
 */
export interface HotkeyGuardContext {
  /** The keydown target is an input/textarea/select/contenteditable. */
  typing: boolean
  /** A context menu is open (its own Escape handling owns the keyboard). */
  contextMenuOpen: boolean
  /** The settings dialog is up — it owns the keyboard (Escape closes it). */
  settingsOpen: boolean
  /** The replay viewer is up. It has its own keys (ReplayView.replayHotkey);
   *  the rail's game hotkeys stand down exactly as the prompt surfaces do. */
  replayOpen: boolean
  /** A rebind capture is armed. */
  capturing: boolean
}

/** Why hotkeys are suspended right now, or null when they are live. */
export type HotkeySuspension = 'capture' | 'settings' | 'replay' | 'typing' | 'menu'

export function hotkeySuspensionReason(ctx: HotkeyGuardContext): HotkeySuspension | null {
  if (ctx.capturing) return 'capture'
  if (ctx.settingsOpen) return 'settings'
  if (ctx.replayOpen) return 'replay'
  if (ctx.typing) return 'typing'
  if (ctx.contextMenuOpen) return 'menu'
  return null
}

/**
 * The action a keypress means in `scope`, or null when it means nothing here
 * (unbound key, another scope's key, or any guard in `ctx` is set).
 *
 * Modifiers are deliberately ignored: matching is on `event.key` alone, which
 * is the shipped behaviour (see the module header).
 */
export function matchHotkeyAction(
  event: { key: string },
  ctx: HotkeyGuardContext,
  scope: 'game',
): GameKeybindingActionId | null
export function matchHotkeyAction(
  event: { key: string },
  ctx: HotkeyGuardContext,
  scope: 'replay',
): ReplayKeybindingActionId | null
export function matchHotkeyAction(
  event: { key: string },
  ctx: HotkeyGuardContext,
  scope: KeybindScope,
): KeybindingActionId | null {
  if (hotkeySuspensionReason(ctx) !== null) return null
  const spec = keySpecFromEvent(event)
  if (spec === null) return null
  const bindings = getBindings()
  for (const a of ACTIONS) {
    if (a.scope !== scope) continue
    if (bindings[a.id] === spec) return a.id
  }
  return null
}

// ── Mutation ──

export type RebindResult =
  | { ok: true; key: KeySpec }
  | { ok: false; reason: 'invalid-key'; raw: string }
  | { ok: false; reason: 'conflict'; conflict: KeybindingAction }

function persist(): void {
  try {
    if (Object.keys(overrides).length === 0) localStorage.removeItem(KEYBINDINGS_STORAGE_KEY)
    else localStorage.setItem(KEYBINDINGS_STORAGE_KEY, JSON.stringify({ version: KEYBINDINGS_VERSION, overrides }))
  } catch {
    /* localStorage unavailable (Node harness / privacy mode) — overrides stay live in memory */
  }
}

/**
 * Bind `id` to `raw` (any form `normalizeKeySpec` accepts). Refuses — without
 * touching the map — a key that cannot be used and a key another action
 * already owns; `ok: true` means the map and the stored blob both changed.
 */
export function rebindKeybinding(id: KeybindingActionId, raw: string): RebindResult {
  const action = actionById(id)
  if (!action.rebindable) {
    throw new Error(`keybindings: '${id}' (${action.label}) is a fixed ${action.scope} key and cannot be rebound`)
  }
  const spec = normalizeKeySpec(raw)
  if (spec === null) return { ok: false, reason: 'invalid-key', raw }
  const conflict = findConflict(spec, id)
  if (conflict) return { ok: false, reason: 'conflict', conflict }
  if (spec === action.defaultKey) delete overrides[id]
  else overrides[id] = spec
  persist()
  notify()
  return { ok: true, key: spec }
}

/**
 * Restore ONE action's default key. Conflict-checked like a rebind: a default
 * the user handed to another action in the meantime refuses instead of
 * creating a double binding (Reset to Default clears everything at once).
 */
export function resetKeybinding(id: KeybindingActionId): RebindResult {
  const action = actionById(id)
  if (!action.rebindable) {
    throw new Error(`keybindings: '${id}' (${action.label}) is a fixed ${action.scope} key and cannot be reset`)
  }
  const conflict = findConflict(action.defaultKey, id)
  if (conflict) return { ok: false, reason: 'conflict', conflict }
  delete overrides[id]
  persist()
  notify()
  return { ok: true, key: action.defaultKey }
}

/**
 * Wipe every override back to the default key map and clear the stored blob —
 * the settings registry's integration point (`settingsDefaults.ts`
 * MODULE_RESETS) behind the dialog's "Reset to Default".
 */
export function resetKeybindings(): void {
  overrides = {}
  loadProblems = []
  try {
    localStorage.removeItem(KEYBINDINGS_STORAGE_KEY)
  } catch {
    /* localStorage unavailable */
  }
  notify()
}

// ── Typing-target predicate ──
//
// Canonical home of `isTypingTarget`, moved here from ReplayView.tsx (which
// re-exports it unchanged) so the rail's hotkey guard and the replay viewer's
// keyboard hand-back share ONE definition: a rebind capture, an app hotkey and
// the viewer's Space/arrow keys must all agree on where a keypress is "typing".

/**
 * True when a keypress is going into a text field — those keys belong to the
 * field. Takes the event target; tagName-based, so it works on any
 * element-like object and is pinned by the harnesses.
 */
export function isTypingTarget(target: { tagName?: string; isContentEditable?: boolean } | null): boolean {
  if (!target || typeof target.tagName !== 'string') return false
  return (
    target.tagName === 'INPUT' ||
    target.tagName === 'TEXTAREA' ||
    target.tagName === 'SELECT' ||
    target.isContentEditable === true
  )
}
