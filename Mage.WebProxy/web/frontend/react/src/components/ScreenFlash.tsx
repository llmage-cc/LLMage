import type { CSSProperties } from 'react'
import { useEffect, useRef, useState } from 'react'

/**
 * ScreenFlash — full-viewport edge-glow overlay for the priority alert.
 *
 * "alongside the ding have the
 * screen flash in a visually appealing way so it's obvious". Chosen character
 * a SUBTLE EDGE GLOW — the screen border/vignette
 * pulses ONCE in the accent colour (~0.5s), not a full-screen tint.
 *
 * Trigger: the priorityDing slice dispatches PRIORITY_FLASH_EVENT once per NEW
 * action prompt (same fresh-arrival decision that drives the sound — epoch
 * dedupe + mana-continuation suppression live THERE; this component only
 * renders what it is told). A flash while a previous one is animating restarts
 * the animation cleanly (keyed re-mount of the glow div).
 *
 * ── SUSTAIN ──
 * "Add a 'sustain' option to the screen edges flash for priority so you can
 * set an intensity for the flash to hold at." With the setting on, the slice
 * raises PRIORITY_FLASH_SUSTAIN_EVENT instead of the pulse: the glow comes up at
 * the set intensity and HOLDS there until PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT
 * (fired by onPromptClearedForPriorityDing — every prompt teardown, game end and
 * new game included). While sustained there is NO animation at all: a held glow
 * that kept pulsing would read as a flicker, and the pulse channel is skipped at
 * the dispatch site for the same reason.
 *
 * A sustain event carrying `preview: true` (the Settings checkbox / intensity
 * slider while no prompt is open) self-clears after PREVIEW_MS, so a live
 * preview can never leave a glow stuck on screen. A real hold has no timer.
 *
 * Page visibility: nothing is rendered while the page is hidden, and a live
 * hold is dropped the moment it goes hidden (nothing to see). On the way back to
 * visible the hold is re-armed from the ONE piece of truth about "is a prompt
 * open?" — the store's `actionPrompt` — so a prompt answered while hidden does
 * not light the glow back up. That is a one-shot read in the visibilitychange
 * handler, never a poll.
 *
 * Accessibility: honours prefers-reduced-motion — no animation in either mode;
 * the edge border appears at a static opacity (the pulse still times out after
 * FLASH_MS, the hold stays until cleared), so the alert is still noticeable.
 *
 * The overlay is pointer-events:none always (it must never block the board the
 * player is about to interact with) and renders NOTHING when idle (no element
 * in the tree between flashes).
 */
import {
  PRIORITY_FLASH_EVENT,
  PRIORITY_FLASH_SUSTAIN_EVENT,
  PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT,
  getDingSettings,
  isPromptOpen,
} from '@/store/slices/priorityDing'
import { useGameStore } from '@/store/gameStore'

/** Total glow lifetime in ms — one pulse: quick ramp up, slower fade out. */
export const FLASH_MS = 500

/** How long a PREVIEW sustain holds before it takes itself down (ms). */
export const PREVIEW_MS = 1500

/** The accent colour of the glow (matches the pulse shipped 2026-09-19). */
const GLOW_RGB = '74, 144, 217'

/** Clamp any incoming intensity to 0..1. */
export function clampIntensity(v: unknown): number {
  const n = typeof v === 'number' && Number.isFinite(v) ? v : 0.5
  return Math.min(1, Math.max(0, n))
}

/**
 * Edge-glow opacity for an intensity setting: the centre of the screen stays
 * untouched (the board must remain readable), only the rim lights up.
 */
export function flashAlpha(intensity: number): number {
  return 0.35 + 0.4 * clampIntensity(intensity)
}

/** The radial gradient that draws the glow — shared by both modes. */
export function flashBackground(intensity: number): string {
  return `radial-gradient(ellipse at center, transparent 55%, rgba(${GLOW_RGB}, ${flashAlpha(intensity)}) 100%)`
}

/**
 * Should the hold be (re-)armed right now? The single decision the re-arm and
 * the renderer share — exported so the harness asserts the real predicate
 * rather than a re-implementation.
 *
 * Both settings must be on (a hold with the flash channel off is not a thing)
 * and a prompt must be open (the store is the truth about that).
 */
export function shouldHoldGlow(opts: {
  visible: boolean
  promptOpen: boolean
  flashEnabled: boolean
  sustainEnabled: boolean
}): boolean {
  return opts.visible && opts.promptOpen && opts.flashEnabled && opts.sustainEnabled
}

/**
 * What to draw, as a plain object — the one place pulse vs sustain is decided.
 * `animation: 'none'` is what makes a hold a hold; the pulse keeps its keyframes
 * unless the user asked for reduced motion, in which case it is a static border
 * that the FLASH_MS timer removes.
 */
export interface GlowVisual {
  /** Gradient + alpha for the rim. */
  background: string
  /** CSS animation shorthand, or 'none' for a static (held / reduced-motion) glow. */
  animation: 'none' | string
  /** Static opacity of the layer (1 for animated modes, the rim alpha otherwise). */
  opacity: number
}

export function glowVisual(opts: {
  mode: 'pulse' | 'sustain'
  intensity: number
  reduced: boolean
}): GlowVisual {
  const alpha = flashAlpha(opts.intensity)
  if (opts.mode === 'sustain') {
    // Held: no animation, no timeout — the glow simply stays up.
    return { background: flashBackground(opts.intensity), animation: 'none', opacity: 1 }
  }
  if (opts.reduced) {
    // A single static blink: visible, no motion, removed by the FLASH_MS timer.
    return { background: flashBackground(opts.intensity), animation: 'none', opacity: Math.min(1, alpha) }
  }
  return {
    background: flashBackground(opts.intensity),
    animation: `screen-flash-pulse ${FLASH_MS}ms ease-out forwards`,
    opacity: 1,
  }
}

export type GlowMode = 'pulse' | 'sustain'

/** The rendered overlay for a glow, or null when there is nothing to draw. */
export interface OverlayRender {
  className: string
  /** The mode actually on screen — the harness asserts this. */
  dataFlashMode: GlowMode
  style: CSSProperties
}

/**
 * The COMPLETE render decision for the overlay: a hidden page renders NOTHING
 * (whatever the state says), no glow renders nothing, otherwise the edge-glow
 * div. `pointerEvents: 'none'` is unconditional — the overlay must never block
 * the board the player is about to click.
 *
 * Exported as a pure function for the same reason `isEmptySelectWindow` is:
 * the harness drives the REAL store, then asserts against the REAL predicate
 * rather than a re-implementation of it (see
 * `scripts/verify-priority-flash-sustain.ts`).
 */
export function overlayRenderState(opts: {
  visible: boolean
  mode: GlowMode | null
  intensity: number
  reduced: boolean
}): OverlayRender | null {
  if (!opts.visible || opts.mode === null) return null
  const visual = glowVisual({ mode: opts.mode, intensity: opts.intensity, reduced: opts.reduced })
  return {
    className: opts.reduced ? 'screen-flash is-reduced' : 'screen-flash',
    dataFlashMode: opts.mode,
    style: {
      position: 'fixed',
      inset: 0,
      pointerEvents: 'none',
      zIndex: 900,
      background: visual.background,
      animation: visual.animation,
      opacity: visual.opacity,
      animationFillMode: 'forwards',
    },
  }
}

type Glow = { id: number; mode: GlowMode; intensity: number; preview: boolean }

/**
 * What the overlay does when something happens — the component maps these onto
 * `setGlow`, and the harness maps them onto a recorder. Keeping the sink an
 * explicit seam means the REAL wiring (which events are registered, which gate
 * each handler reads, what the visibilitychange re-arm actually asks) is
 * exercised under Node by `scripts/verify-priority-flash-sustain.ts` instead of
 * being re-implemented there.
 */
export interface FlashSink {
  /** One-shot pulse — a fresh arrival with sustain OFF. */
  armPulse(intensity: number): void
  /** Held glow — a fresh arrival (or a preview) with sustain ON. */
  armHold(intensity: number, preview: boolean): void
  /** Take any glow down (prompt cleared / hidden). */
  dropGlow(): void
}

/**
 * Register every listener the overlay needs and return the cleanup that removes
 * them. Called once from the component's mount effect.
 *
 * The events:
 *  - PRIORITY_FLASH_EVENT        → one-shot pulse at the carried intensity
 *  - PRIORITY_FLASH_SUSTAIN_EVENT → held glow (preview:true self-clears)
 *  - PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT → drop the glow
 *  - document visibilitychange   → hidden drops the glow; visible re-arms the
 *    HOLD from "is a prompt open?" read off the STORE (`actionPrompt`), so a
 *    prompt answered while the page was hidden does not light the glow back up.
 *    One-shot read, never a poll.
 */
export function installScreenFlashListeners(sink: FlashSink): () => void {
  function onFlash(e: Event) {
    const detail = (e as CustomEvent).detail as { intensity?: number } | undefined
    sink.armPulse(clampIntensity(detail?.intensity))
  }
  function onSustain(e: Event) {
    const detail = (e as CustomEvent).detail as { intensity?: number; preview?: boolean } | undefined
    sink.armHold(clampIntensity(detail?.intensity), detail?.preview === true)
  }
  function onSustainClear() {
    sink.dropGlow()
  }
  function onVisibility() {
    if (document.visibilityState === 'hidden') {
      sink.dropGlow()
      return
    }
    const settings = getDingSettings()
    if (
      shouldHoldGlow({
        visible: true,
        promptOpen: isPromptOpen(useGameStore.getState()),
        flashEnabled: settings.flashEnabled,
        sustainEnabled: settings.flashSustain,
      })
    ) {
      sink.armHold(settings.flashIntensity, false)
    }
  }
  window.addEventListener(PRIORITY_FLASH_EVENT, onFlash)
  window.addEventListener(PRIORITY_FLASH_SUSTAIN_EVENT, onSustain)
  window.addEventListener(PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT, onSustainClear)
  document.addEventListener('visibilitychange', onVisibility)
  return () => {
    window.removeEventListener(PRIORITY_FLASH_EVENT, onFlash)
    window.removeEventListener(PRIORITY_FLASH_SUSTAIN_EVENT, onSustain)
    window.removeEventListener(PRIORITY_FLASH_SUSTAIN_CLEAR_EVENT, onSustainClear)
    document.removeEventListener('visibilitychange', onVisibility)
  }
}

export function ScreenFlash() {
  const [glow, setGlow] = useState<Glow | null>(null)
  const counter = useRef(0)

  useEffect(() => {
    const next = (mode: GlowMode, intensity: number, preview = false): Glow => {
      counter.current += 1
      return { id: counter.current, mode, intensity, preview }
    }
    // One-shot pulse and a live hold are mutually exclusive: the newer event wins
    // (each `setGlow` replaces whatever the other mode had up).
    return installScreenFlashListeners({
      armPulse: (intensity) => setGlow(next('pulse', intensity)),
      armHold: (intensity, preview) => setGlow(next('sustain', intensity, preview)),
      dropGlow: () => setGlow(null),
    })
  }, [])

  // Clear the glow when a PULSE's lifetime is over — a timeout per pulse (the id
  // in the closure must match the current glow, or a rapid second flash would
  // let the FIRST timeout clear the SECOND one early).
  useEffect(() => {
    if (!glow || glow.mode !== 'pulse') return
    const t = setTimeout(() => {
      setGlow((g) => (g && g.id === glow.id ? null : g))
    }, FLASH_MS)
    return () => clearTimeout(t)
  }, [glow])

  // A PREVIEW hold takes itself down (same id-matching rule). A real hold has no
  // timer at all — it ends with the prompt, not with a clock.
  useEffect(() => {
    if (!glow || glow.mode !== 'sustain' || !glow.preview) return
    const t = setTimeout(() => {
      setGlow((g) => (g && g.id === glow.id ? null : g))
    }, PREVIEW_MS)
    return () => clearTimeout(t)
  }, [glow])

  // prefers-reduced-motion: no motion in either mode — the static variant.
  const reduced =
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches

  const render = overlayRenderState({
    visible: typeof document === 'undefined' || document.visibilityState !== 'hidden',
    mode: glow ? glow.mode : null,
    intensity: glow ? glow.intensity : 0,
    reduced,
  })
  if (!render || !glow) return null

  return (
    <div
      key={glow.id}
      className={render.className}
      data-flash-active={render.dataFlashMode}
      style={render.style}
    />
  )
}
