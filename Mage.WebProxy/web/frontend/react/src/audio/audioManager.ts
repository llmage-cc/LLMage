/**
 * Event-sound manager — the web client's equivalent of the
 * desktop `mage.client.util.audio.AudioManager` (Mage.Client/.../AudioManager.java).
 *
 * ── WHY HTMLAudioElement (and not WebAudio buffers) ──
 * The desktop ships its 32 WAVs as static files; the web equivalent keeps the
 * same shape. `HTMLAudioElement` plays a file with no decode plumbing at all,
 * one element per sound id, reused across triggers (a second trigger restarts
 * the element from 0 instead of fanning out overlapping copies — the desktop's
 * LinePool does the loudness bookkeeping instead). The browser's own autoplay
 * policy is the same gate the synthesized ding already documents, so both
 * layers share ONE unlock mechanism (`audioCore.ts`) — no second context, no
 * second listener pair.
 *
 * ── TRIGGER TABLE (sound → event source) ──
 * Wired by `audioTriggers.ts`; each entry names the shipped event source (the
 * hook call site in THIS tree) and the desktop call site it mirrors.
 *
 * | sound id        | file                       | web event source (file:line)                              | desktop reference                                          |
 * |-----------------|----------------------------|-----------------------------------------------------------|------------------------------------------------------------|
 * | game-start      | OnGameStart.wav            | network/messageHandlers.ts:139 (start_game, NEW game only  | GamePanel.java:867 playYourGameStarted (joinGame)          |
 * |                 |                            | — markGameStart() false on a rejoin re-announcement)       |                                                            |
 * | game-can-start  | GameCanStart.wav           | store/slices/lobbySlice.ts:128 (setTables) — the watched   | TableWaitingDialog.java:423 playGameCanStart               |
 * |                 |                            | table's seatNames reaches maxPlayers                       |                                                            |
 * | stack-new       | OnStackNew.wav             | store/slices/gameplaySlice.ts:542 (setGameState) — stack   | AudioManager.java:175 playStackNew (no call site upstream) |
 * |                 |                            | length INCREASED vs the previous state                     |                                                            |
 * | player-won      | OnPlayerWon.wav            | network/messageHandlers.ts:231 (game_over) with            | GameEndDialog.java:82 playPlayerWon (hasWon)               |
 * |                 |                            | hasWon === true (authoritative payload only)               |                                                            |
 * | player-lost     | OnPlayerLost.wav           | same site, hasWon === false                                | GameEndDialog.java:84 playPlayerLost                       |
 * | player-joined   | OnPlayerJoined.wav         | lobbySlice.ts:128 (setTables) — seat count rose (and the   | TableWaitingDialog.java:426 playPlayerJoinedTable          |
 * |                 |                            | table is not yet full)                                     |                                                            |
 * | player-left     | OnPlayerLeft.wav           | lobbySlice.ts:128 (setTables) — seat count dropped         | TableWaitingDialog.java:429 playPlayerLeft                 |
 * | player-whispered| OnPlayerWhispered.wav      | messageHandlers.ts:251 (event) with chatType              | CallbackClientImpl.java:189 playPlayerWhispered            |
 * |                 |                            | 'whisper_from' (incoming whispers only)                    | (ChatSession.java:132 sets SoundToPlay.PlayerWhispered)    |
 * | skip-armed      | OnSkipButton.wav           | gameplaySlice.ts:981 (skipPriority) after the send         | GamePanel.java:2873+ playOnSkipButton (each skip button)   |
 * | skip-cancelled  | OnSkipButtonCancel.wav     | gameplaySlice.ts:988 (cancelSkip) after the send           | GamePanel.java:2957 playOnSkipButtonCancel                 |
 *
 * ── PROMPT-ARRIVAL COORDINATION (no double-fire) ──
 * The priority ding (store/slices/priorityDing.ts + audio/dingSynth.ts) already
 * OWNS the "a new question waits for your answer" moment: one synthesized cue
 * per fresh prompt arrival (epoch-deduped, mana re-prompt suppressed).
 * Therefore this manager ships NO prompt sound and no trigger wires one — a
 * second cue at the same event would double-fire against the ding. The ding
 * keeps working exactly as before (App.tsx still installs the same shared
 * unlock); only its AudioContext/unlock plumbing moved into `audioCore.ts`.
 *
 * ── WHY SOME DESKTOP SOUNDS ARE NOT SHIPPED ──
 * Curated scope: infrastructure + the core event set. Not shipped: hover /
 * button ticks (OnHover, OnButtonOk/Cancel — per-click noise), board-diff
 * sounds (OnSummon, OnSummon-, OnAttack, OnBlock, OnTapPermanent, OnAddPermanent,
 * OnAddArtifact, OnDraw, OnEndTurn, OnNextPhase — each needs a per-card board
 * diff, deferred with the board-animation work), draft sounds
 * (OnCountdown1/OnDraftSelect — no draft UI in the web client yet), page/tab
 * ticks (OnPrevPage/OnNextPage), FeedbackNeeded (no helper-panel surface),
 * OnPlayerSubmittedDeck / OnPlayerQuitTournament / OnTournamentStart (no
 * dedicated web event wiring yet).
 *
 * ── SETTINGS ──
 * Master enable + volume live in the store's xmage-settings object
 * (`audioEnabled` default true, `audioVolume` default 0.6 — see
 * utils/settingsDefaults.ts) and are mirrored into THIS module's live snapshot
 * by uiSlice's setters, the same mirror pattern the priority-ding store field
 * uses. `play()` reads the snapshot; it never imports the store (the store
 * transitively imports this module through the trigger call sites, so a store
 * import here would be a cycle).
 *
 * Fail Fast: a missing `Audio` constructor is a hard error (thrown from
 * `play`), not a degraded path; the trigger layer surfaces it once per session.
 * A sound requested before the page's first user gesture cannot play — that is
 * the browser's documented autoplay rule, logged rather than toasted (same
 * posture as the ding).
 */

import { isAudioUnlocked } from './audioCore'

/** Every sound this manager can play. Extend the union + AUDIO_SOUNDS together. */
export type SoundId =
  | 'game-start'
  | 'game-can-start'
  | 'stack-new'
  | 'player-won'
  | 'player-lost'
  | 'player-joined'
  | 'player-left'
  | 'player-whispered'
  | 'skip-armed'
  | 'skip-cancelled'

export interface AudioSoundSpec {
  /** File under {SOUND_BASE_URL}; the desktop's own WAV, copied verbatim. */
  readonly file: string
  /**
   * Loudness trim applied to the master volume — HTMLAudioElement.volume is
   * capped at 1, so this only ever ATTENUATES. Values come from the measured
   * peak of each file on base commit fe682e48 (GameCanStart peaks at 0.587;
   * every other shipped file ≤ 0.178), normalising the one near-full-scale
   * clip so the master volume means the same thing across sounds.
   */
  readonly gain: number
  /**
   * Minimum ms between two plays of THIS sound id (per-sound debounce). The
   * event sources can burst (seat churn per table_list push, stack growth per
   * state push); the desktop relies on dialog-open state instead, which the
   * web client does not have for all sites.
   */
  readonly minIntervalMs: number
}

/** The served location of the coped WAV subset (Mage.WebProxy/web/frontend/sounds/). */
export const SOUND_BASE_URL = '/sounds/'

export const AUDIO_SOUNDS: Record<SoundId, AudioSoundSpec> = {
  'game-start': { file: 'OnGameStart.wav', gain: 1, minIntervalMs: 1000 },
  'game-can-start': { file: 'GameCanStart.wav', gain: 0.85, minIntervalMs: 2000 },
  'stack-new': { file: 'OnStackNew.wav', gain: 1, minIntervalMs: 200 },
  'player-won': { file: 'OnPlayerWon.wav', gain: 1, minIntervalMs: 1000 },
  'player-lost': { file: 'OnPlayerLost.wav', gain: 1, minIntervalMs: 1000 },
  'player-joined': { file: 'OnPlayerJoined.wav', gain: 1, minIntervalMs: 300 },
  'player-left': { file: 'OnPlayerLeft.wav', gain: 1, minIntervalMs: 300 },
  'player-whispered': { file: 'OnPlayerWhispered.wav', gain: 1, minIntervalMs: 300 },
  'skip-armed': { file: 'OnSkipButton.wav', gain: 1, minIntervalMs: 150 },
  'skip-cancelled': { file: 'OnSkipButtonCancel.wav', gain: 1, minIntervalMs: 150 },
}

/** Every id, in the table's order — the preload sweep and the harness iterate this. */
export const SOUND_IDS: readonly SoundId[] = Object.keys(AUDIO_SOUNDS) as SoundId[]

// ── Live settings snapshot (mirrored from the store by uiSlice) ──

export interface AudioSettings {
  /** Master enable (store field `audioEnabled`). */
  enabled: boolean
  /** Master volume 0..1 (store field `audioVolume`). */
  volume: number
}

/** Clamp a volume to the only range HTMLAudioElement.volume accepts. */
export function clampVolume(v: number): number {
  if (!Number.isFinite(v)) return 0
  return Math.min(1, Math.max(0, v))
}

/**
 * Defaults mirror DEFAULT_SETTINGS.audioEnabled/audioVolume exactly; uiSlice
 * overwrites this at module init from the persisted settings, so the two can
 * only agree. This constant is what a harness sees before any store boots.
 */
const DEFAULT_AUDIO_SETTINGS: AudioSettings = { enabled: true, volume: 0.6 }

let settings: AudioSettings = { ...DEFAULT_AUDIO_SETTINGS }

/** Read the live audio settings. */
export function getAudioSettings(): AudioSettings {
  return settings
}

/** Apply a settings patch (volume clamped). Called by uiSlice's setters. */
export function setAudioSettings(patch: Partial<AudioSettings>): AudioSettings {
  settings = {
    enabled: patch.enabled ?? settings.enabled,
    volume: patch.volume === undefined ? settings.volume : clampVolume(patch.volume),
  }
  return settings
}

// ── Playback ──

type AudioCtor = new (src?: string) => HTMLAudioElement

function getAudioCtor(): AudioCtor {
  const g = globalThis as { Audio?: AudioCtor }
  if (!g.Audio) {
    throw new Error('HTML audio is not available — the web client cannot play sound elements.')
  }
  return g.Audio
}

/** One element per sound id, created lazily and reused (restart-from-0 on replay). */
const elements = new Map<SoundId, HTMLAudioElement>()

function elementFor(id: SoundId): HTMLAudioElement {
  let el = elements.get(id)
  if (!el) {
    el = new (getAudioCtor())(SOUND_BASE_URL + AUDIO_SOUNDS[id].file)
    el.preload = 'auto'
    el.load()
    elements.set(id, el)
  }
  return el
}

/** Last play timestamp per sound id (the per-sound debounce bookkeeping). */
const lastPlayedAt = new Map<SoundId, number>()

/**
 * Create and start loading every sound element. Safe BEFORE the first user
 * gesture (the autoplay policy gates `play()`, not fetching/decoding), which is
 * why App.tsx calls this at mount, beside `installAudioUnlock()`.
 */
export function preloadAudioSounds(): void {
  for (const id of SOUND_IDS) elementFor(id)
}

/**
 * Play one sound. Order of gates:
 *   1. master enable (`audioEnabled`) — nothing else runs when off;
 *   2. user-gesture unlock — before the page's first interaction the browser
 *      refuses page audio, so the request is logged and dropped (no element is
 *      even created);
 *   3. per-sound debounce — a burst of the same event coalesces to one cue;
 *   4. loudness — element.volume = clamp(master volume × sound gain).
 * Throws when no HTML `Audio` constructor exists (Fail Fast — the trigger layer
 * reports it once). Returns true when the sound was started.
 */
export function play(soundId: SoundId): boolean {
  if (!settings.enabled) return false
  if (!isAudioUnlocked()) {
    // The browser's documented gesture rule, not an error path — same posture
    // as dingSynth's suspended-context log.
    console.warn(`[audio] sound skipped — no user interaction yet (${soundId})`)
    return false
  }
  const spec = AUDIO_SOUNDS[soundId]
  const now = Date.now()
  const last = lastPlayedAt.get(soundId)
  if (last !== undefined && now - last < spec.minIntervalMs) return false
  lastPlayedAt.set(soundId, now)

  const el = elementFor(soundId)
  el.currentTime = 0
  el.volume = clampVolume(settings.volume * spec.gain)
  // A refused play() (a race against the autoplay grant) is visible in the
  // console — never a silent no-op.
  el.play().catch((e: unknown) => {
    console.warn(`[audio] playback refused by the browser (${soundId}):`, e)
  })
  return true
}

/**
 * Forget debounce timestamps and cached elements. The harness calls this
 * between scenarios (one process runs many games in sequence); in the app it
 * only drops caches, which the next play/preload recreates.
 */
export function resetAudioRuntime(): void {
  for (const el of elements.values()) el.pause()
  elements.clear()
  lastPlayedAt.clear()
}
