import { useGameStore } from '@/store/gameStore'
import SavedDeckPicker from './deckeditor/SavedDeckPicker'
import BuiltInDeckPicker from './deckeditor/BuiltInDeckPicker'
import { GAME_TYPES, DECK_TYPES } from '@/utils/constants'
import { PlayerSlotRow } from './PlayerSlotRow'

/**
 * Game Setup dialog — ported from index.html popup-overlay + game-setup-content.
 * Handles both "New Game" (create) and "Join Table" (join) modes.
 *
 * Create mode uses a two-pane layout (Game Settings | Players) so the dialog
 * uses horizontal space on widescreen monitors instead of one cramped strip;
 * .setup-columns collapses to a single column on narrow windows (popups.css).
 */
export default function GameSetupDialog() {
  const joinMode = useGameStore((s) => s.joinMode)
  const setupConfig = useGameStore((s) => s.setupConfig)
  const setupError = useGameStore((s) => s.setupError)
  const updateSetupConfig = useGameStore((s) => s.updateSetupConfig)
  const updatePlayerSlot = useGameStore((s) => s.updatePlayerSlot)
  const addPlayerSlot = useGameStore((s) => s.addPlayerSlot)
  const removePlayerSlot = useGameStore((s) => s.removePlayerSlot)
  const startGame = useGameStore((s) => s.startGame)
  const hideGameSetup = useGameStore((s) => s.hideGameSetup)
  const loadDeckFile = useGameStore((s) => s.loadDeckFile)
  const deckUploadNotice = useGameStore((s) => s.deckUploadNotice)
  // this fork: "Share LLM Chat Live" exists only on LLMage servers.
  const serverSupportsLlm = useGameStore((s) => s.capabilities.llm)

  return (
    <div className="popup-overlay" style={{ display: 'flex' }}>
      <div className="game-setup-content">
        <h3 style={{ color: 'var(--accent-primary)', marginBottom: '1rem' }}>
          {joinMode ? 'Join Table' : 'New Game'}
        </h3>

        {!joinMode ? (
          <div className="setup-columns">
            {/* Left pane: game settings + advanced options */}
            <div className="setup-col">
              <div className="setup-col-title">Game Settings</div>
              <div className="setup-field-grid">
                <div className="setup-field">
                  <label>Game Name</label>
                  <input
                    type="text"
                    value={setupConfig.gameName}
                    onChange={(e) => updateSetupConfig({ gameName: e.target.value })}
                  />
                </div>
                <div className="setup-field">
                  <label>Game Type</label>
                  <select
                    value={setupConfig.gameType}
                    onChange={(e) => updateSetupConfig({ gameType: e.target.value })}
                  >
                    {GAME_TYPES.map((gt) => (
                      <option key={gt} value={gt}>{gt}</option>
                    ))}
                  </select>
                </div>
                <div className="setup-field">
                  <label>Deck Type</label>
                  <select
                    value={setupConfig.deckType}
                    onChange={(e) => updateSetupConfig({ deckType: e.target.value })}
                  >
                    {DECK_TYPES.map((dt) => (
                      <option key={dt} value={dt}>{dt}</option>
                    ))}
                  </select>
                </div>
                <div className="setup-field">
                  <label>Wins Needed</label>
                  <select
                    value={setupConfig.winsNeeded}
                    onChange={(e) => updateSetupConfig({ winsNeeded: parseInt(e.target.value) })}
                  >
                    {/* MatchOptions.winsNeeded = wins needed to TAKE the match
                        (MatchImpl.checkIfMatchEnds: getWins() >= getWinsNeeded()).
                        "Best of N" must therefore send ceil(N/2):
                        Bo1→1, Bo3→2, Bo5→3. Sending 3 for "Best of 3" produced a
                        first-to-3 (Bo5) match (found live 2026-09-18;
                        desktop client sends 2). */}
                    <option value={1}>Best of 1</option>
                    <option value={2}>Best of 3</option>
                    <option value={3}>Best of 5</option>
                  </select>
                </div>
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
                  <label>Attack Option</label>
                  <select
                    value={setupConfig.attackOption}
                    onChange={(e) => updateSetupConfig({ attackOption: e.target.value })}
                  >
                    <option value="MULTIPLE">Attack Multiple Players</option>
                    <option value="LEFT">Attack Left</option>
                    <option value="RIGHT">Attack Right</option>
                  </select>
                </div>
                <div className="setup-field">
                  <label>Range of Influence</label>
                  <select
                    value={setupConfig.range}
                    onChange={(e) => updateSetupConfig({ range: e.target.value })}
                  >
                    <option value="ONE">1</option>
                    <option value="TWO">2</option>
                    <option value="ALL">All</option>
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
                <div className="setup-field">
                  <label>Mulligan Type</label>
                  <select
                    value={setupConfig.mulliganType}
                    onChange={(e) => updateSetupConfig({ mulliganType: e.target.value })}
                  >
                    <option value="GAME_DEFAULT">Game Default</option>
                    <option value="VANCOUVER">Vancouver</option>
                    <option value="PARIS">Paris</option>
                    <option value="LONDON">London</option>
                    <option value="SMOOTHED_LONDON">Smoothed London</option>
                    <option value="CANADIAN_HIGHLANDER">Canadian Highlander</option>
                  </select>
                </div>
                <div className="setup-field">
                  <label>Free Mulligans</label>
                  <input
                    type="number"
                    value={setupConfig.freeMulligans}
                    min={0}
                    max={5}
                    step={1}
                    onChange={(e) => updateSetupConfig({ freeMulligans: parseInt(e.target.value) || 0 })}
                  />
                </div>
                <div className="setup-field" style={{ display: 'flex', gap: '0.5rem', alignItems: 'end' }}>
                  <div style={{ flex: 1 }}>
                    <label>Custom Starting Life</label>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                      <input
                        type="checkbox"
                        checked={setupConfig.customStartLifeEnabled}
                        onChange={(e) => updateSetupConfig({ customStartLifeEnabled: e.target.checked })}
                      />
                      <input
                        type="number"
                        value={setupConfig.customStartLife}
                        min={1}
                        max={100}
                        step={1}
                        disabled={!setupConfig.customStartLifeEnabled}
                        style={{ width: '80px' }}
                        onChange={(e) => updateSetupConfig({ customStartLife: parseInt(e.target.value) || 20 })}
                      />
                    </div>
                  </div>
                </div>
                <div className="setup-field" style={{ display: 'flex', gap: '0.5rem', alignItems: 'end' }}>
                  <div style={{ flex: 1 }}>
                    <label>Custom Starting Hand</label>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                      <input
                        type="checkbox"
                        checked={setupConfig.customStartHandSizeEnabled}
                        onChange={(e) => updateSetupConfig({ customStartHandSizeEnabled: e.target.checked })}
                      />
                      <input
                        type="number"
                        value={setupConfig.customStartHandSize}
                        min={0}
                        max={20}
                        step={1}
                        disabled={!setupConfig.customStartHandSizeEnabled}
                        style={{ width: '80px' }}
                        onChange={(e) => updateSetupConfig({ customStartHandSize: parseInt(e.target.value) || 7 })}
                      />
                    </div>
                  </div>
                </div>
              </div>

              <div className="setup-advanced-options" style={{ display: 'flex', flexWrap: 'wrap', gap: '0.75rem', marginTop: '0.75rem', marginBottom: '0.5rem', padding: '0.5rem 0' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.8rem' }}>
                  <input
                    type="checkbox"
                    checked={setupConfig.planeChase}
                    onChange={(e) => updateSetupConfig({ planeChase: e.target.checked })}
                  />{' '}
                  Planechase
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
                    checked={setupConfig.spectatorsAllowed}
                    onChange={(e) => updateSetupConfig({ spectatorsAllowed: e.target.checked })}
                  />{' '}
                  Allow Spectators
                </label>
                {__LLMAGE__ && serverSupportsLlm && (
                  <label
                    style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.8rem' }}
                    title="Let opponents and spectators read LLM bots' chat/reasoning during live play. Replays always include it."
                  >
                    <input
                      type="checkbox"
                      checked={setupConfig.llmChatPublic}
                      onChange={(e) => updateSetupConfig({ llmChatPublic: e.target.checked })}
                    />{' '}
                    Share LLM Chat Live
                  </label>
                )}
                <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem', fontSize: '0.8rem' }}>
                  <input
                    type="checkbox"
                    checked={setupConfig.rated}
                    onChange={(e) => updateSetupConfig({ rated: e.target.checked })}
                  />{' '}
                  Rated
                </label>
              </div>

              {/* Matchmaking constraints row */}
              <div className="setup-matchmaking-options" style={{ display: 'flex', flexWrap: 'wrap', gap: '0.9rem', padding: '0 0.25rem 0.5rem', fontSize: '0.8rem' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem' }}>
                  Password
                  <input
                    type="text"
                    value={setupConfig.password}
                    onChange={(e) => updateSetupConfig({ password: e.target.value })}
                    placeholder="(open table)"
                    style={{ width: '7rem' }}
                  />
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem' }}>
                  Quit Ratio %
                  <input
                    type="number"
                    min={0}
                    max={100}
                    value={setupConfig.quitRatio}
                    onChange={(e) => updateSetupConfig({ quitRatio: parseInt(e.target.value) || 0 })}
                    style={{ width: '4rem' }}
                  />
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem' }}>
                  Min Rating
                  <input
                    type="number"
                    min={0}
                    value={setupConfig.minimumRating}
                    onChange={(e) => updateSetupConfig({ minimumRating: parseInt(e.target.value) || 0 })}
                    style={{ width: '5rem' }}
                  />
                </label>
                <label style={{ display: 'flex', alignItems: 'center', gap: '0.3rem' }}>
                  EDH Power
                  <input
                    type="number"
                    min={0}
                    max={10}
                    value={setupConfig.edhPowerLevel}
                    onChange={(e) => updateSetupConfig({ edhPowerLevel: parseInt(e.target.value) || 0 })}
                    style={{ width: '4rem' }}
                  />
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
        ) : (
          /* Join mode: just a deck textarea */
          <div style={{ marginBottom: '1rem' }}>
            <label style={{ fontSize: '0.8rem', color: 'var(--text-secondary)', display: 'block', marginBottom: '0.25rem' }}>
              Paste your decklist
            </label>
            <SavedDeckPicker onPick={(text) => updatePlayerSlot(0, { deck: text })} />
            <BuiltInDeckPicker onPick={(text) => updatePlayerSlot(0, { deck: text })} />
            <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '0.25rem' }}>
              <input
                type="file"
                accept=".dck,.txt,.dec"
                style={{ fontSize: '0.75rem' }}
                onChange={(e) => {
                  const file = e.target.files?.[0]
                  if (file) loadDeckFile(file, 0)
                }}
              />
            </div>
            <textarea
              rows={10}
              placeholder={'4 Mountain\n4 Lightning Bolt\nSB: 1 Grafdigger\'s Cage'}
              style={{
                width: '100%',
                boxSizing: 'border-box',
                background: 'var(--bg-elevated)',
                border: '1px solid var(--border-default)',
                borderRadius: '6px',
                color: 'var(--text-primary)',
                fontFamily: 'monospace',
                fontSize: '0.85rem',
                padding: '0.5rem',
              }}
              value={setupConfig.playerSlots[0]?.deck ?? ''}
              onChange={(e) => updatePlayerSlot(0, { deck: e.target.value })}
            />
            {/* Deck-upload confirmation for the join-mode seat (slot 0) — same
                additive role=status line the create-mode rows render. */}
            {deckUploadNotice && deckUploadNotice.slotIndex === 0 && (
              <div
                role="status"
                data-testid="deck-upload-notice"
                style={{ color: 'var(--accent-success)', fontSize: '0.75rem', marginTop: '0.25rem' }}
              >
                {deckUploadNotice.message}
              </div>
            )}
          </div>
        )}

        {/* Action buttons */}
        <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1rem' }}>
          <button
            className="btn btn-primary"
            style={{ flex: 1 }}
            onClick={() => startGame()}
            data-explain="setup-start-or-join"
          >
            {joinMode ? 'Join' : 'Start Game'}
          </button>
          <button className="btn btn-secondary" onClick={() => hideGameSetup()}
          data-explain="setup-cancel">
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