import type { ChoosePilePrompt as ChoosePilePromptType } from '@/types'
import { useGameStore } from '@/store/gameStore'
import { safeCardImageUrl } from '@/utils/cardImage'
import { useCardGridPreview } from '@/hooks/useCardGridPreview'

export function ChoosePilePrompt({ prompt }: { prompt: ChoosePilePromptType }) {
  const choosePile = useGameStore((s) => s.choosePile)
  const { showPreview, hidePreview } = useCardGridPreview()

  return (
    <div className="prompt-piles">
      <div className="prompt-pile">
        <div className="prompt-pile-label">
          Pile 1 ({prompt.pile1.cards.length} cards)
        </div>
        <div className="prompt-pile-cards">
          {prompt.pile1.cards.map((card, idx) => (
            <div key={idx} className="popup-card-mini" onMouseEnter={(e) => showPreview(card, e)} onMouseLeave={hidePreview}>
              <img src={safeCardImageUrl(card, () => '')} alt={card.name} loading="lazy" />
              <div className="popup-card-name">{card.name}</div>
            </div>
          ))}
        </div>
        <button
          className="btn btn-primary btn-sm"
          onClick={() => choosePile(0)}
          data-explain="prompt-choose-pile-1"
        >
          Choose Pile 1
        </button>
      </div>
      <div className="prompt-pile">
        <div className="prompt-pile-label">
          Pile 2 ({prompt.pile2.cards.length} cards)
        </div>
        <div className="prompt-pile-cards">
          {prompt.pile2.cards.map((card, idx) => (
            <div key={idx} className="popup-card-mini" onMouseEnter={(e) => showPreview(card, e)} onMouseLeave={hidePreview}>
              <img src={safeCardImageUrl(card, () => '')} alt={card.name} loading="lazy" />
              <div className="popup-card-name">{card.name}</div>
            </div>
          ))}
        </div>
        <button
          className="btn btn-primary btn-sm"
          onClick={() => choosePile(1)}
          data-explain="prompt-choose-pile-2"
        >
          Choose Pile 2
        </button>
      </div>
    </div>
  )
}