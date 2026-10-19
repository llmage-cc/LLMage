import { useSyncExternalStore } from 'react'
import { subscribeViewDeck, getViewDeck, closeViewDeck } from '@/store/slices/viewDeck'
import type { DeckEntryLite } from '@/types'
// Styles travel with the component — the feature adds no shared-CSS footprint.
import './viewDeck.css'

/**
 * View-deck modal — the read-only counterpart of the desktop
 * client's VIEW_LIMITED_DECK deck editor (MageFrame.showDeckEditor, opened from
 * PlayAreaPanel's "View deck (me|playerName)" item).
 *
 * Rendered from the module-level viewDeck store (subscribeViewDeck/getViewDeck —
 * the autoTapRun/tokenArt pattern) so the message handler needs no store widening.
 * The deck arrives complete on the `view_limited_deck` message: maindeck + sideboard
 * rows grouped name×count, plus the totals the desktop panel's status line shows.
 *
 * Read-only by construction: no controls write anything, and only Close is offered.
 */
function cardRows(list: DeckEntryLite[], key: 'main' | 'sb') {
  return (
    <div className="view-deck-rows">
      {list.map((e) => (
        <div
          key={`${key}|${e.name}|${e.setCode}|${e.cardNumber}`}
          className="view-deck-row"
          title={`${e.name} [${e.setCode}:${e.cardNumber}]`}
        >
          <span className="view-deck-row-count">{e.count}×</span>
          <span className="view-deck-row-name">{e.name}</span>
          <span className="view-deck-row-set">[{e.setCode}:{e.cardNumber}]</span>
        </div>
      ))}
      {list.length === 0 && <div className="view-deck-empty">No cards</div>}
    </div>
  )
}

export default function ViewDeckModal() {
  const deck = useSyncExternalStore(subscribeViewDeck, getViewDeck, getViewDeck)
  if (!deck) return null

  const mainCount = deck.cards.reduce((sum, e) => sum + e.count, 0)
  const sbCount = deck.sideboard.reduce((sum, e) => sum + e.count, 0)

  return (
    <div className="modal-overlay" style={{ display: 'flex' }}>
      <div className="modal-content view-deck-modal">
        <div className="modal-title">
          View deck — {deck.seatName || 'unknown seat'}
          <span style={{ marginLeft: '1rem', fontSize: '0.9rem', color: 'var(--text-secondary)' }}>
            {mainCount} cards{sbCount > 0 ? ` · sideboard ${sbCount}` : ''}
          </span>
        </div>

        <div className="view-deck-columns">
          <div className="view-deck-column">
            <div className="view-deck-heading">Maindeck ({mainCount})</div>
            {cardRows(deck.cards, 'main')}
          </div>
          <div className="view-deck-column">
            <div className="view-deck-heading">Sideboard ({sbCount})</div>
            {cardRows(deck.sideboard, 'sb')}
          </div>
        </div>

        <div style={{ marginTop: '1rem' }}>
          <button className="btn btn-primary" onClick={closeViewDeck} data-explain="view-deck-close">
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
