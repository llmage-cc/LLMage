import { useState, useRef, useCallback, useEffect } from 'react'
import { useGameStore } from '@/store/gameStore'
import { resolveCardImage, safeCardImageUrl } from '@/utils/cardImage'
import { rootZoom, cssViewportWidth, cssViewportHeight } from '@/utils/viewport'
import { candidateGridHint, candidateLabel, isCandidateSelectable, clickCandidate } from '@/utils/targetCandidates'
import { hidesCandidate, readOnlySelectablePref, writeOnlySelectablePref } from '@/utils/promptFilters'
import { useCardGridPreview } from '@/hooks/useCardGridPreview'
import {
  TargetPrompt as TargetPromptType,
  ChoosePilePrompt as ChoosePilePromptType,
  LookAtCard,
  Card,
} from '@/types'

/**
 * CardSelectPopup — a resizable, draggable floating window for prompts that
 * require the player to select from a set of cards (deck search, fetchlands,
 * sideboard, Fact or Fiction, etc.).
 *
 * Handles two prompt types:
 *   - target with lookAtCards (fetchlands, Surveil, deck search, sideboard,
 *     and the trigger-order prompt whose candidates arrive as lookAt cards)
 *   - choose_pile (Fact or Fiction)
 *
 * The popup auto-appears when such a prompt is active and auto-dismisses when
 * the prompt is cleared by the server (after a selection is made).
 */
/**
 * Whether the floating card-select popup owns this prompt.
 *
 * Exported (with the body components below) so the behavioural harness in
 * scripts/verify-trigger-order.sh can drive the REAL render path under Node —
 * react-dom/server cannot read a live zustand store (its server snapshot is the
 * module's initial state), so the store-driven shell is asserted via this
 * predicate and the bodies are rendered with explicit prompts.
 */
export function shouldShowCardSelectPopup(actionPrompt: TargetPromptType | ChoosePilePromptType | null): boolean {
  if (!actionPrompt) return false
  if (actionPrompt.type === 'choose_pile') return true
  return actionPrompt.type === 'target' && actionPrompt.lookAtCards != null && actionPrompt.lookAtCards.length > 0
}

export default function CardSelectPopup() {
  const actionPrompt = useGameStore((s) => s.actionPrompt)
  const setRevealedPopup = useGameStore((s) => s.setRevealedPopup)

  // Only render for card-selection prompt types
  const isCardSelectPrompt = !!actionPrompt && (
    (actionPrompt.type === 'target' && (actionPrompt as TargetPromptType).lookAtCards != null &&
     ((actionPrompt as TargetPromptType).lookAtCards!.length > 0)) ||
    actionPrompt.type === 'choose_pile'
  )

  // Reset window position when a new prompt arrives (different message = new prompt)
  const prevPromptKey = useRef<string | null>(null)
  const promptKey = actionPrompt ? `${actionPrompt.type}:${actionPrompt.rawMessage}` : null
  const [pos, setPos] = useState({ x: 150, y: 80, w: 600, h: 400 })

  useEffect(() => {
    if (promptKey !== prevPromptKey.current) {
      prevPromptKey.current = promptKey
      if (promptKey != null) {
        // Center the popup on screen for a new prompt.
        // pos.x/y are ROOT CSS px (fixed left/top), so measure the viewport in
        // the same space (innerWidth is REAL px under root zoom).
        const vw = cssViewportWidth()
        const vh = cssViewportHeight()
        const w = Math.min(700, vw - 100)
        const h = Math.min(500, vh - 100)
        setPos({ x: Math.max(20, (vw - w) / 2), y: Math.max(20, (vh - h) / 2), w, h })
      }
    }
  }, [promptKey])

  // Clear any stale revealedPopup when a card-select prompt is active
  // (they can conflict — revealedPopup is for display-only groups)
  useEffect(() => {
    if (isCardSelectPrompt) {
      setRevealedPopup(null)
    }
  }, [isCardSelectPrompt, setRevealedPopup])

  // ── Dragging ──
  const dragRef = useRef<{ startX: number; startY: number; origX: number; origY: number; zoom: number } | null>(null)
  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    dragRef.current = { startX: e.clientX, startY: e.clientY, origX: pos.x, origY: pos.y, zoom: rootZoom() }
    const move = (ev: MouseEvent) => {
      if (!dragRef.current) return
      const { startX, startY, origX, origY, zoom } = dragRef.current
      // clientX deltas are REAL screen px; pos.x/y are ROOT CSS px (fixed left/top)
      setPos((p) => ({
        ...p,
        x: origX + (ev.clientX - startX) / zoom,
        y: origY + (ev.clientY - startY) / zoom,
      }))
    }
    const up = () => {
      dragRef.current = null
      document.removeEventListener('mousemove', move)
      document.removeEventListener('mouseup', up)
    }
    document.addEventListener('mousemove', move)
    document.addEventListener('mouseup', up)
  }, [pos.x, pos.y])

  // ── Resizing ──
  const resizeRef = useRef<{ startX: number; startY: number; origW: number; origH: number; zoom: number } | null>(null)
  const handleResizeDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    resizeRef.current = { startX: e.clientX, startY: e.clientY, origW: pos.w, origH: pos.h, zoom: rootZoom() }
    const move = (ev: MouseEvent) => {
      if (!resizeRef.current) return
      const { startX, startY, origW, origH, zoom } = resizeRef.current
      setPos((p) => ({
        ...p,
        w: Math.max(300, origW + (ev.clientX - startX) / zoom),
        h: Math.max(200, origH + (ev.clientY - startY) / zoom),
      }))
    }
    const up = () => {
      resizeRef.current = null
      document.removeEventListener('mousemove', move)
      document.removeEventListener('mouseup', up)
    }
    document.addEventListener('mousemove', move)
    document.addEventListener('mouseup', up)
  }, [pos.w, pos.h])

  if (!actionPrompt || !isCardSelectPrompt) return null

  return (
    <div
      className="float-window"
      style={{ left: pos.x, top: pos.y, width: pos.w, height: pos.h }}
    >
      <div className="float-window-header" onMouseDown={handleMouseDown}>
        <span className="float-window-title">{actionPrompt.message}</span>
      </div>
      <div className="float-window-body">
        {actionPrompt.type === 'target' && (
          <TargetSelectBody prompt={actionPrompt as TargetPromptType} />
        )}
        {actionPrompt.type === 'choose_pile' && (
          <ChoosePileBody prompt={actionPrompt as ChoosePilePromptType} />
        )}
      </div>
      <div className="float-window-resize" onMouseDown={handleResizeDown} />
    </div>
  )
}

/** ── Target prompt with lookAtCards — card grid with valid targets highlighted ── */
export function TargetSelectBody({ prompt }: { prompt: TargetPromptType }) {
  const selectTarget = useGameStore((s) => s.selectTarget)
  const hideActionPrompt = useGameStore((s) => s.hideActionPrompt)
  const passPriority = useGameStore((s) => s.passPriority)
  const { showPreview, hidePreview } = useCardGridPreview()
  const cards = prompt.lookAtCards ?? []
  // Trigger-order candidates are all legal answers (the server sent exactly the
  // orderable set) — see utils/targetCandidates.ts. The old sole gate
  // (`card.isValidTarget`) rendered every tile disabled because the proxy marked
  // them all invalid when `targets` arrived null.
  const triggerOrder = prompt.triggerOrder

  // "Only selectable" — hides candidates that cannot be clicked (the non-land
  // cards in a fetch-land search). Display-only: the click path below is
  // untouched, so a filtered grid answers EXACTLY like an unfiltered one.
  //
  // Persisted in localStorage (utils/promptFilters.ts), not component state:
  // every search is a NEW prompt and this component unmounts between prompts, so
  // component state would silently forget the choice by the next fetch land —
  // and the switch stays visible in every popup, so a persisted ON is never
  // hidden from the player. Default (key absent) is OFF.
  const [onlySelectable, setOnlySelectable] = useState(readOnlySelectablePref)
  const visibleCards = onlySelectable ? cards.filter((c) => !hidesCandidate(prompt, c)) : cards
  const hiddenCount = cards.length - visibleCards.length

  const handleSelect = (card: LookAtCard) => {
    clickCandidate(prompt, card, { selectTarget, hideActionPrompt })
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <div
        style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          gap: '0.75rem', flexWrap: 'wrap', marginBottom: '0.4rem',
        }}
      >
        <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
          {candidateGridHint(prompt)}
        </div>
        <label
          htmlFor="only-selectable-toggle"
          title={
            'Hide cards that cannot be selected for this prompt (e.g. the non-land cards '
            + 'in a fetch-land search). Off by default; remembered for your next prompt. '
            + 'Clicking cards still behaves exactly the same.'
          }
          style={{
            display: 'flex', alignItems: 'center', gap: '0.35rem', cursor: 'pointer',
            fontSize: '0.75rem', color: 'var(--text-secondary)', userSelect: 'none',
          }}
        >
          <input
            id="only-selectable-toggle"
            data-testid="only-selectable-toggle"
            type="checkbox"
            checked={onlySelectable}
            onChange={(e) => {
              setOnlySelectable(e.target.checked)
              writeOnlySelectablePref(e.target.checked)
            }}
            style={{ cursor: 'pointer', margin: 0 }}
          />
          Only selectable
        </label>
      </div>
      <div
        data-testid="card-select-count"
        style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: '0.4rem' }}
      >
        {onlySelectable
          ? `Showing ${visibleCards.length} of ${cards.length} cards`
          : `Showing all ${cards.length} cards`}
      </div>
      <div className="popup-cards-grid" style={{ flex: 1, overflowY: 'auto' }}>
        {visibleCards.map((card, idx) => {
          const selectable = isCandidateSelectable(prompt, card)
          return (
            <div
              key={idx}
              className={`popup-card${selectable ? ' valid-target' : ' popup-card-disabled'}${triggerOrder ? ' popup-card-ability' : ''}`}
              onClick={() => handleSelect(card)}
              onMouseEnter={(e) => showPreview(card as unknown as Card, e)}
              onMouseLeave={hidePreview}
              style={selectable ? { cursor: 'pointer' } : undefined}
            >
              <img
                // Ability candidates carry the SOURCE card's art (the raw name is the
                // literal "Ability", which scryfall 404s).
                src={safeCardImageUrl({ imageUrl: card.sourceImageUrl || card.imageUrl }, () => resolveCardImage(card.sourceName || card.name, card, null, 'normal'))}
                alt={card.sourceName || card.name}
                loading="lazy"
              />
              <div className="popup-card-name">{candidateLabel(card)}</div>
              <div className="popup-card-type">{card.typeLine || ''}</div>
              {selectable && (
                <div className="popup-card-validity" style={{ color: 'var(--accent-success, #27ae60)' }}>
                  ✓ Valid
                </div>
              )}
            </div>
          )
        })}
      </div>
      {/* Fail Fast-adjacent guard: an ALL-hidden grid must not look like a bug or
          a dead end — say why it is empty and how to undo it. */}
      {onlySelectable && hiddenCount === cards.length && (
        <div
          data-testid="only-selectable-empty"
          style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', marginTop: '0.4rem' }}
        >
          No card here can be selected — the filter hides all {cards.length}. Untick
          “Only selectable” to see them.
        </div>
      )}
      {!prompt.required && (
        <button
          className="btn btn-secondary btn-sm"
          onClick={() => { passPriority(); hideActionPrompt() }}
          style={{ marginTop: '0.5rem', alignSelf: 'flex-end' }}
        >
          {prompt.doneButtonText ?? "Skip (Don’t select)"}
        </button>
      )}
    </div>
  )
}

/** ── Choose pile prompt (Fact or Fiction) — two piles side by side ── */
function ChoosePileBody({ prompt }: { prompt: ChoosePilePromptType }) {
  const choosePile = useGameStore((s) => s.choosePile)
  const hideActionPrompt = useGameStore((s) => s.hideActionPrompt)
  const { showPreview, hidePreview } = useCardGridPreview()

  const handleChoose = (pile: number) => {
    choosePile(pile)
    hideActionPrompt()
  }

  return (
    <div style={{ display: 'flex', gap: '0.75rem', height: '100%' }}>
      {([
        { label: 'Pile 1', cards: prompt.pile1.cards, pileIdx: 0 },
        { label: 'Pile 2', cards: prompt.pile2.cards, pileIdx: 1 },
      ] as const).map((pile) => (
        <div key={pile.pileIdx} style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
          <div style={{ fontWeight: 600, fontSize: '0.85rem', marginBottom: '0.4rem', color: 'var(--accent-primary)' }}>
            {pile.label} ({pile.cards.length} cards)
          </div>
          <div className="popup-cards-grid" style={{ flex: 1, overflowY: 'auto' }}>
            {pile.cards.map((card, idx) => (
              <div
                key={idx}
                className="popup-card"
                onMouseEnter={(e) => showPreview(card, e)}
                onMouseLeave={hidePreview}
              >
                <img
                  src={safeCardImageUrl(card, () => resolveCardImage(card.name, card, null, 'small'))}
                  alt={card.name}
                  loading="lazy"
                />
                <div className="popup-card-name">{card.name}</div>
              </div>
            ))}
          </div>
          <button
            className="btn btn-primary btn-sm"
            onClick={() => handleChoose(pile.pileIdx)}
            style={{ marginTop: '0.5rem' }}
          >
            Choose {pile.label}
          </button>
        </div>
      ))}
    </div>
  )
}