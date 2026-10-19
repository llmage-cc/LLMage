import { useRef, useEffect, useState } from 'react'
import { rootZoom } from '@/utils/viewport'
import { centerOf, arrowCurve, type ArrowPath } from '@/utils/arrowGeometry'
import type { CombatGroup } from '@/types'

/**
 * Combat arrows — SVG overlay drawn over the players grid.
 * Ported from app.js drawCombatArrows.
 * Red arrows: attacker → defender. Blue arrows: blocker → attacker.
 *
 * Uses DOM measurement after render to position arrows between card elements.
 *
 * ROOT-CAUSE FIX: the arrow
 * endpoints are looked up across the WHOLE players area — the card elements
 * are SIBLINGS of this overlay (both live under .players-area), so scoping the
 * queries to this container found nothing and no arrow ever rendered. The
 * overlay is also rendered even when empty (visibility:hidden instead of
 * display:none) so its rect is measurable the first time arrows appear.
 */
export function CombatArrows({ combatData }: { combatData: CombatGroup[] }) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [arrows, setArrows] = useState<ArrowPath[]>([])

  useEffect(() => {
    if (!combatData || combatData.length === 0) {
      setArrows([])
      return
    }

    // Endpoints are measured across the whole players area: cards and player
    // headers are siblings of this overlay, not descendants of it.
    const scope = containerRef.current?.parentElement
    if (!scope) return

    // rootZoom() is read inside centerOf per point (it is a cheap
    // getComputedStyle); no separate division is needed here any more.
    void rootZoom

    const newArrows: ArrowPath[] = []

    for (const combat of combatData) {
      // Find defender element: a planeswalker/battlefield defender renders as a
      // card ([data-card-id]); a player defender falls back to their header.
      let defenderEl: Element | null = null
      if (combat.defenderId) {
        defenderEl = scope.querySelector(`[data-card-id="${combat.defenderId}"]`)
      }
      if (!defenderEl && combat.defenderName) {
        defenderEl = scope.querySelector(`.player-header[data-player-name="${combat.defenderName}"]`)
      }
      const defCenter = centerOf(defenderEl)
      if (!defCenter) continue

      // Attacker → defender arrows (red)
      for (const att of combat.attackers) {
        if (!att.id) continue
        const attEl = scope.querySelector(`[data-card-id="${att.id}"]`)
        const attCenter = centerOf(attEl)
        if (attCenter) {
          newArrows.push({
            d: arrowCurve(attCenter, defCenter),
            color: '#e94560',
            marker: 'combat-arrowhead-red',
          })
        }
      }

      // Blocker → attacker arrows (blue)
      const firstAttackerId = combat.attackers[0]?.id
      const firstAttEl = firstAttackerId ? scope.querySelector(`[data-card-id="${firstAttackerId}"]`) : null
      const firstAttCenter = centerOf(firstAttEl)

      for (const blocker of combat.blockers) {
        if (!blocker.id) continue
        const blkEl = scope.querySelector(`[data-card-id="${blocker.id}"]`)
        const blkCenter = centerOf(blkEl)
        if (blkCenter && firstAttCenter) {
          newArrows.push({
            d: arrowCurve(blkCenter, firstAttCenter),
            color: '#3b82f6',
            marker: 'combat-arrowhead-blue',
          })
        }
      }
    }

    setArrows(newArrows)
  }, [combatData])

  // Rendered in BOTH states so containerRef.parentElement is stable for the
  // measurement above; visibility:hidden (not display:none) keeps the rect
  // measurable on the very first render that has arrows (the old display:none
  // branch measured a 0x0 origin and wrote every path off-screen).
  return (
    <div
      ref={containerRef}
      className="combat-arrows-container"
      style={{
        position: 'fixed',
        inset: 0,
        pointerEvents: 'none',
        zIndex: 60,
        visibility: arrows.length === 0 ? 'hidden' : 'visible',
      }}
    >
      <svg className="combat-arrows-svg" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
        <defs>
          <marker id="combat-arrowhead-red" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
            <polygon points="0 0, 8 3, 0 6" fill="#e94560" />
          </marker>
          <marker id="combat-arrowhead-blue" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
            <polygon points="0 0, 8 3, 0 6" fill="#3b82f6" />
          </marker>
        </defs>
        {arrows.map((arrow, i) => (
          <path
            key={i}
            d={arrow.d}
            stroke={arrow.color}
            strokeWidth="2.5"
            fill="none"
            strokeLinecap="round"
            markerEnd={`url(#${arrow.marker})`}
          />
        ))}
      </svg>
    </div>
  )
}