import type { StateCreator } from 'zustand'
import type { UIState, GameStore } from '../types'
import {
  getDingSettings,
  setDingSettings,
  type PriorityDingSettings,
} from './priorityDing'
// Audio: the event-sound module keeps its own live snapshot (audioManager
// cannot import this store — the store imports the trigger layer transitively,
// which would be a cycle). These setters are the mirror, exactly like the
// priority-ding field above.
import { getAudioSettings, setAudioSettings } from '@/audio/audioManager'
import { isPickableAvatarId, readStoredAvatarId, resolveAvatarId } from '@/utils/avatars'

let errorToastTimer: ReturnType<typeof setTimeout> | null = null

/** How long an error toast stays before auto-hiding (the previous 5 s). */
const ERROR_TOAST_MS = 5000

/**
 * Arm/extend the auto-hide timer. Kept as ONE helper so every path
 * (show / hold-release) can never leave two timers running. `held` (a hover or
 * an expanded details section) suspends the auto-hide entirely — the timer is
 * simply not armed, so the user can read/copy at leisure.
 */
function armErrorToastTimer(
  held: boolean,
  set: (partial: Partial<{ errorToastVisible: boolean }>) => void,
): void {
  if (errorToastTimer) {
    clearTimeout(errorToastTimer)
    errorToastTimer = null
  }
  if (held) return
  errorToastTimer = setTimeout(() => {
    set({ errorToastVisible: false })
    errorToastTimer = null
  }, ERROR_TOAST_MS)
}

// ── Settings persistence ──
const SETTINGS_KEY = 'xmage-settings'

type RGB = { r: number; g: number; b: number }

interface PersistedSettings {
  /** Multiplayer board modes — see UIState's block comment. */
  boardModeMaximizeOpponents: boolean
  boardModeFocusEnabled: boolean
  boardModeFollowActive: boolean
  autoScrollLog: boolean
  autoScrollChat: boolean
  autoScrollLLM: boolean
  /** Replay LLM tab: show the conversation up to the current frame, not just this frame's bucket. */
  replayLLMFullHistory: boolean
  fontScale: number
  cardScale: number
  /** Auto-fit battlefield — see UIState.autoFitBattlefield. */
  autoFitBattlefield: boolean
  /** Whole-UI structural scale — see base.css --ui-scale. 1 = unscaled. */
  uiScale: number
  theme: 'dark' | 'light'
  /** Hover card-preview panel scale (1 = the CSS-default 540px panel width). */
  cardPreviewScale: number
  highlightPlayable: RGB
  highlightCombat: RGB
  highlightTarget: RGB
  highlightManaTappable: RGB
  highlightActiveTurn: RGB
  /** Event sounds: master enable. */
  audioEnabled: boolean
  /** Event-sound master volume 0..1. */
  audioVolume: number
  /**
   * My avatar id — the desktop's PreferencesDialog "My Avatar" choice
   * (`KEY_AVATAR`/`selectedId`). Selectable range 10…32; 10 is the desktop's
   * DEFAULT_AVATAR_ID. Sent to the proxy on connect (the proxy forwards it as
   * UserData.avatarId) and pushed mid-session when changed in Settings.
   */
  avatarId: number
}

/**
 * Default settings. Exported so a "Reset to default" feature (sibling change) can
 * consume the exact defaults instead of re-declaring them — keep this the single
 * source of truth.
 */
export const UI_DEFAULTS: Readonly<PersistedSettings> = Object.freeze({
  // Board modes — all OFF: 2-player games keep today's look exactly,
  // and a 3+ player game starts on the historical all-players grid.
  boardModeMaximizeOpponents: false,
  boardModeFocusEnabled: false,
  boardModeFollowActive: false,
  autoScrollLog: true,
  autoScrollChat: true,
  autoScrollLLM: true,
  // Replay LLM tab: full-history ON by default. Toggle lives in
  // the Settings dialog, which is where the tab split lives.
  replayLLMFullHistory: true,
  fontScale: 1.0,
  cardScale: 1.0,
  // Auto-fit ON by default: boards + hands must fit the screen
  // without scrolling; the toggle restores the legacy viewport heuristic.
  autoFitBattlefield: true,
  uiScale: 1.35,
  theme: 'dark',
  cardPreviewScale: 1.0,
  highlightPlayable: { r: 39, g: 174, b: 96 },
  highlightCombat: { r: 255, g: 215, b: 0 },
  highlightTarget: { r: 255, g: 50, b: 50 },
  highlightManaTappable: { r: 0, g: 180, b: 255 },
  highlightActiveTurn: { r: 59, g: 130, b: 246 },
  // Event sounds — same defaults as utils/settingsDefaults.ts.
  audioEnabled: true,
  audioVolume: 0.6,
  // Avatar — the desktop's DEFAULT_AVATAR_ID (Constants.java:44).
  avatarId: 10,
})

const DEFAULTS = UI_DEFAULTS

function loadSettings(): PersistedSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_KEY)
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<PersistedSettings>
      return {
        boardModeMaximizeOpponents: parsed.boardModeMaximizeOpponents ?? DEFAULTS.boardModeMaximizeOpponents,
        boardModeFocusEnabled: parsed.boardModeFocusEnabled ?? DEFAULTS.boardModeFocusEnabled,
        boardModeFollowActive: parsed.boardModeFollowActive ?? DEFAULTS.boardModeFollowActive,
        autoScrollLog: parsed.autoScrollLog ?? DEFAULTS.autoScrollLog,
        autoScrollChat: parsed.autoScrollChat ?? DEFAULTS.autoScrollChat,
        autoScrollLLM: parsed.autoScrollLLM ?? DEFAULTS.autoScrollLLM,
        replayLLMFullHistory: parsed.replayLLMFullHistory ?? DEFAULTS.replayLLMFullHistory,
        fontScale: parsed.fontScale ?? DEFAULTS.fontScale,
        cardScale: parsed.cardScale ?? DEFAULTS.cardScale,
        autoFitBattlefield: parsed.autoFitBattlefield ?? DEFAULTS.autoFitBattlefield,
        uiScale: parsed.uiScale ?? DEFAULTS.uiScale,
        theme: parsed.theme ?? DEFAULTS.theme,
        cardPreviewScale: parsed.cardPreviewScale ?? DEFAULTS.cardPreviewScale,
        highlightPlayable: parsed.highlightPlayable ?? DEFAULTS.highlightPlayable,
        highlightCombat: parsed.highlightCombat ?? DEFAULTS.highlightCombat,
        highlightTarget: parsed.highlightTarget ?? DEFAULTS.highlightTarget,
        highlightManaTappable: parsed.highlightManaTappable ?? DEFAULTS.highlightManaTappable,
        highlightActiveTurn: parsed.highlightActiveTurn ?? DEFAULTS.highlightActiveTurn,
        audioEnabled: parsed.audioEnabled ?? DEFAULTS.audioEnabled,
        audioVolume:
          typeof parsed.audioVolume === 'number'
            ? Math.min(1, Math.max(0, parsed.audioVolume))
            : DEFAULTS.audioVolume,
        // Avatar: a stored id outside the selectable set is repaired to the
        // default on READ (the desktop's own clamp, PreferencesDialog:3633-3639)
        // — a hand-edited or stale localStorage value must not wedge the picker.
        avatarId: readStoredAvatarId(parsed.avatarId),
      }
    }
  } catch { /* localStorage unavailable */ }
  return DEFAULTS
}

function saveSettingsFromStore(state: GameStore) {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify({
      boardModeMaximizeOpponents: state.boardModeMaximizeOpponents,
      boardModeFocusEnabled: state.boardModeFocusEnabled,
      boardModeFollowActive: state.boardModeFollowActive,
      autoScrollLog: state.autoScrollLog,
      autoScrollChat: state.autoScrollChat,
      autoScrollLLM: state.autoScrollLLM,
      replayLLMFullHistory: state.replayLLMFullHistory,
      fontScale: state.fontScale,
      cardScale: state.cardScale,
      autoFitBattlefield: state.autoFitBattlefield,
      uiScale: state.uiScale,
      theme: state.theme,
      cardPreviewScale: state.cardPreviewScale,
      highlightPlayable: state.highlightPlayable,
      highlightCombat: state.highlightCombat,
      highlightTarget: state.highlightTarget,
      highlightManaTappable: state.highlightManaTappable,
      highlightActiveTurn: state.highlightActiveTurn,
      audioEnabled: state.audioEnabled,
      audioVolume: state.audioVolume,
      avatarId: state.avatarId,
    }))
  } catch { /* localStorage unavailable */ }
}

const initialSettings = loadSettings()
const initialDingSettings: PriorityDingSettings = getDingSettings()
// Audio: push the persisted values into the audio module's live snapshot
// at module init — the same wiring the ding mirror gets. Without this the
// manager would run on its own defaults until the first settings change.
setAudioSettings({
  enabled: initialSettings.audioEnabled,
  volume: initialSettings.audioVolume,
})

export const createUISlice: StateCreator<
  GameStore,
  [],
  [],
  UIState
> = (set, get) => ({
  // State
  zonePopup: null,
  revealedPopup: null,
  activeRightTab: 'log',
  activeLLMPlayerIdx: 0,
  errorMessage: '',
  errorDetails: '',
  errorToastVisible: false,
  errorToastHeld: false,
  cardPreview: null,
  userRequest: null,

  // Settings state (persisted to localStorage)
  boardModeMaximizeOpponents: initialSettings.boardModeMaximizeOpponents,
  boardModeFocusEnabled: initialSettings.boardModeFocusEnabled,
  boardModeFollowActive: initialSettings.boardModeFollowActive,
  // The pinned focus target is SESSION-scoped (deliberately NOT persisted —
  // "a focused opponent must not survive into the next game/view").
  boardModeFocusTarget: null,
  autoScrollLog: initialSettings.autoScrollLog,
  autoScrollChat: initialSettings.autoScrollChat,
  autoScrollLLM: initialSettings.autoScrollLLM,
  replayLLMFullHistory: initialSettings.replayLLMFullHistory,
  fontScale: initialSettings.fontScale,
  cardScale: initialSettings.cardScale,
  autoFitBattlefield: initialSettings.autoFitBattlefield,
  uiScale: initialSettings.uiScale,
  theme: initialSettings.theme,
  showSettings: false,
  cardPreviewScale: initialSettings.cardPreviewScale,
  settingsResetAt: 0,
  highlightPlayable: initialSettings.highlightPlayable,
  highlightCombat: initialSettings.highlightCombat,
  highlightTarget: initialSettings.highlightTarget,
  highlightManaTappable: initialSettings.highlightManaTappable,
  highlightActiveTurn: initialSettings.highlightActiveTurn,
  // Event sounds — live store mirror of the values loadSettings() just
  // pushed into the audio module (see setAudioSettings above).
  audioEnabled: getAudioSettings().enabled,
  audioVolume: getAudioSettings().volume,
  // Avatar — my chosen avatar, persisted with the rest of xmage-settings.
  avatarId: initialSettings.avatarId,

  // Priority-ding settings — mirrored from the ding module's live settings
  // (persisted under their own key by that module; see priorityDing.ts).
  priorityDing: { ...initialDingSettings },

  // Actions
  setZonePopup: (popup) => set({ zonePopup: popup }),

  setRevealedPopup: (popup) => set({ revealedPopup: popup }),

  setActiveRightTab: (tab) => set({ activeRightTab: tab }),

  setActiveLLMPlayerIdx: (idx) => set({ activeLLMPlayerIdx: idx }),

  showError: (message) => {
    set({ errorMessage: message, errorDetails: '', errorToastVisible: true })
    armErrorToastTimer(get().errorToastHeld, set)
  },

  /**
   * Error WITH a details/stack section (the proxy's error frames may now
   * carry `details`). Same lifetime/hold rules as showError.
   */
  showErrorWithDetails: (message, details) => {
    set({ errorMessage: message, errorDetails: details, errorToastVisible: true })
    armErrorToastTimer(get().errorToastHeld, set)
  },

  /**
   * Hold (hover / expanded) suspends auto-hide; releasing re-arms a fresh
   * full timer. Held state is not carried across a NEW error — showError reads
   * it only to decide whether to arm, and a hold that ends after the toast is
   * gone just clears the timer (no error to hide).
   */
  setErrorToastHeld: (held) => {
    set({ errorToastHeld: held })
    if (!get().errorToastVisible) {
      if (errorToastTimer) {
        clearTimeout(errorToastTimer)
        errorToastTimer = null
      }
      return
    }
    armErrorToastTimer(held, set)
  },

  setCardPreview: (preview) => set({ cardPreview: preview }),

  setUserRequest: (request) => set({ userRequest: request }),

  respondToUserRequest: (action) => {
    const { ws, userRequest } = get()
    if (!userRequest) return
    if (ws) {
      ws.send({
        type: 'user_request_response',
        action,
        gameId: userRequest.gameId,
        relatedUserId: userRequest.relatedUserId,
      })
    }
    set({ userRequest: null })
  },

  // Settings actions (each persists the full settings object to localStorage)
  setBoardModeMaximizeOpponents: (v) => {
    set({ boardModeMaximizeOpponents: v })
    saveSettingsFromStore(get())
  },

  setBoardModeFocusEnabled: (v) => {
    set({ boardModeFocusEnabled: v })
    saveSettingsFromStore(get())
  },

  setBoardModeFollowActive: (v) => {
    set({ boardModeFollowActive: v })
    saveSettingsFromStore(get())
  },

  // Focus pin — session-scoped, so no saveSettingsFromStore here (and a focus
  // toggle turning OFF must not leave a pin for it to re-engage with).
  setBoardModeFocusTarget: (name) => {
    set({ boardModeFocusTarget: name })
  },

  setAutoScrollLog: (v) => {
    set({ autoScrollLog: v })
    saveSettingsFromStore(get())
  },

  setAutoScrollChat: (v) => {
    set({ autoScrollChat: v })
    saveSettingsFromStore(get())
  },

  setAutoScrollLLM: (v) => {
    set({ autoScrollLLM: v })
    saveSettingsFromStore(get())
  },

  setReplayLLMFullHistory: (v) => {
    set({ replayLLMFullHistory: v })
    saveSettingsFromStore(get())
  },

  // Session-scoped (NOT persisted — a collapsed block must not stay collapsed
  // into the next game/replay by default).
  llmCollapsedBlocks: {},
  toggleLlmBlockCollapsed: (key) => {
    const cur = get().llmCollapsedBlocks
    set({ llmCollapsedBlocks: { ...cur, [key]: !cur[key] } })
  },

  setFontScale: (v) => {
    set({ fontScale: v })
    saveSettingsFromStore(get())
  },

  setCardScale: (v) => {
    set({ cardScale: v })
    saveSettingsFromStore(get())
  },

  setAutoFitBattlefield: (v) => {
    set({ autoFitBattlefield: v })
    saveSettingsFromStore(get())
  },

  setUiScale: (v) => {
    set({ uiScale: v })
    saveSettingsFromStore(get())
  },

  setTheme: (t) => {
    set({ theme: t })
    saveSettingsFromStore(get())
  },

  setShowSettings: (show) => set({ showSettings: show }),

  setCardPreviewScale: (v) => {
    set({ cardPreviewScale: v })
    saveSettingsFromStore(get())
  },

  markSettingsReset: () => {
    // Re-read the ding module's live settings into the mirror FIRST: the reset
    // registry's MODULE_RESETS just rewrote them via resetDingSettings(), and
    // this store mirror is what SettingsDialog renders — without the re-read
    // the dialog would show stale values until the next ding-setting change.
    set({ priorityDing: { ...getDingSettings() }, settingsResetAt: Date.now() })
  },

  setHighlightPlayable: (c) => {
    set({ highlightPlayable: c })
    saveSettingsFromStore(get())
  },

  setHighlightCombat: (c) => {
    set({ highlightCombat: c })
    saveSettingsFromStore(get())
  },

  setHighlightTarget: (c) => {
    set({ highlightTarget: c })
    saveSettingsFromStore(get())
  },

  setHighlightManaTappable: (c) => {
    set({ highlightManaTappable: c })
    saveSettingsFromStore(get())
  },

  setHighlightActiveTurn: (c) => {
    set({ highlightActiveTurn: c })
    saveSettingsFromStore(get())
  },

  // ── Priority-ding settings actions (see priorityDing.ts for persistence) ──
  setPriorityDingEnabled: (v) => {
    set({ priorityDing: setDingSettings({ enabled: v }) })
  },

  setPriorityDingVolume: (v) => {
    set({ priorityDing: setDingSettings({ volume: v }) })
  },

  setPriorityDingVariant: (v) => {
    set({ priorityDing: setDingSettings({ variant: v }) })
  },

  setPriorityDingFocusMode: (v) => {
    set({ priorityDing: setDingSettings({ focusMode: v }) })
  },

  setPriorityDingFlashEnabled: (v) => {
    set({ priorityDing: setDingSettings({ flashEnabled: v }) })
  },

  setPriorityDingFlashIntensity: (v) => {
    set({ priorityDing: setDingSettings({ flashIntensity: v }) })
  },

  // Sustain mode: hold the edge glow while a prompt
  // is open instead of pulsing once. Same persistence path as its siblings —
  // resetDingSettings() rewrites the whole defaults object, so the reset
  // registry covers this field with no entry of its own.
  setPriorityDingFlashSustain: (v) => {
    set({ priorityDing: setDingSettings({ flashSustain: v }) })
  },

  setPriorityDingRememberPosition: (v) => {
    set({ priorityDing: setDingSettings({ rememberPromptPosition: v }) })
  },

  // ── Event-sound settings ──
  // Each setter writes the store field (persisted with xmage-settings) AND the
  // audio module's live snapshot the manager's play() reads — one write path,
  // so the two can never disagree.
  setAudioEnabled: (v) => {
    set({ audioEnabled: v })
    setAudioSettings({ enabled: v })
    saveSettingsFromStore(get())
  },

  setAudioVolume: (v) => {
    const clamped = Math.min(1, Math.max(0, v))
    set({ audioVolume: clamped })
    setAudioSettings({ volume: clamped })
    saveSettingsFromStore(get())
  },

  // ── Avatar ──
  // Persist locally, then push to the running session (the desktop's
  // PreferencesDialog behavior: SessionHandler.updatePreferencesForServer on
  // every avatar click). The push is a `set_avatar` verb — the proxy rebuilds
  // UserData and answers `avatar_set` with the EFFECTIVE id, which
  // messageHandlers writes back so a clamped wire value cannot leave the UI
  // showing an avatar the server does not have.
  setAvatarId: (id) => {
    // Fail Fast: the picker offers exactly AVATAR_IDS, so anything else is a bug
    // rather than a value to repair silently (a stored value is repaired on READ
    // in loadSettings, which is where untrusted input lives).
    if (!isPickableAvatarId(id)) {
      throw new Error(`setAvatarId: ${id} is not a selectable avatar id`)
    }
    set({ avatarId: id })
    saveSettingsFromStore(get())
    const { ws } = get()
    if (ws) {
      ws.send({ type: 'set_avatar', avatarId: id })
    }
  },

  /**
   * The proxy's answer (avatar_set) — see the type comment. Persists and adopts
   * the value; never re-sends. An id outside the selectable range is clamped
   * here too: the answer is a WIRE value (a proxy bug or a hand-framed message
   * could carry anything), and the picker's contract is that `avatarId` is
   * always renderable.
   */
  adoptAvatarId: (id) => {
    const effective = resolveAvatarId(id)
    set({ avatarId: effective })
    saveSettingsFromStore(get())
  },
})