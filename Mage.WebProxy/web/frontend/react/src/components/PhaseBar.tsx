import { useState } from 'react'
import type { GameState } from '@/types'
import { PhaseStopSettings } from './PhaseStopSettings'
import { useContextMenu } from '@/hooks/useContextMenu'

/**
 * Phase bar — visual phase indicator matching the Java client's 12 phase buttons.
 * Shows the current phase highlighted, with all phases in order.
 * Clicking a phase opens the Phase Stop Settings dialog (matching the Java client
 * which opens Preferences → Phases tab on phase button click).
 * Right-clicking a phase chip toggles that phase's stop directly
 * (PhaseContextMenu — one-click stop config instead of a dialog round-trip).
 */

interface Phase {
  name: string
  label: string
  /** Server step/phase values that map to this phase button */
  stepKeys: string[]
}

const PHASES: Phase[] = [
  { name: 'Untap', label: 'Untap', stepKeys: ['UNTAP'] },
  { name: 'Upkeep', label: 'Upkeep', stepKeys: ['UPKEEP'] },
  { name: 'Draw', label: 'Draw', stepKeys: ['DRAW'] },
  { name: 'Main1', label: 'Main 1', stepKeys: ['PRECOMBAT_MAIN'] },
  { name: 'Combat_Start', label: 'Begin Combat', stepKeys: ['BEGIN_COMBAT'] },
  { name: 'Combat_Attack', label: 'Attackers', stepKeys: ['DECLARE_ATTACKERS'] },
  { name: 'Combat_Block', label: 'Blockers', stepKeys: ['DECLARE_BLOCKERS'] },
  { name: 'Combat_Damage', label: 'Damage', stepKeys: ['COMBAT_DAMAGE', 'FIRST_COMBAT_DAMAGE'] },
  { name: 'Combat_End', label: 'End Combat', stepKeys: ['END_COMBAT'] },
  { name: 'Main2', label: 'Main 2', stepKeys: ['POSTCOMBAT_MAIN'] },
  // MTG turn order (engine PhaseStep indexes 11→12): the end step (END_TURN)
  // comes FIRST, and cleanup is the LAST step of the turn — never before it.
  { name: 'End_Turn', label: 'End Step', stepKeys: ['END_TURN'] },
  { name: 'Cleanup', label: 'Cleanup', stepKeys: ['CLEANUP'] },
]

function isCurrentPhase(phase: Phase, gameState: GameState): boolean {
  const currentStep = gameState.step || gameState.phase || ''
  return phase.stepKeys.includes(currentStep)
}

interface PhaseBarProps {
  gameState: GameState
  /**
   * Read-only (replay viewer): phases are shown but not clickable — clicking a
   * phase opens the phase-stop settings, whose save sends `update_phase_stops`,
   * a player-only action the proxy refuses while a replay is loaded.
   */
  readOnly?: boolean | undefined
}

export function PhaseBar({ gameState, readOnly = false }: PhaseBarProps) {
  const [showSettings, setShowSettings] = useState(false)
  const phaseMenu = useContextMenu('phase')

  return (
    <>
      <div className="phase-bar">
        {PHASES.map((phase) => {
          const current = isCurrentPhase(phase, gameState)
          return (
            <div
              key={phase.name}
              className={`phase-btn${current ? ' phase-active' : ''}`}
              title={readOnly
                ? `${phase.label} (replay — display only)`
                : `${phase.label} — click to configure phase stops, right-click to toggle`}
              onClick={readOnly ? undefined : () => setShowSettings(true)}
              onContextMenu={readOnly ? undefined : (e) => phaseMenu.openAt(e, { phaseName: phase.name, label: phase.label })}
              style={readOnly ? undefined : { cursor: 'pointer' }}
            >
              <span className="phase-btn-label">{phase.label}</span>
            </div>
          )
        })}
      </div>
      {showSettings && <PhaseStopSettings onClose={() => setShowSettings(false)} />}
    </>
  )
}