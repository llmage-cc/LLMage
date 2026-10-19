import { useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import type { PhaseStopConfig } from '@/types'

/**
 * Fallback for a fresh session where nothing has been sent yet (matches the
 * initial UserData the proxy builds server-side). The dialog initializes from
 * the store's TRACKED config — the last config this client sent — not from
 * this constant; starting from the constant here used to silently revert
 * every other toggle each time the dialog opened.
 */
const DEFAULT_STOPS: PhaseStopConfig = {
  yourTurn: { upkeep: true, draw: true, main1: true, beforeCombat: true, endOfCombat: true, main2: true, endOfTurn: true },
  opponentTurn: { upkeep: true, draw: true, main1: true, beforeCombat: true, endOfCombat: true, main2: true, endOfTurn: true },
  stopOnDeclareBlockersWithZeroPermanents: true,
  stopOnDeclareBlockersWithAnyPermanents: true,
  stopOnAllMainPhases: true,
  stopOnAllEndPhases: true,
  stopOnStackNewObjects: true,
}

const YOUR_TURN_PHASES: Array<{ key: keyof PhaseStopConfig['yourTurn']; label: string }> = [
  { key: 'upkeep', label: 'Upkeep' },
  { key: 'draw', label: 'Draw' },
  { key: 'main1', label: 'Main Phase 1' },
  { key: 'beforeCombat', label: 'Before Combat' },
  { key: 'endOfCombat', label: 'End of Combat' },
  { key: 'main2', label: 'Main Phase 2' },
  { key: 'endOfTurn', label: 'End of Turn' },
]

const OPPONENT_TURN_PHASES: Array<{ key: keyof PhaseStopConfig['opponentTurn']; label: string }> = [
  { key: 'upkeep', label: 'Upkeep' },
  { key: 'draw', label: 'Draw' },
  { key: 'main1', label: 'Main Phase 1' },
  { key: 'beforeCombat', label: 'Before Combat' },
  { key: 'endOfCombat', label: 'End of Combat' },
  { key: 'main2', label: 'Main Phase 2' },
  { key: 'endOfTurn', label: 'End of Turn' },
]

const GLOBAL_OPTIONS: Array<{ key: keyof PhaseStopConfig; label: string; description: string }> = [
  { key: 'stopOnDeclareBlockersWithZeroPermanents', label: 'Stop on Declare Blockers (0 permanents)', description: 'Stop when you have zero permanents to block' },
  { key: 'stopOnDeclareBlockersWithAnyPermanents', label: 'Stop on Declare Blockers (any permanents)', description: 'Stop when you have any permanents that could block' },
  { key: 'stopOnAllMainPhases', label: 'Stop on All Main Phases', description: 'Always stop on main phases regardless of per-turn settings' },
  { key: 'stopOnAllEndPhases', label: 'Stop on All End Phases', description: 'Always stop on end phases regardless of per-turn settings' },
  { key: 'stopOnStackNewObjects', label: 'Stop on New Stack Objects', description: 'Stop when a new object is added to the stack' },
]

/**
 * Phase stop settings dialog — lets the player toggle which phases to stop at.
 * Matches the Java client's Preferences → Phases tab.
 * Changes are sent to the server immediately via updatePhaseStops.
 */
export function PhaseStopSettings({ onClose }: { onClose: () => void }) {
  const updatePhaseStops = useGameStore((s) => s.updatePhaseStops)
  const setPhaseStopConfig = useGameStore((s) => s.setPhaseStopConfig)
  // Initialize from the TRACKED config (the last config this client sent) —
  // not a hardcoded constant, which reverted every other toggle on open.
  const [stops, setStops] = useState<PhaseStopConfig>(
    () => useGameStore.getState().phaseStopConfig ?? DEFAULT_STOPS,
  )

  const updateYourTurn = (key: keyof PhaseStopConfig['yourTurn'], value: boolean) => {
    setStops((prev) => ({ ...prev, yourTurn: { ...prev.yourTurn, [key]: value } }))
  }

  const updateOpponentTurn = (key: keyof PhaseStopConfig['opponentTurn'], value: boolean) => {
    setStops((prev) => ({ ...prev, opponentTurn: { ...prev.opponentTurn, [key]: value } }))
  }

  const updateGlobal = (key: keyof PhaseStopConfig, value: boolean) => {
    setStops((prev) => ({ ...prev, [key]: value }))
  }

  const handleApply = () => {
    updatePhaseStops(stops)
    setPhaseStopConfig(stops)
    onClose()
  }

  const handleResetAll = () => {
    setStops(DEFAULT_STOPS)
    updatePhaseStops(DEFAULT_STOPS)
    setPhaseStopConfig(DEFAULT_STOPS)
  }

  return (
    <div className="modal-overlay" style={{ display: 'flex' }}>
      <div className="modal-content" style={{ maxWidth: '500px', maxHeight: '85vh', overflowY: 'auto' }}>
        <div className="modal-title">Phase Stop Settings</div>
        <div className="modal-body" style={{ color: 'var(--text-secondary)', fontSize: '0.85rem', marginBottom: '0.75rem' }}>
          Toggle which phases to stop at for priority. Unchecked phases auto-skip.
        </div>

        {/* Your Turn */}
        <div style={{ marginBottom: '1rem' }}>
          <div style={{ fontWeight: 600, marginBottom: '0.4rem', color: 'var(--text-primary)' }}>Your Turn</div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.3rem' }}>
            {YOUR_TURN_PHASES.map((phase) => (
              <label key={phase.key} style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', cursor: 'pointer', fontSize: '0.85rem' }}>
                <input
                  type="checkbox"
                  checked={stops.yourTurn[phase.key]}
                  onChange={(e) => updateYourTurn(phase.key, e.target.checked)}
                />
                {phase.label}
              </label>
            ))}
          </div>
        </div>

        {/* Opponent's Turn */}
        <div style={{ marginBottom: '1rem' }}>
          <div style={{ fontWeight: 600, marginBottom: '0.4rem', color: 'var(--text-primary)' }}>Opponent's Turn</div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.3rem' }}>
            {OPPONENT_TURN_PHASES.map((phase) => (
              <label key={phase.key} style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', cursor: 'pointer', fontSize: '0.85rem' }}>
                <input
                  type="checkbox"
                  checked={stops.opponentTurn[phase.key]}
                  onChange={(e) => updateOpponentTurn(phase.key, e.target.checked)}
                />
                {phase.label}
              </label>
            ))}
          </div>
        </div>

        {/* Global Options */}
        <div style={{ marginBottom: '1rem' }}>
          <div style={{ fontWeight: 600, marginBottom: '0.4rem', color: 'var(--text-primary)' }}>Global Options</div>
          {GLOBAL_OPTIONS.map((opt) => (
            <label
              key={opt.key}
              style={{ display: 'flex', alignItems: 'flex-start', gap: '0.4rem', cursor: 'pointer', fontSize: '0.85rem', marginBottom: '0.3rem' }}
              title={opt.description}
            >
              <input
                type="checkbox"
                checked={stops[opt.key] as boolean}
                onChange={(e) => updateGlobal(opt.key, e.target.checked)}
              />
              <span>
                {opt.label}
                <span style={{ display: 'block', fontSize: '0.75rem', color: 'var(--text-muted)' }}>{opt.description}</span>
              </span>
            </label>
          ))}
        </div>

        {/* Buttons */}
        <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem' }}>
          <button className="btn btn-primary btn-sm" onClick={handleApply} style={{ flex: 1 }} data-explain="phase-stop-apply">
            Apply
          </button>
          <button className="btn btn-secondary btn-sm" onClick={handleResetAll} data-explain="phase-stop-reset">
            Reset All
          </button>
          <button className="btn btn-secondary btn-sm" onClick={onClose} data-explain="phase-stop-cancel">
            Cancel
          </button>
        </div>
      </div>
    </div>
  )
}