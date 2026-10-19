/**
 * Standalone regression check for seat names (game + tournament setup).
 *
 * Run with:  bash scripts/verify-seat-names.sh
 *
 * Drives the REAL modules (no browser): the real store slices
 * (lobbySlice.startGame / startTournament — the exact code that builds the
 * create_table / create_tournament wire frames), the real seat row
 * (PlayerSlotRow, rendered through react-dom/server) and the real validator
 * (utils/seatNames).
 *
 * Encodes:
 *  (a) DEFAULT BEHAVIOR IS UNTOUCHED — with no name typed, `playerConfigs`
 *      entries carry NO `name` key at all (the proxy then derives its default:
 *      the connected username for a human seat, "AI-<index>" for an AI seat),
 *  (b) a typed name rides `playerConfigs[i].name` for the "You" seat and for
 *      AI/LLM seats, trimmed; a whitespace-only name is treated as empty,
 *  (c) VALIDATION is fail-fast, not silent truncation: an over-length or
 *      illegal-character name blocks the send entirely (no create_* frame goes
 *      out) and surfaces the reason in the dialog's error line,
 *  (d) the TOURNAMENT payload behaves identically (create_tournament),
 *  (e) the ROW: the Name field renders for the You seat and for AI/LLM seats
 *      with the derived default as placeholder, and does NOT render for an
 *      open HUMAN opponent seat (its name arrives with the joiner),
 *  (f) the grid's "am I seated here?" check still recognizes a RENAMED own seat
 *      (mySeatNames), so the creator is not offered a bogus Join on their own
 *      table,
 *  (g) the llm_setup_warning seat-name match follows the typed name (the proxy
 *      names the seat with `pc.name` when present).
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import { PlayerSlotRow } from '../src/components/PlayerSlotRow'
import { validateSeatName, seatNameForWire, SEAT_NAME_MAX_LENGTH } from '../src/utils/seatNames'
import { actionForTable } from '../src/utils/tablesGrid'
import { EXPLAIN } from '../src/hints/explain'
import { LLM_CONFIG_SECTIONS } from '../src/utils/constants'
import type { PlayerSlot, TableInfo } from '../src/types'

// react-dom/server reads zustand's getServerSnapshot (the INITIAL state object).
// Mirror every setState into it so server renders see the scenario — same
// working lever as verify-thinking-shape-warning.tsx.
const ssrSnapshot = (useGameStore as unknown as { getInitialState: () => Record<string, unknown> }).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ── wire capture ──
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
  noteConnectAccepted: () => {},
} as never)

/** Re-enter a clean lobby state with N slots in the game dialog. */
function openGameSetup(slots: Array<Partial<PlayerSlot>>): void {
  sent.length = 0
  setLive({ connected: true, connStatus: '', setupError: '' })
  // showGameSetup EARLY-RETURNS while the dialog is already open (the
  // dialog-stacking guard) — close it first so every scenario starts from the
  // freshly seeded slot list, not the previous scenario's edits.
  useGameStore.getState().hideGameSetup()
  useGameStore.getState().showGameSetup()
  // Rebuild the slot list deterministically (showGameSetup seeds HUMAN/HUMAN).
  slots.forEach((patch, i) => useGameStore.getState().updatePlayerSlot(i, patch))
}

function makeSlot(type: string, isYou: boolean, extra: Partial<PlayerSlot> = {}): PlayerSlot {
  const slot: PlayerSlot = { type, isYou, deck: '', showLLMConfig: type === 'COMPUTER_LLM', llmConfig: {} }
  for (const sectionDef of LLM_CONFIG_SECTIONS) {
    for (const f of sectionDef.fields) slot.llmConfig[f.key] = f.value
  }
  return { ...slot, ...extra }
}

const createFrame = () => sent.find((m) => m.type === 'create_table')
const tourFrame = () => sent.find((m) => m.type === 'create_tournament')
const configsOf = (frame: Record<string, unknown> | undefined) =>
  (frame?.playerConfigs ?? []) as Array<Record<string, unknown>>

// ═══════════════════════════════════════════════════════════════════════════
section('Validator — the shared rules (utils/seatNames.ts)')

{
  check('empty is legal (empty = derived default)', validateSeatName('') === null)
  check('whitespace-only is legal (treated as empty)', validateSeatName('   ') === null)
  check('a plain name is legal', validateSeatName('Bob') === null)
  check('the documented character set is legal', validateSeatName("Sir Bob's Bot-2.0_X") === null)
  check(`exactly ${SEAT_NAME_MAX_LENGTH} chars is legal`, validateSeatName('x'.repeat(SEAT_NAME_MAX_LENGTH)) === null)
  const tooLong = validateSeatName('x'.repeat(SEAT_NAME_MAX_LENGTH + 1))
  check('over-length is REJECTED (no truncation)', tooLong !== null && tooLong.includes('too long'), String(tooLong))
  check('the over-length message names the limit AND the actual length',
    !!tooLong && tooLong.includes(String(SEAT_NAME_MAX_LENGTH)) && tooLong.includes(String(SEAT_NAME_MAX_LENGTH + 1)))
  const badChars = validateSeatName('bad<name>')
  check('markup characters are REJECTED', badChars !== null, String(badChars))
  check('the message names the allowed set', !!badChars && badChars.includes('letters'), String(badChars))
  const longMarkup = validateSeatName('<script>alert(1)</script>')
  check('an over-length markup string hits the length rule first (no truncation either way)',
    !!longMarkup && longMarkup.includes('too long'), String(longMarkup))
  check('a quote is rejected', validateSeatName('Bob "the" Bot') !== null)

  check('wire value is trimmed', seatNameForWire('  Bob  ') === 'Bob')
  check('no typed name -> undefined (field must be omitted)', seatNameForWire('') === undefined)
  check('whitespace-only -> undefined', seatNameForWire('   ') === undefined)
  let threw = false
  try { seatNameForWire('x'.repeat(SEAT_NAME_MAX_LENGTH + 1)) } catch { threw = true }
  check('an invalid name throws instead of reaching the wire (fail fast)', threw)

  check('the row input has an explanation entry', typeof EXPLAIN['seat-name'] === 'string' && EXPLAIN['seat-name']!.length > 20)
}

// ═══════════════════════════════════════════════════════════════════════════
section('(a) create_table — NO name typed: the payload carries NO name key (default preserved)')

{
  openGameSetup([{ deck: '4 Lightning Bolt' }, { deck: '' }])
  // Explicitly clear any name the previous scenario left behind.
  useGameStore.getState().updatePlayerSlot(0, { name: '' })
  useGameStore.getState().updatePlayerSlot(1, { name: '' })
  useGameStore.getState().startGame()
  const frame = createFrame()
  check('create_table went out', !!frame)
  const cfg = configsOf(frame)
  check('every seat is present', cfg.length === 2, JSON.stringify(cfg))
  check('the You seat has NO `name` key', !('name' in (cfg[0] ?? {})), JSON.stringify(cfg[0]))
  check('the second seat has NO `name` key', !('name' in (cfg[1] ?? {})), JSON.stringify(cfg[1]))
  check('the previous keys are unchanged on the You seat',
    cfg[0] !== undefined && cfg[0]!.type === 'HUMAN' && cfg[0]!.isYou === true && cfg[0]!.deck === '4 Lightning Bolt')
  // Whitespace-only must behave exactly like empty.
  openGameSetup([{ deck: '4 Lightning Bolt' }, { deck: '' }])
  useGameStore.getState().updatePlayerSlot(0, { name: '   ' })
  useGameStore.getState().startGame()
  const cfgWs = configsOf(createFrame())
  check('whitespace-only name -> still NO `name` key', !('name' in (cfgWs[0] ?? {})), JSON.stringify(cfgWs[0]))
}

// ═══════════════════════════════════════════════════════════════════════════
section('(b) create_table — typed names ride the wire for the You seat and for AI seats')

{
  openGameSetup([{ deck: '4 Lightning Bolt' }, { type: 'COMPUTER_MAD', showLLMConfig: false, deck: '' }])
  useGameStore.getState().updatePlayerSlot(0, { name: '  Alice  ' })
  useGameStore.getState().updatePlayerSlot(1, { name: 'Robo-Bob' })
  useGameStore.getState().startGame()
  const cfg = configsOf(createFrame())
  check('You seat name is present and TRIMMED', cfg[0]?.name === 'Alice', JSON.stringify(cfg[0]))
  check('AI seat name is present', cfg[1]?.name === 'Robo-Bob', JSON.stringify(cfg[1]))
  check('the AI seat kept its type', cfg[1]?.type === 'COMPUTER_MAD', JSON.stringify(cfg[1]))
  check('the You seat kept its deck', cfg[0]?.deck === '4 Lightning Bolt')
  check('no setupError on the happy path', useGameStore.getState().setupError === '')
}

// ═══════════════════════════════════════════════════════════════════════════
section('(c) create_table — an invalid name blocks the send and surfaces inline')

{
  openGameSetup([{ deck: '4 Lightning Bolt' }, { deck: '' }])
  useGameStore.getState().updatePlayerSlot(0, { name: 'x'.repeat(SEAT_NAME_MAX_LENGTH + 5) })
  useGameStore.getState().startGame()
  check('NO create_table frame went out', createFrame() === undefined)
  const err = useGameStore.getState().setupError
  check('the dialog error line carries the reason', err.includes('too long'), err)
  check('the error names the offending seat', err.startsWith('Your seat'), err)

  openGameSetup([{ deck: '4 Lightning Bolt' }, { type: 'COMPUTER_MAD', showLLMConfig: false, deck: '' }])
  useGameStore.getState().updatePlayerSlot(1, { name: 'bad<name>' })
  useGameStore.getState().startGame()
  check('an illegal-character AI seat name also blocks the send', createFrame() === undefined)
  check('and the error names the AI seat index', useGameStore.getState().setupError.includes('Seat 1'), useGameStore.getState().setupError)

  // The block is not a dead end: clearing the name lets the create through.
  useGameStore.getState().updatePlayerSlot(1, { name: '' })
  useGameStore.getState().startGame()
  check('clearing the bad name unblocks the create', !!createFrame())
}

// ═══════════════════════════════════════════════════════════════════════════
section('(d) create_tournament — the same contract on the tournament payload')

{
  sent.length = 0
  setLive({ connected: true, connStatus: '', setupError: '' })
  useGameStore.getState().showTournamentSetupDialog()
  // Default tournament seats: HUMAN(you) + 3x COMPUTER_MAD.
  useGameStore.getState().updateTournamentPlayerSlot(0, { deck: '4 Lightning Bolt', name: '' })
  useGameStore.getState().updateTournamentPlayerSlot(1, { deck: '', name: '' })
  useGameStore.getState().updateTournamentPlayerSlot(2, { deck: '', name: '' })
  useGameStore.getState().updateTournamentPlayerSlot(3, { deck: '', name: '' })
  useGameStore.getState().startTournament()
  let cfg = configsOf(tourFrame())
  check('create_tournament went out', !!tourFrame())
  check('no name keys when nothing was typed', cfg.every((c) => !('name' in c)), JSON.stringify(cfg))

  sent.length = 0
  useGameStore.getState().hideTournamentSetup()
  useGameStore.getState().showTournamentSetupDialog()
  useGameStore.getState().updateTournamentPlayerSlot(0, { deck: '4 Lightning Bolt', name: 'Alice' })
  useGameStore.getState().updateTournamentPlayerSlot(1, { deck: '', name: 'Bot One' })
  useGameStore.getState().startTournament()
  cfg = configsOf(tourFrame())
  check('tournament: You seat name rides the wire', cfg[0]?.name === 'Alice', JSON.stringify(cfg[0]))
  check('tournament: AI seat name rides the wire', cfg[1]?.name === 'Bot One', JSON.stringify(cfg[1]))

  sent.length = 0
  useGameStore.getState().hideTournamentSetup()
  useGameStore.getState().showTournamentSetupDialog()
  useGameStore.getState().updateTournamentPlayerSlot(0, { deck: '4 Lightning Bolt', name: 'x'.repeat(SEAT_NAME_MAX_LENGTH + 1) })
  useGameStore.getState().startTournament()
  check('tournament: an invalid name blocks the send', tourFrame() === undefined)
  check('tournament: the error surfaces in the dialog', useGameStore.getState().setupError.includes('too long'), useGameStore.getState().setupError)
}

// ═══════════════════════════════════════════════════════════════════════════
section('(e) the ROW — field presence, placeholders and the open-human-seat decision')

{
  setLive({ connected: true, connStatus: '', capabilities: { llm: true, replayScrubber: true, replaySidecar: true, serverVersion: 'x', playerTypes: [] }, proxyUsername: 'wizard' })
  const renderRow = (slot: PlayerSlot, index: number): string =>
    renderToStaticMarkup(
      <PlayerSlotRow slot={slot} index={index} canRemove={false} onUpdate={() => {}} onRemove={() => {}} onDeckFile={() => {}} />
    )

  const youRow = renderRow(makeSlot('HUMAN', true), 0)
  check('the You seat renders the Name field', youRow.includes('data-explain="seat-name"'))
  check('the You seat placeholder shows the username default', youRow.includes('placeholder="wizard"'), youRow.slice(0, 160))

  const aiRow = renderRow(makeSlot('COMPUTER_MAD', false), 1)
  check('an AI seat renders the Name field', aiRow.includes('data-explain="seat-name"'))
  check('the AI seat placeholder shows the AI-<index> default', aiRow.includes('placeholder="AI-1"'))

  const llmRow = renderRow(makeSlot('COMPUTER_LLM', false), 2)
  check('an LLM seat renders the Name field', llmRow.includes('data-explain="seat-name"'))
  check('the LLM seat placeholder shows the AI-<index> default', llmRow.includes('placeholder="AI-2"'))
  check('the LLM config block is undisturbed by the name field', llmRow.includes('llm-config-section'))

  const openHuman = renderRow(makeSlot('HUMAN', false), 1)
  check('an open HUMAN opponent seat renders NO Name field (documented scope decision)',
    !openHuman.includes('data-explain="seat-name"'))
  check('the open HUMAN seat still renders its deck affordances (row contract intact)',
    openHuman.includes('player-slot') && openHuman.includes('textarea'))

  const badRow = renderRow(makeSlot('HUMAN', true, { name: 'x'.repeat(SEAT_NAME_MAX_LENGTH + 1) }), 0)
  check('an invalid typed value renders the inline fail-fast error', badRow.includes('data-testid="seat-name-error"'))
  check('the inline error is role=alert', /role="alert"[^>]*data-testid="seat-name-error"|data-testid="seat-name-error"[^>]*role="alert"/.test(badRow))
  const goodRow = renderRow(makeSlot('HUMAN', true, { name: 'Alice' }), 0)
  check('a valid value renders NO error', !goodRow.includes('seat-name-error'))
  check('the typed value is the input value', goodRow.includes('value="Alice"'))

  // RandomDeckPicker must still be mounted inside the shared row.
  check('the RandomDeckPicker mount is undisturbed', youRow.includes('random-deck-picker'))
}

// ═══════════════════════════════════════════════════════════════════════════
section('(f) the lobby grid still recognizes a RENAMED own seat')

{
  const myTable = (seatNames: string[]): TableInfo => ({
    id: 't1', name: 'mine', gameType: 'Two Player Duel', playerCount: seatNames.length,
    maxPlayers: 4, state: 'WAITING', seatNames,
  } as TableInfo)

  check('own seat by username: enter (previous behavior preserved)',
    actionForTable(myTable(['wizard']), 't1', 'wizard', []) === 'enter')
  check('own seat by TYPED NAME: still enter (no bogus Join)',
    actionForTable(myTable(['Alice']), 't1', 'wizard', ['Alice']) === 'enter')
  check('genuinely unseated at my own open table: join is still offered',
    actionForTable(myTable(['AI-1']), 't1', 'wizard', ['Alice']) === 'join')
  check('no identity known: fail closed to enter (never a bogus Join)',
    actionForTable(myTable(['AI-1']), 't1', undefined, []) === 'enter')
}

// ═══════════════════════════════════════════════════════════════════════════
section('(g) the llm_setup_warning seat-name match follows the typed name')

{
  setLive({
    connected: true, connStatus: '',
    capabilities: { llm: true, replayScrubber: true, replaySidecar: true, serverVersion: 'x', playerTypes: [] },
    proxyUsername: 'wizard',
    // The proxy names the warned seat with `pc.name` when the seat sent one
    // (WebProxySession.handleCreateTable → SeatConfig.name → sendThinkingShapeWarnings).
    llmSetupWarning: { seat: 'Alice', message: 'Alice (glm): strict endpoint' },
    thinkingEndpointClass: { 'https://opencode.ai/zen/go/v1/chat/completions': false },
  })
  const slot: PlayerSlot = makeSlot('COMPUTER_LLM', true, {
    name: 'Alice',
    llmConfig: { ...makeSlot('COMPUTER_LLM', true).llmConfig, apiEndpoint: 'https://opencode.ai/zen/go/v1/chat/completions', thinkingEnabled: true },
  })
  const html = renderToStaticMarkup(
    <PlayerSlotRow slot={slot} index={0} canRemove={false} onUpdate={() => {}} onRemove={() => {}} onDeckFile={() => {}} />
  )
  check('the warning renders in the seat named by the TYPED name', html.includes('llm-setup-warning'), html.slice(0, 120))

  setLive({ llmSetupWarning: { seat: 'AI-0', message: 'AI-0: other seat' } })
  const html2 = renderToStaticMarkup(
    <PlayerSlotRow slot={slot} index={0} canRemove={false} onUpdate={() => {}} onRemove={() => {}} onDeckFile={() => {}} />
  )
  check('a warning for the DERIVED name does not render when a typed name is set',
    !html2.includes('llm-setup-warning'))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
;(globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(failures === 0 ? 0 : 1)
