/**
 * Shared WebAudio core — ONE AudioContext, ONE browser-autoplay unlock gate for
 * every sound the web client makes.
 *
 * Extracted from `dingSynth.ts` so the WAV
 * event-sound manager (`audioManager.ts`) reuses the exact unlock mechanism the
 * priority ding already ships, instead of adding a second context + listener
 * pair. One context, one pair of document listeners and one gesture flag for
 * both sound layers; `dingSynth.ts` re-exports `getSharedAudioContext` and
 * `installAudioUnlock` from here, so every existing caller (App.tsx,
 * priorityDing.ts, SettingsDialog.tsx) keeps working unchanged.
 *
 * ── BROWSER AUTOPLAY POLICY ──
 * An AudioContext created before any user gesture starts in the 'suspended'
 * state and `resume()` is refused until the page has been interacted with. The
 * unlock is therefore lazy: {@link installAudioUnlock} (called once from
 * App.tsx on mount) listens for the first pointerdown/keydown, records the
 * gesture ({@link isAudioUnlocked}) and resumes the shared context. Until that
 * first interaction a sound that fires cannot play — that is the browser's
 * documented constraint, not a fallback; both settings hints say so in the
 * player's words.
 *
 * Two layers consume the gate differently, deliberately:
 *  - the ding SYNTH (dingSynth.ts) schedules oscillator nodes ON the shared
 *    context, so it keeps its own stricter `ctx.state === 'suspended'` check;
 *  - the WAV layer (audioManager.ts) plays HTMLAudioElements, whose autoplay
 *    policy is bounded by the page's first gesture — exactly what the flag
 *    records. Its play() therefore gates on the flag, and a browser refusal is
 *    logged loudly by the element promise, never swallowed.
 *
 * Fail Fast: {@link getSharedAudioContext} THROWS when WebAudio is unavailable
 * (no AudioContext constructor). Callers surface that visibly — the priority
 * ding through its error toast, the audio manager through the console when it
 * cannot create a context. The gesture flag is set even then, because the WAV
 * layer does not need WebAudio at all.
 */

type AudioCtor = new () => AudioContext

function getAudioCtor(): AudioCtor {
  const g = globalThis as { AudioContext?: AudioCtor; webkitAudioContext?: AudioCtor }
  const ctor = g.AudioContext ?? g.webkitAudioContext
  if (!ctor) {
    throw new Error('WebAudio is not available — sounds cannot play in this browser.')
  }
  return ctor
}

let sharedCtx: AudioContext | null = null
let unlockInstalled = false

/** True once the page has seen its first user gesture (pointerdown/keydown). */
let gestureSeen = false

/**
 * The one AudioContext for all UI sounds, created lazily. Callers must have had
 * a user gesture (see installAudioUnlock); creating it inside a gesture handler
 * starts it running.
 */
export function getSharedAudioContext(): AudioContext {
  if (!sharedCtx) sharedCtx = new (getAudioCtor())()
  return sharedCtx
}

/**
 * Has the page been interacted with yet? True after the first pointerdown or
 * keydown — the browser rule that gates ALL page audio, whichever layer makes
 * the sound. The audio manager's WAV playback gates on this; the ding synth
 * additionally checks its context state (see the module header).
 */
export function isAudioUnlocked(): boolean {
  return gestureSeen
}

/**
 * Install the one-time unlock listeners. Called from App.tsx's mount effect;
 * idempotent. `once: false` on purpose: the FIRST gesture must also work when
 * the context was created suspended by an earlier programmatic play attempt,
 * and a context the browser suspended later (tab discard) re-arms on the next
 * gesture just the same.
 */
export function installAudioUnlock(): void {
  if (unlockInstalled) return
  unlockInstalled = true
  const unlock = () => {
    // Record the gesture FIRST: it is the page-wide autoplay unlock the WAV
    // layer gates on, and it is valid even where WebAudio itself is missing.
    gestureSeen = true
    let ctx: AudioContext
    try {
      ctx = getSharedAudioContext()
    } catch (e) {
      // Visible, not silent: no WebAudio means the synthesized ding cannot
      // play (its own play call throws and the caller toasts). WAVs are
      // unaffected — HTMLAudioElement does not need this context.
      console.error('[audio] WebAudio unavailable — the synthesized ding cannot play:', e)
      return
    }
    if (ctx.state === 'suspended') {
      ctx.resume().catch((e: unknown) => {
        // Visible, not silent: a context that will not resume means the ding
        // cannot sound and the player should see why in the console.
        console.error('[audio] AudioContext.resume() failed:', e)
      })
    }
    // Created inside the gesture (the normal browser path): already running.
  }
  document.addEventListener('pointerdown', unlock)
  document.addEventListener('keydown', unlock)
}
