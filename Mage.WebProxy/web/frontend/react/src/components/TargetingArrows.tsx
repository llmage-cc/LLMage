import { useRef, useEffect, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import { centerOf, arrowCurve, type ArrowPath } from '@/utils/arrowGeometry'
import { realTargetArrows } from '@/utils/realTargets'
import type { GameState } from '@/types'

/**
 * REAL targeting arrows: "They're supposed to show the targets
 * of spells, abilities, and attacks, not potential targets of them."
 *
 * Behaviour decided here (each branch unit-testable offline):
 *  - a TARGET prompt open         → NO arrows. The candidates are potential
 *    targets-to-be; they surface as the selectable highlight only (existing
 *    targetIds→card-target path). Arrows never preview (verify case B).
 *  - stack items with real targets → arrow source→target per item, drawn from
 *    the stack's own serialized data (StackItem.targets, serializer field
 *    `targets`). The source tile is the stack card (data-card-id) or, for an
 *    ability, the owning card's name (source_card → data-card-name).
 *  - no prompt, no stack targets  → nothing.
 *
 * declare-attackers/blockers arrows (attack → defender, blocker → attacker)
 * are the live DECLARATION already: GameStateSerializer mirrors the engine's
 * CombatGroups on every state push, and CombatArrows renders them without
 * gating on the prompt — so this component adds nothing for combat (the
 * declareArrows.ts idea was redundant and is removed).
 */
export function targetingArrowDecision(
  promptType: string | null | undefined,
): boolean {
  // true = render arrows from real stack data
  return promptType !== 'target'
}

export function TargetingArrows() {
  const actionPrompt = useGameStore((s) => s.actionPrompt)
  const gameState = useGameStore((s) => s.gameState)
  const [arrows, setArrows] = useState<ArrowPath[]>([])
  const rafRef = useRef(0)

  // THE decision: while any target prompt is open nothing is drawn — the
  // prompt's candidates are highlights only. Arrows appear only from REAL
  // target data on stack items (no prompt needed, or a non-target prompt).
  const drawReal = targetingArrowDecision(actionPrompt?.type)

  useEffect(() => {
    if (actionPrompt?.type === 'target') {
      setArrows([])
      return
    }
    const stack = gameState?.stack
    const groups = realTargetArrows(stack ?? null)
    if (groups.length === 0) {
      setArrows([])
      return
    }
    // rAF: let React commit the stack rows/self-zoom before measuring rects.
    rafRef.current = requestAnimationFrame(() => {
      const next: ArrowPath[] = []
      for (const g of groups) {
        const sourceEl =
          document.querySelector(`[data-card-id="${g.sourceId}"]`) ??
          document.querySelector(`[data-card-name="${g.sourceName}"]`)
        for (const targetId of g.targetIds) {
          const targetEl = document.querySelector(`[data-card-id="${targetId}"]`)
          const from = centerOf(sourceEl)
          const to = centerOf(targetEl)
          if (from && to) {
            next.push({
              d: arrowCurve(from, to),
              color: '#ffb703',
              marker: 'targeting-arrowhead-real',
            })
          }
        }
      }
      setArrows(next)
    })
    return () => cancelAnimationFrame(rafRef.current)
  }, [drawReal, gameState])

  if (!drawReal || arrows.length === 0) {
    return (
      <div
        data-testid="targeting-arrows"
        style={{ position: 'fixed', inset: 0, pointerEvents: 'none', zIndex: 60, visibility: 'hidden' }}
      >
        <style>{`#targeting-arrows-svg{display:none}`}</style>
        <svg id="targeting-arrows-svg" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }} />
      </div>
    )
  }

  return (
    <div
      data-testid="targeting-arrows"
      style={{ position: 'fixed', inset: 0, pointerEvents: 'none', zIndex: 60 }}
    >
      <svg id="targeting-arrows-svg" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%' }}>
        <defs>
          <marker id="targeting-arrowhead-real" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto">
            <polygon points="0 0, 8 3, 0 6" fill="#ffb703" />
          </marker>
        </defs>
        {arrows.map((arrow, i) => (
          <path
            key={`${i}-${arrow.d}`}
            d={arrow.d}
            stroke={arrow.color}
            strokeWidth="2.5"
            fill="none"
            strokeLinecap="round"
            markerEnd={`url(#targeting-arrowhead-real)`}
          />
        ))}
      </svg>
    </div>
  )
}

/** Exposed for the verify harness: the data a test can assert without DOM. */
export type { GameState }
