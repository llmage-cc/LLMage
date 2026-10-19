import { useState } from 'react'
import type { MultiAmountPrompt as MultiAmountPromptType } from '@/types'
import { useGameStore } from '@/store/gameStore'

export function MultiAmountPrompt({ prompt }: { prompt: MultiAmountPromptType }) {
  const multiAmountChoice = useGameStore((s) => s.multiAmountChoice)
  const booleanChoice = useGameStore((s) => s.booleanChoice)
  const hideActionPrompt = useGameStore((s) => s.hideActionPrompt)
  const [values, setValues] = useState<number[]>(
    prompt.entries.map((e) => e.value)
  )

  const total = values.reduce((sum, v) => sum + (v || 0), 0)

  const handleCancel = () => {
    hideActionPrompt()
    booleanChoice(false)
  }

  return (
    <div className="prompt-multi-amount">
      {prompt.entries.map((entry, idx) => (
        <div key={idx} className="prompt-amount-row">
          <span className="prompt-multi-label">{entry.name}</span>
          <input
            type="number"
            value={values[idx] ?? 0}
            min={entry.min}
            max={entry.max}
            step={1}
            className="prompt-amount-input"
            onChange={(e) => {
              const newValues = [...values]
              newValues[idx] = parseInt(e.target.value) || 0
              setValues(newValues)
            }}
          />
        </div>
      ))}
      {(prompt.total > 0 || prompt.min > 0 || prompt.max > 0) && (
        <div className="prompt-multi-amount-total" style={{ fontWeight: 600 }}>
          Total: {total}
          {prompt.max > 0 ? ` / ${prompt.max}` : ''}
          {prompt.max > 0 ? ` (remaining: ${prompt.max - total})` : ''}
          {!prompt.max && prompt.min > 0 ? ` (min ${prompt.min})` : ''}
          {prompt.max > 0 && total !== prompt.max && (
            <span style={{ color: total > prompt.max ? 'var(--accent-danger, #e74c3c)' : 'var(--accent-warning, #e67e22)', marginLeft: '0.4rem' }}>
              {total > prompt.max ? 'over limit' : 'not enough'}
            </span>
          )}
        </div>
      )}
      <div style={{ display: 'flex', gap: '0.3rem' }}>
        <button
          className="btn btn-primary btn-sm"
          onClick={() => multiAmountChoice(values)}
          data-explain="prompt-submit-amount"
          style={{ flex: 1 }}
        >
          Submit
        </button>
        <button
          className="btn btn-secondary btn-sm"
          onClick={handleCancel}
          data-explain="prompt-cancel-amount"
        >
          Cancel
        </button>
      </div>
    </div>
  )
}