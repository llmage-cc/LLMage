/**
 * Settings presets — named snapshots of every setting the Settings dialog can
 * change, saved under their own localStorage key (`xmage-settings-presets`).
 *
 * Java reference: the desktop client keeps named size presets in
 * PreferencesDialog (PreferencesDialog.SizeSettingsPresets). The web client's
 * preset is broader on purpose: it captures EXACTLY the surface "Reset to
 * Default" covers, so a preset can never miss a setting —
 *
 *   - the store-backed settings (DEFAULT_SETTINGS / STORE_SETTING_FIELDS),
 *   - the standalone localStorage keys (STANDALONE_LOCALSTORAGE_DEFAULTS),
 *   - the two module-owned blobs (priority ding, token art).
 *
 * Capture iterates the REGISTRIES from utils/settingsDefaults.ts, never a
 * hand-written list: a setting that joins the registry is picked up by the next
 * Save automatically, and STORE_SETTER_OF is an exhaustive mapped type, so a
 * field with no applier is a TYPECHECK error instead of a preset that silently
 * skips a key on Load.
 *
 * Apply writes back through the same appliers/setters the reset path uses and
 * ends with `markSettingsReset()`, the mirror-refresh step documented in
 * settingsDefaults.ts: GameView/GameLayout re-read the standalone keys on the
 * settingsResetAt bump, and the store's priority-ding mirror is re-read from
 * the module.
 *
 * Fail Fast: a corrupt stored blob THROWS and is surfaced by the dialog — it is
 * never silently reset to "no presets". Parsing is strict in the same spirit: a
 * preset missing any registry key, or holding a value of the wrong type, is
 * rejected instead of partially applied. When localStorage is unavailable
 * (privacy mode) reads return "no presets" and writes are dropped — the same
 * posture as utils/popupSize.ts — because a lost preset must not break the
 * dialog.
 */
import { DEFAULT_SETTINGS, STORE_SETTING_FIELDS, STANDALONE_LOCALSTORAGE_DEFAULTS } from '@/utils/settingsDefaults'
import type { ResettableStore } from '@/utils/settingsDefaults'
import {
  getDingSettings,
  setDingSettings,
  PRIORITY_DING_DEFAULTS,
  type DingFocusMode,
  type PriorityDingSettings,
} from '@/store/slices/priorityDing'
import { isSoundVariant } from '@/audio/dingSynth'
import { isPickableAvatarId } from '@/utils/avatars'
import {
  exportTokenArtPrefs,
  importTokenArtPrefs,
  type TokenArtSnapshot,
} from '@/utils/tokenArt'

/** The localStorage key that holds every preset. */
export const SETTINGS_PRESETS_KEY = 'xmage-settings-presets'

/** The preset-file schema version. Bump only alongside a migration. */
export const SETTINGS_PRESET_VERSION = 1 as const

export type StoreSettingField = (typeof STORE_SETTING_FIELDS)[number]

/** Every value shape a store-backed setting can hold. */
export type SettingValue = boolean | number | string | { r: number; g: number; b: number }

/**
 * The store surface a preset needs: the value fields it captures, the setters
 * it applies through, and the mirror-refresh action. `GameStore` satisfies it.
 */
export interface PresetStore extends ResettableStore {
  markSettingsReset: () => void
}

/** One captured preset, as stored. */
export interface SettingsPreset {
  version: typeof SETTINGS_PRESET_VERSION
  name: string
  /** Date.now() at capture — the list is shown newest-first. */
  savedAt: number
  settings: Record<StoreSettingField, SettingValue>
  /** Raw values of the standalone localStorage keys (null = absent). */
  standalone: Record<string, string | null>
  ding: PriorityDingSettings
  tokenArt: TokenArtSnapshot
}

/** List row for the dialog: enough to render and act, without the payload. */
export interface SettingsPresetSummary {
  name: string
  savedAt: number
}

/**
 * Setter method name for every store-backed setting field. Exhaustive by
 * construction: adding a field to STORE_SETTING_FIELDS without a line here is
 * a compile error, which is what keeps presets and Reset to Default in step.
 */
const STORE_SETTER_OF: { [K in StoreSettingField]: keyof ResettableStore } = {
  // Board modes.
  boardModeMaximizeOpponents: 'setBoardModeMaximizeOpponents',
  boardModeFocusEnabled: 'setBoardModeFocusEnabled',
  boardModeFollowActive: 'setBoardModeFollowActive',
  autoScrollLog: 'setAutoScrollLog',
  autoScrollChat: 'setAutoScrollChat',
  autoScrollLLM: 'setAutoScrollLLM',
  replayLLMFullHistory: 'setReplayLLMFullHistory',
  fontScale: 'setFontScale',
  cardScale: 'setCardScale',
  uiScale: 'setUiScale',
  theme: 'setTheme',
  cardPreviewScale: 'setCardPreviewScale',
  autoFitBattlefield: 'setAutoFitBattlefield',
  highlightPlayable: 'setHighlightPlayable',
  highlightCombat: 'setHighlightCombat',
  highlightTarget: 'setHighlightTarget',
  highlightManaTappable: 'setHighlightManaTappable',
  highlightActiveTurn: 'setHighlightActiveTurn',
  // Audio — keep appending, never reorder.
  audioEnabled: 'setAudioEnabled',
  audioVolume: 'setAudioVolume',
  // Avatar — appended after the settings above.
  avatarId: 'setAvatarId',
}

const DING_FOCUS_MODES: readonly DingFocusMode[] = ['unfocused', 'always', 'muted-unfocused']

function isPlainRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

function cloneValue(v: SettingValue): SettingValue {
  return typeof v === 'object' && v !== null ? { r: v.r, g: v.g, b: v.b } : v
}

// ── Capture ──

/** Read every store-backed setting from live store state, deep-copied. */
function captureStoreSettings(store: PresetStore): Record<StoreSettingField, SettingValue> {
  const src = store as unknown as Record<string, unknown>
  const out = {} as Record<StoreSettingField, SettingValue>
  for (const field of STORE_SETTING_FIELDS) {
    const value = src[field]
    if (value === undefined) throw new Error(`settings preset: the store has no value for "${field}"`)
    out[field] = cloneValue(value as SettingValue)
  }
  return out
}

/** Read the standalone keys verbatim (absent key and unavailable storage both read null). */
function captureStandalone(): Record<string, string | null> {
  const out: Record<string, string | null> = {}
  for (const { key } of STANDALONE_LOCALSTORAGE_DEFAULTS) {
    let value: string | null = null
    try {
      value = localStorage.getItem(key)
    } catch {
      value = null
    }
    out[key] = value
  }
  return out
}

/**
 * Snapshot every registered setting. Exported so the harness can compare a
 * capture against the registries; the dialog saves through savePreset().
 */
export function capturePreset(name: string, store: PresetStore): SettingsPreset {
  return {
    version: SETTINGS_PRESET_VERSION,
    name,
    savedAt: Date.now(),
    settings: captureStoreSettings(store),
    standalone: captureStandalone(),
    // Module blobs read through their OWN accessors — the store's priorityDing
    // field is only a mirror, and token art lives entirely outside the store.
    ding: { ...getDingSettings() },
    tokenArt: exportTokenArtPrefs(),
  }
}

// ── Validation (Fail Fast on anything malformed) ──

function valueMatchesDefault(field: StoreSettingField, v: unknown): boolean {
  const fallback = DEFAULT_SETTINGS[field]
  if (typeof fallback === 'object') {
    if (!isPlainRecord(v)) return false
    return typeof v.r === 'number' && typeof v.g === 'number' && typeof v.b === 'number'
  }
  if (typeof v !== typeof fallback) return false
  if (field === 'theme') return v === 'dark' || v === 'light'
  // Avatar: a stored preset may only carry an id the picker can render —
  // same fail-fast posture as the theme enum above (a preset with an
  // unrenderable avatar is skew, refused rather than partially applied).
  if (field === 'avatarId') return typeof v === 'number' && isPickableAvatarId(v)
  if (typeof fallback === 'number') return Number.isFinite(v as number)
  return true
}

function validateSettings(v: unknown): Record<StoreSettingField, SettingValue> {
  if (!isPlainRecord(v)) throw new Error('settings preset: "settings" is not an object')
  const out = {} as Record<StoreSettingField, SettingValue>
  for (const field of STORE_SETTING_FIELDS) {
    const value = v[field]
    if (!valueMatchesDefault(field, value)) {
      throw new Error(`settings preset: "${field}" is missing or has the wrong type`)
    }
    out[field] = cloneValue(value as SettingValue)
  }
  for (const key of Object.keys(v)) {
    if (!(STORE_SETTING_FIELDS as readonly string[]).includes(key)) {
      throw new Error(`settings preset: unknown setting "${key}" (registry skew — refusing to load)`)
    }
  }
  return out
}

function validateStandalone(v: unknown): Record<string, string | null> {
  if (!isPlainRecord(v)) throw new Error('settings preset: "standalone" is not an object')
  const out: Record<string, string | null> = {}
  for (const { key } of STANDALONE_LOCALSTORAGE_DEFAULTS) {
    const value = v[key]
    if (value !== null && typeof value !== 'string') {
      throw new Error(`settings preset: standalone key "${key}" is missing or not a string/null`)
    }
    out[key] = value
  }
  return out
}

function validateDing(v: unknown): PriorityDingSettings {
  if (!isPlainRecord(v)) throw new Error('settings preset: "ding" is not an object')
  // Registry-driven shape check: every key of PRIORITY_DING_DEFAULTS must be
  // present with the same primitive TYPE as the default (so a field added to
  // the ding settings module is required here automatically instead of being
  // silently dropped by a hand-maintained list), plus the two enum fields.
  // Type-skew (a field changing between boolean/number/string) is a deliberate
  // version bump, not something this validator guesses at — it throws, loudly.
  const shape = PRIORITY_DING_DEFAULTS as unknown as Record<string, unknown>
  const out: Record<string, unknown> = {}
  for (const key of Object.keys(shape)) {
    const value = v[key]
    if (typeof value !== typeof shape[key]) {
      throw new Error(`settings preset: ding field "${key}" is missing or has the wrong type`)
    }
    out[key] = value
  }
  for (const key of Object.keys(v)) {
    if (!(key in shape)) {
      throw new Error(`settings preset: unknown ding setting "${key}" (registry skew — refusing to load)`)
    }
  }
  const focusModeOk =
    typeof out.focusMode === 'string' && (DING_FOCUS_MODES as readonly string[]).includes(out.focusMode)
  if (!focusModeOk || !isSoundVariant(out.variant)) {
    throw new Error('settings preset: the priority-ding block is malformed')
  }
  return out as unknown as PriorityDingSettings
}

function validateTokenArt(v: unknown): TokenArtSnapshot {
  if (!isPlainRecord(v)) throw new Error('settings preset: "tokenArt" is not an object')
  if (!isPlainRecord(v.own) || !isPlainRecord(v.shared) || typeof v.overrideOpponent !== 'boolean') {
    throw new Error('settings preset: the token-art block is malformed')
  }
  // The deep per-entry check (every url is a string) lives in importTokenArtPrefs,
  // which applyPreset runs — validation here only proves the block's shape.
  return v as unknown as TokenArtSnapshot
}

function validatePreset(name: string, v: unknown): SettingsPreset {
  if (!isPlainRecord(v)) throw new Error(`settings preset "${name}": not an object`)
  if (v.version !== SETTINGS_PRESET_VERSION) {
    throw new Error(`settings preset "${name}": unsupported version ${String(v.version)}`)
  }
  if (v.name !== name) throw new Error(`settings preset "${name}": stored under a different name`)
  if (typeof v.savedAt !== 'number') throw new Error(`settings preset "${name}": savedAt is not a number`)
  return {
    version: SETTINGS_PRESET_VERSION,
    name,
    savedAt: v.savedAt,
    settings: validateSettings(v.settings),
    standalone: validateStandalone(v.standalone),
    ding: validateDing(v.ding),
    tokenArt: validateTokenArt(v.tokenArt),
  }
}

// ── Storage ──
// Reads: unavailable storage = "no presets"; CORRUPT content THROWS.
// Writes: unavailable storage drops the write (a lost preset must not crash).

function readAllPresets(): Record<string, SettingsPreset> {
  let raw: string | null = null
  try {
    raw = localStorage.getItem(SETTINGS_PRESETS_KEY)
  } catch {
    return {}
  }
  if (raw == null) return {}
  const parsed: unknown = JSON.parse(raw) // corrupt JSON throws — surfaced, never silently reset
  if (!isPlainRecord(parsed)) throw new Error(`${SETTINGS_PRESETS_KEY}: stored value is not an object`)
  const out: Record<string, SettingsPreset> = {}
  for (const [name, value] of Object.entries(parsed)) out[name] = validatePreset(name, value)
  return out
}

function writeAllPresets(presets: Record<string, SettingsPreset>): void {
  try {
    localStorage.setItem(SETTINGS_PRESETS_KEY, JSON.stringify(presets))
  } catch {
    /* localStorage unavailable/quota — see the module header's posture note */
  }
}

// ── Public API ──

/** Every saved preset, newest first. Throws when the stored blob is corrupt. */
export function listPresets(): SettingsPresetSummary[] {
  const all = readAllPresets()
  return Object.values(all)
    .map(({ name, savedAt }) => ({ name, savedAt }))
    .sort((a, b) => b.savedAt - a.savedAt || a.name.localeCompare(b.name))
}

/** Capture the current settings under `name` (overwrites an existing name). */
export function savePreset(name: string, store: PresetStore): SettingsPresetSummary {
  const trimmed = name.trim()
  if (!trimmed) throw new Error('settings preset: a name is required')
  const preset = capturePreset(trimmed, store)
  const all = readAllPresets()
  all[trimmed] = preset
  writeAllPresets(all)
  return { name: preset.name, savedAt: preset.savedAt }
}

/**
 * Apply a captured preset through the same setters the reset path uses, then
 * bump the mirror-refresh trigger (see the module header).
 */
export function applyPreset(store: PresetStore, preset: SettingsPreset): void {
  for (const field of STORE_SETTING_FIELDS) {
    const setter = store[STORE_SETTER_OF[field]] as unknown as (v: SettingValue) => void
    setter(preset.settings[field])
  }
  setDingSettings({ ...preset.ding })
  importTokenArtPrefs(preset.tokenArt)
  try {
    for (const { key } of STANDALONE_LOCALSTORAGE_DEFAULTS) {
      const value = preset.standalone[key] ?? null
      if (value === null) localStorage.removeItem(key)
      else localStorage.setItem(key, value)
    }
  } catch {
    /* localStorage unavailable */
  }
  store.markSettingsReset()
}

/** Load a named preset into the store. Throws when it does not exist. */
export function loadPreset(name: string, store: PresetStore): void {
  const preset = readAllPresets()[name]
  if (!preset) throw new Error(`settings preset "${name}" not found`)
  applyPreset(store, preset)
}

/** Delete a preset. Returns false when the name was not saved. */
export function deletePreset(name: string): boolean {
  const all = readAllPresets()
  if (!(name in all)) return false
  delete all[name]
  writeAllPresets(all)
  return true
}
