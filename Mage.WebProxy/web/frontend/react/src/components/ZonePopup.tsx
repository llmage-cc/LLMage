import { useGameStore } from '@/store/gameStore'
import { selectBoardState } from '@/store/selectors'
import { resolveCardImage, safeCardImageUrl } from '@/utils/cardImage'
import { useFloatWindow } from '@/hooks/useFloatWindow'
import { useCardGridPreview } from '@/hooks/useCardGridPreview'
import { POPUP_MIN_H, POPUP_MIN_W } from '@/utils/popupSize'
import type { Card } from '@/types'

/**
 * Zone popup — draggable floating window for graveyard/exile.
 * Ported from index.html float-window + app.js openZonePopup/startDrag/startResize.
 * Opens when a graveyard/exile zone title is clicked.
 *
 * Sizing/drag/resize/persistence live in useFloatWindow (shared with
 * RevealedPopup); the size is stored per zone so graveyard and exile keep
 * independent sizes.
 *
 * PLAYABLE AFFORDANCE:
 * a card in this window whose current select prompt offers a play/activate
 * choice (flashback, play-from-exile) is highlighted and clickable — the same
 * affordance the board tiles have. The board is the primary surface (the tiles
 * in the player board carry the affordance too); this window is the fallback
 * when the zone stays open for reading. Clicking dispatches selectChoice with
 * the matching choice index — the same answer the rail button sends.
 */
export function ZonePopup() {
  const zonePopup = useGameStore((s) => s.zonePopup)
  const setZonePopup = useGameStore((s) => s.setZonePopup)
  const selectChoice = useGameStore((s) => s.selectChoice)
  const actionPrompt = useGameStore((s) => s.actionPrompt)
  // The board the visible view is showing — a live game OR a replay frame.
  // Reading raw `gameState` here meant a replay fell through to the frozen
  // click-time snapshot, so stepping frames never updated an open graveyard.
  const gameState = useGameStore(selectBoardState)
  const { showPreview, hidePreview } = useCardGridPreview()
  // A new zone/player pair re-inits the window size from its persisted value.
  const openKey = zonePopup ? `${zonePopup.zone}:${zonePopup.playerName}` : null
  const { pos, onDragStart, onResizeStart } = useFloatWindow(openKey, zonePopup?.zone ?? 'zone')

  if (!zonePopup) return null

  // Derive the card list LIVE from game state (deltas keep it fresh) instead of
  // the click-time snapshot — the sideboard window must auto-update during games
  // The stored popup identifies the zone+player; the
  // gameState carries current contents. Snapshot fallback ONLY if the player
  // vanished from state (spectator edge) — Fail Fast would strand a frozen popup.
  const zonePlayer = gameState?.players.find((p) => p.name === zonePopup.playerName)
  const liveCards: Card[] =
    zonePlayer != null
      ? zonePopup.zone === 'graveyard'
        ? zonePlayer.graveyard
        : zonePopup.zone === 'exile'
          ? zonePlayer.exile
          : zonePopup.zone === 'sideboard'
            ? (zonePlayer.sideboard ?? [])
            : zonePopup.cards
      : zonePopup.cards

  // Which rendered card is playable right now: the card's own id must be a
  // card-based (cast/land/activate) choice of the LIVE select prompt. Prompt
  // ids are engine card UUIDs (WebProxyClient.handleGameSelect), the same ids
  // the zone lists carry, so an id match IS the playable card — no name
  // matching. Only MY zones get choices, so an opponent's window stays inert,
  // and the affordance is scoped to graveyard/exile — the zones this popup serves
  // (sideboard stays a pure viewing surface, deck search stays popup-only).
  const playableIds = new Set<string>()
  const isPlayableZone = zonePopup.zone === 'graveyard' || zonePopup.zone === 'exile'
  if (isPlayableZone && actionPrompt?.type === 'select' && gameState && zonePlayer?.isMe) {
    for (const choice of actionPrompt.choices) {
      if (choice.id && choice.action && ['cast', 'land', 'activate'].includes(choice.action)) {
        playableIds.add(choice.id)
      }
    }
  }

  return (
    <div
      className="float-window"
      style={{ left: pos.x, top: pos.y, width: pos.w, height: pos.h, minWidth: POPUP_MIN_W, minHeight: POPUP_MIN_H }}
    >
      <div className="float-window-header" onMouseDown={onDragStart}>
        <span className="float-window-title">
          {zonePopup.zone === 'graveyard'
            ? zonePopup.playerName + "'s Graveyard"
            : zonePopup.zone === 'exile'
              ? zonePopup.playerName + "'s Exile"
              : zonePopup.zone === 'sideboard'
                ? zonePopup.playerName + "'s Sideboard"
                : zonePopup.zone}
        </span>
        <button className="float-window-close" onClick={() => setZonePopup(null)}>&times;</button>
      </div>
      <div className="float-window-body">
        <div className="popup-cards-grid">
          {liveCards.map((card, idx) => {
            // Playable card in an open zone window: highlighted and clickable. The
            // window stays open after the play (the zone contents refresh from live
            // state on the next push), matching how the board keeps its surfaces alive.
            const cardId = card.id ?? null
            const isPlayable = !!cardId && playableIds.has(cardId)
            const choice = isPlayable && actionPrompt?.type === 'select'
              ? actionPrompt.choices.find((c) => c.id === cardId)
              : undefined
            return (
              <div
                key={idx}
                className={`popup-card${isPlayable ? ' popup-card-playable' : ''}`}
                role={isPlayable ? 'button' : undefined}
                tabIndex={isPlayable ? 0 : undefined}
                onClick={isPlayable && choice ? () => selectChoice(choice.index) : undefined}
                onKeyDown={isPlayable && choice
                  ? (e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault()
                        selectChoice(choice.index)
                      }
                    }
                  : undefined}
                onMouseEnter={(e) => showPreview(card, e)}
                onMouseLeave={hidePreview}
              >
                <img
                  src={safeCardImageUrl(card, () => resolveCardImage(card.name, card, null, 'small'))}
                  alt={card.name}
                  loading="lazy"
                />
                <div className="popup-card-name">{card.name}</div>
                <div className="popup-card-type">{card.typeLine || ''}</div>
              </div>
            )
          })}
        </div>
      </div>
      <div className="float-window-resize" onMouseDown={onResizeStart} title="Drag to resize" />
    </div>
  )
}
