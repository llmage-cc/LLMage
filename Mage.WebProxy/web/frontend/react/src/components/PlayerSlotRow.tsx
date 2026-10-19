import { useGameStore } from '@/store/gameStore'
import type { PlayerSlot } from '@/types'
import { validateSeatName, seatNameIsSet } from '@/utils/seatNames'
import { LLMConfigTabs } from './LLMConfigTabs'
import SavedDeckPicker from './deckeditor/SavedDeckPicker'
import BuiltInDeckPicker from './deckeditor/BuiltInDeckPicker'
import RandomDeckPicker from './deckeditor/RandomDeckPicker'
import P1TrainingDeckPicker from './P1TrainingDeckPicker'

/**
 * Player slot row — one seat in the game setup dialog.
 * Extracted from GameSetupDialog.tsx.
 *
 * this fork: the "LLM Player" seat type only renders when the
 * connected server supports LLM players (capabilities.llm) — an official
 * XMage server's PlayerType list has no COMPUTER_LLM, and sending it would
 * be rejected.
 *
 * When the proxy classified a saved seat's thinking shape
 * as strict (llm_setup_warning), the warning renders inside THIS seat's LLM
 * block — next to the config the user must change — and only in the seat the
 * proxy named.
 *
 * Seat names: the seat carries an optional Name field — `slot.name` rides
 * the wire as `playerConfigs[].name` and becomes the seat's player name for the
 * match (board header, lobby seat list, game log). EMPTY = the proxy's own
 * default (connected username for a human seat, "AI-<index>" for an AI seat —
 * WebProxySession.handleCreateTable/handleCreateTournament), which the
 * placeholder shows.
 *
 * The field is offered for YOUR seat and for AI/LLM seats only. It is
 * DELIBERATELY absent on a second HUMAN seat: that seat is meant for a
 * different person to join later, and the name it plays under arrives with
 * THEIR join (`handleJoinTable` joins as `proxyClient.getUsername()`), so a
 * name typed here would be overwritten server-side. Do not "complete" this
 * by adding the field to HUMAN opponent seats.
 */
export function PlayerSlotRow({
  slot,
  index,
  canRemove,
  onUpdate,
  onRemove,
  onDeckFile,
}: {
  slot: PlayerSlot
  index: number
  canRemove: boolean
  onUpdate: (patch: Partial<PlayerSlot>) => void
  onRemove: () => void
  onDeckFile: (file: File) => void
}) {
  const serverSupportsLlm = useGameStore((s) => s.capabilities.llm)
  const serverSupportsP1 = useGameStore((s) => s.capabilities.p1)
  const p1Decks = useGameStore((s) => s.p1Decks)
  const p1DefaultDeck = useGameStore((s) => s.p1DefaultDeck)
  const llmSetupWarning = useGameStore((s) => s.llmSetupWarning)
  const proxyUsername = useGameStore((s) => s.proxyUsername)
  const deckUploadNotice = useGameStore((s) => s.deckUploadNotice)

  // Name field for the You seat + AI/LLM seats (see the header comment
  // for why open HUMAN opponent seats get none).
  const showNameField = slot.isYou || slot.type !== 'HUMAN'
  // Fail Fast: the error renders inline, right under the field, the moment the
  // typed value breaks a rule — and the send path (`startGame`/`startTournament`)
  // refuses to send an invalid name rather than truncating it.
  const nameProblem = validateSeatName(slot.name ?? '')

  return (
    <div className="player-slot">
      <div className="player-slot-header">
        <span className="player-slot-title">
          {slot.isYou ? 'You' : `Opponent ${index}`}
        </span>
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
          {canRemove && (
            <button
              onClick={onRemove}
              data-explain="player-slot-remove"
              style={{ background: 'none', border: 'none', color: 'var(--color-danger)', cursor: 'pointer' }}
            >
              ✕
            </button>
          )}
          <select
            value={slot.type}
            onChange={(e) => {
              const nextType = e.target.value
              const patch: Partial<PlayerSlot> = {
                type: nextType,
                showLLMConfig: nextType === 'COMPUTER_LLM',
              }
              // P1 seat: pre-fill the deterministic training default so the seat
              // never carries an arbitrary/empty deck into the server.
              if (nextType === 'COMPUTER_P1' && !slot.deck) {
                patch.deck = p1Decks.find((d) => d.name === p1DefaultDeck)?.deck ?? ''
              }
              onUpdate(patch)
            }}
          >
            <option value="HUMAN">Human</option>
            <option value="COMPUTER_MAD">AI (Mad)</option>
            <option value="COMPUTER_MONTE_CARLO">AI (Monte Carlo)</option>
            {/* __LLMAGE__ = build flavor (stock bundles tree-shake this out);
                capabilities.llm = the connected server runs LLM players */}
            {__LLMAGE__ && serverSupportsLlm && <option value="COMPUTER_LLM">LLM Player</option>}
            {/* Argentum-P1 policy-net seat. Only offered when the connected server
                advertises the P1 player type; its deck is restricted to the 55
                training lists (P1TrainingDeckPicker below). */}
            {__LLMAGE__ && serverSupportsP1 && <option value="COMPUTER_P1">P1 Policy Net</option>}
          </select>
        </div>
      </div>

      {showNameField && (
        <div className="player-slot-name" style={{ marginBottom: '0.25rem' }}>
          <label style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', display: 'block', marginBottom: '0.25rem' }}>
            Name
          </label>
          <input
            type="text"
            value={slot.name ?? ''}
            placeholder={defaultSeatName(slot, index, proxyUsername)}
            onChange={(e) => onUpdate({ name: e.target.value })}
            data-explain="seat-name"
            style={{ width: '100%', boxSizing: 'border-box' }}
          />
          {nameProblem && (
            <div
              role="alert"
              data-testid="seat-name-error"
              style={{ color: 'var(--color-danger)', fontSize: '0.75rem', marginTop: '0.25rem' }}
            >
              {nameProblem}
            </div>
          )}
        </div>
      )}

      <label style={{ fontSize: '0.75rem', color: 'var(--text-secondary)', display: 'block', marginBottom: '0.25rem' }}>
        Deck {slot.type === 'COMPUTER_P1' ? '(P1 training decks only)' : '(paste or upload .dck/.txt file)'}
      </label>
      {slot.type === 'COMPUTER_P1' ? (
        <>
          <P1TrainingDeckPicker onPick={(text) => onUpdate({ deck: text })} />
          <div
            data-testid="p1-deck-restricted-note"
            style={{ fontSize: '0.7rem', color: 'var(--text-secondary)', margin: '0.25rem 0' }}
          >
            The P1 policy net may only face the {p1Decks.length || 55} Standard decks it was trained on
            (default: {p1DefaultDeck || 'a training deck'}).
          </div>
          {/* Read-only preview of the selected training deck text. */}
          <textarea
            rows={6}
            readOnly
            value={slot.deck}
            data-testid="p1-deck-preview"
          />
        </>
      ) : (
        <>
          <SavedDeckPicker onPick={(text) => onUpdate({ deck: text })} />
          <BuiltInDeckPicker onPick={(text) => onUpdate({ deck: text })} />
          {/* Random deck (desktop parity: btnGenerate next to the deck field).
              Fills the textarea below via the same onUpdate({deck}) path. */}
          <RandomDeckPicker onDeck={(text) => onUpdate({ deck: text })} />
          <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '0.25rem' }}>
            <input
              type="file"
              accept=".dck,.txt,.dec"
              style={{ fontSize: '0.75rem' }}
              onChange={(e) => {
                const file = e.target.files?.[0]
                if (file) onDeckFile(file)
              }}
            />
          </div>
          <textarea
            rows={6}
            placeholder={'4 Mountain\n4 Lightning Bolt\nSB: 1 Grafdigger\'s Cage'}
            value={slot.deck}
            onChange={(e) => onUpdate({ deck: e.target.value })}
          />
        </>
      )}
      {/* Deck-upload confirmation (bug-hunt: the upload silently replaced the
          textarea). Additive role=status line for THIS seat only — the store
          carries one notice keyed by slot index. Not a form error (setupError
          owns that), so it is informational, not role=alert. */}
      {deckUploadNotice && deckUploadNotice.slotIndex === index && (
        <div
          role="status"
          data-testid="deck-upload-notice"
          style={{ color: 'var(--accent-success)', fontSize: '0.75rem', marginTop: '0.25rem' }}
        >
          {deckUploadNotice.message}
        </div>
      )}

      {/* LLM config — build flavor AND server capability AND seat type.
          Split into tabs (Core/Behavior/Personality/Conversation/Summarization)
          mirroring the desktop client's LLMConfigDialog. */}
      {__LLMAGE__ && slot.type === 'COMPUTER_LLM' && serverSupportsLlm && (
        <div className="llm-config-block">
          {llmSetupWarning && llmSetupWarning.seat === seatName(slot, index) && (
            <div
              className="llm-setup-warning"
              data-testid="llm-setup-warning"
              role="alert"
              style={{
                color: 'var(--color-danger)',
                fontSize: '0.75rem',
                border: '1px solid var(--color-danger)',
                borderRadius: '6px',
                padding: '0.5rem',
                marginBottom: '0.5rem',
              }}
            >
              {llmSetupWarning.message}
            </div>
          )}
          <LLMConfigTabs
            config={slot.llmConfig}
            onConfigChange={(llmConfig) => onUpdate({ llmConfig })}
          />
        </div>
      )}
    </div>
  )
}

/**
 * The name the seat will actually play under when the field is left empty —
 * the same derivation the proxy's seat loop makes
 * (`pc.has("name") ? name : (pt.isAI() ? "AI-" + i : username)`). Shown as the
 * field's placeholder so an empty field is visibly "the default", not "unset".
 */
function defaultSeatName(slot: PlayerSlot, index: number, username: string): string {
  if (slot.type !== 'HUMAN') return 'AI-' + index
  return username || 'your username'
}

/**
 * The name the proxy's create_table/create_tournament loop will give this seat —
 * `pc.has("name") ? name : (pt.isAI() ? "AI-" + i : username)`. The
 * llm_setup_warning names the seat with THAT string (sendThinkingShapeWarnings →
 * LLMPlayerConfig seat name), so the seat row must match on it: the typed name
 * when one is set, else the derived default.
 */
function seatName(slot: PlayerSlot, index: number): string {
  return seatNameIsSet(slot.name) ? slot.name!.trim() : 'AI-' + index
}