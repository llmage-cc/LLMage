import { useState } from 'react'
import type { ChooseChoicePrompt as ChooseChoicePromptType } from '@/types'
import { useGameStore } from '@/store/gameStore'

/** One chooser option, as `buildActionPrompt` produces it from the wire payload. */
type ChoiceOption = ChooseChoicePromptType['choices'][number]

/**
 * The engine's relevance for one option, or null when it is not a suggestion.
 *
 * `mage.choices.ChoiceCreatureType.onChooseStart` scans the chooser's own hand +
 * every player's battlefield/graveyard/exile + the stack and writes a priority
 * into `Choice.getSortData` (me = -100, opponent = -10, unused = 0), which the
 * proxy forwards per option as `order` (WebProxyClient.handleGameChooseChoice).
 * The desktop client sorts its dialog by the same field (PickChoiceDialog.java).
 *
 * Only NEGATIVE values are relevance: 0 means "not used" and the alt-cost chooser
 * numbers its options 0..n, which is ordering, not a suggestion. A missing
 * `order` means the engine did not rank the option — never treat it as suggested.
 */
export function suggestionOrder(choice: ChoiceOption): number | null {
  return typeof choice.order === 'number' && choice.order < 0 ? choice.order : null
}

/**
 * Split the option list into the Suggested group and the rest.
 *
 *  - Suggested: ranked options, priority ascending (me before opponent), stable —
 *    ties keep the engine's own wire order.
 *  - Rest: every other option, in wire order, exactly as it rendered before.
 *  - `filter` narrows BOTH groups in place (it never replaces the list), so the
 *    filter box keeps working once a Suggested heading is added.
 */
export function splitChoiceOptions(
  choices: ChoiceOption[],
  filter: string
): { suggested: ChoiceOption[]; rest: ChoiceOption[] } {
  const matched = filter
    ? choices.filter((c) =>
        (c.name || '').toLowerCase().includes(filter.toLowerCase())
      )
    : choices
  const ranked: Array<{ choice: ChoiceOption; position: number; order: number }> = []
  matched.forEach((choice, position) => {
    const order = suggestionOrder(choice)
    if (order !== null) ranked.push({ choice, position, order })
  })
  ranked.sort((a, b) => a.order - b.order || a.position - b.position)
  return {
    suggested: ranked.map((r) => r.choice),
    rest: matched.filter((c) => suggestionOrder(c) === null),
  }
}

export function ChooseChoicePrompt({ prompt }: { prompt: ChooseChoicePromptType }) {
  const selectChoice = useGameStore((s) => s.selectChoice)
  const [filter, setFilter] = useState('')

  const { suggested, rest } = splitChoiceOptions(prompt.choices, filter)

  const option = (choice: ChoiceOption) => (
    <button
      key={choice.index}
      className="btn-prompt"
      onClick={() => selectChoice(choice.index)}
    >
      {choice.name}
    </button>
  )

  return (
    <div className="prompt-choices">
      {prompt.choices.length > 8 && (
        <input
          type="text"
          className="prompt-choice-filter"
          placeholder="Filter choices…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          style={{
            width: '100%',
            marginBottom: '0.35rem',
            padding: '0.25rem 0.4rem',
            fontSize: '0.82rem',
          }}
        />
      )}
      {suggested.length > 0 && (
        <div
          className="prompt-choice-group-heading"
          // Inline (not popups.css): that stylesheet is shared with three sibling
          // prompts, and this component already styles its filter row
          // and empty-state inline the same way.
          style={{
            flexBasis: '100%',
            fontSize: '0.75rem',
            fontWeight: 600,
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
            color: 'var(--text-secondary)',
            marginTop: '0.15rem',
          }}
        >
          Suggested
        </div>
      )}
      {suggested.map(option)}
      {rest.map(option)}
      {suggested.length + rest.length === 0 && (
        <div style={{ fontSize: '0.8rem', opacity: 0.7 }}>No matches</div>
      )}
    </div>
  )
}
