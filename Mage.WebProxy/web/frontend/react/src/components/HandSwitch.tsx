import { useState } from 'react'
import type { HandOption } from '@/store/slices/handSwitch'
import { YOUR_HAND_KEY, setHandSwitchKey } from '@/store/slices/handSwitch'
// Styles travel with the component — the feature adds no shared-CSS footprint
// (same pattern as ViewDeckModal/viewDeck.css).
import './handSwitch.css'

/**
 * Hand switch control — the web counterpart of the desktop
 * client's `btnSwitchHands` (GamePanel.java:2329-2340:2695-2699, tooltip
 * "Switch between your hand cards and hand cards of controlled players.").
 *
 * Desktop behaviour mirrored here:
 *  - the button exists only when MORE THAN ONE hand is available
 *    (GamePanel.java:1122 `btnSwitchHands.setVisible(handCards.size() > 1)`; the
 *    caller applies the same gate through showHandSwitch);
 *  - picking an item switches the displayed hand
 *    (btnSwitchHandActionPerformed → handContainer.loadCards);
 *  - the selected hand is remembered and falls back to your own when it
 *    disappears (GamePanel.java:1108-1115) — `effectiveHandKey` owns that rule,
 *    so this component only renders a plain list of the LIVE options;
 *  - when control starts the desktop shows "You control other player's turn.
 *    Use \"Switch Hand\" button…" — the button's own label carries the same hint
 *    here (the web client has no modal notification surface for it).
 *
 * Placement deviation, deliberate: the desktop hangs the button off its shortcut
 * panel; the web client puts it in the hand area's title row, i.e. exactly on the
 * surface it changes (the desktop's equivalent of `handContainer`).
 */
export function HandSwitch({ options, currentKey }: { options: HandOption[]; currentKey: string }) {
  const [open, setOpen] = useState(false)
  const current = options.find((o) => o.key === currentKey)

  const label = current == null || current.kind === 'own' ? 'your hand' : `${current.name}'s hand`
  // The desktop's notification text ("You control other player's turn. Use
  // "Switch Hand" button…"), folded into the control's own explanation (see the
  // data-explain id in hints/explain.ts) and its native title.
  const title =
    current?.kind === 'controlled'
      ? `You control ${current.name}'s turn — showing ${current.name}'s hand. Click to switch back to your own hand or another controlled player's hand.`
      : current?.kind === 'watched'
        ? `Showing ${current.name}'s hand (they granted you permission). Click to switch hands.`
        : 'Switch between your hand and the hands of players whose turn you control or who let you see their hand.'

  return (
    <div className="hand-switch">
      <button
        type="button"
        className="hand-switch-btn"
        data-explain="hand-switch"
        aria-haspopup="menu"
        aria-expanded={open}
        title={title}
        {...{ [HAND_SWITCH_KEY_ATTR]: currentKey }}
        onClick={() => setOpen((v) => !v)}
      >
        ⇄ {label}
      </button>
      {open && (
        <div className="hand-switch-menu" role="menu" aria-label="Choose hand to display">
          {options.map((option) => (
            <button
              key={option.key}
              type="button"
              role="menuitem"
              className={`context-menu-item hand-switch-item${option.key === currentKey ? ' active' : ''}`}
              // Literal explanation (the `@` form): the text names this specific
              // hand, which a registry id cannot do (see hints/explain.ts).
              data-explain={`@${handOptionExplanation(option)}`}
              onClick={() => {
                setHandSwitchKey(option.key)
                setOpen(false)
              }}
            >
              {handOptionLabel(option)}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

/** Menu item text: "You" for your own hand, the player's name for a foreign one. */
export function handOptionLabel(option: HandOption): string {
  if (option.kind === 'own') return `You (${option.cards.length})`
  return `${option.name} — ${option.cards.length} card${option.cards.length === 1 ? '' : 's'}${option.kind === 'controlled' ? ' (you control their turn)' : ' (you may see their hand)'}`
}

/** Explanation for one menu entry — names the reason that hand is visible at all. */
export function handOptionExplanation(option: HandOption): string {
  if (option.kind === 'own') {
    return `Show your own hand (${option.cards.length} card${option.cards.length === 1 ? '' : 's'}). You play these cards on your own turn.`
  }
  if (option.kind === 'controlled') {
    return `Show ${option.name}'s hand (${option.cards.length} card${option.cards.length === 1 ? '' : 's'}) — you control their turn, so you see and play their cards until that turn ends.`
  }
  return `Show ${option.name}'s hand (${option.cards.length} card${option.cards.length === 1 ? '' : 's'}) — they granted you permission to see it. You cannot play these cards.`
}

/**
 * Whether a displayed hand's cards may be clicked to play.
 *
 * Only while you actually control that player's turn: your own hand always, a
 * controlled player's hand during their controlled turn (the engine offers that
 * player's cards as playables to the controlling session — see
 * WebProxyClient.findCardViewById's foreign-hand pass), and a WATCHED hand
 * never (permission to look is not permission to play).
 */
export function handCardsPlayable(kind: HandOption['kind']): boolean {
  return kind === 'own' || kind === 'controlled'
}

/** Marker so the harness (and a reader) can see which option is selected. */
export const HAND_SWITCH_KEY_ATTR = 'data-hand-switch-current'
export { YOUR_HAND_KEY }
