import { useGameStore } from '@/store/gameStore'
import DeckCardTile from './DeckCardTile'

/**
 * Sideboard panel — cards in the sideboard, rendered as a card-image grid.
 * Right-click a card for Change art / ± copy / Move to Main / Remove.
 */
export default function SideboardPanel() {
  const sideboard = useGameStore((s) => s.currentDeck.sideboard)
  const totalSb = sideboard.reduce((a, e) => a + e.count, 0)

  return (
    <div className="deck-panel deck-sideboard-panel">
      <div className="deck-panel-header">
        <span className="deck-panel-title">Sideboard</span>
        <span className="deck-panel-total">{totalSb} cards</span>
      </div>

      {sideboard.length === 0 ? (
        <div className="deck-list">
          <div className="deck-empty">Sideboard empty. Right-click search results to add them here.</div>
        </div>
      ) : (
        <div className="deck-cards-grid">
          {sideboard.map((entry, i) => (
            <DeckCardTile
              // Index prefix: same duplicate-key hazard as DeckPanel (N4).
              key={`SB:${i}|${entry.name}|${entry.setCode}|${entry.cardNumber}`}
              entry={entry}
              sideboard={true}
            />
          ))}
        </div>
      )}
    </div>
  )
}
