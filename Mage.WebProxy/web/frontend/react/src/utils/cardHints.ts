/**
 * Card hints helper — collect, group and search the engine's own
 * card hints from the ALREADY-SERIALIZED game rules.
 *
 * WHY THIS MODULE EXISTS (desktop parity): the Java client's
 * `CardHintsHelperDialog` ("Hints" button on your own player panel,
 * `PlayerPanelExt.btnToolHintsHelperActionPerformed` → `GamePanel.openCardHintsWindow`)
 * scans every VISIBLE card of the current game, keeps the ones whose rules list
 * carries the engine's hint marker, and shows them grouped by hint or by card
 * with a player filter and a free-text search. The web client had no such surface.
 *
 * ── THE WIRE IS ALREADY THERE ──
 * The engine appends hints to a card's rules as a marker line plus the hint lines
 * after it (`HintUtils.HINT_START_MARK = "<br/><hintstart/>"`, added in
 * `CardUtil.getCardRulesWithAdditionalInfo`, `PermanentImpl.getRules` for
 * restrict/requirement hints and `StackAbilityView`). `GameStateSerializer`
 * emits each card's rules verbatim as `rules: string[]` (serializeCard /
 * serializePermanent / commander objects), and `GameState.rules` is typed
 * `string[]` (`src/types/gameState.ts`). No proxy change is needed — this module
 * turns those already-carried strings into the helper's data.
 *
 * ── PARSING SEMANTICS (desktop-exact) ──
 * Mirrors `CardHintsHelperDialog.parseCardHints` byte for byte:
 *   - no marker ANYWHERE in the rules list  → NOT a hint card (returns null);
 *   - the marker must be its OWN rules entry (the engine pushes it as one line);
 *     a marker EMBEDDED in a longer rule line does not start hint collection;
 *   - everything AFTER the marker entry is a hint line (including empty strings);
 *   - a marker that is the LAST entry yields an empty hint list (still a hint
 *     card — the desktop keeps it in `lastHints`, and its group-by-hints view
 *     renders nothing for it while group-by-cards shows the card name).
 *
 * ── ZONES SCANNED (desktop scope, mirrored) ──
 * The desktop scans the player's view of the game:
 *   own hand (`getMyHand`), own helper emblems (`getMyHelperEmblems`, zone "xmage"),
 *   the stack (`getStack`), and for EVERY player: battlefield, command
 *   (`getCommandObjectList` CardView entries only — emblems' own rules are
 *   deliberately NOT scanned, same TODO as the desktop), graveyard, exile.
 * The web state carries exactly those fields on the serializer's player objects
 * (hand is serialized for the session's own seat only — the engine's own
 * visibility filter, not something this module re-implements). NOT scanned
 * (desktop TODO, mirrored): revealed/looked-at groups, top card, sideboard,
 * emblems/planes/dungeons.
 *
 * OUT-OF-REACH ZONE: the desktop's "xmage" group is the HELPER emblems
 * (`GameState.helperEmblems` — the "storm counter"/"day or night" info sources).
 * They are not part of the player command list and the proxy does not serialize
 * them, so no web surface can show them; the zone simply never appears here.
 * Everything else is desktop-exact.
 *
 * The zone labels are the desktop's own strings ("hand", "xmage", "stack",
 * "battlefield", "command", "graveyard", "exile") so the rendering reads like
 * the Java dialog's.
 *
 * Fail Fast: nothing here invents hints. A card without the marker contributes
 * nothing; a board with no hint cards anywhere renders the empty state
 * (EMPTY_HINTS_WARNING below, the desktop's own text).
 */

import type { GameState, Card, Commander } from '@/types'

/** Any card-shaped wire object the helper scans — Card or a CommandObjectView. */
export type HintCard = Card | Commander

/** The engine's hint marker (`mage.abilities.hint.HintUtils.HINT_START_MARK`). */
export const HINT_START_MARK = '<br/><hintstart/>'

/**
 * Groups shown per group-by-hints entry before "and N other" — the desktop's
 * MAX_CARD_IDS_PER_HINT (CardHintsHelperDialog.java:44).
 */
export const MAX_CARD_IDS_PER_HINT = 3

/** The desktop's empty-state warning text (EMPTY_HINTS_WARNING:42). */
export const EMPTY_HINTS_WARNING = 'Game not started or nothing to show'

/** The desktop's free-text placeholder (SEARCH_EMPTY_TEXT / SEARCH_TOOLTIP). */
export const SEARCH_EMPTY_TEXT = 'search'
export const SEARCH_TOOLTIP = 'Filter hints by any words in player, card, id or hint'

/** The desktop's filter-all sentinel (FILTER_ALL). */
export const FILTER_ALL = 'ALL'

/** Group-by modes — the desktop's CardHintGroupBy enum (Hints default first). */
export type CardHintGroupBy = 'hints' | 'cards'

/** One hint-carrying card: attribution + its parsed hint lines. */
export interface CardHintInfo {
  /** Seat name, or '' for a watcher-only card (desktop: player can be null). */
  player: string
  /** Desktop zone label: hand | xmage | stack | battlefield | command | graveyard | exile. */
  zone: string
  card: HintCard
  /** Hint lines (everything after the marker entry in the rules list). */
  hints: string[]
}

/**
 * Parse a rules list for hint lines — the desktop's `parseCardHints` exactly.
 *
 * Returns null when the list carries no marker ENTRY (not a hint card), and the
 * (possibly empty) list of lines after it otherwise. An EMPTY rules list is
 * "not a hint card" — an empty array is never a hint carrier.
 */
export function parseCardHints(rules: readonly string[] | undefined | null): string[] | null {
  if (!rules || rules.length === 0 || !rules.includes(HINT_START_MARK)) {
    return null
  }
  const hints: string[] = []
  let started = false
  for (const rule of rules) {
    if (rule === HINT_START_MARK) {
      started = true
      continue
    }
    if (started) {
      hints.push(rule)
    }
  }
  return hints
}

/** True when this card's rules carry at least one hint (desktop `removeIf` guard). */
export function cardHasHints(card: { rules?: string[] | undefined }): boolean {
  return parseCardHints(card.rules) !== null
}

/**
 * Collect hint cards from a game state — the desktop's `loadHints` scan.
 *
 * Returns one entry per visible card that carries the marker, in the desktop's
 * scan order (own hand → helper emblems → stack → per player: battlefield →
 * command → graveyard → exile). Every entry keeps its seat + zone attribution.
 *
 * `isSpectator` (a watcher) has no own-hand field on the wire; the remaining
 * zones still scan — the watcher view is the desktop dialog's "player == null"
 * case, rendered as the "watcher" seat label by the UI.
 */
export function collectCardHints(gameState: GameState | null): CardHintInfo[] {
  if (!gameState) return []
  const out: CardHintInfo[] = []
  const myPlayer = gameState.players.find((p) => p.isMe)
  const myName = myPlayer?.name ?? ''

  const push = (player: string, zone: string, card: HintCard) => {
    if (!cardHasHints(card)) return
    const hints = parseCardHints(card.rules) ?? []
    out.push({ player, zone, card, hints })
  }

  // Own hand — serialized only for the session's own seat (serializer gate).
  if (myPlayer) {
    for (const card of myPlayer.hand ?? []) push(myName, 'hand', card)
  }

  // Stack — shared by all seats.
  for (const card of gameState.stack ?? []) push(myName, 'stack', card)

  // Per player: battlefield, command (CardViews only), graveyard, exile.
  for (const player of gameState.players) {
    for (const card of player.battlefield ?? []) push(player.name, 'battlefield', card)
    for (const card of player.commanders ?? []) push(player.name, 'command', card)
    for (const card of player.graveyard ?? []) push(player.name, 'graveyard', card)
    for (const card of player.exile ?? []) push(player.name, 'exile', card)
  }

  return out
}

/**
 * Apply the player filter and the free-text search — the desktop's `updateHints`
 * filter pass.
 *
 * Search semantics are desktop-exact: the query is lower-cased, trimmed and split
 * on spaces into WORDS; a card matches when ANY word appears in its search base
 * (`player@zone@name-id`) or in its joined hint text. So "krenko" matches the card
 * name, "U17Alpha" the player, the id prefix the id, and "goblins" the hint text.
 */
export function filterCardHints(
  hints: readonly CardHintInfo[],
  playerFilter: string,
  search: string,
): CardHintInfo[] {
  let out = hints.slice()
  if (playerFilter !== FILTER_ALL) {
    out = out.filter((h) => h.player === playerFilter)
  }
  const query = search.trim()
  if (query !== '') {
    const words = query.toLowerCase().split(' ')
    out = out.filter((h) => {
      const base = `${h.player}@${h.zone}@${h.card.name} ${h.card.id ?? ''}`.toLowerCase()
      const hintText = h.hints.join('@').toLowerCase()
      return words.some((w) => base.includes(w) || hintText.includes(w))
    })
  }
  return out
}

/**
 * The player choices the filter offers — desktop-exact (`setGameData`): "ALL"
 * first, then your own seat, then the other players in state order. Every seat is
 * offered even when it currently owns no hint card: the desktop lists all players
 * unconditionally, and its own empty-state text covers a filter that matches
 * nothing.
 */
export function playerFilterChoices(gameState: GameState | null): string[] {
  const out: string[] = [FILTER_ALL]
  if (!gameState) return out
  const me = gameState.players.find((p) => p.isMe)
  if (me) out.push(me.name)
  for (const p of gameState.players) {
    if (p.isMe || p.name === '') continue
    if (!out.includes(p.name)) out.push(p.name)
  }
  return out
}

/** One rendered group-by-hints entry: a hint line + the cards that carry it. */
export interface HintGroup {
  hint: string
  cards: CardHintInfo[]
}

/** One rendered group-by-cards entry: a player+zone header + its cards. */
export interface CardGroup {
  player: string
  zone: string
  cards: CardHintInfo[]
}

/**
 * Group by HINTS (desktop `prepareGroupByHints`): unique hint lines, sorted, each
 * mapped to the cards carrying it (cards sorted by id — the desktop sorts by id
 * because the rows show id prefixes).
 *
 * A hint line belongs to a card when the card's rules contain it — the desktop's
 * `info.card.getRules().contains(currentHintName)` — which is equivalent to the
 * card's parsed hint list containing it for lines produced by the marker parse.
 */
export function groupByHints(hints: readonly CardHintInfo[]): HintGroup[] {
  const allHints = Array.from(new Set(hints.flatMap((h) => h.hints))).sort()
  return allHints.map((hint) => ({
    hint,
    cards: hints
      .filter((h) => h.hints.includes(hint))
      .slice()
      .sort((a, b) => (a.card.id ?? '').localeCompare(b.card.id ?? '')),
  }))
}

/**
 * Group by CARDS (desktop `prepareGroupByPlayerAndZones(..., true, true)`):
 * player+zone groups in scan order, cards sorted by name inside. The desktop
 * builds these groups off the ORDER of the collected list, which is exactly the
 * order `collectCardHints` produces.
 */
export function groupByCards(hints: readonly CardHintInfo[]): CardGroup[] {
  const groups: CardGroup[] = []
  for (const h of hints) {
    const last = groups[groups.length - 1]
    if (!last || last.player !== h.player || last.zone !== h.zone) {
      groups.push({ player: h.player, zone: h.zone, cards: [h] })
    } else {
      last.cards.push(h)
    }
  }
  // Cards sorted by name inside each group (desktop sorts after grouping).
  for (const g of groups) {
    g.cards.sort((a, b) => a.card.name.localeCompare(b.card.name))
  }
  return groups
}

/**
 * The rendered rows for one group-by-hints entry's card list — the desktop's
 * "[abc], [def], [ghi] and N other" (MAX_CARD_IDS_PER_HINT = 3).
 *
 * Id prefixes are the first 3 chars of the card id, exactly like the desktop's
 * `getId().toString().substring(0, 3)`. A card with no id renders "(no id)" —
 * visible, never silently dropped.
 */
export function hintCardListLabel(cards: readonly CardHintInfo[]): string {
  const shown = cards.slice(0, MAX_CARD_IDS_PER_HINT).map((h) => {
    const id = h.card.id
    return id ? `[${id.slice(0, 3)}]` : '(no id)'
  })
  let out = shown.join(', ')
  if (cards.length > MAX_CARD_IDS_PER_HINT) {
    out += ` and ${cards.length - MAX_CARD_IDS_PER_HINT} other`
  }
  return out
}
