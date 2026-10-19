import { useCallback, useEffect, useId, useRef, useState, useSyncExternalStore } from 'react'
import type { ChangeEvent, CSSProperties } from 'react'
import { useGameStore } from '@/store/gameStore'
import { SOUND_VARIANTS, playSoundVariant, type SoundVariant } from '@/audio/dingSynth'
import type { DingFocusMode } from '@/store/slices/priorityDing'
import {
  PRIORITY_FLASH_EVENT,
  PRIORITY_FLASH_SUSTAIN_EVENT,
  PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT,
} from '@/store/slices/priorityDing'
import { resetSettingsToDefaults } from '@/utils/settingsDefaults'
import {
  KEYBINDING_ACTIONS,
  beginCapture,
  captureKeyDecision,
  endCapture,
  formatKeySpec,
  getKeybindingLoadProblems,
  isKeybindingModified,
  keyFor,
  keybindingsRevision,
  rebindKeybinding,
  resetKeybinding,
  resetKeybindings,
  subscribeKeybindings,
  type KeybindingActionId,
} from '@/utils/keybindings'
import {
  deletePreset,
  listPresets,
  loadPreset,
  savePreset,
  type SettingsPresetSummary,
} from '@/utils/settingsPresets'
import {
  subscribeTokenArt,
  tokenArtRevision,
  countOwnTokenArt,
  isOverrideOpponent,
  setOverrideOpponent,
} from '@/utils/tokenArt'
import {
  AVATAR_IDS,
  DEFAULT_AVATAR_ID,
  avatarUrlFor,
} from '@/utils/avatars'

type RGB = { r: number; g: number; b: number }

interface HighlightConfig {
  /** Human-readable label shown beside the swatch. */
  label: string
  /** Current {r,g,b} value from the store. */
  value: RGB
  /** Store setter that accepts the next {r,g,b}. */
  setter: (c: RGB) => void
}

/**
 * Settings dialog — split into tabs
 * ("the settings menu should be split into tabs so there isn't so much
 * scrolling to do").
 *
 * The tab recipe is the in-repo `LLMConfigTabs` one (Tier-S beac52d949):
 *  - a canonical tab list validated Fail-Fast (`settingsTabIndex`/`settingsTabAt`
 *    throw on an unknown name or out-of-range index — a renamed/typo'd tab must
 *    never degrade into a silent empty tab),
 *  - a WAI-ARIA tablist (role=tablist/tab/tabpanel, aria-selected,
 *    aria-controls, aria-labelledby, roving tabIndex, Arrow/Home/End with
 *    selection-follows-focus — the same way the desktop `PreferencesDialog`
 *    JTabbedPane moves between tabs),
 *  - ALL panels stay mounted-hidden so component-local state survives a switch
 *    (the Presets name input's in-progress text, like EndpointSelectInput's
 *    "Other…" draft, must not be lost on a tab switch),
 *  - one shared `data-explain="settings-tab"` id for the whole tab bar (the
 *    explain-coverage gate counts <button> per file; every tab button carries
 *    the id — the LLMConfigTabs family rule is not needed).
 *
 * The split is presentation-only: every section, control, hint and
 * data-explain id from the flat column is kept verbatim, and the settings
 * registry (utils/settingsDefaults.ts) is untouched — Reset to Default and
 * Presets cover exactly what they covered before. The footer is a sticky row
 * OUTSIDE the scrolling panel body: Reset to Default / Close stay visible on
 * every tab (one DOM copy).
 */

/** Tab titles, in the flat column's historical section order. Canonical: the render validates against this. */
export const SETTINGS_TABS = ['Display', 'Appearance', 'Alerts', 'Tokens', 'LLM Chat', 'Presets', 'Hotkeys'] as const
export type SettingsTabName = (typeof SETTINGS_TABS)[number]

/** The tab the dialog opens on (the desktop PreferencesDialog does not persist its tab either). */
export const DEFAULT_SETTINGS_TAB: SettingsTabName = 'Display'

/**
 * Index of a tab name. Fail Fast: an unknown name is a programming error (a
 * renamed/typo'd tab), never a silent fallback to the first tab.
 */
export function settingsTabIndex(name: string): number {
  const index = (SETTINGS_TABS as readonly string[]).indexOf(name)
  if (index < 0) {
    throw new Error(`Settings tabs: unknown tab '${name}' — known tabs are ${SETTINGS_TABS.join(', ')}`)
  }
  return index
}

/** Tab name at an index. Fail Fast: out-of-range is a programming error, never undefined. */
export function settingsTabAt(index: number): SettingsTabName {
  const name = SETTINGS_TABS[index]
  if (name === undefined) throw new Error(`Settings tabs: no tab at index ${index}`)
  return name
}

/**
 * The tab a key press moves to (automatic activation, as JTabbedPane does it).
 * Only the keys the tablist pattern owns move the selection; anything else
 * (Tab, Enter, Space, letters) returns the current tab so the keydown handler
 * can leave the event alone. Throws for an unknown current tab.
 */
export function settingsTabAfterKey(current: string, key: string): SettingsTabName {
  const index = settingsTabIndex(current)
  const last = SETTINGS_TABS.length - 1
  switch (key) {
    case 'ArrowRight':
    case 'ArrowDown':
      return settingsTabAt((index + 1) % SETTINGS_TABS.length)
    case 'ArrowLeft':
    case 'ArrowUp':
      return settingsTabAt((index + last) % SETTINGS_TABS.length)
    case 'Home':
      return settingsTabAt(0)
    case 'End':
      return settingsTabAt(last)
    default:
      return settingsTabAt(index)
  }
}

/** DOM-id slug for a tab name — names are validated against SETTINGS_TABS. */
function tabSlug(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-')
}

/**
 * The tabbed settings dialog (a global mount — replay and live both raise it).
 *
 * `activeTab` is component-local state: a fresh dialog opens on Display and a
 * switch is not persisted, mirroring the desktop `PreferencesDialog`.
 */
export default function SettingsDialog() {
  const showSettings = useGameStore((s) => s.showSettings)
  const fontScale = useGameStore((s) => s.fontScale)
  const cardScale = useGameStore((s) => s.cardScale)
  const autoFitBattlefield = useGameStore((s) => s.autoFitBattlefield)
  const uiScale = useGameStore((s) => s.uiScale)
  const theme = useGameStore((s) => s.theme)
  const cardPreviewScale = useGameStore((s) => s.cardPreviewScale)
  const setFontScale = useGameStore((s) => s.setFontScale)
  const setCardScale = useGameStore((s) => s.setCardScale)
  const setAutoFitBattlefield = useGameStore((s) => s.setAutoFitBattlefield)
  const setUiScale = useGameStore((s) => s.setUiScale)
  const setTheme = useGameStore((s) => s.setTheme)
  const setCardPreviewScale = useGameStore((s) => s.setCardPreviewScale)
  const setShowSettings = useGameStore((s) => s.setShowSettings)
  const markSettingsReset = useGameStore((s) => s.markSettingsReset)
  const replayLLMFullHistory = useGameStore((s) => s.replayLLMFullHistory)
  const setReplayLLMFullHistory = useGameStore((s) => s.setReplayLLMFullHistory)

  /**
   * Token-art preferences live in a module-level store (utils/tokenArt.ts), not the
   * Zustand store, because CardThumb reads them for every tile and routing them
   * through Zustand would re-render the whole board on every change. Subscribe with
   * useSyncExternalStore so this dialog still reflects live edits — including one made
   * through a token's right-click menu while the dialog is open. Under Node the
   * harness renders with react-dom/server, so this call must carry the 3-arg
   * (getServerSnapshot) form — react-dom/server throws on the 2-arg form.
   */
  const tokenArtRev = useSyncExternalStore(subscribeTokenArt, tokenArtRevision, tokenArtRevision)
  // Recompute from the store on each revision change (the revision is the trigger;
  // these reads are cheap and always return the current values).
  void tokenArtRev
  const tokenArtOverride = isOverrideOpponent()
  const setTokenArtOverrideState = setOverrideOpponent
  const tokenArtOwnCount = countOwnTokenArt()

  // Highlight colors + setters
  const highlightPlayable = useGameStore((s) => s.highlightPlayable)
  const highlightCombat = useGameStore((s) => s.highlightCombat)
  const highlightTarget = useGameStore((s) => s.highlightTarget)
  const highlightManaTappable = useGameStore((s) => s.highlightManaTappable)
  const highlightActiveTurn = useGameStore((s) => s.highlightActiveTurn)
  const setHighlightPlayable = useGameStore((s) => s.setHighlightPlayable)
  const setHighlightCombat = useGameStore((s) => s.setHighlightCombat)
  const setHighlightTarget = useGameStore((s) => s.setHighlightTarget)
  const setHighlightManaTappable = useGameStore((s) => s.setHighlightManaTappable)
  const setHighlightActiveTurn = useGameStore((s) => s.setHighlightActiveTurn)

  // Priority-ding settings (Alerts tab)
  const priorityDing = useGameStore((s) => s.priorityDing)
  const setPriorityDingEnabled = useGameStore((s) => s.setPriorityDingEnabled)
  const setPriorityDingVolume = useGameStore((s) => s.setPriorityDingVolume)
  const setPriorityDingVariant = useGameStore((s) => s.setPriorityDingVariant)
  const setPriorityDingFocusMode = useGameStore((s) => s.setPriorityDingFocusMode)
  const setPriorityDingFlashEnabled = useGameStore((s) => s.setPriorityDingFlashEnabled)
  const setPriorityDingFlashIntensity = useGameStore((s) => s.setPriorityDingFlashIntensity)
  const setPriorityDingFlashSustain = useGameStore((s) => s.setPriorityDingFlashSustain)
  const setPriorityDingRememberPosition = useGameStore((s) => s.setPriorityDingRememberPosition)

  // Event-sound settings — the Alerts tab's Audio section.
  const audioEnabled = useGameStore((s) => s.audioEnabled)
  const audioVolume = useGameStore((s) => s.audioVolume)
  const setAudioEnabled = useGameStore((s) => s.setAudioEnabled)
  const setAudioVolume = useGameStore((s) => s.setAudioVolume)

  // My avatar — the Appearance tab's picker.
  const avatarId = useGameStore((s) => s.avatarId)
  const setAvatarId = useGameStore((s) => s.setAvatarId)

  // Multiplayer board modes — the Display tab's "Multiplayer layout".
  const boardModeMaximizeOpponents = useGameStore((s) => s.boardModeMaximizeOpponents)
  const boardModeFocusEnabled = useGameStore((s) => s.boardModeFocusEnabled)
  const boardModeFollowActive = useGameStore((s) => s.boardModeFollowActive)
  const setBoardModeMaximizeOpponents = useGameStore((s) => s.setBoardModeMaximizeOpponents)
  const setBoardModeFocusEnabled = useGameStore((s) => s.setBoardModeFocusEnabled)
  const setBoardModeFollowActive = useGameStore((s) => s.setBoardModeFollowActive)

  // Settings presets. The list is read from localStorage on
  // open; a corrupt stored blob is surfaced in presetError instead of being
  // silently reset (utils/settingsPresets.ts throws by design).
  const [presetName, setPresetName] = useState('')
  const [presets, setPresets] = useState<SettingsPresetSummary[]>([])
  const [presetError, setPresetError] = useState('')
  // Tab bar state lives here with the other hooks, ABOVE the early return
  // (React hook rules: every render runs the same hook sequence).
  const [activeTab, setActiveTab] = useState<SettingsTabName>(DEFAULT_SETTINGS_TAB)
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([])
  const idPrefix = useId()

  // ── Hotkeys tab ──
  // Key bindings live in a module store (utils/keybindings.ts) — the same
  // useSyncExternalStore pattern as token art above, and for the same reason:
  // the RAIL reads the bindings for every keypress, so routing them through
  // Zustand would re-render the whole board on a rebind. 3-arg form required
  // under react-dom/server (see the token-art note above).
  const keybindingsRev = useSyncExternalStore(subscribeKeybindings, keybindingsRevision, keybindingsRevision)
  void keybindingsRev
  /** The action whose row is capturing the next keydown, or null. */
  const [capturingAction, setCapturingAction] = useState<KeybindingActionId | null>(null)
  /** Last refused rebind, per row: what was pressed and why it was refused. */
  const [captureError, setCaptureError] = useState<{ id: KeybindingActionId; message: string } | null>(null)
  const keybindingProblems = getKeybindingLoadProblems()

  /**
   * While a row is capturing, the dialog owns the WHOLE keyboard: this
   * window-capture-phase listener swallows every keydown (stopPropagation +
   * preventDefault) so a captured key can never reach the rail's document
   * listener, the replay viewer's hand-back, or the tab bar — the app-wide
   * `beginCapture` flag is the second half of the same guarantee. Escape
   * cancels; modifier-only clicks are ignored; anything else is decided by the
   * pure captureKeyDecision() and either bound or reported.
   *
   * The capture TOKEN is armed here (inside the effect) and released in its
   * cleanup, so StrictMode's mount/cleanup/mount cycle and a dialog close both
   * release exactly what they armed — never a capture a later row owns.
   */
  useEffect(() => {
    const pending: KeybindingActionId | null = capturingAction
    if (pending === null) return
    // Explicitly typed local: TS does not carry a `null`-check narrowing into a
    // hoisted function declaration, and every handler below needs the non-null id.
    const target: KeybindingActionId = pending
    const token = beginCapture()
    function onCaptureKeydown(e: KeyboardEvent) {
      e.preventDefault()
      e.stopPropagation()
      const decision = captureKeyDecision(e.key)
      if (decision.kind === 'ignore') return
      if (decision.kind === 'cancel') {
        setCapturingAction(null)
        return
      }
      if (decision.kind === 'invalid') {
        setCaptureError({ id: target, message: `'${decision.raw}' cannot be bound — use a function key (F1–F12), a letter, a digit or Space.` })
        return
      }
      const result = rebindKeybinding(target, decision.key)
      if (result.ok) {
        setCaptureError(null)
        setCapturingAction(null)
        return
      }
      // 'invalid-key' cannot happen here (decided above); 'conflict' carries
      // the action that owns the key — surfaced verbatim, never accepted.
      setCaptureError({
        id: target,
        message: result.reason === 'conflict'
          ? `${formatKeySpec(decision.key)} is already used by '${result.conflict.label}' — pick another key.`
          : `'${result.raw}' cannot be bound.`,
      })
    }
    window.addEventListener('keydown', onCaptureKeydown, true)
    return () => {
      window.removeEventListener('keydown', onCaptureKeydown, true)
      endCapture(token)
    }
  }, [capturingAction])

  /** Start (or cancel, when the same row is clicked again) a rebind capture. */
  const toggleCapture = (id: KeybindingActionId) => {
    setCaptureError(null)
    setCapturingAction((current) => (current === id ? null : id))
  }

  const handleResetKeybinding = (id: KeybindingActionId) => {
    const result = resetKeybinding(id)
    if (!result.ok) {
      if (result.reason === 'conflict') {
        setCaptureError({
          id,
          message: `The default key is currently bound to '${result.conflict.label}' — reset that one first (or use Reset to Default).`,
        })
      } else {
        setCaptureError({ id, message: `'${result.raw}' cannot be bound.` })
      }
      return
    }
    setCaptureError(null)
  }

  const handleResetAllKeybindings = () => {
    resetKeybindings()
    setCaptureError(null)
    setCapturingAction(null)
  }

  const refreshPresets = useCallback(() => {
    try {
      setPresets(listPresets())
      setPresetError('')
    } catch (err) {
      setPresets([])
      setPresetError(err instanceof Error ? err.message : String(err))
    }
  }, [])

  useEffect(() => {
    if (showSettings) refreshPresets()
  }, [showSettings, refreshPresets])

  if (!showSettings) return null

  const fontPercent = Math.round(fontScale * 100)
  const cardPercent = Math.round(cardScale * 100)
  const uiPercent = Math.round(uiScale * 100)
  const cardPreviewPercent = Math.round(cardPreviewScale * 100)
  const flashIntensityPercent = Math.round(priorityDing.flashIntensity * 100)
  // The slider stays usable across the 0.75–6.0 range: fine 0.05 steps up to
  // 200% (where the old max was), coarser 0.1 steps above for 4K screens.
  const UI_SCALE_MAX = 6.0
  const uiScaleStep = uiScale <= 2 ? 0.05 : 0.1

  const handleFontChange = (e: ChangeEvent<HTMLInputElement>) => {
    setFontScale(parseFloat(e.target.value))
  }

  const handleCardChange = (e: ChangeEvent<HTMLInputElement>) => {
    setCardScale(parseFloat(e.target.value))
  }

  const handleAutoFitChange = (next: boolean) => {
    setAutoFitBattlefield(next)
  }

  const handleUiScaleChange = (e: ChangeEvent<HTMLInputElement>) => {
    setUiScale(parseFloat(e.target.value))
  }

  const handleCardPreviewScaleChange = (e: ChangeEvent<HTMLInputElement>) => {
    setCardPreviewScale(parseFloat(e.target.value))
  }

  const handleThemeChange = (next: 'dark' | 'light') => {
    setTheme(next)
  }

  /**
   * Avatar pick. The store setter does the real work: it persists the choice
   * and — when a session is live — sends `set_avatar`, so the running game's
   * PlayerView carries the new id (desktop parity: PreferencesDialog pushes on
   * every pick). Out-of-range ids cannot reach here (the grid renders AVATAR_IDS),
   * and the setter throws if one somehow does.
   */
  const handleAvatarChange = (id: number) => {
    setAvatarId(id)
  }

  const handleResetToDefault = () => {
    resetSettingsToDefaults(useGameStore.getState())
    // Views holding localStorage-backed state outside the store (GameView rail
    // widths, battlefield split) watch settingsResetAt and re-read their keys.
    markSettingsReset()
  }

  // ── Settings presets ──
  // Save captures the CURRENT settings under the typed name; Load applies the
  // saved snapshot through the same registry the reset path uses (so it can
  // never miss a setting) and bumps the mirror-refresh trigger. Errors are
  // surfaced in the section, never swallowed — see utils/settingsPresets.ts.
  const surfacePresetError = (err: unknown) =>
    setPresetError(err instanceof Error ? err.message : String(err))

  const handleSavePreset = () => {
    try {
      savePreset(presetName, useGameStore.getState())
      setPresetName('')
      refreshPresets()
    } catch (err) {
      surfacePresetError(err)
    }
  }

  const handleLoadPreset = (name: string) => {
    try {
      loadPreset(name, useGameStore.getState())
      setPresetError('')
    } catch (err) {
      surfacePresetError(err)
    }
  }

  const handleDeletePreset = (name: string) => {
    try {
      deletePreset(name)
      refreshPresets()
    } catch (err) {
      surfacePresetError(err)
    }
  }

  const close = () => setShowSettings(false)

  const highlights: HighlightConfig[] = [
    { label: 'Playable', value: highlightPlayable, setter: setHighlightPlayable },
    { label: 'Combat', value: highlightCombat, setter: setHighlightCombat },
    { label: 'Target', value: highlightTarget, setter: setHighlightTarget },
    { label: 'Mana Tappable', value: highlightManaTappable, setter: setHighlightManaTappable },
    { label: 'Active Turn', value: highlightActiveTurn, setter: setHighlightActiveTurn },
  ]

  const rgbString = (c: RGB): string => `rgb(${c.r}, ${c.g}, ${c.b})`

  const makeHighlightHandler =
    (setter: (c: RGB) => void, current: RGB, channel: keyof RGB) =>
    (e: ChangeEvent<HTMLInputElement>) => {
      const v = Math.max(0, Math.min(255, parseInt(e.target.value, 10)))
      setter({ ...current, [channel]: v })
    }

  const overlayStyle: CSSProperties = {
    display: 'flex',
  }

  const contentStyle: CSSProperties = {
    maxWidth: '480px',
    width: '100%',
    maxHeight: '80vh',
    // The dialog no longer scrolls as a whole: the tab bar and the footer stay
    // fixed while the ACTIVE PANEL scrolls inside the body div below.
    overflowY: 'hidden',
    textAlign: 'left',
    position: 'relative',
    display: 'flex',
    flexDirection: 'column',
  }

  const closeButtonStyle: CSSProperties = {
    position: 'absolute',
    top: '0.75rem',
    right: '0.75rem',
    background: 'none',
    border: 'none',
    color: 'var(--text-muted)',
    fontSize: '1.4rem',
    cursor: 'pointer',
    padding: '0',
    width: '28px',
    height: '28px',
    lineHeight: 1,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: '4px',
  }

  const sectionStyle: CSSProperties = {
    marginBottom: '1.5rem',
  }

  const labelStyle: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'space-between',
    fontSize: '0.9rem',
    fontWeight: 600,
    color: 'var(--text-primary)',
    marginBottom: '0.5rem',
  }

  const valueStyle: CSSProperties = {
    color: 'var(--accent-primary)',
    fontWeight: 700,
    minWidth: '3.5rem',
    textAlign: 'right',
  }

  const sliderStyle: CSSProperties = {
    width: '100%',
    accentColor: 'var(--accent-primary)',
    cursor: 'pointer',
  }

  const hintStyle: CSSProperties = {
    fontSize: '0.78rem',
    color: 'var(--text-secondary)',
    marginTop: '0.35rem',
    lineHeight: 1.4,
  }

  const themeButtonRowStyle: CSSProperties = {
    display: 'flex',
    gap: '0.5rem',
  }

  const themeButtonBase: CSSProperties = {
    flex: 1,
    padding: '0.5rem 1rem',
    border: '1px solid var(--border-default)',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '0.85rem',
    fontWeight: 600,
  }

  const themeButtonActive: CSSProperties = {
    ...themeButtonBase,
    background: 'var(--accent-primary)',
    color: 'white',
    borderColor: 'var(--accent-primary)',
  }

  const themeButtonInactive: CSSProperties = {
    ...themeButtonBase,
    background: 'var(--bg-elevated)',
    color: 'var(--text-primary)',
  }

  /**
   * Avatar picker. A responsive grid of square thumbnails: 4 per row at the
   * dialog's width, so 23 avatars read as a compact block instead of a tall list.
   */
  const avatarGridStyle: CSSProperties = {
    display: 'grid',
    gridTemplateColumns: 'repeat(4, 1fr)',
    gap: '0.4rem',
  }

  const avatarButtonBase: CSSProperties = {
    padding: '2px',
    border: '2px solid var(--border-default)',
    borderRadius: '6px',
    cursor: 'pointer',
    background: 'var(--bg-elevated)',
    lineHeight: 0,
  }

  const avatarButtonActive: CSSProperties = {
    ...avatarButtonBase,
    borderColor: 'var(--accent-primary)',
  }

  const avatarButtonInactive: CSSProperties = avatarButtonBase

  const footerStyle: CSSProperties = {
    display: 'flex',
    justifyContent: 'flex-end',
    gap: '0.5rem',
    marginTop: '1rem',
    borderTop: '1px solid var(--border-default)',
    paddingTop: '1rem',
    flexShrink: 0,
  }

  // Highlight colors subsection styles
  const highlightRowStyle: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem',
    marginBottom: '0.5rem',
  }

  const highlightSwatchStyle = (c: RGB): CSSProperties => ({
    width: '20px',
    height: '20px',
    borderRadius: '4px',
    border: '1px solid var(--border-default)',
    background: rgbString(c),
    flexShrink: 0,
  })

  const highlightLabelStyle: CSSProperties = {
    fontSize: '0.85rem',
    fontWeight: 600,
    color: 'var(--text-primary)',
    minWidth: '5.5rem',
  }

  const rgbRowStyle: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: '0.4rem',
    marginBottom: '0.3rem',
    paddingLeft: '26px', // align under the swatch column
  }

  const rgbChannelLabelStyle: CSSProperties = {
    fontSize: '0.72rem',
    fontWeight: 700,
    color: 'var(--text-secondary)',
    width: '0.9rem',
    textAlign: 'center',
    flexShrink: 0,
  }

  const rgbSliderStyle: CSSProperties = {
    flex: 1,
    accentColor: 'var(--accent-primary)',
    cursor: 'pointer',
    minWidth: '120px',
  }

  const rgbValueStyle: CSSProperties = {
    fontSize: '0.75rem',
    color: 'var(--accent-primary)',
    fontWeight: 700,
    minWidth: '2.2rem',
    textAlign: 'right',
    flexShrink: 0,
  }

  const highlightBlockStyle: CSSProperties = {
    marginBottom: '0.85rem',
  }

  // Sound (priority ding) section styles
  const soundSelectStyle: CSSProperties = {
    flex: 1,
    padding: '0.45rem 0.6rem',
    border: '1px solid var(--border-default)',
    borderRadius: '6px',
    background: 'var(--bg-elevated)',
    color: 'var(--text-primary)',
    fontSize: '0.85rem',
    cursor: 'pointer',
  }

  const soundRowStyle: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem',
    marginBottom: '0.6rem',
  }

  const soundLabelStyle: CSSProperties = {
    fontSize: '0.85rem',
    fontWeight: 600,
    color: 'var(--text-primary)',
    minWidth: '5.5rem',
    flexShrink: 0,
  }

  const soundCheckboxStyle: CSSProperties = {
    width: '18px',
    height: '18px',
    accentColor: 'var(--accent-primary)',
    cursor: 'pointer',
    flexShrink: 0,
  }

  const soundToggleRowStyle: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem',
    marginBottom: '0.75rem',
    cursor: 'pointer',
  }

  // Presets section styles
  const presetRowStyle: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem',
    marginBottom: '0.5rem',
  }

  const presetNameInputStyle: CSSProperties = {
    flex: 1,
    minWidth: 0,
    padding: '0.45rem 0.6rem',
    border: '1px solid var(--border-default)',
    borderRadius: '6px',
    background: 'var(--bg-elevated)',
    color: 'var(--text-primary)',
    fontSize: '0.85rem',
  }

  const presetListStyle: CSSProperties = {
    display: 'flex',
    flexDirection: 'column',
    gap: '0.35rem',
  }

  const presetListRowStyle: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem',
    padding: '0.35rem 0.5rem',
    border: '1px solid var(--border-default)',
    borderRadius: '6px',
    background: 'var(--bg-elevated)',
  }

  const presetListNameStyle: CSSProperties = {
    flex: 1,
    minWidth: 0,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    fontSize: '0.85rem',
    fontWeight: 600,
    color: 'var(--text-primary)',
  }

  const presetErrorStyle: CSSProperties = {
    marginTop: '0.5rem',
    padding: '0.4rem 0.6rem',
    border: '1px solid var(--danger)',
    borderRadius: '6px',
    background: 'var(--bg-elevated)',
    color: 'var(--danger)',
    fontSize: '0.78rem',
    lineHeight: 1.4,
    wordBreak: 'break-word',
  }

  // Hotkeys row styles — one row per action: label, current key, Rebind,
  // Reset. The capturing row is highlighted so it is obvious which row owns the
  // next keypress.
  const hotkeyRowStyle: CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem',
    marginBottom: '0.4rem',
    padding: '0.3rem 0.45rem',
    border: '1px solid var(--border-default)',
    borderRadius: '6px',
    background: 'var(--bg-elevated)',
  }

  const hotkeyLabelStyle: CSSProperties = {
    flex: 1,
    minWidth: 0,
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
    fontSize: '0.85rem',
    fontWeight: 600,
    color: 'var(--text-primary)',
  }

  const hotkeyKeyStyle = (capturing: boolean): CSSProperties => ({
    minWidth: '4.6rem',
    textAlign: 'center',
    padding: '0.2rem 0.5rem',
    border: capturing ? '1px solid var(--accent-primary)' : '1px solid var(--border-default)',
    borderRadius: '4px',
    background: capturing ? 'var(--accent-primary)' : 'var(--bg-surface)',
    color: capturing ? 'white' : 'var(--accent-primary)',
    fontFamily: 'monospace',
    fontSize: '0.8rem',
    fontWeight: 700,
    flexShrink: 0,
  })

  const dingVolumePercent = Math.round(priorityDing.volume * 100)
  // Event-sound section.
  const audioVolumePercent = Math.round(audioVolume * 100)

  const handleDingVolumeChange = (e: ChangeEvent<HTMLInputElement>) => {
    setPriorityDingVolume(Math.max(0, Math.min(1, parseFloat(e.target.value))))
  }

  const handleAudioVolumeChange = (e: ChangeEvent<HTMLInputElement>) => {
    // The setter clamps too; clamping here keeps the displayed percent honest
    // while the drag is in flight.
    setAudioVolume(Math.max(0, Math.min(1, parseFloat(e.target.value))))
  }

  const handleFlashIntensityChange = (e: ChangeEvent<HTMLInputElement>) => {
    const v = Math.max(0, Math.min(1, parseFloat(e.target.value)))
    setPriorityDingFlashIntensity(v)
    // Immediate visual feedback — fire a one-shot test flash at the new level
    // (the same "changes apply immediately" behaviour as the sound preview).
    // With sustain on, preview the HOLD instead (preview:true self-clears), so
    // the tester sees the mode they actually chose.
    window.dispatchEvent(
      priorityDing.flashSustain
        ? new CustomEvent(PRIORITY_FLASH_SUSTAIN_EVENT, { detail: { intensity: v, preview: true } })
        : new CustomEvent(PRIORITY_FLASH_EVENT, { detail: { intensity: v } })
    )
  }

  const handleFlashSustainChange = (on: boolean) => {
    setPriorityDingFlashSustain(on)
    // Live preview of the mode: turning it ON shows the held glow for a moment
    // (the overlay self-clears a preview), turning it OFF takes any glow down.
    // A real hold is bounded by the prompt, not this test.
    window.dispatchEvent(
      on
        ? new CustomEvent(PRIORITY_FLASH_SUSTAIN_EVENT, {
            detail: { intensity: priorityDing.flashIntensity, preview: true },
          })
        : new CustomEvent(PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT)
    )
  }

  const handleDingVariantChange = (e: ChangeEvent<HTMLSelectElement>) => {
    const v = e.target.value as SoundVariant
    setPriorityDingVariant(v)
    // Immediate audible feedback for the selection — the same "changes apply
    // immediately" behaviour as the highlight colors. This click IS a user
    // gesture, so the AudioContext is allowed to run.
    try {
      playSoundVariant(v, priorityDing.volume)
    } catch (err) {
      // Fail Fast: a broken WebAudio stack must be visible, not a silent select.
      console.error('[settings] sound preview failed:', err)
    }
  }

  const handleDingFocusModeChange = (e: ChangeEvent<HTMLSelectElement>) => {
    setPriorityDingFocusMode(e.target.value as DingFocusMode)
  }

  const activateTab = (name: SettingsTabName) => {
    setActiveTab(name)
    tabRefs.current[settingsTabIndex(name)]?.focus()
  }

  const panelId = (name: SettingsTabName): string => `${idPrefix}settings-panel-${tabSlug(name)}`
  const tabId = (name: SettingsTabName): string => `${idPrefix}settings-tab-${tabSlug(name)}`

  const tabBodyStyle: CSSProperties = {
    overflowY: 'auto',
    flex: 1,
    minHeight: 0,
  }

  return (
    <div
      className="modal-overlay"
      style={overlayStyle}
      onClick={(e) => {
        if (e.target === e.currentTarget) close()
      }}
    >
      <div className="modal-content" style={contentStyle}>
        <button
          style={closeButtonStyle}
          onClick={close}
          aria-label="Close settings"
          data-explain="settings-close"
        >
          ✕
        </button>

        <div className="modal-title" style={{ fontSize: '1.3rem', textAlign: 'center' }}>
          Settings
        </div>

        {/* Tab bar — WAI-ARIA tablist, same recipe as LLMConfigTabs: every tab
            button carries the shared explain id (the explain-coverage gate
            counts <button> per file). */}
        <div className="llm-config-tabs" role="tablist" aria-label="Settings sections">
          {SETTINGS_TABS.map((name, idx) => (
            <button
              key={name}
              type="button"
              role="tab"
              id={tabId(name)}
              aria-selected={name === activeTab}
              aria-controls={panelId(name)}
              tabIndex={name === activeTab ? 0 : -1}
              ref={(el) => {
                tabRefs.current[idx] = el
              }}
              className="llm-config-tab"
              data-explain="settings-tab"
              onClick={() => setActiveTab(name)}
              onKeyDown={(e) => {
                const next = settingsTabAfterKey(activeTab, e.key)
                if (next === activeTab) return
                e.preventDefault()
                activateTab(next)
              }}
            >
              {name}
            </button>
          ))}
        </div>

        {/* Panels — all stay MOUNTED-hidden (display:none via the shared
            .llm-config-tabpanel[hidden] rule) so in-progress component-local
            state (the Presets name input) survives a tab switch. */}
        <div style={tabBodyStyle}>
          {/* ── Display: font, UI scale, card size, auto-fit, hover preview ── */}
          <div role="tabpanel" id={panelId('Display')} aria-labelledby={tabId('Display')} hidden={activeTab !== 'Display'} className="llm-config-tabpanel">
            {/* Font Size */}
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>Font Size</span>
                <span style={valueStyle}>{fontPercent}%</span>
              </div>
              <input
                type="range"
                min={0.75}
                max={1.5}
                step={0.05}
                value={fontScale}
                onChange={handleFontChange}
                style={sliderStyle}
              />
              <div style={hintStyle}>
                Scales the UI font size independently of browser zoom.
              </div>
            </div>

            {/* UI Scale — structural scale for the whole interface */}
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>UI Scale</span>
                <span style={valueStyle}>{uiPercent}%</span>
              </div>
              <input
                type="range"
                min={0.75}
                max={UI_SCALE_MAX}
                step={uiScaleStep}
                value={uiScale}
                onChange={handleUiScaleChange}
                style={sliderStyle}
              />
              <div style={hintStyle}>
                Scales the whole interface — panel widths, header/phase bar, buttons and
                spacing — together with the window. 100% is unscaled; 135% is the
                comfortable default for 1080p. Goes up to 600% for 4K and larger
                monitors (fine steps below 200%, coarser above).
              </div>
            </div>

            {/* Card Display Size */}
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>Card Display Size</span>
                <span style={valueStyle}>{cardPercent}%</span>
              </div>
              <input
                type="range"
                min={0.5}
                max={2.0}
                step={0.1}
                value={cardScale}
                onChange={handleCardChange}
                style={sliderStyle}
              />
              <div style={hintStyle}>
                Scales card thumbnails independently of browser zoom.
                {autoFitBattlefield
                  ? ' Auto-fit battlefield is ON: this acts as a size CAP — the board always shrinks to fit the screen first.'
                  : ' Auto-fit battlefield is OFF: this slider directly sets the card size.'}
              </div>
            </div>

            {/* Auto-fit battlefield */}
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>Auto-fit battlefield</span>
              </div>
              <div style={themeButtonRowStyle}>
                <button
                  style={autoFitBattlefield ? themeButtonActive : themeButtonInactive}
                  onClick={() => handleAutoFitChange(true)}
                  data-explain="settings-autofit-on"
                >
                  On
                </button>
                <button
                  style={!autoFitBattlefield ? themeButtonActive : themeButtonInactive}
                  onClick={() => handleAutoFitChange(false)}
                  data-explain="settings-autofit-off"
                >
                  Off
                </button>
              </div>
              <div style={hintStyle}>
                Shrink cards automatically so both battlefields and both hands always fit
                the screen without scrolling, no matter how full the board gets. Card
                Display Size then only limits how big the cards may get. Off restores the
                fixed viewport-based card size, which may require scrolling.
              </div>
            </div>

            {/* Hover Preview Size */}
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>Hover Preview Size</span>
                <span style={valueStyle}>{cardPreviewPercent}%</span>
              </div>
              <input
                type="range"
                min={0.5}
                max={4.0}
                step={0.1}
                value={cardPreviewScale}
                onChange={handleCardPreviewScaleChange}
                style={sliderStyle}
                data-explain="settings-card-preview-scale"
              />
              <div style={hintStyle}>
                Scales the card panel that appears when you hover a card — image and
                rules text together, independently of the Card Display Size.
                Oversized previews stay clamped to the window.
              </div>
            </div>

            {/* ── Multiplayer layout:
                two toggles (+ a sub-toggle) for how 3+ player games render.
                Inert in a 2-player game — the existing 1v1 path is exact. The
                layout math lives in utils/boardModes.ts via the shared
                GameLayout shell (live board AND replay viewer). ── */}
            <div style={{ borderTop: '1px solid var(--border-default)', marginTop: '0.9rem', paddingTop: '0.9rem' }}>
              <div style={sectionStyle}>
                <div style={labelStyle}>
                  <span>Multiplayer layout</span>
                </div>
                <div style={hintStyle}>
                  How a game with 3 or more players arranges the boards (2-player
                  games always use the standard split). Applies to the live board
                  and the replay viewer alike.
                </div>

                <label style={soundToggleRowStyle} data-explain="board-mode-maximize-row">
                  <input
                    type="checkbox"
                    checked={boardModeMaximizeOpponents}
                    onChange={(e) => setBoardModeMaximizeOpponents(e.target.checked)}
                    style={soundCheckboxStyle}
                    data-explain="board-mode-maximize"
                  />
                  <span style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                    Maximize my board
                  </span>
                </label>
                <div style={hintStyle}>
                  Opponents collapse into a compact strip of chips along the top of the
                  board area — each chip shows their avatar, name and life — and your
                  own board takes the remaining height.
                </div>

                <label style={soundToggleRowStyle} data-explain="board-mode-focus-row">
                  <input
                    type="checkbox"
                    checked={boardModeFocusEnabled}
                    onChange={(e) => setBoardModeFocusEnabled(e.target.checked)}
                    style={soundCheckboxStyle}
                    data-explain="board-mode-focus"
                  />
                  <span style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                    Focus on one opponent
                  </span>
                </label>
                <div style={hintStyle}>
                  Play effectively 1-on-1: the board split shows only the focused
                  opponent. The other opponents are small chips above — click one to
                  focus them, use the &#9664;/&#9654; arrows to cycle, and &#10005;
                  returns to all players. While a player is focused, this beats the
                  Maximize toggle above.
                </div>

                <label style={soundToggleRowStyle} data-explain="board-mode-follow-active-row">
                  <input
                    type="checkbox"
                    checked={boardModeFollowActive}
                    onChange={(e) => setBoardModeFollowActive(e.target.checked)}
                    style={soundCheckboxStyle}
                    // Sub-toggle: visible/enabled only when Focus is on.
                    disabled={!boardModeFocusEnabled}
                    data-explain="board-mode-follow-active"
                  />
                  <span
                    style={{
                      fontSize: '0.9rem',
                      fontWeight: 600,
                      color: boardModeFocusEnabled ? 'var(--text-primary)' : 'var(--text-muted)',
                    }}
                  >
                    Follow the active player
                  </span>
                </label>
                <div style={hintStyle}>
                  {boardModeFocusEnabled
                    ? 'The focused opponent follows whoever\u2019s turn it is among your opponents; on your own turn the last active opponent stays. Turn it off to keep the choice pinned as clicked.'
                    : 'Needs "Focus on one opponent" to be on.'}
                </div>
              </div>
            </div>
          </div>

          {/* ── Appearance: theme + avatar + highlight colours ── */}
          <div role="tabpanel" id={panelId('Appearance')} aria-labelledby={tabId('Appearance')} hidden={activeTab !== 'Appearance'} className="llm-config-tabpanel">
            {/* My Avatar — the desktop's "My Avatar" tab, folded into
                Appearance because this dialog has no separate tab for it.
                Placement rationale: the desktop keeps the picker on its OWN
                Preferences tab, and the alternative (the connect overlay) would
                hide a per-user identity choice behind a screen the user only
                sees at login — while the Appearance tab is where every other
                "what does my client look like" control already lives. Changing
                it mid-session pushes to the running game (the desktop's own
                behavior), so it is never a next-connect-only setting. */}
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>My Avatar</span>
                <span style={valueStyle}>#{avatarId}</span>
              </div>
              <div style={avatarGridStyle}>
                {AVATAR_IDS.map((id) => (
                  <button
                    key={id}
                    style={avatarId === id ? avatarButtonActive : avatarButtonInactive}
                    onClick={() => handleAvatarChange(id)}
                    data-explain="settings-avatar-pick"
                    aria-label={`Avatar ${id}`}
                    aria-pressed={avatarId === id}
                  >
                    <img
                      src={avatarUrlFor(id)}
                      alt=""
                      width={48}
                      height={48}
                      className="settings-avatar-thumb"
                      data-avatar-id={id}
                    />
                  </button>
                ))}
              </div>
              <div style={hintStyle}>
                The picture shown next to your name on the game board. Avatar {DEFAULT_AVATAR_ID} is
                the default. Changing it applies to this session immediately — the other players
                see the new avatar on their boards too.
              </div>
            </div>

            {/* Theme */}
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>Theme</span>
              </div>
              <div style={themeButtonRowStyle}>
                <button
                  style={theme === 'dark' ? themeButtonActive : themeButtonInactive}
                  onClick={() => handleThemeChange('dark')}
                  data-explain="settings-theme-dark"
                >
                  Dark
                </button>
                <button
                  style={theme === 'light' ? themeButtonActive : themeButtonInactive}
                  onClick={() => handleThemeChange('light')}
                  data-explain="settings-theme-light"
                >
                  Light
                </button>
              </div>
              <div style={hintStyle}>
                Switch between dark and light color schemes.
              </div>
            </div>

            {/* Highlight Colors */}
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>Highlight Colors</span>
              </div>
              <div style={{ ...hintStyle, marginBottom: '0.75rem' }}>
                Customize the RGB glow colors used to highlight cards and zones.
                Changes apply immediately.
              </div>
              {highlights.map((hl) => (
                <div key={hl.label} style={highlightBlockStyle}>
                  <div style={highlightRowStyle}>
                    <div style={highlightSwatchStyle(hl.value)} />
                    <span style={highlightLabelStyle}>{hl.label}</span>
                    <span
                      style={{
                        fontSize: '0.72rem',
                        color: 'var(--text-secondary)',
                        fontFamily: 'monospace',
                        marginLeft: 'auto',
                      }}
                    >
                      {rgbString(hl.value)}
                    </span>
                  </div>
                  {(['r', 'g', 'b'] as const).map((ch) => (
                    <div key={ch} style={rgbRowStyle}>
                      <span style={rgbChannelLabelStyle}>{ch.toUpperCase()}</span>
                      <input
                        type="range"
                        min={0}
                        max={255}
                        step={1}
                        value={hl.value[ch]}
                        onChange={makeHighlightHandler(hl.setter, hl.value, ch)}
                        style={rgbSliderStyle}
                      />
                      <span style={rgbValueStyle}>{hl.value[ch]}</span>
                    </div>
                  ))}
                </div>
              ))}
            </div>
          </div>

          {/* ── Alerts: priority ding + screen flash + prompt memory (the flat
              column's biggest section — exactly what a tab isolates) ── */}
          <div role="tabpanel" id={panelId('Alerts')} aria-labelledby={tabId('Alerts')} hidden={activeTab !== 'Alerts'} className="llm-config-tabpanel">
            {/* Alerts — priority ding + screen flash */}
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>Sound</span>
              </div>
              <label style={soundToggleRowStyle}>
                <input
                  type="checkbox"
                  checked={priorityDing.enabled}
                  onChange={(e) => setPriorityDingEnabled(e.target.checked)}
                  style={soundCheckboxStyle}
                  data-explain="settings-ding-enabled"
                />
                <span style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                  Play a sound when it is your turn
                </span>
              </label>
              <div style={soundRowStyle}>
                <span style={soundLabelStyle}>Sound</span>
                <select
                  value={priorityDing.variant}
                  onChange={handleDingVariantChange}
                  style={soundSelectStyle}
                  data-explain="settings-ding-variant"
                >
                  {SOUND_VARIANTS.map((sv) => (
                    <option key={sv.value} value={sv.value}>{sv.label}</option>
                  ))}
                </select>
              </div>
              <div style={soundRowStyle}>
                <span style={soundLabelStyle}>Volume</span>
                <input
                  type="range"
                  min={0}
                  max={1}
                  step={0.05}
                  value={priorityDing.volume}
                  onChange={handleDingVolumeChange}
                  style={{ ...sliderStyle, flex: 1 }}
                  data-explain="settings-ding-volume"
                />
                <span style={rgbValueStyle}>{dingVolumePercent}%</span>
              </div>
              <div style={soundRowStyle}>
                <span style={soundLabelStyle}>Play</span>
                <select
                  value={priorityDing.focusMode}
                  onChange={handleDingFocusModeChange}
                  style={soundSelectStyle}
                  data-explain="settings-ding-focus"
                >
                  <option value="unfocused">Only when the window is out of focus</option>
                  <option value="always">Always (focused or not)</option>
                  <option value="muted-unfocused">Never when the window is out of focus</option>
                </select>
              </div>
              <div style={hintStyle}>
                Rings once each time a new question waits for you (a spell to respond to,
                a target to choose, your draw step). Choosing a sound previews it.
                Browsers only allow audio after your first click or keypress on the page —
                until then the ding stays silent.
              </div>

              {/* Screen flash — the second alert channel */}
              <label style={soundToggleRowStyle}>
                <input
                  type="checkbox"
                  checked={priorityDing.flashEnabled}
                  onChange={(e) => setPriorityDingFlashEnabled(e.target.checked)}
                  style={soundCheckboxStyle}
                  data-explain="settings-flash-enabled"
                />
                <span style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                  Flash the screen edge when it is your turn
                </span>
              </label>
              <div style={soundRowStyle}>
                <span style={soundLabelStyle}>Intensity</span>
                <input
                  type="range"
                  min={0}
                  max={1}
                  step={0.05}
                  value={priorityDing.flashIntensity}
                  onChange={handleFlashIntensityChange}
                  style={{ ...sliderStyle, flex: 1 }}
                  data-explain="settings-flash-intensity"
                />
                <span style={rgbValueStyle}>{flashIntensityPercent}%</span>
              </div>
              <div style={hintStyle}>
                The border of the whole screen glows once in the accent colour for half a
                second — visible even when the window is not focused, as long as the page
                is on screen. Fire a test flash by changing the intensity.
              </div>

              {/* Sustain mode — hold the glow while a prompt
                  is open, instead of pulsing once. */}
              <label style={soundToggleRowStyle}>
                <input
                  type="checkbox"
                  checked={priorityDing.flashSustain}
                  onChange={(e) => handleFlashSustainChange(e.target.checked)}
                  style={soundCheckboxStyle}
                  data-explain="settings-flash-sustain"
                />
                <span style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                  Hold the glow while a prompt is open
                </span>
              </label>
              <div style={hintStyle}>
                Sustained flash: the edge stays lit at the Intensity above for as long as a
                question is waiting on you, and goes out the moment you answer, pass or the
                game ends — instead of one quick pulse. Hidden pages stay dark. Toggling this
                shows a short preview of the hold.
              </div>

              {/* Prompt window position memory */}
              <label style={soundToggleRowStyle}>
                <input
                  type="checkbox"
                  checked={priorityDing.rememberPromptPosition}
                  onChange={(e) => setPriorityDingRememberPosition(e.target.checked)}
                  style={soundCheckboxStyle}
                  data-explain="settings-remember-prompt-position"
                />
                <span style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                  Prompt window stays where you dragged it
                </span>
              </label>
              <div style={hintStyle}>
                When on, the centered prompt window reopens at the position you last
                dragged it to (instead of the screen centre). The mana-payment window
                still anchors at your mana pool.
              </div>

              {/* ── Event sounds — appended INSIDE the Alerts tab. The
                  priority-ding controls above stay untouched; this is
                  the master switch for the game/lobby event sounds (start, stack,
                  game end, table seat changes, whispers, skip acks). The two layers
                  are independent: muting here keeps the ding and vice versa. ── */}
              <div style={{ borderTop: '1px solid var(--border-default)', marginTop: '0.9rem', paddingTop: '0.9rem' }}>
                <label style={soundToggleRowStyle}>
                  <input
                    type="checkbox"
                    checked={audioEnabled}
                    onChange={(e) => setAudioEnabled(e.target.checked)}
                    style={soundCheckboxStyle}
                    data-explain="settings-audio-enabled"
                  />
                  <span style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                    Game event sounds
                  </span>
                </label>
                <div style={soundRowStyle}>
                  <span style={soundLabelStyle}>Volume</span>
                  <input
                    type="range"
                    min={0}
                    max={1}
                    step={0.05}
                    value={audioVolume}
                    onChange={handleAudioVolumeChange}
                    style={{ ...sliderStyle, flex: 1 }}
                    data-explain="settings-audio-volume"
                  />
                  <span style={rgbValueStyle}>{audioVolumePercent}%</span>
                </div>
                <div style={hintStyle}>
                  Sounds for game events: the start of your game, a new object on the stack,
                  winning or losing a game, players joining or leaving your table, an incoming
                  whisper, and arming or cancelling a skip. These are separate from the priority
                  ding above. Browsers only allow audio after your first click or keypress on the
                  page — until then everything stays silent.
                </div>
              </div>
            </div>
          </div>

          {/* ── Tokens: the global token-art override ── */}
          <div role="tabpanel" id={panelId('Tokens')} aria-labelledby={tabId('Tokens')} hidden={activeTab !== 'Tokens'} className="llm-config-tabpanel">
            {/* Token art: tokens render with a chosen printing instead of
                the engine's default. The per-token picker is the right-click menu on a
                token; this is the global override switch plus a list of what is set. */}
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>Token Art</span>
              </div>
              <label style={soundToggleRowStyle}>
                <input
                  type="checkbox"
                  checked={tokenArtOverride}
                  onChange={(e) => setTokenArtOverrideState(e.target.checked)}
                  style={soundCheckboxStyle}
                  data-explain="settings-token-art-override"
                />
                <span style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                  Always use my token art
                </span>
              </label>
              <div style={hintStyle}>
                A player at this table can pick which printing of a token is shown; that choice
                follows their tokens on everyone&rsquo;s board. Turn this on to use YOUR chosen
                art everywhere instead, including on an opponent&rsquo;s tokens. Off, your art
                applies only where the owner has not picked one. Right-click a token to choose
                its art.
              </div>
              {tokenArtOwnCount > 0 && (
                <div style={hintStyle}>
                  {tokenArtOwnCount} token type{tokenArtOwnCount === 1 ? '' : 's'} set to your art.
                  &ldquo;Reset to Default&rdquo; clears them.
                </div>
              )}
            </div>
          </div>

          {/* ── LLM Chat: the replay-history toggle's settings home
              (the toggle lives in the Settings dialog) ── */}
          <div role="tabpanel" id={panelId('LLM Chat')} aria-labelledby={tabId('LLM Chat')} hidden={activeTab !== 'LLM Chat'} className="llm-config-tabpanel">
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>LLM Chat</span>
              </div>
              <label style={soundToggleRowStyle}>
                <input
                  type="checkbox"
                  checked={replayLLMFullHistory}
                  onChange={(e) => setReplayLLMFullHistory(e.target.checked)}
                  style={soundCheckboxStyle}
                  data-explain="replay-llm-history-toggle"
                />
                <span style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-primary)' }}>
                  Show entire history in replays
                </span>
              </label>
              <div style={hintStyle}>
                In the replay viewer&rsquo;s LLM tab, show the conversation accumulated UP TO
                the current frame instead of only the exchanges recorded at that exact frame.
                Off, the panel returns to single-frame behavior. The choice is remembered in
                this browser (the same setting the in-panel checkbox edits).
              </div>
            </div>
          </div>

          {/* ── Presets — named save/load of everything Reset to
              Default covers; the section sits beside the other whole-dialog
              controls. Java reference: PreferencesDialog's size presets. ── */}
          <div role="tabpanel" id={panelId('Presets')} aria-labelledby={tabId('Presets')} hidden={activeTab !== 'Presets'} className="llm-config-tabpanel">
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>Presets</span>
              </div>
              <div style={hintStyle}>
                Save the current settings as a named preset and switch between them —
                every setting in this dialog is captured, including the panel widths and
                the battlefield divider.
              </div>
              <div style={{ ...presetRowStyle, marginTop: '0.6rem' }}>
                <input
                  type="text"
                  value={presetName}
                  onChange={(e) => setPresetName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') handleSavePreset()
                  }}
                  placeholder="Preset name"
                  style={presetNameInputStyle}
                  aria-label="Preset name"
                  data-explain="settings-preset-name"
                />
                <button
                  className="btn btn-secondary"
                  onClick={handleSavePreset}
                  data-explain="settings-preset-save"
                >
                  Save
                </button>
              </div>
              {presets.length > 0 ? (
                <div style={presetListStyle}>
                  {presets.map((p) => (
                    <div key={p.name} style={presetListRowStyle}>
                      <span style={presetListNameStyle} title={p.name}>{p.name}</span>
                      <button
                        className="btn btn-primary btn-sm"
                        onClick={() => handleLoadPreset(p.name)}
                        data-explain="@Load this preset — apply every saved setting in one click."
                      >
                        Load
                      </button>
                      <button
                        className="btn btn-danger btn-sm"
                        onClick={() => handleDeletePreset(p.name)}
                        data-explain="@Delete this preset — remove it from the list. Your current settings stay as they are."
                      >
                        Delete
                      </button>
                    </div>
                  ))}
                </div>
              ) : (
                presetError === '' && (
                  <div style={hintStyle}>No presets saved yet.</div>
                )
              )}
              {presetError !== '' && (
                <div style={presetErrorStyle}>
                  Presets could not be read: {presetError}
                </div>
              )}
            </div>
          </div>

          {/* ── Hotkeys — rebind the game shortcuts. Java reference:
              PreferencesDialog's "Game - Hotkeys" tab (click a button, press a
              key; Escape cancels; Space clears; a Reset to default per row and
              for the whole tab). The key map itself is the registry in
              utils/keybindings.ts; this panel renders whatever it holds, so a
              new action appears here with no edit to this file. The replay
              rows are shown but FIXED — their keys are shared with typing,
              menus and this dialog through ReplayView's hand-back. ── */}
          <div role="tabpanel" id={panelId('Hotkeys')} aria-labelledby={tabId('Hotkeys')} hidden={activeTab !== 'Hotkeys'} className="llm-config-tabpanel">
            <div style={sectionStyle}>
              <div style={labelStyle}>
                <span>Hotkeys</span>
              </div>
              <div style={hintStyle}>
                Click Rebind, then press the key you want. Escape cancels a rebind; the
                shortcuts apply immediately and are remembered in this browser. One key can
                only mean one thing — a key another action already uses is refused with a
                message here.
              </div>
              {keybindingProblems.length > 0 && (
                <div style={presetErrorStyle}>
                  Some saved shortcuts could not be read: {keybindingProblems.join(' ')}
                </div>
              )}
              {(['game', 'replay'] as const).map((scope) => (
                <div key={scope} style={{ marginTop: '0.6rem' }}>
                  <div style={{ ...hintStyle, fontWeight: 700, color: 'var(--text-primary)' }}>
                    {scope === 'game' ? 'Game' : 'Replay viewer (fixed)'}
                  </div>
                  {KEYBINDING_ACTIONS.filter((a) => a.scope === scope).map((a) => (
                    <div key={a.id} style={hotkeyRowStyle}>
                      <span style={hotkeyLabelStyle} title={a.label}>{a.label}</span>
                      <span style={hotkeyKeyStyle(capturingAction === a.id)}>
                        {capturingAction === a.id ? 'Press a key…' : formatKeySpec(keyFor(a.id))}
                      </span>
                      {a.rebindable ? (
                        <>
                          <button
                            className={capturingAction === a.id ? 'btn btn-primary btn-sm' : 'btn btn-secondary btn-sm'}
                            onClick={() => toggleCapture(a.id)}
                            data-explain="settings-hotkeys-rebind"
                          >
                            {capturingAction === a.id ? 'Cancel' : 'Rebind'}
                          </button>
                          <button
                            className="btn btn-secondary btn-sm"
                            onClick={() => handleResetKeybinding(a.id)}
                            disabled={!isKeybindingModified(a.id)}
                            data-explain="settings-hotkeys-reset"
                          >
                            Reset
                          </button>
                        </>
                      ) : (
                        <span style={{ ...hintStyle, marginTop: 0, minWidth: '7.5rem', textAlign: 'right' }}>
                          fixed
                        </span>
                      )}
                      {captureError !== null && captureError.id === a.id && (
                        <span style={{ ...presetErrorStyle, marginTop: 0, flexBasis: '100%' }}>
                          {captureError.message}
                        </span>
                      )}
                    </div>
                  ))}
                  {scope === 'replay' && (
                    <div style={hintStyle}>
                      The replay viewer hands these keys back to text fields, open menus and this
                      dialog (Escape closes it there), so they are not rebindable.
                    </div>
                  )}
                </div>
              ))}
              <button
                className="btn btn-secondary btn-sm"
                onClick={handleResetAllKeybindings}
                data-explain="settings-hotkeys-reset-all"
              >
                Reset All Shortcuts
              </button>
            </div>
          </div>
        </div>

        {/* Footer — sticky: outside the scrolling panel body, so Reset to
            Default / Close are visible on every tab (one DOM copy). */}
        <div style={footerStyle}>
          <button
            className="btn btn-secondary"
            onClick={handleResetToDefault}
            data-explain="settings-reset"
          >
            Reset to Default
          </button>
          <button className="btn btn-primary" onClick={close} data-explain="settings-done">
            Close
          </button>
        </div>
      </div>
    </div>
  )
}