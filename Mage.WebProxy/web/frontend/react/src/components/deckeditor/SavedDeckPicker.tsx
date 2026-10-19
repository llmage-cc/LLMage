import { useGameStore } from '@/store/gameStore'

interface SavedDeckPickerProps {
  /** Called with the deck's .dck-format text when a saved deck is chosen. */
  onPick: (deckText: string) => void
}

/**
 * Picker shown above the deck textarea in GameSetup/TournamentSetup dialogs.
 * Lets the player choose one of their saved decks from localStorage instead
 * of pasting text.
 *
 * The picked deck is serialized to .dck text via the deckSlice's export logic
 * (same path the deck editor uses) and handed to the parent, which fills the
 * existing deck textarea. From there the standard join_table / create_table
 * wire path is untouched.
 */
export default function SavedDeckPicker({ onPick }: SavedDeckPickerProps) {
  const savedDecks = useGameStore((s) => s.savedDecks)

  const handleChange = (e: React.ChangeEvent<HTMLSelectElement>): void => {
    const id = e.target.value
    if (!id) return
    const deck = savedDecks.find((d) => d.id === id)
    if (!deck) return
    // Format as .dck text — identical to what ImportExportControls exports.
    const lines: string[] = [`NAME:${deck.name}`]
    for (const c of deck.cards) {
      const setPart = c.setCode && c.cardNumber ? ` [${c.setCode}:${c.cardNumber}]` : ''
      lines.push(`${c.count}${setPart} ${c.name}`)
    }
    for (const c of deck.sideboard) {
      const setPart = c.setCode && c.cardNumber ? ` [${c.setCode}:${c.cardNumber}]` : ''
      lines.push(`SB: ${c.count}${setPart} ${c.name}`)
    }
    onPick(lines.join('\n'))
  }

  if (savedDecks.length === 0) return null

  return (
    <div className="saved-deck-picker">
      <label>Use a saved deck:</label>
      <select onChange={handleChange} defaultValue="">
        <option value="">— pick one —</option>
        {savedDecks.map((d) => (
          <option key={d.id} value={d.id}>
            {d.name} ({d.cards.reduce((a, c) => a + c.count, 0)} cards)
          </option>
        ))}
      </select>
      <span className="saved-deck-picker-hint">or paste below</span>
    </div>
  )
}
