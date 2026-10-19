/**
 * Root-zoom coordinate conversion.
 *
 * base.css sets `zoom: 0.75` on <html> (replicates 75% browser zoom). This
 * creates TWO coordinate spaces:
 *   - REAL screen px: MouseEvent.clientX/clientY, window.innerWidth/innerHeight,
 *     Element.getBoundingClientRect() — all reported unzoomed by the browser.
 *   - ROOT CSS px: any CSS length inside the zoomed root, including
 *     position:fixed left/top, element widths, and SVG user units.
 * A fixed element placed at CSS left: 500px renders at 500 × 0.75 = 375 real
 * px (measured live). So every cursor-anchored overlay must divide mouse
 * coordinates by the zoom factor, and every viewport clamp must compare
 * against the CSS-px viewport (innerWidth / zoom), not the real one.
 *
 * Convention in this codebase: stores/state hold REAL px (as delivered by
 * mouse events); conversion happens at the positioning layer, when the value
 * is written into a style.
 */

/** The CSS `zoom` factor applied to <html>. Identity 1 when no zoom is set. */
export function rootZoom(): number {
  const raw = getComputedStyle(document.documentElement).zoom
  const z = parseFloat(raw)
  // An unparseable/missing zoom property means no zoom is applied, in which
  // case the conversion factor genuinely is 1 (identity) — not a fallback.
  return Number.isFinite(z) && z > 0 ? z : 1
}

/** Viewport width in ROOT CSS px (for clamping fixed overlays). */
export function cssViewportWidth(): number {
  return window.innerWidth / rootZoom()
}

/** Viewport height in ROOT CSS px (for clamping fixed overlays). */
export function cssViewportHeight(): number {
  return window.innerHeight / rootZoom()
}

/**
 * The ROOT CSS px value of an `Xvh` length (pass the fraction: 0.6 for `60vh`).
 *
 * `vh` resolves against the REAL viewport, and the root `zoom` then scales the
 * rendered result — so a `60vh` box renders at `0.6 × innerHeight` real px and
 * measures `0.6 × innerHeight` in ROOT CSS px too (the zoom cancels: ×zoom
 * then ÷zoom). Zoom-independent by construction, unlike the tempting
 * `fraction × cssViewportHeight()`, which is `1/zoom` times too large at
 * zoom≠1 and over-clamps cursor-anchored panels (measured: a 420px declaration
 * on a 60vh panel floated the menu ~300 real px above a bottom-edge click).
 */
export function vhToCssPx(fraction: number): number {
  return fraction * window.innerHeight
}