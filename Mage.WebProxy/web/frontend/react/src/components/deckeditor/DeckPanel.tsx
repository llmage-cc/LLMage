import { useGameStore } from '@/store/gameStore'
import DeckCardTile, { DeckCardContextMenu, ArtPickerModal } from './DeckCardTile'

/**
 * Main deck panel — cards in the current working deck, rendered as a card-image grid.
 * Right-click a card for Change art / ± copy / Move to SB / Remove.
 */
export default function DeckPanel() {
  const currentDeck = useGameStore((s) => s.currentDeck)
  const renameDeck = useGameStore((s) => s.renameDeck)

  const totalCards = currentDeck.cards.reduce((a, e) => a + e.count, 0)

  return (
    <div className="deck-panel">
      <div className="deck-panel-header">
        <input
          className="deck-panel-name"
          type="text"
          value={currentDeck.name}
          onChange={(e) => renameDeck(e.target.value)}
          placeholder="Deck name"
        />
        <span className="deck-panel-total">{totalCards} cards</span>
      </div>

      {currentDeck.cards.length === 0 ? (
        <div className="deck-list">
          <div className="deck-empty">
            Empty deck. Click cards in the search pane to add them.
          </div>
        </div>
      ) : (
        <div className="deck-cards-grid">
          {currentDeck.cards.map((entry, i) => (
            <DeckCardTile
              // Name+set+num is NOT unique: a text import can produce several entries
              // for one nonbasic name (the import parser keeps per-line set/num), and
              // name-only lines all collapse to the same `name||` key — React then
              // renders N tiles for fewer entries with undefined duplicate-key behaviour
              // (a reopened 11-entry deck drew 12 tiles; see N4). The index is stable
              // here because the array order only changes when an entry is removed, and
              // it is the same identity the deck panel's own controls act on.
              key={`${i}|${entry.name}|${entry.setCode}|${entry.cardNumber}`}
              entry={entry}
              sideboard={false}
            />
          ))}
        </div>
      )}

      {/* Modals rendered at this level so they cover the whole editor */}
      <DeckCardContextMenu />
      <ArtPickerModal />
    </div>
  )
}
