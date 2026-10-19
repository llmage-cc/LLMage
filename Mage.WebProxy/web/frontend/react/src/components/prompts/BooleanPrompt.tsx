import { useState, type ReactNode } from 'react'
import type { BooleanPrompt as BooleanPromptType } from '@/types'
import { useGameStore } from '@/store/gameStore'

/**
 * Yes/No prompt body with the remembered-answer affordance.
 *
 * Desktop reference: right-clicking the Yes or No button of the Swing FeedbackPanel
 * opens HelperPanel's auto-answer submenu — "Auto-answer YES/NO for the same TEXT and
 * ABILITY" (the id variant, disabled when the ask has no source ability) and
 * "Auto-answer YES/NO for the same TEXT" (the text variant) — and picking an item
 * sends the PlayerAction AND clicks that button, i.e. it stores the memory and answers
 * the prompt on screen in the same action (HelperPanel.java:493-521).
 *
 * This is that port: the small ▾ beside each answer button opens the submenu for THAT
 * answer, and its items call store.autoAnswerPrompt(...) — memory first, then the
 * boolean answer, in one action, exactly like the desktop — followed by a confirmation
 * line. There is no "remember only" mode, because the desktop has none.
 *
 * The id variant is offered only when the ask carries an originalId (a source-less ask
 * like chooseMulligan has none) — the same gating as the desktop, which disables those
 * items when originalId == null (HelperPanel.java:531-539). The whole affordance is
 * withheld when the payload has no auto_answer_message: the engine's key would be a
 * guess, and a wrong key is a silent no-op later.
 *
 * The two answer groups are spelled out rather than looped so every button carries its
 * `data-explain` id as a literal (the explain-coverage source scan reads the attribute
 * text; an id behind a ternary reads as an unexplained button — see AGENTS.md).
 */
export function BooleanPrompt({ prompt }: { prompt: BooleanPromptType }) {
  const booleanChoice = useGameStore((s) => s.booleanChoice)
  const autoAnswerPrompt = useGameStore((s) => s.autoAnswerPrompt)
  // Which answer's submenu is open, null = none. Local to the prompt: DecisionPopup
  // remounts per prompt identity, so it resets with the ask.
  const [menuFor, setMenuFor] = useState<boolean | null>(null)
  const [remembered, setRemembered] = useState<string | null>(null)

  const key = prompt.autoAnswerMessage
  const canRemember = typeof key === 'string' && key.length > 0
  const hasAbilityId = prompt.originalId != null

  const remember = (value: boolean, variant: 'id' | 'text'): void => {
    // Key presence is re-checked here: a closure does not keep the outer narrowing,
    // and a missing key must never become a guessed one (store refuses it too).
    if (typeof key !== 'string' || key.length === 0) return
    const action: 'id_yes' | 'id_no' | 'text_yes' | 'text_no' =
      variant === 'id'
        ? (value ? 'id_yes' : 'id_no')
        : (value ? 'text_yes' : 'text_no')
    // Desktop semantics: store the memory AND answer this prompt (HelperPanel clicks
    // the button after sending the PlayerAction). The store action does both, in order.
    autoAnswerPrompt(action, variant === 'id' ? prompt.originalId ?? null : null, key, value)
    setMenuFor(null)
    setRemembered(
      variant === 'id'
        ? `Auto-answering ${value ? 'YES' : 'NO'} for this text + ability`
        : `Auto-answering ${value ? 'YES' : 'NO'} for this text`,
    )
  }

  const toggle = (value: boolean): void => setMenuFor(menuFor === value ? null : value)

  /** Both submenu bodies — one per answer. Id entries gated on the ask's ability id. */
  const menu = (value: boolean): ReactNode =>
    canRemember && menuFor === value ? (
      <div className="prompt-always-menu" role="menu" aria-label={`Remember this ${value ? 'YES' : 'NO'} answer`}>
        {hasAbilityId && (
          <button
            type="button"
            className="context-menu-item"
            role="menuitem"
            data-explain="prompt-always-id"
            onClick={() => remember(value, 'id')}
          >
            Auto-answer {value ? 'YES' : 'NO'} for the same TEXT and ABILITY
          </button>
        )}
        <button
          type="button"
          className="context-menu-item"
          role="menuitem"
          data-explain="prompt-always-text"
          onClick={() => remember(value, 'text')}
        >
          Auto-answer {value ? 'YES' : 'NO'} for the same TEXT
        </button>
      </div>
    ) : null

  return (
    <div className="prompt-choices prompt-choices-boolean">
      <div className="prompt-boolean-choice">
        <button
          className="btn btn-primary btn-sm"
          onClick={() => booleanChoice(true)}
          data-explain="prompt-yes"
        >
          {prompt.yesLabel}
        </button>
        {canRemember && (
          <button
            type="button"
            className="btn-always"
            aria-haspopup="menu"
            aria-expanded={menuFor === true}
            data-explain="prompt-always-yes"
            title={`Auto-answer ${prompt.yesLabel} when this question comes up again`}
            onClick={() => toggle(true)}
          >
            ▾
          </button>
        )}
        {menu(true)}
      </div>
      <div className="prompt-boolean-choice">
        <button
          className="btn btn-secondary btn-sm"
          onClick={() => booleanChoice(false)}
          data-explain="prompt-no"
        >
          {prompt.noLabel}
        </button>
        {canRemember && (
          <button
            type="button"
            className="btn-always"
            aria-haspopup="menu"
            aria-expanded={menuFor === false}
            data-explain="prompt-always-no"
            title={`Auto-answer ${prompt.noLabel} when this question comes up again`}
            onClick={() => toggle(false)}
          >
            ▾
          </button>
        )}
        {menu(false)}
      </div>
      {remembered && (
        <div className="prompt-always-note" role="status">
          {remembered} — reset it later via a player header's right-click menu.
        </div>
      )}
    </div>
  )
}
