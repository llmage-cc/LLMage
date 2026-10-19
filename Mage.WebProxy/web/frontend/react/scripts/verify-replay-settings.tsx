/**
 * Standalone behavioural check: the REPLAY VIEW's settings button and keyboard
 * hand-back.
 *
 * Run with:  bash scripts/verify-replay-settings.sh
 *
 * The ask: the live board's header carries ⚙ Settings (GameHeader.tsx), the
 * replay viewer's header did not — even though SettingsDialog is a global mount
 * (`App.tsx`) and display/highlight preferences apply to a replay board exactly
 * as they do live.
 *
 * The fix is not just a button: opening the dialog INSIDE the replay introduced
 * keyboard ownership. The viewer's document-level keydown handler claimed
 * Space / arrows / Escape unconditionally, so with the dialog (or the rail's
 * chat input, or a context menu) on screen those keys were stolen:
 *   - Space and arrows are dialog controls (sliders/selects), and typing a chat
 *     message could not even produce a space character;
 *   - Escape would drop the whole viewer out from under an open dialog.
 * The mapping moved into the pure, exported `replayHotkey()` (same split as
 * LLMConfigTabs.tabAfterKey): the dialog owns every key but Escape (which
 * dismisses IT), typing targets keep their keys, an open context menu takes its
 * own Escape (the shell closes it — one Escape must not do two things), and
 * otherwise Space/arrows/Escape behave exactly as before.
 *
 * Layers:
 *   (a) the pure key matrix (replayHotkey + isTypingTarget),
 *   (b) SSR renders of the REAL ReplayView (react-dom/server): the header shows
 *       ⚙ Settings next to ✕ Exit Replay, in BOTH the loaded and the loading
 *       state, and the shared explain id resolves (the explain-coverage gate
 *       treats the live header's gear with this id — one entry, two surfaces);
 *   (c) source-wiring greps (SSR markup cannot carry event handlers): the
 *       button's onClick, the keydown effect's hand-back arguments, the
 *       dialog's global mount, and the preserved invariants (exit button,
 *       playback controls, the .game-menu auto margin the header-gap fix
 *       relies on).
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import ReplayView, { replayHotkey, isTypingTarget } from '../src/components/ReplayView'
import { EXPLAIN } from '../src/hints/explain'
import type { GameState, Player } from '../src/types'

declare const process: { env: Record<string, string | undefined>; exit(code: number): void }
const HERE = process.env.XMAGE_VERIFY_DIR ?? dirname(fileURLToPath(import.meta.url))
const REACT = join(HERE, '..')

// rootZoom() (utils/viewport.ts) reads getComputedStyle(document.documentElement).zoom.
// The Node stubs provide documentElement but not getComputedStyle; identity zoom (1)
// is the correct answer for a headless render — it is what an unzoomed page reports.
;(globalThis as unknown as { getComputedStyle: () => { zoom: number } }).getComputedStyle =
  () => ({ zoom: 1 })

// react-dom/server reads zustand's getServerSnapshot, which is
// `selector(api.getInitialState())` — the store's INITIAL state object, NOT the
// live state (zustand v5 react.mjs wires the third useSyncExternalStore argument
// to api.getInitialState). Assigning useGameStore.getInitialState does not reach
// the api closure, so the working lever is the snapshot object itself: it is
// returned by reference, so mutating it (in lockstep with the live setState)
// is what makes each server render below see the delivered scenario.
// (Same pattern as verify-token-menu-hides-preview.tsx.)
const ssrSnapshot = (
  useGameStore as unknown as { getInitialState: () => Record<string, unknown> }
).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

const read = (rel: string) => readFileSync(join(REACT, rel), 'utf8')
const replaySrc = read('src/components/ReplayView.tsx')
const appSrc = read('src/App.tsx')
const gameHeaderSrc = read('src/components/GameHeader.tsx')

// ─────────────────────────────────────────────────────────────────────────────
section('A. the pure key matrix — the dialog/menu/typing hand-back')

{
  const idle = { showSettings: false, contextMenuOpen: false, typing: false }
  check('idle: Space toggles play', replayHotkey(' ', idle) === 'play')
  check('idle: ArrowRight steps forward', replayHotkey('ArrowRight', idle) === 'next')
  check('idle: ArrowLeft steps back', replayHotkey('ArrowLeft', idle) === 'previous')
  check('idle: Escape exits the viewer', replayHotkey('Escape', idle) === 'exit')
  check('idle: an unrelated key does nothing', replayHotkey('x', idle) === null)
  check('idle: Enter is not the viewer\u2019s key', replayHotkey('Enter', idle) === null)

  const dlg = { ...idle, showSettings: true }
  check('dialog open: Escape CLOSES THE DIALOG (not the viewer)',
    replayHotkey('Escape', dlg) === 'close-settings')
  check('dialog open: Space stands down (sliders/buttons own it)', replayHotkey(' ', dlg) === null)
  check('dialog open: ArrowRight stands down', replayHotkey('ArrowRight', dlg) === null)
  check('dialog open: ArrowLeft stands down', replayHotkey('ArrowLeft', dlg) === null)

  const typing = { ...idle, typing: true }
  check('typing: Space reaches the field (no play toggle, no preventDefault)',
    replayHotkey(' ', typing) === null)
  check('typing: ArrowLeft moves the caret (no step)', replayHotkey('ArrowLeft', typing) === null)
  check('typing: ArrowRight moves the caret (no step)', replayHotkey('ArrowRight', typing) === null)
  check('typing: Escape does not kill the viewer mid-typing',
    replayHotkey('Escape', typing) === null)

  const menu = { ...idle, contextMenuOpen: true }
  check('context menu open: Escape is the MENU\u2019s (no double-close)',
    replayHotkey('Escape', menu) === null)
  check('context menu open: Space still plays (the menu is mouse-driven)',
    replayHotkey(' ', menu) === 'play')
  check('context menu open: arrows still step', replayHotkey('ArrowRight', menu) === 'next')

  // The dialog outranks typing: modal semantics — Escape dismisses it even from
  // inside its own text field.
  check('dialog open + focus in its input: Escape still closes the dialog',
    replayHotkey('Escape', { showSettings: true, contextMenuOpen: false, typing: true }) === 'close-settings')
}

section('B. isTypingTarget — where the keys must go to the field, not the viewer')

{
  check('input is a typing target', isTypingTarget({ tagName: 'INPUT' }))
  check('textarea is a typing target', isTypingTarget({ tagName: 'TEXTAREA' }))
  check('select is a typing target', isTypingTarget({ tagName: 'SELECT' }))
  check('contenteditable is a typing target', isTypingTarget({ tagName: 'DIV', isContentEditable: true }))
  check('a plain div is not', !isTypingTarget({ tagName: 'DIV' }))
  check('null target is not', !isTypingTarget(null))
  check('a non-element target (no tagName) is not', !isTypingTarget({}))
}

// ─────────────────────────────────────────────────────────────────────────────
section('C. SSR render — the REAL ReplayView shows the header gear in both states')

const player = (name: string, isMe: boolean): Player => ({
  name, playerId: name, life: 20, isMe, libraryCount: 40, handCount: 0,
  isActive: false, hasPriority: false, hasLeft: false, timerActive: false,
  priorityTimeLeftSecs: 0, isMonarch: false, hasInitiative: false,
  passedTurn: false, passedUntilEndOfTurn: false, passedUntilNextMain: false,
  passedAllTurns: false, passedUntilStackResolved: false, passedUntilEndStepBeforeMyTurn: false,
  manaPool: null, counters: [], commanders: [], emblems: [], planes: [],
  battlefield: [], hand: [], graveyard: [], exile: [],
} as unknown as Player)

const frame = (): GameState => ({
  status: 'playing', turn: 5, phase: 'PRECOMBAT_MAIN', step: null,
  activePlayer: 'QA-A', priorityPlayer: 'QA-A',
  stack: [], combat: [], players: [player('QA-A', false), player('QA-B', false)],
  llmDebug: [], revealed: [], lookedAt: [], stateSeq: 9,
} as unknown as GameState)

const renderReplay = (): string => renderToStaticMarkup(<ReplayView />)

// Scenario 1: a replay is loaded (the normal viewing state).
setLive({
  inReplay: true, replayGameId: 'g-123', replayState: frame(),
  replayStateIndex: 3, replayStateCount: 100, replayFrame: 4,
  replayPlaying: false, replayLoading: false, replayDone: null, replayLastDirection: null,
})
{
  const html = renderReplay()
  check('replay header renders', html.includes('🎬 Replaying g-123'), html.slice(0, 120))
  check('the ⚙ Settings button is in the replay header', html.includes('⚙ Settings'))
  check('it carries the shared explain id (settings-gear)', html.includes('data-explain="settings-gear"'))
  check('the ✕ Exit Replay button is still there', html.includes('✕ Exit Replay') && html.includes('data-explain="replay-exit"'))
  const header = html.slice(html.indexOf('game-header-bar'), html.indexOf('replay-controls'))
  check('both live in the header menu cluster', header.includes('settings-gear') && header.includes('replay-exit'))
  check('Settings sits BEFORE Exit (mirrors the live header order)',
    header.indexOf('settings-gear') < header.indexOf('replay-exit'))
  check('the playback controls are untouched (prev/play/next/skip/stop)',
    ['replay-prev', 'replay-play', 'replay-next', 'replay-skip', 'replay-stop']
      .every((id) => html.includes(`data-explain="${id}"`)))
  check('the header keeps the .game-menu auto margin the header-gap fix relies on',
    /class="game-menu"[^>]*margin-left:auto/.test(html), html.slice(header.indexOf('game-menu'), header.indexOf('game-menu') + 120))
}

// Scenario 2: the loading state (no frame yet) — the gear must be there too,
// because theme/size choices are most wanted exactly then.
setLive({ inReplay: true, replayGameId: 'g-456', replayState: null, replayLoading: true })
{
  const html = renderReplay()
  check('loading state renders', html.includes('🎬 Loading replay g-456'), html.slice(0, 120))
  check('the gear is offered while the replay loads too', html.includes('data-explain="settings-gear"'))
}

// The shared registry entry must exist (the source scan in
// verify-explain-coverage.sh throws on an unknown id; this pins the text).
check('EXPLAIN[\'settings-gear\'] is registered (shared with the live header)',
  typeof EXPLAIN['settings-gear'] === 'string' && EXPLAIN['settings-gear']!.includes('Settings'))

// ─────────────────────────────────────────────────────────────────────────────
section('D. source wiring (SSR markup cannot carry handlers — checked on the file)')

check('the button opens the dialog via the store',
  /setShowSettings\(true\)/.test(replaySrc) && /data-explain="settings-gear"/.test(replaySrc))
check('the keydown effect routes through replayHotkey()', /replayHotkey\(/.test(replaySrc))
check('...and hands back while the dialog is open', /showSettings: st\.showSettings/.test(replaySrc))
check('...and hands back while a context menu owns Escape', /contextMenuOpen: st\.openContextMenu != null/.test(replaySrc))
check('...and hands back while typing', /isTypingTarget\(/.test(replaySrc))
check('dialog Escape path calls setShowSettings(false)', /setShowSettings\(false\)/.test(replaySrc))
check('the viewer still stops on the exit path', /stopReplay\(\)/.test(replaySrc))

check('App.tsx mounts SettingsDialog globally (no per-view gate)',
  /<SettingsDialog \/>/.test(appSrc) && !/view !== 'replay' && <SettingsDialog/.test(appSrc))
check('the LIVE header still offers its gear (no rename/removal)',
  /data-explain="settings-gear"/.test(gameHeaderSrc) && /setShowSettings\(true\)/.test(gameHeaderSrc))

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
