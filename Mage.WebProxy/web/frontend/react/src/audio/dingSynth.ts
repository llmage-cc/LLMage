/**
 * WebAudio synth for UI sounds — no binary audio assets, fully parameterizable.
 *
 * WHY: the client had zero audio infrastructure. A tiny
 * OscillatorNode-based synth needs no files in the repo, can be previewed from the
 * settings dialog, and every parameter (variant, volume) is a plain persisted
 * setting.
 *
 * ── BROWSER AUTOPLAY POLICY ──
 * An AudioContext created before any user gesture starts in the 'suspended' state
 * and `resume()` is refused until the page has been interacted with. The ding is
 * therefore unlocked lazily: {@link installAudioUnlock} (called once from App.tsx
 * on mount) listens for the first pointerdown/keydown, creates the shared context
 * and resumes it. Until that first interaction happens, a ding that fires cannot
 * sound — that is the browser's documented constraint, not a fallback; the
 * settings hint says so in the player's words.
 *
 * The context and the unlock gate were EXTRACTED to `audio/audioCore.ts` when
 * the WAV event-sound manager landed: one
 * AudioContext and one listener pair now serve BOTH sound layers. The two
 * functions below are re-exports, so every pre-existing caller keeps working
 * with no import change.
 *
 * Fail Fast: {@link playSoundVariant} THROWS when WebAudio is unavailable (no
 * AudioContext constructor). The caller (store/slices/priorityDing.ts) surfaces
 * the error through the visible error toast instead of swallowing it.
 */

import { getSharedAudioContext, installAudioUnlock } from './audioCore'

// Re-exported so every pre-existing caller (App.tsx, priorityDing.ts, SettingsDialog.tsx)
// keeps its `@/audio/dingSynth` import path unchanged after the extraction.
export { getSharedAudioContext, installAudioUnlock }

export type SoundVariant = 'ding' | 'chime' | 'soft-click'

/** The variants offered in the settings dialog, in display order. */
export const SOUND_VARIANTS: readonly { value: SoundVariant; label: string }[] = [
  { value: 'ding', label: 'Ding' },
  { value: 'chime', label: 'Chime' },
  { value: 'soft-click', label: 'Soft click' },
]

export function isSoundVariant(v: unknown): v is SoundVariant {
  return SOUND_VARIANTS.some((s) => s.value === v)
}

/** One synthesized note inside a variant. */
interface Note {
  freq: number
  /** Offset from the variant's start, in seconds. */
  start: number
  /** Total envelope length (attack + decay), in seconds. */
  dur: number
  type: OscillatorType
  /** Peak gain BEFORE the master volume is applied. */
  gain: number
}

const VARIANTS: Record<SoundVariant, readonly Note[]> = {
  // Classic bell: fundamental + one soft octave partial. Gains are chosen so the
  // notes' SUM never exceeds 1.0 while overlapping (a louder sum would clip at
  // the destination once the user's volume slider passes ~50%).
  ding: [
    { freq: 880, start: 0, dur: 0.55, type: 'sine', gain: 0.8 },
    { freq: 1760, start: 0, dur: 0.3, type: 'sine', gain: 0.2 },
  ],
  // Three rising notes (E5 – B5 – E6), slightly overlapping.
  chime: [
    { freq: 659.25, start: 0, dur: 0.4, type: 'sine', gain: 0.45 },
    { freq: 987.77, start: 0.09, dur: 0.45, type: 'sine', gain: 0.45 },
    { freq: 1318.51, start: 0.18, dur: 0.6, type: 'sine', gain: 0.5 },
  ],
  // A quiet high tick — the least intrusive option.
  'soft-click': [
    { freq: 2400, start: 0, dur: 0.06, type: 'triangle', gain: 0.6 },
  ],
}

/**
 * Play one variant at `volume` (0..1). Throws when WebAudio is unavailable —
 * the caller surfaces that; this function never degrades silently.
 *
 * A suspended context (ding fired before the page's first interaction — browser
 * autoplay policy) is resumed here as well; until that resume is granted the ding
 * cannot sound. That is the browser's documented gesture rule, not an error path,
 * so the rejection is logged rather than toasted (the settings hint states the
 * limitation in the player's words).
 */
export function playSoundVariant(variant: SoundVariant, volume: number): void {
  const ctx = getSharedAudioContext()
  if (ctx.state === 'suspended') {
    ctx.resume().catch((e: unknown) => {
      console.warn('[audio] ding skipped — AudioContext still suspended by the browser (no user interaction yet):', e)
    })
  }
  const now = ctx.currentTime
  for (const note of VARIANTS[variant]) {
    const osc = ctx.createOscillator()
    const gain = ctx.createGain()
    osc.type = note.type
    osc.frequency.value = note.freq
    const t0 = now + note.start
    // 5 ms attack, then exponential decay — clicks-free and bell-like.
    gain.gain.setValueAtTime(0, t0)
    gain.gain.linearRampToValueAtTime(note.gain * volume, t0 + 0.005)
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + note.dur)
    osc.connect(gain).connect(ctx.destination)
    osc.start(t0)
    osc.stop(t0 + note.dur + 0.05)
  }
}
