import { useGameStore } from '@/store/gameStore'

/**
 * P1 training-deck picker (Argentum-P1 seat offering surface).
 *
 * A COMPUTER_P1 seat may only be given one of the 55 Standard decklists the
 * policy net was trained on (mage.cards.decks.P1DeckPool). This component renders
 * that pool as a SELECT-only control — the user picks a name, never types an
 * arbitrary list — and writes the chosen deck's canonical text into the seat via
 * the same `onPick` path the other deck pickers use.
 *
 * The list comes from the proxy (`p1_decks`, requested on connect when the server
 * advertises capabilities.p1). The select defaults to the pool's deterministic
 * default so an untouched P1 seat gets a real training deck, never an arbitrary
 * one. The server remains authoritative: a crafted client that ignores this list
 * is rejected at join (TableController.P1DeckRestriction).
 */
export default function P1TrainingDeckPicker({
  onPick,
}: {
  onPick: (deckText: string) => void
}) {
  const p1Decks = useGameStore((s) => s.p1Decks)
  const p1DefaultDeck = useGameStore((s) => s.p1DefaultDeck)

  if (p1Decks.length === 0) {
    return (
      <div className="p1-deck-picker" data-testid="p1-deck-picker-empty">
        <label>P1 training decks:</label>
        <span className="p1-deck-picker-hint">Loading the P1 training deck list…</span>
      </div>
    )
  }

  const handlePick = (e: React.ChangeEvent<HTMLSelectElement>): void => {
    const name = e.target.value
    if (!name) return
    const deck = p1Decks.find((d) => d.name === name)
    if (deck) onPick(deck.deck)
  }

  return (
    <div className="p1-deck-picker" data-testid="p1-deck-picker">
      <label>P1 training decks ({p1Decks.length}):</label>
      <select
        data-explain="p1-deck-picker"
        value={p1DefaultDeck}
        onChange={handlePick}
      >
        {p1Decks.map((d) => (
          <option key={d.name} value={d.name}>
            {d.name}
            {d.name === p1DefaultDeck ? ' (default)' : ''}
          </option>
        ))}
      </select>
    </div>
  )
}
