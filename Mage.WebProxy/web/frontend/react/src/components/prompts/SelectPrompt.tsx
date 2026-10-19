import { useMemo } from 'react'
import type { SelectPrompt as SelectPromptType, GameState, ActionChoice, Card } from '@/types'
import { useGameStore } from '@/store/gameStore'
import { isCombatChoice } from '@/utils/combatClick'

/** Actions that correspond to clicking a card in the hand, on the battlefield,
 * or in a non-hand zone (graveyard flashback, exile plays — see
 * offHandClickableCardIds below). These choices are interactable directly via
 * the card UI (highlight + click), so we don't render a redundant button for
 * them in the right-rail prompt box. Shared with DecisionPopup's window gate
 * (isEmptySelectWindow) via selectChoicesKeptAsButtons below — one definition
 * decides both "no button list here" and "no window at all". */
export const CARD_BASED_ACTIONS = new Set(['cast', 'land', 'activate'])

/** Hand + battlefield clickable card ids (null when there is no state — the
 * callers then keep ALL choices as buttons rather than hiding anything). */
function handBattlefieldClickableCardIds(gameState: GameState | null): Set<string> | null {
  if (!gameState) return null
  const ids = new Set<string>()
  const me = gameState.players.find((p) => p.isMe)
  if (me) {
    for (const card of me.hand) {
      if (card.id) ids.add(card.id)
    }
  }
  for (const player of gameState.players) {
    for (const permanent of player.battlefield) {
      if (permanent.id) ids.add(permanent.id)
    }
  }
  return ids
}

/**
 * Off-hand playable card ids: card-based select choices (cast/land/activate)
 * whose card is rendered in a NON-hand zone of the viewing player — graveyard
 * and exile. The board marks those tiles playable and clickable (PlayerBoard),
 * and the ZonePopup window gives the same affordance to the tiles it renders,
 * so such a choice is interactable on the interface and needs no rail button.
 *
 * The proxy labels off-hand plays 'activate' with the card's own UUID
 * (WebProxyClient.handleGameSelect: findCardViewById resolves graveyard/exile
 * cards), and GameStateSerializer carries every card's id into the zone lists,
 * so the choice id and the tile's card id are the SAME UUID — the lookup needs
 * no matching by name.
 *
 * Only the viewing player's zones qualify: an opponent's graveyard renders the
 * same tiles, but the engine never offers a choice id that belongs to a card
 * there, so the id test alone cannot light them up.
 */
export function offHandClickableCardIds(
  prompt: SelectPromptType,
  gameState: GameState | null
): Set<string> {
  const ids = new Set<string>()
  if (!gameState) return ids
  const cardBased = prompt.choices.filter(
    (c) => c.action && CARD_BASED_ACTIONS.has(c.action) && c.id
  )
  if (cardBased.length === 0) return ids
  const me = gameState.players.find((p) => p.isMe)
  if (!me) return ids
  const zones: Array<Card[] | undefined> = [me.graveyard, me.exile]
  for (const zone of zones) {
    for (const card of zone ?? []) {
      if (card.id && cardBased.some((c) => c.id === card.id)) ids.add(card.id)
    }
  }
  return ids
}

/** Every card id that is clickable in the interface for this prompt: hand +
 * battlefield (the original selection path) plus playable cards in my
 * graveyard/exile (the off-hand path). Null when there is no state — the
 * callers then keep ALL choices as buttons rather than hiding anything. */
function clickableCardIds(prompt: SelectPromptType, gameState: GameState | null): Set<string> | null {
  const base = handBattlefieldClickableCardIds(gameState)
  if (!base) return null
  for (const id of offHandClickableCardIds(prompt, gameState)) base.add(id)
  return base
}

/**
 * The choices that would be kept as BUTTONS — the single source of truth shared
 * by the rail's SelectPrompt (this file) and DecisionPopup's window gate, so
 * "the prompt has nothing to click" is computed identically in both places.
 *
 * Card-based choices (cast/land/activate) are hidden when their card is
 * clickable in the interface — hand, battlefield, OR my graveyard/exile
 * (offHandClickableCardIds: flashback and exile plays are clicked on the board
 * tiles and in ZonePopup, not on a button). Everything else stays.
 */
export function selectChoicesKeptAsButtons(
  prompt: SelectPromptType,
  gameState: GameState | null
): ActionChoice[] {
  const clickableIds = clickableCardIds(prompt, gameState)
  return prompt.choices.filter((c) => {
    if (c.action && CARD_BASED_ACTIONS.has(c.action)) {
      return !(clickableIds && clickableIds.has(c.id))
    }
    return true
  })
}

export function SelectPrompt({ prompt }: { prompt: SelectPromptType }) {
  const selectChoice = useGameStore((s) => s.selectChoice)
  const playCard = useGameStore((s) => s.playCard)
  const selectSpecial = useGameStore((s) => s.selectSpecial)
  const gameState = useGameStore((s) => s.gameState)

  // When the server sends a special_button (e.g. "All attack"), clicking it
  // sends sendPlayerString("special") which triggers the server-side all-attack logic
  // that declares ALL creatures as attackers at once (not one at a time).
  const hasSpecialButton = !!prompt.specialButton

  // Choices kept in the right-rail button list: everything that is NOT a
  // card-based choice whose card is clickable in the interface. On-board
  // cast/land/activate choices are handled by clicking the card itself —
  // hand, battlefield, and (offHandClickableCardIds) my graveyard/exile
  // tiles for flashback and exile plays. Combat choices (attack/block) and
  // choices without an action stay as buttons.
  const visibleChoices = useMemo(
    () => selectChoicesKeptAsButtons(prompt, gameState),
    [prompt, gameState]
  )

  const noVisibleChoices = visibleChoices.length === 0

  const handleSpecialButton = () => {
    selectSpecial('special')
  }

  return (
    <div className="prompt-choices">
      {hasSpecialButton && (
        <button
          className="btn-prompt btn-prompt-all-attack"
          onClick={handleSpecialButton}
        >
          {prompt.specialButton}
        </button>
      )}
      {noVisibleChoices ? (
        <div className="prompt-hint">
          {prompt.choices.length === 0
            ? 'Nothing to play — Pass Priority'
            : 'Click a highlighted card to play it'}
        </div>
      ) : (
        visibleChoices.map((choice, idx) => (
          <button
            key={idx}
            className="btn-prompt"
            // Combat choices (attack/block) answer by creature UUID, not index:
            // the engine renumbers the choice list after every declare and the
            // rail can still be one render behind, so a positional index from a
            // stale render declares the WRONG creature (or 400s as “Invalid
            // choice index”). Identity can't be stale. See utils/combatClick.
            onClick={() => (isCombatChoice(choice) ? playCard(choice.id) : selectChoice(choice.index))}
          >
            {choice.name || `Choice ${idx + 1}`}
          </button>
        ))
      )}
    </div>
  )
}