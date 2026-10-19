/**
 * useFloatWindow — drag + resize behaviour for a `.float-window` popup.
 *
 * Shared by ZonePopup (graveyard/exile/sideboard) and RevealedPopup. Defaults
 * come from utils/popupSize (sized to read well on a 1920x1080 screen), the
 * size is clamped to the zoom-compensated viewport on every drag step, and the
 * settled size is persisted per zone so the next open restores it.
 *
 * Zoom contract (see utils/viewport.ts): mouse deltas are REAL px while the
 * fixed left/top/width/height are ROOT CSS px, so every delta is divided by
 * rootZoom() captured at drag start.
 *
 * The move handlers compute the next rect from values captured at mousedown
 * rather than from the state updater's previous value: x/y and w/h are fixed
 * by the kind of drag (resize never moves, drag never resizes), and this keeps
 * the mouseup handler able to persist the exact size even when React has not
 * flushed the last mousemove.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import { rootZoom } from '@/utils/viewport'
import {
  POPUP_EDGE,
  POPUP_DEFAULT_H,
  POPUP_DEFAULT_W,
  POPUP_MIN_H,
  POPUP_MIN_W,
  fitPopupToViewport,
  loadPopupSize,
  popupViewport,
  savePopupSize,
  type PopupRect,
} from '@/utils/popupSize'

export interface FloatWindowControls {
  /** Current window rect in ROOT CSS px, ready for the inline style. */
  pos: PopupRect
  onDragStart: (e: React.MouseEvent) => void
  onResizeStart: (e: React.MouseEvent) => void
}

const clamp = (v: number, min: number, max: number) => Math.max(min, Math.min(max, v))

/**
 * @param openKey    Identity of the currently open popup (zone+player, or the
 *                   revealed group name); null when closed. A change re-inits
 *                   the size from localStorage / the defaults.
 * @param storageKey localStorage key suffix for the persisted size (one per zone).
 */
export function useFloatWindow(openKey: string | null, storageKey: string): FloatWindowControls {
  const [pos, setPos] = useState<PopupRect>(() =>
    fitPopupToViewport({ x: 100, y: 100, w: POPUP_DEFAULT_W, h: POPUP_DEFAULT_H }))

  // Re-init the size when a popup opens (or switches to another zone): restore
  // the persisted size for that zone, else the defaults, and fit it on screen.
  const lastKeyRef = useRef<string | null>(null)
  useEffect(() => {
    if (openKey == null) {
      lastKeyRef.current = null
      return
    }
    if (lastKeyRef.current === openKey) return
    lastKeyRef.current = openKey
    const stored = loadPopupSize(storageKey)
    setPos(fitPopupToViewport({
      x: 100,
      y: 100,
      w: stored?.w ?? POPUP_DEFAULT_W,
      h: stored?.h ?? POPUP_DEFAULT_H,
    }))
  }, [openKey, storageKey])

  // Keep the window on screen when the viewport shrinks (browser resize).
  useEffect(() => {
    if (openKey == null) return
    const onWindowResize = () => setPos((p) => fitPopupToViewport(p))
    window.addEventListener('resize', onWindowResize)
    return () => window.removeEventListener('resize', onWindowResize)
  }, [openKey])

  const onDragStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    const startX = e.clientX
    const startY = e.clientY
    const origX = pos.x
    const origY = pos.y
    const { w, h } = pos
    const zoom = rootZoom()
    const move = (ev: MouseEvent) => {
      const { vw, vh } = popupViewport()
      setPos({
        w, h,
        // Keep the WHOLE window on screen — a header dragged past the edge
        // leaves no way back.
        x: clamp(origX + (ev.clientX - startX) / zoom, POPUP_EDGE, Math.max(POPUP_EDGE, vw - w - POPUP_EDGE)),
        y: clamp(origY + (ev.clientY - startY) / zoom, POPUP_EDGE, Math.max(POPUP_EDGE, vh - h - POPUP_EDGE)),
      })
    }
    const up = () => {
      document.removeEventListener('mousemove', move)
      document.removeEventListener('mouseup', up)
    }
    document.addEventListener('mousemove', move)
    document.addEventListener('mouseup', up)
  }, [pos])

  const onResizeStart = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    const startX = e.clientX
    const startY = e.clientY
    const origW = pos.w
    const origH = pos.h
    const { x, y } = pos
    const zoom = rootZoom()
    const sizeAt = ({ clientX, clientY }: { clientX: number; clientY: number }) => {
      const { vw, vh } = popupViewport()
      // Cap to the space between the window's own edges and the viewport
      // border, so growing never pushes the window off screen. The floor stays
      // the minimum usable size even when the viewport is smaller than it.
      const maxW = Math.max(POPUP_MIN_W, vw - x - POPUP_EDGE)
      const maxH = Math.max(POPUP_MIN_H, vh - y - POPUP_EDGE)
      return {
        rect: { x, y, w: clamp(origW + (clientX - startX) / zoom, POPUP_MIN_W, maxW), h: clamp(origH + (clientY - startY) / zoom, POPUP_MIN_H, maxH) },
      }
    }
    let last = sizeAt(e)
    const move = (ev: MouseEvent) => {
      last = sizeAt(ev)
      setPos(last.rect)
    }
    const up = () => {
      document.removeEventListener('mousemove', move)
      document.removeEventListener('mouseup', up)
      // Persist the settled (clamped) size — computed from the last mouse
      // position, not from possibly-unflushed component state.
      savePopupSize(storageKey, { w: last.rect.w, h: last.rect.h })
    }
    document.addEventListener('mousemove', move)
    document.addEventListener('mouseup', up)
  }, [pos, storageKey])

  return { pos, onDragStart, onResizeStart }
}
