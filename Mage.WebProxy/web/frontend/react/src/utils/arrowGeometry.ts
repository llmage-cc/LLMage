import { rootZoom } from '@/utils/viewport'

/**
 * Shared arrow geometry for the SVG overlays (CombatArrows + TargetingArrows).
 *
 * Coordinate spaces (see utils/viewport.ts):
 *  - getBoundingClientRect() reports REAL screen px.
 *  - SVG user units are ROOT CSS px (base.css zooms <html>), so measured
 *    offsets must be divided by the zoom before being written into path data.
 *
 * THE ORIGIN RULE (the defect that killed combat arrows, 2026-09-18): an SVG
 * positioned absolutely inside container C must measure its endpoints relative
 * to C's OWN rect. The overlay is a SIBLING of the card elements here (both are
 * children of .players-area), so measuring from a 0x0 document origin — or from
 * a container that is still display:none (a 0x0 rect) — puts every path's
 * coordinates off-screen and no arrow is visible at all.
 *
 * Both overlays render as position:fixed, so their rects ARE the viewport in
 * root CSS px, and endpoints are measured relative to the element's own rect.
 */

/** A rendered arrow: SVG path data + stroke + marker id (defined by the overlay). */
export interface ArrowPath {
  d: string
  color: string
  marker: string
}

/** Center of `el` in ROOT CSS px, in the coordinate space of an absolutely/
 *  fixed-positioned overlay that covers the viewport. Null when el is missing
 *  or not laid out (display:none subtree — a 0x0 rect cannot be drawn to). */
export function centerOf(el: Element | null): { x: number; y: number } | null {
  if (!el) return null
  const r = el.getBoundingClientRect()
  if (r.width === 0 && r.height === 0) return null
  const z = rootZoom()
  return { x: (r.left + r.width / 2) / z, y: (r.top + r.height / 2) / z }
}

/** Quadratic curve from `from` to `to` with a slight upward bow (viewport px in). */
export function arrowCurve(from: { x: number; y: number }, to: { x: number; y: number }): string {
  const dx = to.x - from.x
  const dy = to.y - from.y
  const cx = from.x + dx * 0.5
  const cy = from.y + dy * 0.5 - Math.min(40, Math.abs(dx) * 0.15)
  return `M${from.x},${from.y} Q${cx},${cy} ${to.x},${to.y}`
}