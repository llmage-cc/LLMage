import { useCallback } from 'react'
import { useGameStore } from '@/store/gameStore'
import type { Card } from '@/types'

/**
 * Hover card preview for the floating popup card grids (RevealedPopup,
 * ZonePopup, CardSelectPopup, choose_pile) — the same store-driven preview the
 * board's CardThumb uses ("using mechanics like surveil there is
 * no preview popup for cards").
 *
 * The grid tiles carry plain image URLs; the popup-card <img> shows them
 * small, so the enlarged preview is the normal-size art (resolveCardImage
 * 'normal' inside CardPreview handles the big render — this hook only feeds
 * the card + cursor position).
 */
export function useCardGridPreview() {
  const setCardPreview = useGameStore((s) => s.setCardPreview)

  const showPreview = useCallback(
    (card: Card, e: { clientX: number; clientY: number }) => {
      if (!card || !card.name) return
      setCardPreview({ card, face: 'front', x: e.clientX, y: e.clientY })
    },
    [setCardPreview],
  )

  const hidePreview = useCallback(() => {
    setCardPreview(null)
  }, [setCardPreview])

  return { showPreview, hidePreview }
}