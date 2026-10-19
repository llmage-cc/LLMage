import { useContextMenu, ContextMenuShell } from '@/hooks/useContextMenu'
import { useGameStore } from '@/store/gameStore'

/**
 * Generic card context menu — any non-token card tile (hand, battlefield,
 * stack spell, graveyard/exile preview, replay frame, popup grid). Read-only
 * by default; action items appear only when the caller supplies the wiring.
 *
 * Items:
 *  - Pin preview — writes the card into the store's cardPreview slot (the same
 *    payload hover produces) and closes the menu; the pinned panel renders
 *    above the board until the next hover cycle clears it.
 *  - Play / Cast — exactly the tile's own left-click dispatch; only when the
 *    caller marked the card playable (the same set the engine highlighted).
 *  - Copy name / Copy rules text — navigator.clipboard; disabled with a note
 *    when the card view carries no rules text.
 */
export function CardContextMenu() {
  const { isOpen, close, position } = useContextMenu('card')
  const openMenu = useGameStore((s) => s.openContextMenu)
  const setCardPreview = useGameStore((s) => s.setCardPreview)
  // The store union narrows by kind: this component only renders when the open
  // menu IS the card menu, so the payload is CardMenuData.
  const payload = isOpen && openMenu?.kind === 'card' ? openMenu : null
  const card = payload?.card
  const rules = card?.rules ?? []
  const rulesText = rules.join('\n')

  if (!isOpen || !card) return null

  const copy = (text: string) => {
    void navigator.clipboard?.writeText(text).catch(() => {
      useGameStore.getState().showError('Clipboard unavailable in this browser')
    })
  }

  const name = card.name || 'Unknown'

  return (
    <ContextMenuShell position={position} onClose={close} width={200} height={150} ariaLabel={`Card menu — ${name}`}>
      <div className="context-menu-title">{name}</div>
      <button
        type="button"
        className="context-menu-item"
        data-explain="card-ctx-pin"
        onClick={() => {
          setCardPreview({ card, face: 'front', x: position!.x, y: position!.y })
          close()
        }}
      >
        📌 Pin preview
      </button>
      {payload!.onClick && (
        <button
          type="button"
          className="context-menu-item"
          data-explain="card-ctx-play"
          disabled={!payload!.isPlayable}
          onClick={() => {
            payload!.onClick!(card.id ?? null)
            close()
          }}
        >
          ▶ Play / Cast
        </button>
      )}
      <hr className="context-menu-sep" />
      <button type="button" className="context-menu-item" data-explain="card-ctx-copy-name" onClick={() => { copy(name); close() }}>
        Copy name
      </button>
      <button
        type="button"
        className="context-menu-item"
        data-explain="card-ctx-copy-rules"
        onClick={() => { copy(rulesText || name); close() }}
        disabled={rules.length === 0}
      >
        Copy rules text
      </button>
      {rules.length === 0 && <div className="context-menu-note">No rules text on this card view</div>}
    </ContextMenuShell>
  )
}
