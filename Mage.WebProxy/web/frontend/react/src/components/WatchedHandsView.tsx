import { useEffect } from 'react'
import { useGameStore } from '@/store/gameStore'
import { selectBoardState } from '@/store/selectors'
import { CardThumb } from './CardThumb'
import { useFloatWindow } from '@/hooks/useFloatWindow'
import { closeHandView, selectHand, useSelectedHand } from '@/store/slices/handWatch'
import './watchedHands.css'
import type { Card } from '@/types'

/**
 * Watched hands — the viewer for hands this browser was GRANTED
 * permission to see.
 *
 * DESKTOP PARITY (GamePanel.updateGame:1089-1134): the Java client keeps ONE
 * hand container fed from `GameView.getWatchedHands()` plus your own hand and
 * the hands of players you control; `btnSwitchHands` ("Switch Hand") is visible
 * whenever more than one hand is available and cycles the container between
 * them. The engine ships watched hands in exactly that one map — there is no
 * per-player zone for them — and the desktop's watched-hand container is
 * READ-ONLY (the engine never offers its card ids as selectable, so nothing can
 * be played from it: you may look, you may not act).
 *
 * LEAST-INVASIVE WEB MIRROR (deliberate deviation, documented): the web board
 * renders each seat's OWN hand section and the live hand's tiles carry the
 * play affordances — swapping the container out from under them (the desktop
 * shape) would touch the click/play path this repo just stabilised.
 * Instead the granted hand opens in a floating viewer with the SAME chrome as
 * the graveyard/revealed windows (`float-window` + useFloatWindow: drag,
 * resize, persisted size), one switch button per granted seat when several are
 * available — the desktop's `handCards.size() > 1 → Switch Hand` condition.
 * Cards are inert (no onClick): a granted hand is a viewing surface, exactly
 * like the desktop's.
 *
 * The cards themselves are read LIVE from `gameState.watchedHands` (deltas keep
 * it fresh — see deltaApplicator) rather than stored at click time, so a drawn
 * card or a revoke updates the open viewer with no extra bookkeeping. A revoke
 * empties the list: the viewer then closes itself instead of showing a stale
 * hand (the desktop drops the container's entry for the same reason).
 */
export function WatchedHandsView() {
  // Board state: a live game OR a replay frame — same source the zone windows use.
  const state = useGameStore(selectBoardState)
  const selected = useSelectedHand()
  const hands = state?.watchedHands ?? []
  const open = selected != null ? hands.find((h) => h.name === selected) : undefined
  const { pos, onDragStart, onResizeStart } = useFloatWindow(open ? open.name : null, 'watched-hand')

  // Revoked / seat gone while the viewer was open: close it rather than render
  // an empty "granted hand" panel. The engine state is authoritative — an
  // EFFECT, not an inline call, so the render stays pure (a write during render
  // would re-render mid-flight and is exactly what React's purity rules forbid).
  const revoked = selected != null && !open
  useEffect(() => {
    if (revoked) closeHandView()
  }, [revoked])

  // No grants at all → nothing to show (and nothing to switch between).
  if (hands.length === 0) return null

  return (
    <>
      {/* Switch rail — one button per granted seat. Mirrors the desktop's
          "Switch Hand" button's visibility rule (only when >1 hand exists);
          with exactly one grant the viewer is opened from the player menu. */}
      {hands.length > 1 && (
        <div className="watched-hands-switch">
          {hands.map((h) => (
            <button
              key={h.name}
              type="button"
              className={`hand-switch-btn${h.name === selected ? ' active' : ''}`}
              onClick={() => (h.name === selected ? closeHandView() : selectHand(h.name))}
              data-explain="hand-watch-switch"
            >
              🖐 {h.name} ({h.cards.length})
            </button>
          ))}
        </div>
      )}

      {open && (
        <div
          className="float-window watched-hand-window"
          style={{ left: pos.x, top: pos.y, width: pos.w, height: pos.h }}
        >
          <div className="float-window-header" onMouseDown={onDragStart}>
            <span className="float-window-title">
              {open.name}&apos;s Hand — granted to you ({open.cards.length})
            </span>
            <button
              className="float-window-close"
              onClick={() => closeHandView()}
              data-explain="hand-watch-close"
            >
              &times;
            </button>
          </div>
          <div className="float-window-body">
            <div className="hand-cards-row watched-hand-cards">
              {open.cards.map((card: Card) => (
                // No onClick: a granted hand is read-only on both clients (the
                // engine offers no playable ids for it).
                <CardThumb key={card.id ?? card.name} card={card} className="card-thumb-sm" />
              ))}
            </div>
          </div>
          <div className="float-window-resize" onMouseDown={onResizeStart} title="Drag to resize" />
        </div>
      )}
    </>
  )
}
