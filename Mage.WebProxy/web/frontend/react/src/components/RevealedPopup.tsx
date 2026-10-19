import { useGameStore } from '@/store/gameStore'
import { resolveCardImage, safeCardImageUrl } from '@/utils/cardImage'
import { useFloatWindow } from '@/hooks/useFloatWindow'
import { useCardGridPreview } from '@/hooks/useCardGridPreview'
import { POPUP_MIN_H, POPUP_MIN_W } from '@/utils/popupSize'

/**
 * Revealed popup — floating window for revealed/looked-at cards.
 * Ported from index.html float-window (revealedPopup) + app.js openRevealedPopup.
 * Shows when the backend sends revealed/lookedAt groups in game state.
 *
 * Sizing/drag/resize live in useFloatWindow (shared with ZonePopup); the size
 * is persisted per group name.
 */
export function RevealedPopup() {
  const revealedPopup = useGameStore((s) => s.revealedPopup)
  const setRevealedPopup = useGameStore((s) => s.setRevealedPopup)
  const selectTarget = useGameStore((s) => s.selectTarget)
  const hideActionPrompt = useGameStore((s) => s.hideActionPrompt)
  const { showPreview, hidePreview } = useCardGridPreview()
  const { pos, onDragStart, onResizeStart } = useFloatWindow(revealedPopup?.name ?? null, 'revealed')

  if (!revealedPopup) return null

  return (
    <div
      className="float-window"
      style={{ left: pos.x, top: pos.y, width: pos.w, height: pos.h, minWidth: POPUP_MIN_W, minHeight: POPUP_MIN_H }}
    >
      <div className="float-window-header" onMouseDown={onDragStart}>
        <span className="float-window-title">{revealedPopup.name}</span>
        <button className="float-window-close" onClick={() => setRevealedPopup(null)}>&times;</button>
      </div>
      <div className="float-window-body">
        <div className="popup-cards-grid">
          {revealedPopup.cards.map((card, idx) => (
            <div
              key={idx}
              className="popup-card"
              onClick={() => {
                if (card.id) {
                  selectTarget(card.id)
                  hideActionPrompt()
                  setRevealedPopup(null)
                }
              }}
              onMouseEnter={(e) => showPreview(card, e)}
              onMouseLeave={hidePreview}
              style={card.id ? { cursor: 'pointer' } : undefined}
            >
              <img
                src={safeCardImageUrl(card, () => resolveCardImage(card.name, card, null, 'small'))}
                alt={card.name}
                loading="lazy"
              />
              <div className="popup-card-name">{card.name}</div>
              <div className="popup-card-type">{card.typeLine || ''}</div>
            </div>
          ))}
        </div>
      </div>
      <div className="float-window-resize" onMouseDown={onResizeStart} title="Drag to resize" />
    </div>
  )
}
