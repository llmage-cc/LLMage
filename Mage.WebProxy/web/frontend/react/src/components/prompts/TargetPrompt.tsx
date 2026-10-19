import type { TargetPrompt as TargetPromptType } from '@/types'
import { useGameStore } from '@/store/gameStore'
import { isAllowedImageUrl } from '@/utils/cardImage'
import { useCardGridPreview } from '@/hooks/useCardGridPreview'
import type { Card } from '@/types'

export function TargetPrompt({ prompt }: { prompt: TargetPromptType }) {
  const selectTarget = useGameStore((s) => s.selectTarget)
  const passPriority = useGameStore((s) => s.passPriority)
  const { showPreview, hidePreview } = useCardGridPreview()

  // Trigger-order prompt: the server asks the player to ORDER simultaneous
  // triggered abilities. `targets` carries one entry per candidate ability
  // (id = ability UUID, name = source card, abilityText = engine-rendered trigger
  // text — see WebProxyClient.handleGameTarget). These are always selectable —
  // the whole list is the set of valid answers — so they must NOT be gated on any
  // per-card validity flag. One click picks one ability; the engine wants a single
  // UUID, not an N-target selection.
  if (prompt.triggerOrder) {
    return (
      <div className="prompt-targets">
        <div className="prompt-hint" style={{ marginBottom: '0.4rem' }}>
          {prompt.targets.length > 1
            ? 'Choose which triggered ability goes on the stack first'
            : 'Choose the triggered ability to put on the stack'}
        </div>
        {prompt.targets.map((target, idx) => (
          <button
            key={target.id || idx}
            className="btn-prompt prompt-ability-choice"
            onClick={() => selectTarget(target.id)}
            title={target.abilityText ? `${target.name} — ${target.abilityText}` : target.name}
            style={{ display: 'block', width: '100%', textAlign: 'left', marginBottom: '0.25rem' }}
          >
            <span className="prompt-ability-source">{target.name}</span>
            {target.abilityText && (
              <span className="prompt-ability-rule">{target.abilityText}</span>
            )}
          </button>
        ))}
        {/* Dismiss path, identical to every other GAME_TARGET prompt: when the server
            marks the prompt optional (required=false), the player may decline. The
            engine's trigger-order prompt is always required (HumanPlayer
            .chooseTriggeredAbility waits for one ability UUID), so in practice this
            button only appears if the engine ever relaxes that — the point is the
            family must not be a dead end when it does. */}
        {!prompt.required && (
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => passPriority()}
            style={{ marginTop: '0.25rem' }}
          >
            {prompt.doneButtonText ?? "Skip (Don't select)"}
          </button>
        )}
      </div>
    )
  }

  // If there are lookAtCards (Surveil/scry/fetch), show them as a popup
  // with clickable cards for the player to choose.
  if (prompt.lookAtCards && prompt.lookAtCards.length > 0) {
    return (
      <div className="prompt-targets">
        <div style={{ marginBottom: '0.4rem', fontSize: '0.85rem' }}>
          {prompt.message}
        </div>
        <div className="prompt-hint" style={{ marginBottom: '0.4rem' }}>
          Click a card in the revealed window
        </div>
        <div className="popup-cards-grid">
          {prompt.lookAtCards.map((card, idx) => (
            <div
              key={idx}
              className={`popup-card-thumb${card.isValidTarget ? ' valid-target' : ''}`}
              onClick={card.isValidTarget && card.id ? () => selectTarget(card.id!) : undefined}
              onMouseEnter={(e) => showPreview(card as unknown as Card, e)}
              onMouseLeave={hidePreview}
              style={card.isValidTarget ? { cursor: 'pointer' } : undefined}
            >
              {card.imageUrl && isAllowedImageUrl(card.imageUrl) && (
                <img src={card.imageUrl} alt={card.name} draggable={false} />
              )}
              <div className="popup-card-name">{card.name}</div>
              <div className="popup-card-type">{card.typeLine}</div>
            </div>
          ))}
        </div>
        {!prompt.required && (
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => passPriority()}
            data-explain="prompt-skip-target"
            style={{ marginTop: '0.4rem' }}
          >
            Skip (Don't select)
          </button>
        )}
      </div>
    )
  }

  const isAnyNumber = (prompt.chosenCount ?? 0) > 0 || prompt.doneButtonText != null

  // Standard target prompt — list of target buttons
  return (
    <div className="prompt-targets">
      {isAnyNumber ? (
        <div className="prompt-hint" style={{ marginBottom: '0.25rem' }}>
          {(prompt.chosenCount ?? 0) > 0
            ? `${prompt.chosenCount} chosen — select another, or ${prompt.doneButtonText ?? 'Done'}`
            : 'Click a highlighted target, or pass'}
        </div>
      ) : prompt.targets.length > 0 ? (
        <div className="prompt-hint" style={{ marginBottom: '0.25rem' }}>
          Click a highlighted target
        </div>
      ) : null}
      {prompt.targets.map((target, idx) => (
        <button
          key={idx}
          className="btn-prompt"
          onClick={() => selectTarget(target.id)}
          style={{ display: 'block', width: '100%', textAlign: 'left', marginBottom: '0.25rem' }}
        >
          {target.name}
        </button>
      ))}
      {!prompt.required && (
        <button
          className={isAnyNumber ? 'btn btn-primary btn-sm' : 'btn btn-secondary btn-sm'}
          onClick={() => passPriority()}
          data-explain="prompt-skip-target"
          style={{ marginTop: '0.25rem' }}
        >
          {prompt.doneButtonText ?? "Skip (Don't select)"}
        </button>
      )}
    </div>
  )
}
