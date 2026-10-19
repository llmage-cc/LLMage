import type { ChooseAbilityPrompt as ChooseAbilityPromptType } from '@/types'
import { useGameStore } from '@/store/gameStore'
import { ManaText } from '../ManaCost'

export function ChooseAbilityPrompt({ prompt }: { prompt: ChooseAbilityPromptType }) {
  const selectChoice = useGameStore((s) => s.selectChoice)
  const booleanChoice = useGameStore((s) => s.booleanChoice)
  const hideActionPrompt = useGameStore((s) => s.hideActionPrompt)
  // Cancel = booleanChoice(false) — the desktop client's AbilityPicker cancels
  // the same way (popupMenuCanceled → sendPlayerBoolean(gameId, false)), and
  // the engine treats a non-UUID answer as "activate nothing" (the waiting
  // HumanPlayer reads only response.getUUID; null → no ability activated).
  const handleCancel = () => {
    hideActionPrompt()
    booleanChoice(false)
  }
  return (
    <div className="prompt-choices">
      {prompt.choices.map((choice, idx) => (
        <button
          key={idx}
          className="btn-prompt"
          onClick={() => selectChoice(choice.index)}
        >
          <div>{choice.name}</div>
          <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
            <ManaText text={choice.abilityText} />
          </div>
        </button>
      ))}
      <button
        className="btn btn-secondary btn-sm"
        onClick={handleCancel}
        title="Cancel this activation — nothing is played"
      >
        Cancel
      </button>
    </div>
  )
}