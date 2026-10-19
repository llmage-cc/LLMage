/**
 * Event-sound system behavioural check (Java reference:
 * mage.client.util.audio.AudioManager).
 *
 * Run with:  bash scripts/verify-audio.sh
 *
 * Node-only, no browser: the REAL store, the REAL message handler and the REAL
 * audio manager are bundled with browser stubs (scripts/verify-stubs.mjs),
 * exactly like the other store-driving harnesses. HTMLAudioElement is replaced
 * by a recording fake — the same trick the priority-flash harness uses for the
 * overlay wiring — so every assertion runs against the SHIPPED trigger code.
 *
 * Encodes:
 *  (a) UNLOCK GATE — before the page's first gesture NOTHING plays and no
 *      element is even created (browser autoplay policy); after the first
 *      pointerdown the same trigger plays (the one shared mechanism extracted
 *      into audio/audioCore.ts).
 *  (b) MASTER ENABLE — audioEnabled=false suppresses both the direct call and
 *      every store-driven trigger; the store field, the persisted blob and the
 *      manager's live snapshot agree.
 *  (c) VOLUME — clamped at both ends in the store, persisted, and applied to
 *      the element as master × per-sound gain.
 *  (d) TRIGGERS — each shipped trigger fires exactly its sound id, driven
 *      through the REAL paths:
 *        game start (new vs rejoin re-announcement),
 *        stack growth (vs resolve / vs first push),
 *        game over win / loss (vs partial payload),
 *        incoming whisper (vs whisper_to / talk),
 *        seat churn joined / left / can-start (baseline + empty-first guards),
 *        skip armed / cancelled (via the ws send path).
 *  (e) NO DOUBLE-FIRE — a prompt arrival rings the priority DING (oscillator on
 *      the shared context) and NO event sound; the ding also keeps its own
 *      independent enable.
 *  (f) REGISTRY — the reset registry covers the new keys (Reset to Default
 *      restores them; settingsPresets' exhaustive setter map compiles).
 *  (g) RELOAD SURVIVAL — the game-start guard's known-game id persists in
 *      sessionStorage, so a rejoin after an F5 (module memory wiped, storage
 *      intact) is NOT treated as a new game: no cue, no prompt-hide, no log
 *      reset. Positive controls: a genuinely new game still sounds and resets
 *      the log; the rejoin-offer path covers a fresh second tab (empty
 *      storage); forgetGameKey runs on game_over / logout / disconnect.

 */
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import type { GameEvent, GameState, Player, ServerMessage, TableInfo } from '../src/types'
import {
  AUDIO_SOUNDS,
  SOUND_BASE_URL,
  SOUND_IDS,
  clampVolume,
  getAudioSettings,
  play,
  resetAudioRuntime,
  type SoundId,
} from '../src/audio/audioManager'
import { installAudioUnlock } from '../src/audio/audioCore'
import { getDingSettings, setDingSettings, resetDingSettings } from '../src/store/slices/priorityDing'
import { forgetGameKey } from '../src/store/slices/gameplaySlice'
import { resetSettingsToDefaults } from '../src/utils/settingsDefaults'

declare const process: { exit(code: number): void }

// ── sessionStorage stub (per-tab store — the reload-survival seam) ──
// The fixed page's known-game key must survive a reload exactly as the browser
// does it: sessionStorage is per-tab and outlives F5. Same stub shape as
// verify-logout.tsx / verify-connect-target-toggle.tsx.
const sessionStore = new Map<string, string>()
;(globalThis as unknown as { sessionStorage: unknown }).sessionStorage = {
  getItem: (k: string) => (sessionStore.has(k) ? sessionStore.get(k)! : null),
  setItem: (k: string, v: string) => sessionStore.set(k, String(v)),
  removeItem: (k: string) => sessionStore.delete(k),
}

let failures = 0
function check(name: string, cond: boolean, detail = ''): void {
  if (!cond) failures++
  console.log(`  [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' :: ' + detail : ''}`)
}
function section(t: string): void {
  console.log(`\n## ${t}`)
}

// ── Fake clock (per-sound debounce is time-based) ──
let fakeNow = 1_000_000
const realDateNow = Date.now
Date.now = () => fakeNow
const advance = (ms: number) => {
  fakeNow += ms
}

// ── Recording HTMLAudioElement + WebAudio stubs ──
// The manager constructs `new Audio(src)` lazily per sound and plays via
// element.play(); the ding synth (used in scenario e) schedules oscillators on
// the shared AudioContext. Both are recorded here.
interface PlayedSound {
  id: SoundId
  volume: number
}
const srcToId = new Map<string, SoundId>()
for (const id of SOUND_IDS) srcToId.set(SOUND_BASE_URL + AUDIO_SOUNDS[id].file, id)

const created: Array<{ src: string }> = []
const played: PlayedSound[] = []
let oscStarts = 0

class FakeAudioElement {
  src: string
  preload = ''
  currentTime = 0
  volume = 1
  constructor(src?: string) {
    this.src = src ?? ''
    created.push({ src: this.src })
  }
  load(): void {}
  play(): Promise<void> {
    const id = srcToId.get(this.src)
    if (id) played.push({ id, volume: this.volume })
    return Promise.resolve()
  }
  pause(): void {}
}

class FakeGainNode {
  gain = {
    value: 0,
    setValueAtTime: () => {},
    linearRampToValueAtTime: () => {},
    exponentialRampToValueAtTime: () => {},
  }
  connect(dest: unknown): unknown {
    return dest
  }
}
class FakeOscillatorNode {
  type = 'sine'
  frequency = { value: 0 }
  connect(dest: unknown): unknown {
    return dest
  }
  start(): void {
    oscStarts++
  }
  stop(): void {}
}
class FakeAudioContext {
  state = 'running'
  currentTime = 0
  destination = {}
  createGain(): FakeGainNode {
    return new FakeGainNode()
  }
  createOscillator(): FakeOscillatorNode {
    return new FakeOscillatorNode()
  }
  resume(): Promise<void> {
    return Promise.resolve()
  }
}

;(globalThis as unknown as { Audio: unknown }).Audio = FakeAudioElement
;(globalThis as unknown as { AudioContext: unknown }).AudioContext = FakeAudioContext

// ── document event capture so the harness can fire the FIRST GESTURE ──
// verify-stubs.mjs installs an inert document; the app's unlock installs its
// listeners on it, so the harness swaps in a recorder first (same pattern as
// verify-priority-flash-sustain.ts).
const docListeners = new Map<string, Set<(e: Event) => void>>()
;(document as unknown as { addEventListener: (t: string, f: (e: Event) => void) => void }).addEventListener =
  (t, f) => {
    if (!docListeners.has(t)) docListeners.set(t, new Set())
    docListeners.get(t)!.add(f)
  }
;(document as unknown as { removeEventListener: (t: string, f: (e: Event) => void) => void }).removeEventListener =
  (t, f) => {
    docListeners.get(t)?.delete(f)
  }
const fireGesture = () => {
  for (const fn of docListeners.get('pointerdown') ?? []) fn(new Event('pointerdown'))
}

// ── Fake WebSocket (records the skip sends; the trigger rides these actions) ──
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: Record<string, unknown>) => {
    sent.push(m)
  },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
} as never)

const store = useGameStore
const deliver = (msg: ServerMessage) => handleMessage(msg, store.getState())

/** Clear all recording between scenarios (and the debounce memory with it). */
function resetRecorder(): void {
  created.length = 0
  played.length = 0
  oscStarts = 0
  sent.length = 0
  resetAudioRuntime()
}

// ── GameState fixtures (same shape as verify-priority-flash-sustain.ts) ──
const SKIP_FLAGS_OFF = {
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
} as const

const player = (name: string, isMe: boolean): Player => ({
  name, playerId: name, life: 20, isMe, libraryCount: 40, handCount: 7,
  isActive: false, hasPriority: false, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false, manaPool: null,
  counters: [], commanders: [], battlefield: [], hand: [], graveyard: [], exile: [],
  statesSavedSize: 1, ...SKIP_FLAGS_OFF,
})

const gameState = (stackLen: number, seq: number): GameState => ({
  status: 'playing', turn: 3, phase: null, step: null,
  activePlayer: 'QA-A', priorityPlayer: 'QA-A',
  stack: Array.from({ length: stackLen }, (_, i) => ({ id: `stack-${i}`, name: 'Shock' })) as GameState['stack'],
  combat: [], players: [player('QA-A', true), player('QA-B', false)],
  llmDebug: [], revealed: [], lookedAt: [], stateSeq: seq,
})

const tableRow = (seatNames: string[], maxPlayers = 4): TableInfo => ({
  id: 'T1', name: 'My Table', gameType: 'Two Player Duel',
  playerCount: seatNames.length, maxPlayers, state: 'WAITING', seatNames,
})

const chatEvent = (chatType: string): GameEvent => ({
  seq: 1, type: 'talk', message: 'psst', from: 'QA-B', chatType,
})

// ═════════════════════════════════════════════════════════════════════════════

section('(a) unlock gate — nothing plays before the page\'s first gesture')
{
  resetRecorder()
  // Direct call, pre-gesture.
  const direct = play('game-start')
  check('direct play() before the first gesture returns false', direct === false)
  check('no element was created pre-gesture', created.length === 0, String(created.length))
  // Same through a real wire path.
  deliver({ type: 'start_game', gameId: 'GATE-GAME' } as ServerMessage)
  advance(5000)
  check('a store-driven trigger pre-gesture stays silent too', created.length === 0 && played.length === 0)

  // The first gesture (installed exactly as App.tsx does it) unlocks.
  installAudioUnlock()
  check('the unlock installs pointerdown + keydown listeners', (docListeners.get('pointerdown')?.size ?? 0) === 1 && (docListeners.get('keydown')?.size ?? 0) === 1)
  fireGesture()
  advance(1000)
  resetRecorder()
  const after = play('game-start')
  check('after the first gesture play() returns true', after === true)
  check('the element is created with the served path', created[0]?.src === '/sounds/OnGameStart.wav', String(created[0]?.src))
  check('the element was actually played', played.length === 1 && played[0]!.id === 'game-start', JSON.stringify(played))
}

section('(b) master enable — audioEnabled=false suppresses playback')
{
  resetRecorder()
  store.getState().setAudioEnabled(false)
  const direct = play('game-start')
  check('direct play() with audio disabled returns false', direct === false)
  check('no element played while disabled', played.length === 0)
  deliver({ type: 'game_over', result: 'You won', hasWon: true, complete: true } as ServerMessage)
  advance(5000)
  check('a store-driven trigger is suppressed while disabled', played.length === 0, JSON.stringify(played))
  check('the store field mirrors false', store.getState().audioEnabled === false)
  check('the manager snapshot mirrors false', getAudioSettings().enabled === false)
  const persisted = JSON.parse(localStorage.getItem('xmage-settings')!) as { audioEnabled?: boolean }
  check('the persisted xmage-settings blob carries audioEnabled=false', persisted.audioEnabled === false, JSON.stringify(persisted))

  store.getState().setAudioEnabled(true)
  check('re-enabling flips both mirrors', store.getState().audioEnabled === true && getAudioSettings().enabled === true)
}

section('(c) volume — clamped in the store, persisted, applied as master × gain')
{
  resetRecorder()
  store.getState().setAudioVolume(5)
  check('volume above 1 clamps to 1 (store)', store.getState().audioVolume === 1, String(store.getState().audioVolume))
  check('volume above 1 clamps to 1 (module)', getAudioSettings().volume === 1)
  store.getState().setAudioVolume(-2)
  check('volume below 0 clamps to 0 (store)', store.getState().audioVolume === 0, String(store.getState().audioVolume))
  check('clampVolume is the same rule the manager applies', clampVolume(0.9999) === 0.9999 && clampVolume(NaN) === 0)

  // The element gets master × per-sound gain: 0.5 × 0.85 (GameCanStart) = 0.425.
  store.getState().setAudioVolume(0.5)
  const persistedMid = JSON.parse(localStorage.getItem('xmage-settings')!) as { audioVolume?: number }
  check('the mid volume is persisted', persistedMid.audioVolume === 0.5, String(persistedMid.audioVolume))
  store.getState().setTableCreated('T1', 'My Table')
  advance(10_000)
  store.getState().setTables([tableRow(['me'])])
  advance(10_000)
  store.getState().setTables([tableRow(['me', 'opp', 'c', 'd'])])
  const canStart = played.find((p) => p.id === 'game-can-start')
  check('game-can-start element volume is master × gain', canStart !== undefined && Math.abs(canStart.volume - 0.425) < 1e-9, JSON.stringify(played))
  const full = played.filter((p) => p.id === 'player-lost' || p.id === 'player-joined')
  check('volume clamped sounds never exceed 1', played.every((p) => p.volume <= 1), JSON.stringify(full))
  store.getState().setAudioVolume(0.6)
}

section('(d) triggers — each shipped trigger fires exactly its sound id')
{
  // ── d1: game start (new game fires; a rejoin re-announcement does not) ──
  resetRecorder()
  advance(10_000)
  deliver({ type: 'start_game', gameId: 'G-1' } as ServerMessage)
  check('start_game (new) fires game-start', played.length === 1 && played[0]!.id === 'game-start', JSON.stringify(played))
  advance(10_000)
  deliver({ type: 'start_game', gameId: 'G-1' } as ServerMessage)
  check('a rejoin re-announcement of the SAME gameId is silent', played.length === 1, JSON.stringify(played))
  advance(10_000)
  deliver({ type: 'start_game', gameId: 'G-2' } as ServerMessage)
  check('the next NEW game fires again', played.length === 2 && played[1]!.id === 'game-start', JSON.stringify(played))

  // ── d2: stack growth (vs resolve vs first push) ──
  resetRecorder()
  advance(10_000)
  deliver({ type: 'game_init', state: gameState(0, 1) } as ServerMessage)
  check('the first state push (rejoin/spectate) is silent', played.length === 0, JSON.stringify(played))
  advance(10_000)
  deliver({ type: 'game_update', state: gameState(1, 2) } as ServerMessage)
  check('a stack length increase fires stack-new', played.length === 1 && played[0]!.id === 'stack-new', JSON.stringify(played))
  advance(10_000)
  deliver({ type: 'game_update', state: gameState(1, 3) } as ServerMessage)
  check('an unchanged stack is silent', played.length === 1, JSON.stringify(played))
  advance(10_000)
  deliver({ type: 'game_update', state: gameState(0, 4) } as ServerMessage)
  check('a stack RESOLVE (length drop) is silent', played.length === 1, JSON.stringify(played))

  // ── d3: game over win / loss / partial ──
  resetRecorder()
  advance(10_000)
  deliver({ type: 'game_over', result: 'You lost the game', hasWon: false, complete: true } as ServerMessage)
  check('game_over with hasWon=false fires player-lost', played.length === 1 && played[0]!.id === 'player-lost', JSON.stringify(played))
  advance(10_000)
  deliver({ type: 'game_over', result: 'You won the game', hasWon: true, complete: true } as ServerMessage)
  check('game_over with hasWon=true fires player-won', played.length === 2 && played[1]!.id === 'player-won', JSON.stringify(played))
  advance(10_000)
  deliver({ type: 'game_over', result: 'The game has ended', complete: false } as ServerMessage)
  check('a PARTIAL game_over (no hasWon) is silent', played.length === 2, JSON.stringify(played))

  // ── d4: whisper (incoming only) ──
  resetRecorder()
  advance(10_000)
  deliver({ type: 'event', event: chatEvent('whisper_to') } as ServerMessage)
  check('an outgoing whisper (whisper_to) is silent', played.length === 0, JSON.stringify(played))
  deliver({ type: 'event', event: chatEvent('talk') } as ServerMessage)
  check('table talk is silent', played.length === 0, JSON.stringify(played))
  advance(10_000)
  deliver({ type: 'event', event: chatEvent('whisper_from') } as ServerMessage)
  check('an incoming whisper fires player-whispered', played.length === 1 && played[0]!.id === 'player-whispered', JSON.stringify(played))

  // ── d5: seat churn at MY table ──
  resetRecorder()
  advance(10_000)
  store.getState().setTableCreated('T1', 'My Table')
  // Clear the rows scenario (c) left behind FIRST: with no T1 row in the list,
  // the replacement is not a seat change (the hook needs a before-row).
  store.getState().setTables([])
  check('a replacement with no seated row is silent', played.length === 0, JSON.stringify(played))
  store.getState().setTables([tableRow(['me'])])
  check('the first observation of the table is the baseline (silent)', played.length === 0, JSON.stringify(played))
  advance(10_000)
  store.getState().setTables([tableRow(['me', 'opp'])])
  check('a seat increase fires player-joined', played.length === 1 && played[0]!.id === 'player-joined', JSON.stringify(played))
  advance(10_000)
  store.getState().setTables([tableRow(['me', 'opp', 'c'])])
  check('a second seat increase fires player-joined again', played.length === 2 && played[1]!.id === 'player-joined', JSON.stringify(played))
  advance(10_000)
  store.getState().setTables([tableRow(['me', 'opp', 'c', 'd'])])
  check('reaching maxPlayers fires game-can-start (not joined)', played.length === 3 && played[2]!.id === 'game-can-start', JSON.stringify(played))
  advance(10_000)
  store.getState().setTables([tableRow(['me', 'opp', 'c'])])
  check('a seat decrease fires player-left', played.length === 4 && played[3]!.id === 'player-left', JSON.stringify(played))
  advance(10_000)
  // Unrelated table churn stays silent (only myTableId is watched).
  store.getState().setTables([tableRow(['me', 'opp', 'c']), { ...tableRow(['x', 'y']), id: 'T9' }])
  check('churn on a table this session is NOT seated at is silent', played.length === 4, JSON.stringify(played))
  // Empty-first guard: 0 → 1 seats is not "someone joined" (desktop count>0 rule).
  advance(10_000)
  store.getState().setTables([{ ...tableRow([]), id: 'T1' }])
  check('the drop to zero seats fires player-left only once more', played.length === 5 && played[4]!.id === 'player-left', JSON.stringify(played))
  advance(10_000)
  store.getState().setTables([{ ...tableRow(['me']), id: 'T1' }])
  check('0 → 1 seats is silent (no baseline to hear a join from)', played.length === 5, JSON.stringify(played))

  // ── d6: skip armed / cancelled ──
  resetRecorder()
  advance(10_000)
  store.getState().skipPriority('next_turn')
  check('skipPriority fires skip-armed', played.length === 1 && played[0]!.id === 'skip-armed', JSON.stringify(played))
  check('the skip_priority message still goes out', sent.some((m) => m.type === 'skip_priority' && m.skip === 'next_turn'), JSON.stringify(sent))
  advance(10_000)
  store.getState().cancelSkip()
  check('cancelSkip fires skip-cancelled', played.length === 2 && played[1]!.id === 'skip-cancelled', JSON.stringify(played))
  check('the cancel_skip message still goes out', sent.some((m) => m.type === 'cancel_skip'), JSON.stringify(sent))

  // ── d7: per-sound debounce coalesces bursts ──
  resetRecorder()
  store.getState().skipPriority('end_of_turn')
  const first = played.length
  store.getState().skipPriority('end_of_turn')
  store.getState().skipPriority('end_of_turn')
  check('a burst of the same sound coalesces to one cue', played.length === first && first === 1, JSON.stringify(played))
  advance(200)
  store.getState().skipPriority('end_of_turn')
  check('after the per-sound interval it plays again', played.length === 2, JSON.stringify(played))
}

section('(e) no double-fire — prompt arrival rings the ding, never an event sound')
{
  resetRecorder()
  advance(10_000)
  // Ding on, flash off so only the sound channel is observable.
  setDingSettings({ enabled: true, flashEnabled: false, volume: 0.5, variant: 'ding', focusMode: 'always' })
  deliver({
    type: 'action_prompt',
    action_type: 'select',
    message: 'Play spells and abilities',
    choices: [],
  } as unknown as ServerMessage)
  check('the priority ding sounded (oscillator scheduled)', oscStarts > 0, String(oscStarts))
  check('NO event sound fired on the prompt arrival', played.length === 0, JSON.stringify(played))
  check('the ding keeps its own enable switch', getDingSettings().enabled === true)
  // And the ding's own setting still mutes it independently of the event layer.
  resetRecorder()
  setDingSettings({ enabled: false })
  advance(10_000)
  deliver({
    type: 'action_prompt',
    action_type: 'select',
    message: 'Second question',
    choices: [],
  } as unknown as ServerMessage)
  check('ding disabled: no oscillator and still no event sound', oscStarts === 0 && played.length === 0)
  resetDingSettings()
  setDingSettings({ flashEnabled: true })
}

section('(f) registry — Reset to Default covers the audio keys')
{
  store.getState().setAudioEnabled(false)
  store.getState().setAudioVolume(0.1)
  resetSettingsToDefaults(store.getState())
  check('Reset restores audioEnabled=true', store.getState().audioEnabled === true)
  check('Reset restores audioVolume=0.6', store.getState().audioVolume === 0.6, String(store.getState().audioVolume))
  check('Reset reaches the manager snapshot too', getAudioSettings().enabled === true && getAudioSettings().volume === 0.6, JSON.stringify(getAudioSettings()))
  check('every shipped sound id has an element spec', SOUND_IDS.every((id) => typeof AUDIO_SOUNDS[id].file === 'string'))
}

section('(g) reload survival — post-reload rejoin is not a new game')
{
  // The defect: module memory held the known gameId, an F5 wiped it, and the
  // rejoin's start_game re-announcement (SAME id) looked like a new game —
  // replaying the game-start cue and (with the same seam) hiding a live prompt
  // and resetting the log. The known id now persists in sessionStorage, which a
  // reload preserves. Model the post-reload state exactly: module memory has no
  // knowledge, storage carries the pre-reload fact.
  forgetGameKey()                       // clean slate (module + storage)
  sessionStore.set('xmage-active-game', 'G-RELOAD')  // what the pre-reload page wrote
  resetRecorder()
  advance(10_000)
  deliver({ type: 'start_game', gameId: 'G-RELOAD' } as ServerMessage)
  check('post-reload rejoin of the SAME gameId is silent (no game-start cue)', played.length === 0, JSON.stringify(played))

  // Positive control: a genuinely NEW game (never-seen id) still sounds.
  resetRecorder()
  advance(10_000)
  deliver({ type: 'start_game', gameId: 'G-NEW-AFTER-RELOAD' } as ServerMessage)
  check('a genuinely new game still fires game-start', played.length === 1 && played[0]!.id === 'game-start', JSON.stringify(played))

  // Log-keep (F-A14): a rejoin keeps the log; a new game resets it.
  useGameStore.setState({ eventLog: [] })
  sessionStore.set('xmage-active-game', 'G-LOG-KEEP')
  forgetGameKey()                       // module clean; re-populate storage only
  sessionStore.set('xmage-active-game', 'G-LOG-KEEP')
  deliver({ type: 'start_game', gameId: 'G-LOG-KEEP' } as ServerMessage)
  deliver({ type: 'event', event: { seq: 1, type: 'action', message: 'rejoin event 1' } } as ServerMessage)
  deliver({ type: 'event', event: { seq: 2, type: 'action', message: 'rejoin event 2' } } as ServerMessage)
  check('post-reload rejoin keeps the log (no reset armed)', useGameStore.getState().eventLog.length === 2,
    JSON.stringify(useGameStore.getState().eventLog.map(e => e.message)))
  deliver({ type: 'start_game', gameId: 'G-LOG-RESET' } as ServerMessage)
  deliver({ type: 'event', event: { seq: 3, type: 'action', message: 'new game event 1' } } as ServerMessage)
  check('a genuinely new game still resets the log', useGameStore.getState().eventLog.length === 1
    && useGameStore.getState().eventLog[0]!.message === 'new game event 1',
    JSON.stringify(useGameStore.getState().eventLog.map(e => e.message)))

  // Prompt-keep: a rejoin must NOT hide a live prompt; a new game must.
  forgetGameKey()
  sessionStore.set('xmage-active-game', 'G-PROMPT')
  deliver({
    type: 'action_prompt', action_type: 'select', message: 'Priority', choices: [],
  } as unknown as ServerMessage)
  check('prompt installed', useGameStore.getState().actionPrompt != null)
  deliver({ type: 'start_game', gameId: 'G-PROMPT' } as ServerMessage)
  check('post-reload rejoin does NOT hide a live prompt', useGameStore.getState().actionPrompt != null)
  deliver({
    type: 'action_prompt', action_type: 'select', message: 'Priority again', choices: [],
  } as unknown as ServerMessage)
  deliver({ type: 'start_game', gameId: 'G-PROMPT-NEW' } as ServerMessage)
  check('a genuinely new game still hides the previous prompt', useGameStore.getState().actionPrompt == null)

  // Fresh second tab: no storage, no memory — but the proxy OFFERS the rejoin
  // first (`rejoin_game`), which records the id before start_game follows.
  forgetGameKey()
  deliver({ type: 'rejoin_game', gameId: 'G-SECOND-TAB', tableId: 'T1' } as unknown as ServerMessage)
  resetRecorder()
  advance(10_000)
  deliver({ type: 'start_game', gameId: 'G-SECOND-TAB' } as ServerMessage)
  check('rejoin offered via rejoin_game is silent when start_game follows', played.length === 0, JSON.stringify(played))

  // Lifecycle clears: game_over / logout / disconnect forget the stored id.
  sessionStore.set('xmage-active-game', 'G-OVER')
  deliver({ type: 'game_over', result: 'The game has ended', complete: false } as ServerMessage)
  check('game_over forgets the stored game id', !sessionStore.has('xmage-active-game'))

  deliver({ type: 'start_game', gameId: 'G-LOGOUT' } as ServerMessage)
  check('a live game records its id for the reload', sessionStore.get('xmage-active-game') === 'G-LOGOUT')
  store.getState().logout()
  check('logout forgets the stored game id', !sessionStore.has('xmage-active-game'))

  deliver({ type: 'start_game', gameId: 'G-DISCONNECT' } as ServerMessage)
  check('a live game records its id (disconnect case)', sessionStore.get('xmage-active-game') === 'G-DISCONNECT')
  store.getState().disconnect()
  check('disconnect forgets the stored game id', !sessionStore.has('xmage-active-game'))

  // A stale id can never mark a NEW game as rejoin: ids are exact-match UUIDs.
  sessionStore.set('xmage-active-game', 'G-STALE-OLD-GAME')
  resetRecorder()
  advance(10_000)
  deliver({ type: 'start_game', gameId: 'G-BRAND-NEW' } as ServerMessage)
  check('a stale stored id does not suppress a different new game',
    played.length === 1 && played[0]!.id === 'game-start', JSON.stringify(played))

  forgetGameKey()
}

Date.now = realDateNow

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
