import { useMemo, useState, useSyncExternalStore } from 'react'
import { useGameStore } from '@/store/gameStore'
import { selectBoardState } from '@/store/selectors'
import { subscribeCardHints, getCardHintsOpen, closeCardHints } from '@/store/slices/cardHints'
import { ManaText } from './ManaCost'
import {
  collectCardHints,
  filterCardHints,
  groupByCards,
  groupByHints,
  playerFilterChoices,
  EMPTY_HINTS_WARNING,
  FILTER_ALL,
  MAX_CARD_IDS_PER_HINT,
  SEARCH_EMPTY_TEXT,
  SEARCH_TOOLTIP,
  type CardHintGroupBy,
} from '@/utils/cardHints'
// Styles travel with the component (same convention as viewDeck.css).
import './cardHints.css'

/**
 * Card hints helper — the web counterpart of the Java client's
 * `CardHintsHelperDialog`.
 *
 * WHAT IT SHOWS: every VISIBLE game card whose engine rules carry the hint marker
 * (`<br/><hintstart/>`), with its seat + zone attribution; group-by Hints
 * (default) or Cards; a per-player filter; and the desktop's free-text search
 * over player / card / id / hint. The data comes from the state the proxy
 * ALREADY serializes (`rules: string[]` on every card) — see utils/cardHints.ts
 * for the parsing + scanning semantics (desktop-exact).
 *
 * WHERE IT LAUNCHES: the game header's "💡 Hints" button — the desktop's
 * counterpart is the "Hints" button on your own player panel
 * (`PlayerPanelExt.btnToolHintsHelperActionPerformed` → `GamePanel.openCardHintsWindow`).
 * The panel is the least-invasive existing surface (one button on the header,
 * next to Settings), and it is reachable in both the live game and the replay
 * viewer because GameLayout/GameHeader are shared — the modal reads the board on
 * screen through `selectBoardState`, exactly like RightRail.
 *
 * EMPTY STATE: no hint cards anywhere → the desktop's own warning line
 * ("Game not started or nothing to show"), never a blank panel.
 */
export default function CardHintsModal() {
  const open = useSyncExternalStore(subscribeCardHints, getCardHintsOpen, getCardHintsOpen)
  // Board on screen: the live game state, or the current replay frame — the same
  // selector RightRail uses, so the helper works while watching a replay too.
  const gameState = useGameStore(selectBoardState)

  const [group, setGroup] = useState<CardHintGroupBy>('hints')
  const [playerFilter, setPlayerFilter] = useState<string>(FILTER_ALL)
  const [search, setSearch] = useState('')

  // Collect once per state push (the desktop re-collects on every game update).
  const allHints = useMemo(() => collectCardHints(gameState), [gameState])
  const choices = useMemo(() => playerFilterChoices(gameState), [gameState])
  // A filter for a seat that no longer has hint cards (state moved on) falls
  // back to ALL rather than rendering an empty panel with no way to tell why.
  const effectiveFilter = choices.includes(playerFilter) ? playerFilter : FILTER_ALL

  if (!open) return null

  const filtered = filterCardHints(allHints, effectiveFilter, search)

  // Title settings mirror the desktop's dynamic title: [Filter, Group, Search].
  const titleSettings: string[] = []
  if (effectiveFilter !== FILTER_ALL) titleSettings.push(effectiveFilter)
  titleSettings.push(group === 'hints' ? 'Hints' : 'Cards')
  if (search.trim() !== '') titleSettings.push(search.trim())

  return (
    <div className="modal-overlay" style={{ display: 'flex' }}>
      <div className="modal-content card-hints-modal">
        <div className="modal-title">
          Card hints helper [{titleSettings.join(', ')}]
          <span style={{ marginLeft: '1rem', fontSize: '0.9rem', color: 'var(--text-secondary)' }}>
            {filtered.length} card{filtered.length === 1 ? '' : 's'}
          </span>
        </div>

        {/* Controls row — the desktop's three command widgets: filter, group, search. */}
        <div className="card-hints-controls">
          <select
            value={effectiveFilter}
            onChange={(e) => setPlayerFilter(e.target.value)}
            data-explain="card-hints-filter"
            aria-label="Filter hints by player"
          >
            {choices.map((c) => (
              <option key={c} value={c}>
                {c === FILTER_ALL ? 'All players' : c}
              </option>
            ))}
          </select>
          <select
            value={group}
            onChange={(e) => setGroup(e.target.value as CardHintGroupBy)}
            data-explain="card-hints-group"
            aria-label="Group hints by"
          >
            <option value="hints">Hints</option>
            <option value="cards">Cards</option>
          </select>
          <input
            type="text"
            className="card-hints-search"
            value={search}
            placeholder={SEARCH_EMPTY_TEXT}
            title={SEARCH_TOOLTIP}
            data-explain="card-hints-search"
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>

        {/* Body — group-by view, or the desktop's explicit empty state. */}
        <div className="card-hints-body" data-hint-count={filtered.length}>
          {filtered.length === 0 ? (
            <div className="card-hints-empty">{EMPTY_HINTS_WARNING}</div>
          ) : group === 'hints' ? (
            <HintRows hints={filtered} />
          ) : (
            <CardRows hints={filtered} />
          )}
        </div>

        <div style={{ marginTop: '1rem' }}>
          <button className="btn btn-primary" onClick={closeCardHints} data-explain="card-hints-close">
            Close
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * Hint text can carry the engine's icon tokens (`HintUtils.HINT_ICON_GOOD` etc. —
 * e.g. "ICON_BADYou gained life this turn"). The desktop maps them to icon images
 * via `ManaSymbols.replaceSymbolsWithHTML` (`GuiDisplayUtil.getHintIconHtml`);
 * the web has no hint-icon asset, so map them to the equivalent unicode glyphs —
 * the text must never render the raw token (live run showed "ICON_BADA permanent
 * left..." before this fix).
 */
function hintGlyphs(text: string): string {
  return text
    .replace(/ICON_GOOD/g, '\u2714') // ✔
    .replace(/ICON_BAD/g, '\u2718') // ✘
    .replace(/ICON_RESTRICT/g, '\u26D4') // ⛔
    .replace(/ICON_REQUIRE/g, '\u2757') // ❗
    .replace(/ICON_DUNGEON_ROOM_CURRENT/g, '\u25B6') // ▶
    .replace(/ICON_DUNGEON_ROOM_NEXT/g, '\u2937') // ⤷
}

/**
 * Group-by-HINTS view (desktop GROUP_BY_HINTS): per SEAT, the unique hint lines
 * with the cards carrying each one — "[abc], [def], [ghi] and N other"
 * (MAX_CARD_IDS_PER_HINT = 3). Hovering a card code previews that card (the web
 * counterpart of the desktop's click-through to BigCard), by writing the same
 * `cardPreview` payload hover produces.
 */
function HintRows({ hints }: { hints: ReturnType<typeof filterCardHints> }) {
  const setCardPreview = useGameStore((s) => s.setCardPreview)
  // Seat order = the order the seats appear in the collected list (desktop groups
  // by the list order too: own seat's zones first, then each player in turn).
  const seats: string[] = []
  for (const h of hints) {
    const name = h.player === '' ? 'watcher' : h.player
    if (!seats.includes(name)) seats.push(name)
  }

  return (
    <>
      {seats.map((seat) => {
        const seatHints = hints.filter((h) => (h.player === '' ? 'watcher' : h.player) === seat)
        const groups = groupByHints(seatHints)
        return (
          <div key={seat} className="card-hints-seat" data-hints-seat={seat}>
            <div className="card-hints-seat-title">{seat}:</div>
            {groups.map((g) => (
              <div key={g.hint} className="card-hints-hint-row" data-hint-text={g.hint}>
                <span className="card-hints-hint-text">
                  <ManaText text={hintGlyphs(g.hint)} />
                </span>
                <span className="card-hints-card-codes">
                  {'('}
                  {g.cards.slice(0, MAX_CARD_IDS_PER_HINT).map((h, i) => (
                    <span key={`${h.card.id ?? h.card.name}-${i}`}>
                      {i > 0 ? ', ' : ''}
                      <button
                        type="button"
                        className="card-hints-card-code"
                        title={`${h.card.name} — ${h.zone}`}
                        onClick={(e) =>
                          setCardPreview({ card: h.card, face: 'front', x: e.clientX, y: e.clientY })
                        }
                        data-explain={`@Preview ${h.card.name}`}
                      >
                        {h.card.id ? `[${h.card.id.slice(0, 3)}]` : '(no id)'}
                      </button>
                    </span>
                  ))}
                  {g.cards.length > MAX_CARD_IDS_PER_HINT
                    ? ` and ${g.cards.length - MAX_CARD_IDS_PER_HINT} other`
                    : ''}
                  {')'}
                </span>
              </div>
            ))}
          </div>
        )
      })}
    </>
  )
}

/**
 * Group-by-CARDS view (desktop GROUP_BY_CARDS): player+zone headers, then each
 * card with its hint lines as "Card name [abc]: hint".
 */
function CardRows({ hints }: { hints: ReturnType<typeof filterCardHints> }) {
  const setCardPreview = useGameStore((s) => s.setCardPreview)
  const groups = groupByCards(hints)
  return (
    <>
      {groups.map((g) => (
        <div key={`${g.player}-${g.zone}`} className="card-hints-seat" data-hints-group={`${g.player}|${g.zone}`}>
          <div className="card-hints-seat-title">
            {g.player === '' ? 'watcher' : g.player} - {g.zone}:
          </div>
          {g.cards.map((h, i) => (
            <div key={`${h.card.id ?? h.card.name}-${i}`} className="card-hints-card-row" data-hint-card={h.card.name}>
              <button
                type="button"
                className="card-hints-card-name"
                onClick={(e) => setCardPreview({ card: h.card, face: 'front', x: e.clientX, y: e.clientY })}
                data-explain={`@Preview ${h.card.name}`}
              >
                {h.card.name}
                {h.card.id ? ` [${h.card.id.slice(0, 3)}]` : ''}:
              </button>{' '}
              {h.hints.map((line, li) => (
                <div key={li} className="card-hints-hint-line">
                  <ManaText text={hintGlyphs(line)} />
                </div>
              ))}
            </div>
          ))}
        </div>
      ))}
    </>
  )
}
