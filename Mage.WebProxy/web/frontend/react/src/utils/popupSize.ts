/**
 * Floating-popup sizing — shared constants, viewport fitting, and per-zone
 * persistence for the zone windows (graveyard / exile / sideboard) and the
 * revealed-cards window.
 *
 * All values are ROOT CSS px: the windows are `position: fixed` inside the
 * zoomed root (base.css `html { zoom: 0.75 }`), so every viewport cap must be
 * computed against the zoom-compensated CSS viewport (see utils/viewport.ts) —
 * window.innerWidth is REAL px and would overstate the available space by 1/zoom.
 *
 * NOTE: when the layout gains its shared `--ui-scale`
 * variable, POPUP_DEFAULT_* / POPUP_MIN_* should be derived from it instead of
 * being literals — they are deliberately local constants until then.
 */
import { cssViewportWidth, cssViewportHeight } from './viewport'

/** Default zone-window size — readable on a 1920x1080 screen ("Popup window way
 *  too small"; the old 400x300 held barely two card tiles). */
export const POPUP_DEFAULT_W = 520
export const POPUP_DEFAULT_H = 420

/** Minimum usable size: header + a row of tiles; below this the title and close
 *  button collide and the grid degenerates to one cramped column. */
export const POPUP_MIN_W = 360
export const POPUP_MIN_H = 260

/** Gap kept between a fitted window and the viewport edge. */
export const POPUP_EDGE = 8

/** localStorage key prefix for per-zone persisted window sizes. */
const LS_PREFIX = 'xmage-popup-size-'

export interface PopupRect {
  x: number
  y: number
  w: number
  h: number
}

/** Size of the usable viewport in ROOT CSS px. */
export function popupViewport(): { vw: number; vh: number } {
  return { vw: cssViewportWidth(), vh: cssViewportHeight() }
}

/**
 * Max popup size — capped to the (zoom-compensated) viewport so a window can
 * never be resized past the screen edge. Never below the minimum: on a viewport
 * smaller than MIN + 2*POPUP_EDGE the window overflows by design rather than
 * collapsing to something unusable.
 */
export function popupMaxSize(): { maxW: number; maxH: number } {
  const { vw, vh } = popupViewport()
  return {
    maxW: Math.max(POPUP_MIN_W, vw - POPUP_EDGE * 2),
    maxH: Math.max(POPUP_MIN_H, vh - POPUP_EDGE * 2),
  }
}

/**
 * Fit a popup rect to the viewport: clamp w/h into [MIN, max] first, then pull
 * x/y so the whole window (header included) stays on screen. Used on open, on
 * resize-drag end and whenever the viewport changes size.
 */
export function fitPopupToViewport(rect: PopupRect): PopupRect {
  const { maxW, maxH } = popupMaxSize()
  const w = Math.min(maxW, Math.max(POPUP_MIN_W, rect.w))
  const h = Math.min(maxH, Math.max(POPUP_MIN_H, rect.h))
  const { vw, vh } = popupViewport()
  return {
    w,
    h,
    x: Math.min(Math.max(POPUP_EDGE, rect.x), Math.max(POPUP_EDGE, vw - w - POPUP_EDGE)),
    y: Math.min(Math.max(POPUP_EDGE, rect.y), Math.max(POPUP_EDGE, vh - h - POPUP_EDGE)),
  }
}

/**
 * Load a persisted window size for a zone. Returns null when nothing valid is
 * stored; a corrupt entry is dropped (it can never be repaired and would
 * otherwise pin the window to a garbage size on every open).
 */
export function loadPopupSize(zone: string): { w: number; h: number } | null {
  try {
    const raw = localStorage.getItem(LS_PREFIX + zone)
    if (raw == null) return null
    const parsed = JSON.parse(raw) as { w?: unknown; h?: unknown }
    if (typeof parsed.w !== 'number' || typeof parsed.h !== 'number' ||
        !Number.isFinite(parsed.w) || !Number.isFinite(parsed.h)) {
      localStorage.removeItem(LS_PREFIX + zone)
      return null
    }
    return { w: parsed.w, h: parsed.h }
  } catch {
    // localStorage unavailable (privacy mode): no persistence, defaults apply.
    return null
  }
}

/** Persist a window size for a zone. */
export function savePopupSize(zone: string, size: { w: number; h: number }): void {
  try {
    localStorage.setItem(LS_PREFIX + zone, JSON.stringify({ w: size.w, h: size.h }))
  } catch {
    // quota/unavailable — a lost size preference must not break the popup.
  }
}
