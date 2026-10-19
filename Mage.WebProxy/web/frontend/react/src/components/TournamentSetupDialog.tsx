import { useGameStore } from '@/store/gameStore'
import { PlayerSlotRow } from './PlayerSlotRow'
import type { TournamentTypeInfo } from '@/types'

/**
 * Tournament Setup dialog — mirrors the Java client's NewTournamentDialog.
 * Supports constructed tournaments (limited/draft types are shown but may
 * not be fully functional yet — the server-side limited options need pack
 * selection UI that's not implemented here).
 *
 * Two-pane layout (Tournament Settings | Players) so the dialog uses
 * horizontal space on widescreen monitors.setup-columns collapses to a
 * single column on narrow windows (popups.css).
 */
export default function TournamentSetupDialog() {
  const setupConfig = useGameStore((s) => s.tournamentSetupConfig)
  const setupError = useGameStore((s) => s.setupError)
  const updateSetupConfig = useGameStore((s) => s.updateTournamentSetupConfig)
  const updatePlayerSlot = useGameStore((s) => s.updateTournamentPlayerSlot)
  const addPlayerSlot = useGameStore((s) => s.addTournamentPlayerSlot)
  const removePlayerSlot = useGameStore((s) => s.removeTournamentPlayerSlot)
  const startTournament = useGameStore((s) => s.startTournament)
  const hide = useGameStore((s) => s.hideTournamentSetup)
  const loadDeckFile = useGameStore((s) => s.loadDeckFile)
  const tournamentTypes = useGameStore((s) => s.tournamentTypes)
  const tournamentGameTypes = useGameStore((s) => s.tournamentGameTypes)
  const tournamentDeckTypes = useGameStore((s) => s.tournamentDeckTypes)

  const selectedType: TournamentTypeInfo | undefined = tournamentTypes.find(
    (t) => t.name === setupConfig.tournamentType
  )
  const isElimination = selectedType?.elimination ?? false
  const isLimited = selectedType?.limited ?? false

  return (
    <div className="popup-overlay" style={{ display: 'flex' }}>
      <div className="game-setup-content">
        <h3 style={{ color: 'var(--accent-primary)', marginBottom: '1rem' }}>
          New Tournament
        </h3>

        <div className="setup-columns">
          {/* Left pane: tournament settings + advanced options */}
          <div className="setup-col">
            <div className="setup-col-title">Tournament Settings</div>
            <div className="setup-field-grid">
              <div className="setup-field">
                <label>Tournament Name</label>
                <input
                  type="text"
                  value={setupConfig.name}
                  onChange={(e) => updateSetupConfig({ name: e.target.value })}
                />
              </div>

              <div className="setup-field">
                <label>Tournament Type</label>
                <select
                  value={setupConfig.tournamentType}
                  onChange={(e) => updateSetupConfig({ tournamentType: e.target.value })}
                >
                  <option value="">— Select —</option>
                  {tournamentTypes.map((t) => (
                    <option key={t.name} value={t.name}>
                      {t.name} ({t.minPlayers}-{t.maxPlayers}p{t.draft ? ', Draft' : ''}{t.elimination ? ', Elim' : ', Swiss'})
                    </option>
                  ))}
                </select>
              </div>

              {/* Game type — hidden for limited tournaments */}
              {!isLimited && (
                <div className="setup-field">
                  <label>Game Type</label>
                  <select
                    value={setupConfig.gameType}
                    onChange={(e) => updateSetupConfig({ gameType: e.target.value })}
                  >
                    {(tournamentGameTypes.length > 0 ? tournamentGameTypes : ['Two Player Duel']).map((gt) => (
                      <option key={gt} value={gt}>{gt}</option>
                    ))}
                  </select>
                </div>
              )}

              {/* Deck type — hidden for limited tournaments */}
              {!isLimited && (
                <div className="setup-field">
                  <label>Deck Type</label>
                  <select
                    value={setupConfig.deckType}
                    onChange={(e) => updateSetupConfig({ deckType: e.target.value })}
                  >
                    {(tournamentDeckTypes.length > 0 ? tournamentDeckTypes : ['Constructed - Freeform']).map((dt) => (
                      <option key={dt} value={dt}>{dt}</option>
                    ))}
                  </select>
                </div>
              )}

              <div className="setup-field">
                <label>Wins Needed (per match)</label>
                <select
                  value={setupConfig.winsNeeded}
                  onChange={(e) => updateSetupConfig({ winsNeeded: parseInt(e.target.value) })}
                >
                  <option value={1}>Best of 1</option>
                  <option value={2}>Best of 3</option>
                  <option value={3}>Best of 5</option>
                </select>
              </div>

              {/* Number of rounds — only for Swiss (non-elimination) */}
              {!isElimination && (
                <div className="setup-field">
                  <label>Swiss Rounds</label>
                  <select
                    value={setupConfig.numRounds}
                    onChange={(e) => updateSetupConfig({ numRounds: parseInt(e.target.value) })}
                  >
                    {[2, 3, 4, 5, 6, 7, 8, 9, 10].map((r) => (
                      <option key={r} value={r}>{r}</option>
                    ))}
                  </select>
                </div>
              )}

              <div className="setup-field">
                <label>Skill Level</label>
                <select
                  value={setupConfig.skillLevel}
                  onChange={(e) => updateSetupConfig({ skillLevel: e.target.value })}
                >
                  <option value="BEGINNER">Beginner</option>
                  <option value="CASUAL">Casual</option>
                  <option value="SERIOUS">Serious</option>
                </select>
              </div>

              <div className="setup-field">
                <label>Time Limit</label>
                <select
                  value={setupConfig.timeLimit}
                  onChange={(e) => updateSetupConfig({ timeLimit: e.target.value })}
                >
                  <option value="NONE">None</option>
                  <option value="MIN___5">5 Minutes</option>
                  <option value="MIN__10">10 Minutes</option>
                  <option value="MIN__15">15 Minutes</option>
                  <option value="MIN__20">20 Minutes</option>
                  <option value="MIN__25">25 Minutes</option>
                  <option value="MIN__30">30 Minutes</option>
                  <option value="MIN__45">45 Minutes</option>
                  <option value="MIN__60">60 Minutes</option>
                  <option value="MIN__90">90 Minutes</option>
                  <option value="MIN_120">120 Minutes</option>
                </select>
              </div>

              <div className="setup-field">
                <label>Buffer Time</label>
                <select
                  value={setupConfig.bufferTime}
                  onChange={(e) => updateSetupConfig({ bufferTime: e.target.value })}
                >
                  <option value="NONE">None</option>
                  <option value="SEC__01">1 Second</option>
                  <option value="SEC__02">2 Seconds</option>
                  <option value="SEC__03">3 Seconds</option>
                  <option value="SEC__05">5 Seconds</option>
                  <option value="SEC__10">10 Seconds</option>
                  <option value="SEC__15">15 Seconds</option>
                  <option value="SEC__20">20 Seconds</option>
                  <option value="SEC__25">25 Seconds</option>
                  <option value="SEC__30">30 Seconds</option>
                </select>
              </div>
            </div>

            {/* Advanced options checkboxes */}
            <div className="setup-advanced-options" style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', marginTop: '0.75rem', marginBottom: '0.5rem', padding: '0.5rem 0' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.8rem' }}>
                <input
                  type="checkbox"
                  checked={setupConfig.singleMultiplayerGame}
                  onChange={(e) => updateSetupConfig({ singleMultiplayerGame: e.target.checked })}
                />{' '}
                Single Multiplayer Game
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.8rem' }}>
                <input
                  type="checkbox"
                  checked={setupConfig.allowSpectators}
                  onChange={(e) => updateSetupConfig({ allowSpectators: e.target.checked })}
                />{' '}
                Allow Spectators
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.8rem' }}>
                <input
                  type="checkbox"
                  checked={setupConfig.rollbackTurnsAllowed}
                  onChange={(e) => updateSetupConfig({ rollbackTurnsAllowed: e.target.checked })}
                />{' '}
                Allow Rollback
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.8rem' }}>
                <input
                  type="checkbox"
                  checked={setupConfig.rated}
                  onChange={(e) => updateSetupConfig({ rated: e.target.checked })}
                />{' '}
                Rated
              </label>
            </div>
          </div>

          {/* Right pane: player seats */}
          <div className="setup-col">
            <div className="setup-col-title">Players</div>
            <div style={{ marginBottom: '0.5rem' }}>
              {setupConfig.playerSlots.map((slot, idx) => (
                <PlayerSlotRow
                  key={idx}
                  slot={slot}
                  index={idx}
                  canRemove={setupConfig.playerSlots.length > 2 && !slot.isYou}
                  onUpdate={(patch) => updatePlayerSlot(idx, patch)}
                  onRemove={() => removePlayerSlot(idx)}
                  onDeckFile={(file) => loadDeckFile(file, idx)}
                />
              ))}
              <button
                className="btn btn-secondary btn-sm"
                onClick={() => addPlayerSlot('COMPUTER_MAD', false)}
                data-explain="setup-add-player"
              >
                + Add Player
              </button>
            </div>
          </div>
        </div>

        {/* Action buttons */}
        <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1rem' }}>
          <button
            className="btn btn-primary"
            style={{ flex: 1 }}
            onClick={() => startTournament()}
            data-explain="tour-create"
          >
            Create Tournament
          </button>
          <button className="btn btn-secondary" onClick={() => hide()}
          data-explain="tour-cancel">
            Cancel
          </button>
        </div>
        {setupError && (
          <div style={{ color: 'var(--color-danger)', fontSize: '0.85rem', marginTop: '0.5rem' }}>
            {setupError}
          </div>
        )}
      </div>
    </div>
  )
}