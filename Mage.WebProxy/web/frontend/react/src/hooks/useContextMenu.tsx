import { useCallback, useEffect, useMemo, useRef } from 'react'
import { useGameStore } from '@/store/gameStore'
import { rootZoom, cssViewportWidth, cssViewportHeight } from '@/utils/viewport'
import type { ContextMenuKind, OpenContextMenu } from '@/store/slices/contextMenuSlice'

/**
 * Shared context-menu state + behaviour.
 *
 * Every right-click menu in the app goes through this one hook so that:
 *  1. only ONE menu can be open at a time (a second open replaces the first),
 *  2. CardPreview can suppress the hover card for ANY menu via the single
 *     `openContextMenu` store field (the tokenArtMenuOpen bug shape — each
 *     menu used to ship its own flag and the hover card covered it),
 *  3. close-on-outside-click / Escape is implemented once.
 *
 * The hook does NOT render anything; pair it with <ContextMenuShell>, which
 * draws the transparent blocker overlay and the positioned panel.
 *
 * Type note: the openAt overloads accept the kind's payload fields; the
 * discrimination happens at the store (`OpenContextMenu` union), so a caller
 * cannot register a payload that doesn't match its declared kind without a
 * compile error.
 */
export function useContextMenu<K extends ContextMenuKind>(kind: K) {
  const open = useGameStore((s) => s.openContextMenu)
  const setOpen = useGameStore((s) => s.setOpenContextMenu)
  const isOpen = open?.kind === kind

  type Payload = Extract<OpenContextMenu, { kind: K }> extends infer T
    ? T extends { kind: ContextMenuKind; x: number; y: number }
      ? Omit<T, 'kind' | 'x' | 'y'>
      : never
    : never

  const openAt = useCallback(
    (e: { clientX: number; clientY: number; preventDefault: () => void; stopPropagation?: () => void },
     payload?: Payload) => {
      e.preventDefault()
      e.stopPropagation?.()
      setOpen({ kind, x: e.clientX, y: e.clientY, ...(payload as object ?? {}) } as OpenContextMenu)
    },
    [kind, setOpen],
  )

  const close = useCallback(() => {
    // Only clear when THIS menu is the open one — a late close from a menu that
    // was already replaced must not cancel its successor.
    const current = useGameStore.getState().openContextMenu
    if (current?.kind === kind) setOpen(null)
  }, [kind, setOpen])

  return { isOpen, openAt, close, position: isOpen && open ? { x: open.x, y: open.y } : null }
}

/** Props ContextMenuShell needs from the opener. */
export interface ContextMenuShellProps {
  /** REAL (unzoomed) client px from the opening event; null renders nothing. */
  position: { x: number; y: number } | null
  onClose: () => void
  /** Rendered inside the positioned panel. */
  children: React.ReactNode
  /** Approximate rendered panel size, for viewport clamping. */
  width?: number
  height?: number
  /** Class for the panel element (styles live in context-menu.css). */
  className?: string
  /** Accessible label for the panel. */
  ariaLabel?: string
}

/**
 * The shared menu chrome: a transparent full-screen blocker (left-click AND
 * right-click close — right-clicking elsewhere reopening a menu under the
 * cursor feels like the menu is stuck) plus the clamped, zoom-corrected panel.
 *
 * Zoom correction: x/y arrive in REAL px from the event; `position: fixed`
 * left/top renders in ROOT CSS px because base.css zooms <html>, so divide by
 * the root zoom — the same conversion TokenArtMenu/TriggerOrderMenu do inline.
 *
 * Dismissal: document-level mousedown outside the panel and Escape both close
 * (deck-menu pattern — the most complete of the three pre-existing
 * implementations); the blocker's own click/contextmenu handlers cover clicks
 * that land outside any scroll container's hit area.
 */
export function ContextMenuShell({
  position,
  onClose,
  children,
  width = 200,
  height = 160,
  className = '',
  ariaLabel = 'Context menu',
}: ContextMenuShellProps) {
  const panelRef = useRef<HTMLDivElement>(null)

  // x/y REAL px -> ROOT CSS px, then clamp so the panel stays inside the window.
  const { left, top } = useMemo(() => {
    if (!position) return { left: 0, top: 0 }
    const z = rootZoom()
    let l = position.x / z
    let t = position.y / z
    if (l + width > cssViewportWidth()) l = Math.max(4, cssViewportWidth() - width - 10)
    if (t + height > cssViewportHeight()) t = Math.max(4, cssViewportHeight() - height - 10)
    return { left: l, top: t }
  }, [position, width, height])

  useEffect(() => {
    if (!position) return
    const onDocMouseDown = (e: MouseEvent): void => {
      if (panelRef.current && !panelRef.current.contains(e.target as Node)) onClose()
    }
    const onKeyDown = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('mousedown', onDocMouseDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onDocMouseDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [position, onClose])

  if (!position) return null

  return (
    <>
      <div
        className="modal-overlay"
        style={{ display: 'block', background: 'transparent', zIndex: 2900 }}
        onClick={onClose}
        onContextMenu={(e) => {
          e.preventDefault()
          onClose()
        }}
      />
      <div
        ref={panelRef}
        role="menu"
        aria-label={ariaLabel}
        className={`context-menu-panel ${className}`.trim()}
        style={{ position: 'fixed', left, top, zIndex: 3000 }}
      >
        {children}
      </div>
    </>
  )
}
