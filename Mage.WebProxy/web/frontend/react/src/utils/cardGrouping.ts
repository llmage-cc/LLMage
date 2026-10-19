/**
 * Card grouping utilities — ported from game-renderer.js.
 * Used by BattlefieldZone to group identical cards into visual stacks.
 */
import type { Permanent } from '@/types'

/** Maximum number of cards to render visually in a stack before showing ×N badge. */
export const MAX_VISUAL_STACK_DEPTH = 4

/**
 * A card with an optional _hasAttachments flag used in grouping.
 * Cards with attachments should not group with cards without attachments.
 */
type GroupableCard = Permanent & { _hasAttachments?: boolean }

/**
 * Compute a composite key that uniquely identifies a card's visual state.
 * Two cards share a group ONLY when their entire visible status is identical.
 * The instant one is buffed, tapped, gains a counter, or gets an attachment,
 * its key changes and it splits into its own group.
 */
export function computeCardGroupKey(card: Permanent & { _hasAttachments?: boolean }): string {
  const parts: string[] = [
    card.name || 'Unknown',
    card.tapped ? 'T' : 'U',
    card.power != null ? `p${card.power}` : 'p-',
    card.toughness != null ? `t${card.toughness}` : 't-',
    card.damage != null ? `d${card.damage}` : 'd0',
    card.loyalty != null ? `l${card.loyalty}` : 'l-',
    card.faceDown ? 'FD' : 'FU',
    card.summoningSick ? 'SS' : 'NS',
    card.token ? 'TK' : 'NT',
  ]

  // Counters (sorted for stable key)
  if (card.counters && typeof card.counters === 'object') {
    const counterKeys = Object.keys(card.counters).sort()
    if (counterKeys.length > 0) {
      parts.push(counterKeys.map((k) => `${k}:${card.counters![k]}`).join(','))
    }
  }

  // Has attachments
  if ((card as GroupableCard)._hasAttachments) parts.push('att')

  return parts.join('|')
}

/** A group of cards that share the same visual key. */
export interface CardGroup {
  key: string
  cards: Permanent[]
  name: string
}

/**
 * Group cards by their visual identity key.
 * Cards with identical visual state (same name, same tap state, same P/T, etc.)
 * are grouped together for rendering as a stack with ×N badge.
 */
export function groupCardsByKey(cards: Permanent[]): CardGroup[] {
  const groups: CardGroup[] = []
  const seen: Record<string, CardGroup> = {}

  for (const card of cards) {
    const key = computeCardGroupKey(card)
    if (!seen[key]) {
      const group: CardGroup = { key, cards: [], name: card.name || 'Unknown' }
      seen[key] = group
      groups.push(group)
    }
    seen[key]!.cards.push(card)
  }

  return groups
}

/** A group of lands with the same name. */
export interface LandGroup {
  name: string
  cards: Permanent[]
}

/**
 * Group lands by name only (mixed tapped + untapped in same group).
 * Lands are simpler — they don't have P/T or counters that need separate groups.
 */
export function groupLandsByName(lands: Permanent[]): LandGroup[] {
  const groups: LandGroup[] = []
  const seen: Record<string, LandGroup> = {}

  for (const card of lands) {
    const name = card.name || 'Unknown'
    if (!seen[name]) {
      const group: LandGroup = { name, cards: [] }
      seen[name] = group
      groups.push(group)
    }
    seen[name]!.cards.push(card)
  }

  return groups
}