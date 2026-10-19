import { useCallback, useEffect, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useGameStore } from '@/store/gameStore'
import { selectCurrentView } from '@/store/selectors'
import { rootZoom, cssViewportWidth, cssViewportHeight } from '@/utils/viewport'
import { HINTS, dismissHint, getHintsState, isHintEligible, subscribeHints, turnOffAllHints, type HintDef } from './hints'

/**
 * HintLayer — the onboarding tips.
 *
 * Shows at most ONE tip at a time: the first tip in HINTS order that is (a) not dismissed and
 * (b) whose anchor is currently visible on screen. Dismissing it lets the next eligible tip
 * appear, so the player is walked through the basics in the order they get stuck.
 *
 * VIEW GATE: tips are resolved against `selectCurrentView(state)` and only render when the view
 * matches the tip's own `view` (every tip today is 'game'). In the lobby, in a replay and on the
 * connect screen the layer renders nothing — a tip can never fire where it does not belong.
 *
 * PLACEMENT: the box is positioned BESIDE or ABOVE its anchor with a real gap, never on top of
 * it, and it is `position: fixed` in ROOT CSS px. Its own box is the only interactive part (CSS
 * gives it a solid background; the rest of the layer is not rendered at all), so it cannot
 * intercept the click the anchored control is waiting for. A collision check moves the tip to
 * the OTHER side when the preferred side would cover the anchor.
 *
 * SEMANTICS (summary — the authoritative comment lives in hints.ts):
 *   * localStorage key `xmage-hints-v1` = { v, off, dismissed[] }
 *   * dismissal is per BROWSER and permanent for that browser
 *   * once every tip is dismissed, or `off` is set, nothing shows again
 */
export function HintLayer() {
  const view = useGameStore(selectCurrentView)
  const { off, dismissed } = useSyncExternalStore(subscribeHints, getHintsState, getHintsState)

  // The tip that is on screen, and the rect its anchor had when it was chosen.
  const [active, setActive] = useState<{ tip: HintDef; anchor: HTMLElement; rect: DOMRect } | null>(null)
  const elRef = useRef<HTMLDivElement>(null)

  /** First eligible tip whose anchor is visible, or null. */
  const pick = useCallback((): { tip: HintDef; anchor: HTMLElement; rect: DOMRect } | null => {
    for (const tip of HINTS) {
      if (tip.view !== view) continue
      if (!isHintEligible(tip.id)) continue
      for (const selector of tip.anchor) {
        const el = document.querySelector<HTMLElement>(selector)
        if (!el) continue
        const rect = el.getBoundingClientRect()
        if (rect.width === 0 && rect.height === 0) continue
        // Must be inside the viewport — a tip pointing off-screen teaches nothing.
        if (rect.bottom < 0 || rect.top > window.innerHeight || rect.right < 0 || rect.left > window.innerWidth) continue
        return { tip, anchor: el, rect }
      }
    }
    return null
  }, [view])

  // Re-pick whenever the view changes or the hint state changes (a dismissal reveals the next
  // tip), and whenever the board changes shape underneath an anchor that has gone away.
  useEffect(() => {
    function refresh() {
      setActive((prev) => {
        // Keep the current tip while its anchor is still on screen, so the tip does not jump to
        // a different anchor on every state push (a game pushes state constantly).
        if (prev) {
          const stillThere = prev.anchor.isConnected && prev.anchor.getBoundingClientRect().width > 0
          if (stillThere) return { ...prev, rect: prev.anchor.getBoundingClientRect() }
        }
        return pick()
      })
    }
    refresh()
    const store = useGameStore.subscribe(refresh)
    window.addEventListener('resize', refresh)
    return () => {
      store()
      window.removeEventListener('resize', refresh)
    }
    // `off`/`dismissed` are read through isHintEligible(); they are dependencies so a dismissal
    // immediately re-picks the next tip. `view` gates which tips are candidates at all.
  }, [pick, off, dismissed])

  // Re-place on scroll without re-picking (the anchor moves, the tip follows).
  useLayoutEffect(() => {
    if (!active) return
    function replace() {
      if (active) setActive({ ...active, rect: active.anchor.getBoundingClientRect() })
    }
    window.addEventListener('scroll', replace, true)
    return () => window.removeEventListener('scroll', replace, true)
  }, [active])

  const dismiss = useCallback(() => {
    if (active) dismissHint(active.tip.id)
    setActive(null)
  }, [active])

  const turnOff = useCallback(() => {
    turnOffAllHints()
    setActive(null)
  }, [])

  if (!active) return null

  // Placement — ROOT CSS px. Prefer BELOW the anchor; if the tip would not fit (or would cover
  // the anchor's own box), flip ABOVE. Then clamp horizontally into the viewport.
  const z = rootZoom()
  const vw = cssViewportWidth()
  const vh = cssViewportHeight()
  const MARGIN = 8
  const WIDTH = 340
  const HEIGHT = elRef.current?.getBoundingClientRect().height != null
    ? (elRef.current.getBoundingClientRect().height / z)
    : 150
  const ax = active.rect.left / z
  const ay = active.rect.top / z
  const aw = active.rect.width / z
  const ah = active.rect.height / z
  let left = ax + aw / 2 - WIDTH / 2
  left = Math.max(MARGIN, Math.min(left, vw - MARGIN - WIDTH))

  // Below first (a tip under the control does not cover the hand), above if it does not fit.
  let top = ay + ah + 10
  if (top + HEIGHT > vh - MARGIN) top = ay - HEIGHT - 10
  if (top < MARGIN) top = MARGIN

  const remaining = HINTS.filter((h) => isHintEligible(h.id)).length

  return (
    <div ref={elRef} className="xm-hint" data-hint-id={active.tip.id} role="note" style={{ left, top }}>
      {active.tip.text}
      <div className="xm-hint-controls">
        <button type="button" className="xm-hint-btn xm-hint-btn-primary" onClick={dismiss} data-explain="hint-dismiss">
          Don't show again
        </button>
        <button type="button" className="xm-hint-btn" onClick={turnOff} data-explain="hint-turn-off-all">
          Turn off all hints
        </button>
        <span className="xm-hint-step">
          {remaining > 1 ? `tip ${HINTS.length - remaining + 1} of ${HINTS.length}` : 'last tip'}
        </span>
      </div>
    </div>
  )
}
