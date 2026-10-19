import { useState } from 'react'
import type { AmountPrompt as AmountPromptType } from '@/types'
import { useGameStore } from '@/store/gameStore'

export function AmountPrompt({ prompt }: { prompt: AmountPromptType }) {
  const amountChoice = useGameStore((s) => s.amountChoice)
  const booleanChoice = useGameStore((s) => s.booleanChoice)
  const hideActionPrompt = useGameStore((s) => s.hideActionPrompt)
  const [value, setValue] = useState(prompt.defaultValue)

  const handleCancel = () => {
    hideActionPrompt()
    booleanChoice(false)
  }

  return (
    <div className="prompt-amount">
      <div className="prompt-amount-row">
        <input
          type="number"
          value={value}
          min={prompt.min}
          max={prompt.max}
          step={1}
          className="prompt-amount-input"
          autoFocus
          onChange={(e) => setValue(parseInt(e.target.value) || 0)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') amountChoice(value)
          }}
        />
        <button
          className="btn btn-primary btn-sm"
          onClick={() => amountChoice(value)}
          data-explain="prompt-submit-amount"
        >
          Submit
        </button>
        <button
          className="btn btn-secondary btn-sm"
          onClick={handleCancel}
          data-explain="prompt-cancel-amount"
          title="Cancel"
        >
          Cancel
        </button>
      </div>
      <div className="prompt-amount-hint">
        Range: {prompt.min}–{prompt.max}
      </div>
    </div>
  )
}