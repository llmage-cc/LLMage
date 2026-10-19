import { useContextMenu, ContextMenuShell } from '@/hooks/useContextMenu'
import { useGameStore } from '@/store/gameStore'
import type { PhaseStopConfig } from '@/types'

/**
 * Phase-name (PhaseBar PHASES `name`) -> PhaseStopConfig stop key. The two
 * combat-adjacent chips have no single direct config row — the config uses
 * beforeCombat/endOfCombat brackets instead of the individual combat steps —
 * so those chips are marked unsupported in the menu rather than silently
 * writing the wrong row.
 */
const PHASE_TO_STOP_KEY: Partial<Record<string, { key: keyof PhaseStopConfig['yourTurn']; label: string }>> = {
  Untap: undefined, // no stop row — the engine never asks here (matches dialog)
  Upkeep: { key: 'upkeep', label: 'Upkeep' },
  Draw: { key: 'draw', label: 'Draw Step' },
  Main1: { key: 'main1', label: 'Main Phase 1' },
  Combat_Start: undefined,
  Combat_Attack: undefined,
  Combat_Block: undefined,
  Combat_Damage: undefined,
  Combat_End: undefined,
  Main2: { key: 'main2', label: 'Main Phase 2' },
  End_Turn: { key: 'endOfTurn', label: 'End of Turn' },
  Cleanup: undefined,
}

/**
 * Right-click a phase chip → toggle that phase's priority stop directly.
 *
 * The write path is the SAME one the Phase Stop Settings dialog uses
 * (`updatePhaseStops`, which sends `update_phase_stops` and the proxy rebuilds
 * UserSkipPrioritySteps server-side). The config is write-only on the wire —
 * the proxy never echoes it back — so the slice tracks the last config the
 * client sent (`phaseStopConfig`) and the menu toggles against that copy.
 * That tracked copy also FIXES the dialog's own initialization: it used to
 * start from a hardcoded all-on constant, silently reverting every other
 * toggle each time it opened.
 *
 * Chips whose phase has no single config row (combat steps, Untap, Cleanup)
 * open a menu that says so instead of writing a wrong row. Read-only (replay)
 * offers no menu at all — same guard as PhaseBar's click.
 */
export function PhaseContextMenu() {
  const { isOpen, close, position } = useContextMenu('phase')
  const openMenu = useGameStore((s) => s.openContextMenu)
  const config = useGameStore((s) => s.phaseStopConfig)
  const setPhaseStopConfig = useGameStore((s) => s.setPhaseStopConfig)
  const updatePhaseStops = useGameStore((s) => s.updatePhaseStops)

  const payload = isOpen && openMenu?.kind === 'phase' ? openMenu : null
  if (!isOpen || !payload) return null

  const stop = PHASE_TO_STOP_KEY[payload.phaseName]
  if (!stop) {
    return (
      <ContextMenuShell position={position} onClose={close} width={230} height={70} ariaLabel="Phase menu">
        <div className="context-menu-title">{payload.label}</div>
        <div className="context-menu-note">
          This phase has no individual stop setting — use click → Phase Stop Settings
          (combat brackets and stack stops live there).
        </div>
      </ContextMenuShell>
    )
  }

  const mine = config.yourTurn[stop.key]
  const opp = config.opponentTurn[stop.key]

  const toggle = (whose: 'yourTurn' | 'opponentTurn') => {
    const next: PhaseStopConfig = {
      ...config,
      yourTurn: { ...config.yourTurn },
      opponentTurn: { ...config.opponentTurn },
    }
    next[whose][stop.key] = !next[whose][stop.key]
    setPhaseStopConfig(next)
    updatePhaseStops(next)
    close()
  }

  return (
    <ContextMenuShell position={position} onClose={close} width={250} height={120} ariaLabel={`Phase menu — ${payload.label}`}>
      <div className="context-menu-title">{payload.label} — stop for priority</div>
      <button type="button" className="context-menu-item" data-explain="phase-ctx-toggle-mine" onClick={() => toggle('yourTurn')}>
        {mine ? '✓ Stop' : 'Stop'} on MY turn
      </button>
      <button type="button" className="context-menu-item" data-explain="phase-ctx-toggle-opp" onClick={() => toggle('opponentTurn')}>
        {opp ? '✓ Stop' : 'Stop'} on OPPONENT&apos;s turn
      </button>
      <div className="context-menu-note">
        Unchecked phases auto-skip. Same settings the Phase Stop dialog edits.
      </div>
    </ContextMenuShell>
  )
}
