/**
 * Settings reset registry — the SINGLE list of every user-adjustable setting
 * with its default value. "Reset to Default" in SettingsDialog applies all of
 * them in one click.
 *
 * HOW TO EXTEND (one line per setting):
 *   1. A store-backed setting: add the field to DEFAULT_SETTINGS here, add it
 *      to the store slice's DEFAULTS/persist (uiSlice for xmage-settings), and
 *      add one applier line in STORE_APPLIERS below.
 *   2. A standalone localStorage setting (no store field): add an entry to
 *      STANDALONE_LOCALSTORAGE_DEFAULTS — the key and its default string.
 *      Reset removes the key entirely, which is the same as "never changed".
 *   3. A setting owned by another module with its own reset function (the
 *      priority-ding blob): add a call in MODULE_RESETS below.
 */
import { resetDingSettings } from '@/store/slices/priorityDing'
import { resetTokenArtPrefs } from '@/utils/tokenArt'
import { resetKeybindings } from '@/utils/keybindings'
import { DEFAULT_AVATAR_ID } from '@/utils/avatars'

/** The xmage-settings object defaults — mirrors uiSlice's DEFAULTS (single source here). */
export const DEFAULT_SETTINGS = {
  // Board modes: maximize / focus / follow-active, all OFF by default.
  boardModeMaximizeOpponents: false,
  boardModeFocusEnabled: false,
  boardModeFollowActive: false,
  autoScrollLog: true,
  autoScrollChat: true,
  autoScrollLLM: true,
  /** Replay LLM tab: show the conversation up to the current frame. */
  replayLLMFullHistory: true,
  fontScale: 1.0,
  cardScale: 1.0,
  uiScale: 1.35,
  theme: 'dark' as 'dark' | 'light',
  cardPreviewScale: 1.0,
  autoFitBattlefield: true,
  highlightPlayable: { r: 39, g: 174, b: 96 },
  highlightCombat: { r: 255, g: 215, b: 0 },
  highlightTarget: { r: 255, g: 50, b: 50 },
  highlightManaTappable: { r: 0, g: 180, b: 255 },
  highlightActiveTurn: { r: 59, g: 130, b: 246 },
  /**
   * Event sounds: master enable for the WAV event-sound layer
   * (audio/audioManager.ts). The priority ding keeps its OWN enable switch —
   * this one governs the game/lobby event sounds only.
   */
  audioEnabled: true,
  /**
   * Event-sound master volume 0..1 (per-sound trim applied on top).
   */
  audioVolume: 0.6,
  /**
   * My avatar — the desktop's "My Avatar" choice
   * (PreferencesDialog KEY_AVATAR). Mirrors `Constants.DEFAULT_AVATAR_ID` (10);
   * the selectable range is AVATAR_IDS in utils/avatars.ts, mirroring
   * Constants.MIN/MAX_AVATAR_ID (10…32). Reset to Default restores 10.
   */
  avatarId: DEFAULT_AVATAR_ID,
}

/** Fields of DEFAULT_SETTINGS that live in the store (UIState). */
export const STORE_SETTING_FIELDS = [
  // Board modes — three toggles, one unit.
  'boardModeMaximizeOpponents',
  'boardModeFocusEnabled',
  'boardModeFollowActive',
  'autoScrollLog',
  'autoScrollChat',
  'autoScrollLLM',
  'replayLLMFullHistory',
  'fontScale',
  'cardScale',
  'uiScale',
  'theme',
  'cardPreviewScale',
  'autoFitBattlefield',
  'highlightPlayable',
  'highlightCombat',
  'highlightTarget',
  'highlightManaTappable',
  'highlightActiveTurn',
  // Audio — appended after the settings above; keep appending here, never reorder.
  'audioEnabled',
  'audioVolume',
  // Avatar — appended after the settings above.
  'avatarId',
] as const

/** localStorage keys held OUTSIDE the xmage-settings object, with defaults. */
export const STANDALONE_LOCALSTORAGE_DEFAULTS: ReadonlyArray<{ key: string; value: string | null }> = [
  { key: 'xmage-left-col-w', value: null }, // GameView left rail width — null = remove (falls back to code default)
  { key: 'xmage-right-col-w', value: null }, // GameView right rail width
  { key: 'xmage-battlefield-split', value: null }, // GameView battlefield divider % (0-100)
  { key: 'xmage-token-art-own', value: null }, // Token art: my preferred art per token type
  { key: 'xmage-token-art-shared', value: null }, // Token art: choices relayed from the table
  { key: 'xmage-token-art-override', value: null }, // Token art: "always use my art" flag ('1')
]

/** Minimal shape of the store surface resetSettings needs (subset of GameStore). */
export interface ResettableStore {
  setBoardModeMaximizeOpponents: (v: boolean) => void
  setBoardModeFocusEnabled: (v: boolean) => void
  setBoardModeFollowActive: (v: boolean) => void
  setAutoScrollLog: (v: boolean) => void
  setAutoScrollChat: (v: boolean) => void
  setAutoScrollLLM: (v: boolean) => void
  setReplayLLMFullHistory: (v: boolean) => void
  setFontScale: (v: number) => void
  setCardScale: (v: number) => void
  setUiScale: (v: number) => void
  setTheme: (t: 'dark' | 'light') => void
  setCardPreviewScale: (v: number) => void
  setAutoFitBattlefield: (v: boolean) => void
  setHighlightPlayable: (c: { r: number; g: number; b: number }) => void
  setHighlightCombat: (c: { r: number; g: number; b: number }) => void
  setHighlightTarget: (c: { r: number; g: number; b: number }) => void
  setHighlightManaTappable: (c: { r: number; g: number; b: number }) => void
  setHighlightActiveTurn: (c: { r: number; g: number; b: number }) => void
  // Audio — additive (same rule as the lists above).
  setAudioEnabled: (v: boolean) => void
  setAudioVolume: (v: number) => void
  // Avatar — appended after the settings above.
  setAvatarId: (id: number) => void
}

/**
 * One applier per store-backed setting, fully typed — each calls the field's
 * own setter (which also re-persists xmage-settings). Add a line here when a
 * setting joins DEFAULT_SETTINGS/STORE_SETTING_FIELDS.
 */
const STORE_APPLIERS: ReadonlyArray<(store: ResettableStore) => void> = [
  // Board modes — appended at the FRONT list position but via their
  // own setters; keep appending new settings, never reorder.
  (s) => s.setBoardModeMaximizeOpponents(DEFAULT_SETTINGS.boardModeMaximizeOpponents),
  (s) => s.setBoardModeFocusEnabled(DEFAULT_SETTINGS.boardModeFocusEnabled),
  (s) => s.setBoardModeFollowActive(DEFAULT_SETTINGS.boardModeFollowActive),
  (s) => s.setAutoScrollLog(DEFAULT_SETTINGS.autoScrollLog),
  (s) => s.setAutoScrollChat(DEFAULT_SETTINGS.autoScrollChat),
  (s) => s.setAutoScrollLLM(DEFAULT_SETTINGS.autoScrollLLM),
  (s) => s.setReplayLLMFullHistory(DEFAULT_SETTINGS.replayLLMFullHistory),
  (s) => s.setFontScale(DEFAULT_SETTINGS.fontScale),
  (s) => s.setCardScale(DEFAULT_SETTINGS.cardScale),
  (s) => s.setUiScale(DEFAULT_SETTINGS.uiScale),
  (s) => s.setTheme(DEFAULT_SETTINGS.theme),
  (s) => s.setCardPreviewScale(DEFAULT_SETTINGS.cardPreviewScale),
  (s) => s.setAutoFitBattlefield(DEFAULT_SETTINGS.autoFitBattlefield),
  (s) => s.setHighlightPlayable(DEFAULT_SETTINGS.highlightPlayable),
  (s) => s.setHighlightCombat(DEFAULT_SETTINGS.highlightCombat),
  (s) => s.setHighlightTarget(DEFAULT_SETTINGS.highlightTarget),
  (s) => s.setHighlightManaTappable(DEFAULT_SETTINGS.highlightManaTappable),
  (s) => s.setHighlightActiveTurn(DEFAULT_SETTINGS.highlightActiveTurn),
  // Audio — keep appending here, never reorder.
  (s) => s.setAudioEnabled(DEFAULT_SETTINGS.audioEnabled),
  (s) => s.setAudioVolume(DEFAULT_SETTINGS.audioVolume),
  // Avatar — appended after the settings above. Goes through the normal setter, so a
  // reset with a live session pushes the default avatar to the running game
  // (the desktop pushes on every preferences change, reset included).
  (s) => s.setAvatarId(DEFAULT_SETTINGS.avatarId),
]

/**
 * Module-owned settings with their own reset functions. The priority ding
 * persists under its own localStorage key ('xmage-priority-ding', deliberately
 * NOT inside xmage-settings) — resetDingSettings wipes it back to
 * PRIORITY_DING_DEFAULTS and is the module's single integration point.
 *
 * Keybindings follow the same pattern under their own key
 * ('xmage-keybindings'): resetKeybindings() drops every user override, so
 * "Reset to Default" restores the shipped key map (F2–F11, nothing on F8/F12).
 * NOT covered by Settings Presets — see keybindings.ts's header for why that
 * needs a preset-schema decision rather than a silent extension.
 */
const MODULE_RESETS: ReadonlyArray<() => void> = [resetDingSettings, resetTokenArtPrefs, resetKeybindings]

/**
 * Apply every registered setting's default: through the store setters (which
 * each re-persist xmage-settings), by rewriting/removing the standalone
 * localStorage keys, and through the module-owned resets. Effects that read
 * CSS vars react via their normal store subscription (App.tsx), so no page
 * reload is needed.
 */
export function resetSettingsToDefaults(store: ResettableStore): void {
  for (const apply of STORE_APPLIERS) apply(store)
  for (const reset of MODULE_RESETS) reset()
  try {
    for (const { key, value } of STANDALONE_LOCALSTORAGE_DEFAULTS) {
      if (value === null) localStorage.removeItem(key)
      else localStorage.setItem(key, value)
    }
  } catch { /* localStorage unavailable */ }
}


