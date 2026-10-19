import type { GameState } from '@/types'
import { useGameStore } from '@/store/gameStore'
import { openCardHints } from '@/store/slices/cardHints'

/** Step display labels — ported from game-renderer.js STEP_LABELS. */
const STEP_LABELS: Record<string, string> = {
  PRECOMBAT_MAIN: 'Main Phase 1',
  POSTCOMBAT_MAIN: 'Main Phase 2',
  BEGIN_COMBAT: 'Begin Combat',
  DECLARE_ATTACKERS: 'Declare Attackers',
  DECLARE_BLOCKERS: 'Declare Blockers',
  COMBAT_DAMAGE: 'Combat Damage',
  FIRST_COMBAT_DAMAGE: 'Combat Damage',
  END_COMBAT: 'End Combat',
  END_TURN: 'End Step',
  CLEANUP: 'Cleanup',
  UPKEEP: 'Upkeep',
  DRAW: 'Draw Step',
  UNTAP: 'Untap',
}

/**
 * Phase display labels. The wire `phase` is the ABBREVIATED client form the
 * serializer maps to (GameStateSerializer.mapPhaseToClient: M1/C1/M2/E1 — the
 * C2/M3/E2 members exist in the frontend `Phase` type but Java never emits
 * them). The desktop shows the Java `TurnPhase.toString()` in its own labelled
 * `txtPhase` field (GamePanel.java:1137-1141, `lblPhase` = "Phase:") — this is
 * the same idea, labelled the same way, in the web header.
 */
const PHASE_LABELS: Record<string, string> = {
  M1: 'Main Phase 1',
  C1: 'Combat Phase',
  M2: 'Main Phase 2',
  E1: 'End Phase',
  C2: 'Cleanup Phase',
  M3: 'Main Phase 3',
  E2: 'End Phase 2',
}

/** "FOO_BAR" -> "Foo Bar" — the shared fallback for an unmapped enum value. */
function titleCaseEnum(value: string): string {
  return value.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())
}

/** The labelled STEP text (desktop `txtStep`), or '' when the step is null. */
export function formatStepLabel(step: string | null): string {
  if (!step) return ''
  const key = step.toUpperCase()
  return STEP_LABELS[key] ?? titleCaseEnum(key)
}

/** The labelled PHASE text (desktop `txtPhase`), or '' when the phase is null. */
export function formatPhaseLabel(phase: string | null): string {
  if (!phase) return ''
  const key = phase.toUpperCase()
  return PHASE_LABELS[key] ?? titleCaseEnum(key)
}

function formatTurnLabel(turn: number, activePlayer: string): string {
  if (!activePlayer && (turn == null || turn === 0)) return 'Pregame'
  if (!activePlayer) return `Turn ${turn}`
  const turnNum = turn != null ? `Turn ${turn}` : 'Turn ?'
  return `${activePlayer}'s ${turnNum}`
}

function formatPriorityLabel(priorityPlayer: string): string {
  return priorityPlayer ? `Priority: ${priorityPlayer}` : 'Priority: —'
}

interface GameHeaderProps {
  gameState: GameState
  gameTitle: string
  onConcede?: () => void
  onUndo?: () => void
  onStopWatching?: () => void
}

/**
 * Game header — ported from index.html game-header-bar + app.js turn info.
 * Shows the game title, turn/phase/step info, and game menu buttons.
 */
export function GameHeader({ gameState, gameTitle, onConcede, onUndo, onStopWatching }: GameHeaderProps) {
  const stepLabel = formatStepLabel(gameState.step)
  const phaseLabel = formatPhaseLabel(gameState.phase)

  // Undo availability — same gating as the desktop client (GamePanel.java:2139):
  // the player must have a stored undo bookmark (statesSavedSize > 0) and the
  // stack must be empty. Without the bookmark the server's game.undo() no-ops,
  // so showing the button would be a silent lie.
  const myPlayer = gameState.players.find((p) => p.isMe)
  const canUndo =
    !gameState.isSpectator &&
    gameState.stack.length === 0 &&
    (myPlayer?.statesSavedSize ?? 0) > 0

  return (
    <div className="game-header-bar">
      <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>
        {gameState.isSpectator ? `👁 ${gameTitle}` : gameTitle}
      </div>
      <div id="turn-info">
        {formatTurnLabel(gameState.turn, gameState.activePlayer)}
        {' | '}
        {/* Step and phase are SEPARATELY LABELLED fields, as the
            desktop's GamePanel shows them (lblStep/txtStep and lblPhase/txtPhase,
            GamePanel.java:1137-1149). The old single combined `formatPhaseStep`
            string collapsed the two into one unlabelled value. Each field renders
            '—' when the wire value is null (the desktop blanks its txt field
            there; a labelled field with an empty value reads as broken in a
            single-line header). */}
        <span id="turn-step">
          Step: {stepLabel || '—'}
        </span>
        {' | '}
        <span id="turn-phase">
          Phase: {phaseLabel || '—'}
        </span>
        {' | '}
        {formatPriorityLabel(gameState.priorityPlayer)}
      </div>
      <div className="game-menu" style={{ marginLeft: 'auto', display: 'flex', gap: '0.5rem' }}>
        {/* Card hints helper — the desktop's "Hints" button
            (PlayerPanelExt:138 `toolHintsHelper.setVisible(this.isMe)` →
            btnToolHintsHelperActionPerformed → GamePanel.openCardHintsWindow).
            Desktop parity: your own seat only, so a watcher gets no button; the
            modal itself is mounted at App level and reads the board on screen. */}
        {!gameState.isSpectator && (
          <button className="btn btn-secondary btn-sm" onClick={() => openCardHints()}
          data-explain="header-card-hints">💡 Hints</button>
        )}
        <button className="btn btn-secondary btn-sm" onClick={() => useGameStore.getState().setShowSettings(true)}
        data-explain="settings-gear">⚙ Settings</button>
        {onUndo && canUndo && <button className="btn btn-secondary btn-sm" onClick={onUndo}
        data-explain="header-undo">Undo</button>}
        {gameState.isSpectator ? (
          onStopWatching && <button className="btn btn-danger btn-sm" onClick={onStopWatching}
          data-explain="stop-watching">Stop Watching</button>
        ) : (
          onConcede && <button className="btn btn-secondary btn-sm" onClick={onConcede}
          data-explain="header-concede">Concede</button>
        )}
      </div>
    </div>
  )
}