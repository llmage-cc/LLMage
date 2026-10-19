import type { GameState } from '@/types'
import { PhaseBar } from './PhaseBar'

/**
 * Phase strip — the 12 phase chips as a horizontal strip centred in the middle
 * of the board area instead of a full-width row above it ("vertically
 * centered between the two player boards").
 *
 * Placement is owned by the shared GameLayout shell: it renders this at index
 * ceil(n/2)-1 of the players grid (1v1: between the two split panes; 4 players:
 * in the same `auto` grid row, which also hosts the row-split divider), so
 *  - 1v1: the strip sits BETWEEN the two boards (the grid's middle `auto` row
 *    from GameLayout's `1fr auto 1fr` template — a reserved row, so the chips
 *    never overlay cards and both boards keep equal halves);
 *  - 3+ players: the strip spans the full grid width between the top and
 *    bottom halves (the 2-column multiplayer grid has no single natural gap);
 *    with 4 players the row-split drag divider sits directly below the strip
 *    in that same row.
 *
 * grid-column makes it span every board column; z-index 20 lifts it above the
 * combat-arrow overlay (z-index 10) so arrows never draw over the chips, while
 * the bar's translucent background keeps arrows/cards crossing behind it
 * legible.
 */
export function PhaseStrip({ gameState, readOnly = false }: { gameState: GameState; readOnly?: boolean | undefined }) {
  return (
    <div
      className="phase-strip"
      style={{
        gridColumn: '1 / -1',
        display: 'flex',
        justifyContent: 'center',
        position: 'relative',
        zIndex: 20,
      }}
    >
      <PhaseBar gameState={gameState} readOnly={readOnly} />
    </div>
  )
}
