import { useGameStore } from '@/store/gameStore'

interface RandomDeckPickerProps {
  /** Called with the generated .dck text when a deck is generated. */
  onDeck: (deckText: string) => void
}

/**
 * The Random deck affordance, rendered inside PlayerSlotRow next to the
 * saved-deck picker (the desktop parity point is TournamentPlayerPanel's
 * btnGenerate "Generate a random deck" next to the deck field).
 *
 * Fills the EXISTING deck textarea via onDeck — the picked deck rides the
 * standard join_table/create_table wire path untouched, and the existing
 * legality/validation display runs as-is afterwards. No parallel validator.
 *
 * Fail Fast: with an empty (or unusably small) collection the button is
 * disabled and the slice's error is shown in its place — never a silent
 * empty deck. `randomDeckError` is only cleared by the next attempt.
 */
export default function RandomDeckPicker({ onDeck }: RandomDeckPickerProps) {
  const generateRandomDeck = useGameStore((s) => s.generateRandomDeck)
  const randomDeckError = useGameStore((s) => s.randomDeckError)
  const randomDeckLoading = useGameStore((s) => s.randomDeckLoading)
  // Empty-collection precondition, read live: with no owned cards the button is
  // disabled and the error shown BEFORE any click (Fail Fast), not only after
  // a failed attempt. A corrupt key can only be detected server-side-by-proxy
  // at generation time, so the post-attempt error path still covers it.
  const collectionHasCards = useGameStore((s) =>
    Object.values(s.collection).some((count) => count > 0),
  )
  // Disable only on the named precondition (empty collection) + in-flight
  // generation. A post-attempt error leaves the button ENABLED so the user can
  // retry — every attempt clears the previous error first. Disabling on the
  // error itself would wedge the control: the only error-clearing path is a
  // fresh attempt, and there would be nothing left to click.
  const disabled = randomDeckLoading || !collectionHasCards
  const emptyMessage =
    'Your collection is empty — add cards in the deck editor\'s Collection tab before generating a random deck'
  const shownError = randomDeckError ?? (collectionHasCards ? null : emptyMessage)

  const handleClick = (): void => {
    // generateRandomDeck Fail-Fasts by throwing; surface the error it set
    // (randomDeckError) and never fill the textarea on failure.
    void generateRandomDeck()
      .then((text) => {
        onDeck(text)
      })
      .catch(() => {
        // The slice has already stored the message in randomDeckError.
      })
  }

  return (
    <div className="random-deck-picker">
      <button
        className="btn btn-secondary btn-sm"
        onClick={handleClick}
        disabled={disabled}
        data-explain="random-deck-generate"
      >
        {randomDeckLoading ? 'Generating…' : 'Generate random deck'}
      </button>
      {shownError && (
        <div className="random-deck-picker-error" role="alert" style={{ color: 'var(--color-danger)', fontSize: '0.75rem' }}>
          {shownError}
        </div>
      )}
    </div>
  )
}