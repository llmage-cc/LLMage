import { useEffect, useRef, useState } from 'react'
import { rootZoom, cssViewportWidth, cssViewportHeight } from '@/utils/viewport'
import { findExplainTarget, resolveExplanation } from './explain'

/**
 * Explainer — the ONE tooltip for every button in the client.
 *
 * One delegated `mouseover`/`mouseout` + `focusin`/`focusout` listener pair on the document
 * (capture phase, so it also sees events inside popups whose own handlers stop propagation)
 * resolves the hovered/focused control through `resolveExplanation()` and renders a single
 * styled bubble. No per-button wiring: a new button needs a `data-explain` attribute — or
 * nothing at all when a family rule covers it.
 *
 * Hover AND keyboard focus both show it, it is
 * `pointer-events: none` so it cannot swallow a click, and it is clamped to the viewport in
 * ROOT CSS px (the fixed left/top space under `html { zoom }`) so it never hangs off an edge.
 *
 * A native `title` is SUPPRESSED while the bubble is visible: the bubble already contains the
 * upgraded title text (explanations resolve `title` as their last resort), and letting the
 * browser draw its own tooltip on top would stack two tooltips on one control.
 */
export function Explainer() {
  const [tip, setTip] = useState<{ text: string; rect: DOMRect } | null>(null)
  // Elements/state that the listeners mutate. Refs, not state: re-registering the delegated
  // listeners on every tip change would drop a hover that is already in flight.
  const anchorRef = useRef<HTMLElement | null>(null)
  const suppressedTitleRef = useRef<string | null>(null)
  const hideTimer = useRef<number | null>(null)

  useEffect(() => {
    function cancelHide() {
      if (hideTimer.current != null) {
        window.clearTimeout(hideTimer.current)
        hideTimer.current = null
      }
    }

    function restoreTitle() {
      const el = anchorRef.current
      if (el && suppressedTitleRef.current != null) el.setAttribute('title', suppressedTitleRef.current)
      suppressedTitleRef.current = null
    }

    function show(el: HTMLElement) {
      cancelHide()
      if (anchorRef.current === el) return
      restoreTitle()
      const text = resolveExplanation(el)
      anchorRef.current = el
      if (!text) {
        setTip(null)
        return
      }
      // Suppress the native tooltip while the bubble describes this element, so the reader
      // never gets two overlapping tooltips for one control.
      const native = el.getAttribute('title')
      if (native) {
        suppressedTitleRef.current = native
        el.removeAttribute('title')
      }
      setTip({ text, rect: el.getBoundingClientRect() })
    }

    function hide(el: HTMLElement | null) {
      // Ignore a mouseout for something that is not the anchor (a sibling we already left).
      if (el && el !== anchorRef.current) return
      cancelHide()
      const leaving = el
      // Grace period: moving inside one button (into a child span) fires mouseout on the button
      // before mouseover on the child; without this the bubble flickers in between.
      hideTimer.current = window.setTimeout(() => {
        hideTimer.current = null
        if (anchorRef.current !== leaving && leaving != null) return
        restoreTitle()
        anchorRef.current = null
        setTip(null)
      }, 40)
    }

    function onOver(e: Event) {
      const el = findExplainTarget(e.target)
      if (el) show(el)
    }
    function onOut(e: Event) {
      hide(findExplainTarget(e.target))
    }
    function onFocusIn(e: Event) {
      const el = findExplainTarget(e.target)
      if (el) show(el)
    }
    function onFocusOut(e: Event) {
      const next = (e as FocusEvent).relatedTarget
      // Focus moving to a child of the same control stays on it; focus going anywhere else hides.
      if (next instanceof HTMLElement && anchorRef.current?.contains(next)) return
      hide(findExplainTarget(e.target))
    }

    // Re-place on scroll/resize: the anchor moves while the bubble stays fixed.
    function replace() {
      const el = anchorRef.current
      if (!el) return
      setTip((t) => (t ? { ...t, rect: el.getBoundingClientRect() } : t))
    }

    document.addEventListener('mouseover', onOver, true)
    document.addEventListener('mouseout', onOut, true)
    document.addEventListener('focusin', onFocusIn, true)
    document.addEventListener('focusout', onFocusOut, true)
    window.addEventListener('scroll', replace, true)
    window.addEventListener('resize', replace)
    return () => {
      cancelHide()
      document.removeEventListener('mouseover', onOver, true)
      document.removeEventListener('mouseout', onOut, true)
      document.removeEventListener('focusin', onFocusIn, true)
      document.removeEventListener('focusout', onFocusOut, true)
      window.removeEventListener('scroll', replace, true)
      window.removeEventListener('resize', replace)
      // Never leave a control stripped of its native title because the app unmounted mid-hover.
      restoreTitle()
    }
  }, [])

  if (!tip) return null

  // Placement: prefer below the anchor, flip above when there is no room, then clamp into the
  // viewport. All maths in ROOT CSS px (the space the fixed left/top style renders in).
  const z = rootZoom()
  const vw = cssViewportWidth()
  const vh = cssViewportHeight()
  const MARGIN = 8
  const WIDTH = 320
  const HEIGHT = 104 // estimate for the wrap point; the clamp below is what guarantees fit
  const ax = tip.rect.left / z
  const ay = tip.rect.top / z
  const aw = tip.rect.width / z
  const ah = tip.rect.height / z
  let left = ax + aw / 2 - WIDTH / 2
  let top = ay + ah + 8
  if (top + HEIGHT > vh - MARGIN) top = ay - HEIGHT - 8
  if (top < MARGIN) top = MARGIN
  left = Math.max(MARGIN, Math.min(left, vw - MARGIN - WIDTH))

  return (
    <div className="xm-tooltip" role="tooltip" style={{ left, top }}>
      {tip.text}
    </div>
  )
}
