import { useMemo } from 'react'
import type { Permanent } from '@/types'
import { isLikelyLand, isCreature } from '@/utils/cardTypes'
import { groupCardsByKey, groupLandsByName, MAX_VISUAL_STACK_DEPTH } from '@/utils/cardGrouping'
import { CardThumb } from './CardThumb'

/**
 * Battlefield zone — ported from game-renderer.js makeBattlefieldZone.
 * Splits cards into lands/creatures/other, groups identical cards with ×N badges,
 * and renders auras/equipment as attachments under their targets.
 *
 * Props:
 * - cards: Array of permanents on the battlefield
 * - isTopPlayer: Whether this is the opponent (lands on top, creatures below)
 * - playableCardIds: Set of card IDs that can be played (for highlighting)
 * - combatChoiceIds: Set of card IDs that can be selected for combat
 * - targetIds: Set of card IDs that are valid targets
 * - onCardClick: Click handler for card selection
 */
interface BattlefieldZoneProps {
  cards: Permanent[]
  isTopPlayer?: boolean | undefined
  playableCardIds?: Set<string> | undefined
  combatChoiceIds?: Set<string> | undefined
  combatDeclaredIds?: Set<string> | undefined
  targetIds?: Set<string> | undefined
  manaTappableIds?: Set<string> | undefined
  onCardClick?: ((cardId: string | null) => void) | undefined
  /**
   * The player who controls this battlefield, and whether that is the viewing
   * player. Together they resolve token-art preferences (an opponent's shared
   * choice vs. my own art) — see utils/tokenArt.ts.
   */
  ownerPlayerId?: string | null | undefined
  isMine?: boolean | undefined
}

/**
 * The `ownerPlayerId`/`isMine` pair every CardThumb in this zone needs, bundled once
 * so each of the six render sites spreads a single prop instead of two.
 */
type ThumbOwner = { ownerPlayerId: string | null | undefined; isMine: boolean }

export function BattlefieldZone({
  cards,
  isTopPlayer = false,
  playableCardIds,
  combatChoiceIds,
  combatDeclaredIds,
  targetIds,
  manaTappableIds,
  onCardClick,
  ownerPlayerId,
  isMine = false,
}: BattlefieldZoneProps) {
  // Bundled once and spread into every CardThumb below.
  const owner: ThumbOwner = { ownerPlayerId, isMine }

  // Build id→card map and attachment mapping
  const { lands, creatures, otherNonLands, attachments } = useMemo(() => {
    const idMap = new Map<string, Permanent>()
    const attMap = new Map<string, Permanent[]>()
    const attIds = new Set<string>()

    // First pass: build id map
    for (const card of cards) {
      if (card.id) {
        idMap.set(card.id, card)
      }
    }

    // Second pass: find attachments
    for (const card of cards) {
      if (card.attachedTo && idMap.has(card.attachedTo)) {
        const existing = attMap.get(card.attachedTo) ?? []
        existing.push(card)
        attMap.set(card.attachedTo, existing)
        if (card.id) attIds.add(card.id)
      }
    }

    // Split into lands, creatures, and other non-lands
    const lnds: Permanent[] = []
    const crts: Permanent[] = []
    const other: Permanent[] = []

    for (const card of cards) {
      if (card.id && attIds.has(card.id)) {
        continue // skip attached cards — they render under their targets
      }
      if (isLikelyLand(card)) {
        lnds.push(card)
      } else if (isCreature(card)) {
        crts.push(card)
      } else {
        other.push(card)
      }
    }

    return { lands: lnds, creatures: crts, otherNonLands: other, attachments: attMap }
  }, [cards])

  // Mark cards that have attachments so they don't group with non-attached copies
  const creaturesWithAttachmentFlag = useMemo(() => {
    return creatures.map((c) => ({
      ...c,
      _hasAttachments: c.id ? attachments.has(c.id) : false,
    }))
  }, [creatures, attachments])

  const otherWithAttachmentFlag = useMemo(() => {
    return otherNonLands.map((c) => ({
      ...c,
      _hasAttachments: c.id ? attachments.has(c.id) : false,
    }))
  }, [otherNonLands, attachments])

  const creatureGroups = useMemo(() => groupCardsByKey(creaturesWithAttachmentFlag), [creaturesWithAttachmentFlag])
  const otherGroups = useMemo(() => groupCardsByKey(otherWithAttachmentFlag), [otherWithAttachmentFlag])
  const landGroups = useMemo(() => groupLandsByName(lands), [lands])

  const totalCount = cards.length

  const isPlayable = (card: Permanent) => !!playableCardIds && !!card.id && playableCardIds.has(card.id)
  const isCombatSelectable = (card: Permanent) => !!combatChoiceIds && !!card.id && combatChoiceIds.has(card.id)
  // Already declared in this combat (click again to undeclare) — distinct glow
  // so "highlighted = can declare" and "red glow = will UNdeclare" read apart.
  const isCombatDeclared = (card: Permanent) => !!combatDeclaredIds && !!card.id && combatDeclaredIds.has(card.id)
  const isTarget = (card: Permanent) => !!targetIds && !!card.id && targetIds.has(card.id)
  const isManaTappable = (card: Permanent) => !!manaTappableIds && !!card.id && manaTappableIds.has(card.id)

  // Render a single card with its attachments
  const renderCardWithAttachments = (card: Permanent) => {
    const cardAttachments = card.id ? (attachments.get(card.id) ?? []) : []

    if (cardAttachments.length > 0) {
      return (
        <div key={card.id ?? card.name} className="card-with-attachments">
          {cardAttachments.map((att) => (
            <CardThumb
              key={att.id ?? att.name}
              card={att}
              {...owner}
              isTapped={!!att.tapped}
              className="attachment-card"
              onClick={onCardClick}
            />
          ))}
          <CardThumb
            card={card}
            {...owner}
            isTapped={!!card.tapped}
            isPlayable={isPlayable(card)}
            isCombatSelectable={isCombatSelectable(card)}
            isCombatDeclared={isCombatDeclared(card)}
            isTarget={isTarget(card)}
            isManaTappable={isManaTappable(card)}
            className="main-card"
            onClick={onCardClick}
          />
        </div>
      )
    }

    return (
      <CardThumb
        key={card.id ?? card.name}
        card={card}
        {...owner}
        isTapped={!!card.tapped}
        isPlayable={isPlayable(card)}
        isCombatSelectable={isCombatSelectable(card)}
        isCombatDeclared={isCombatDeclared(card)}
        isTarget={isTarget(card)}
        isManaTappable={isManaTappable(card)}
        onClick={onCardClick}
      />
    )
  }

  // Render a group of identical cards as a visual stack with ×N badge
  const renderCardGroup = (group: { cards: Permanent[]; name: string }) => {
    const groupCards = group.cards
    if (groupCards.length === 1) {
      return renderCardWithAttachments(groupCards[0]!)
    }

    const visibleCount = Math.min(groupCards.length, MAX_VISUAL_STACK_DEPTH)

    return (
      <div key={group.name} className="card-stack-group">
        {groupCards.slice(0, visibleCount).map((card) => (
          <CardThumb
            key={card.id ?? card.name}
            card={card}
            {...owner}
            isTapped={!!card.tapped}
            isPlayable={isPlayable(card)}
            isCombatSelectable={isCombatSelectable(card)}
            isCombatDeclared={isCombatDeclared(card)}
            isTarget={isTarget(card)}
            isManaTappable={isManaTappable(card)}
            onClick={onCardClick}
          />
        ))}
        {groupCards.length > 1 && (
          <div className="card-stack-badge">×{groupCards.length}</div>
        )}
      </div>
    )
  }

  // Render a land group (stacked by name)
  const renderLandGroup = (group: { name: string; cards: Permanent[] }) => {
    if (group.cards.length === 1) {
      const card = group.cards[0]!
      return (
        <CardThumb
          key={card.id ?? card.name}
          card={card}
          {...owner}
          isTapped={!!card.tapped}
          className="land-stack"
          isPlayable={isPlayable(card)}
          isCombatSelectable={isCombatSelectable(card)}
          isCombatDeclared={isCombatDeclared(card)}
          isTarget={isTarget(card)}
          isManaTappable={isManaTappable(card)}
          onClick={onCardClick}
        />
      )
    }

    return (
      <div key={group.name} className="land-stack-group">
        {group.cards.map((card) => (
          <CardThumb
            key={card.id ?? card.name}
            card={card}
            {...owner}
            isTapped={!!card.tapped}
            isPlayable={isPlayable(card)}
            isCombatSelectable={isCombatSelectable(card)}
            isCombatDeclared={isCombatDeclared(card)}
            isTarget={isTarget(card)}
            isManaTappable={isManaTappable(card)}
            onClick={onCardClick}
          />
        ))}
      </div>
    )
  }

  const nonLandRow = (
    <div className="cards-row cards-grid">
      {creatureGroups.map(renderCardGroup)}
      {otherNonLands.length > 0 && creatures.length > 0 && (
        <div className="bf-separator" />
      )}
      {otherGroups.map(renderCardGroup)}
    </div>
  )

  const landsRow = (
    <div className="cards-row cards-grid land-row">
      {landGroups.map(renderLandGroup)}
    </div>
  )

  return (
    <div className="zone battlefield-zone">
      <div className="zone-title">Battlefield ({totalCount})</div>
      {isTopPlayer ? (
        <>
          {landsRow}
          {nonLandRow}
        </>
      ) : (
        <>
          {nonLandRow}
          {landsRow}
        </>
      )}
    </div>
  )
}