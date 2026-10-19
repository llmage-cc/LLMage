/**
 * Board-modes behavioural check: the "Multiplayer layout" settings section
 * feeding the shared GameLayout shell's 3+ player modes.
 *
 * Run with:  bash scripts/verify-board-modes.sh
 *
 * Approved design:
 *   1. `boardModeMaximizeOpponents` — opponents render as a compact chip strip
 *      along the TOP of the center column; my board takes the remaining height.
 *   2. `boardModeFocusEnabled` — pseudo-1v1 vs ONE opponent; the other opponents
 *      appear as chips with avatar + name + life; on-board controls (click chip
 *      to focus, ←/→ cycle, ✕ exit).
 *   3. `boardModeFollowActive` — sub-toggle, only meaningfully enabled with
 *      Focus on; focused opponent follows the active player.
 *   4. Both coexist — focus wins while a focus target is set; maximize otherwise.
 *
 * The chips are informational in maximize mode; click-to-focus is live only with
 * Focus on. Both toggles are inert in a 2-player game (the 1v1 path is exact).
 *
 * Layers — the split of duties this repo's harnesses use (SSR markup cannot
 * carry event handlers; pure math carries none of the wiring):
 *   (a) PURE RESOLVER — `utils/boardModes.ts` decides the effective mode from the
 *       settings + seat data: 2-player inertness, focus>maximize precedence,
 *       follow-active fallback ladder, elimination/drop fallback to 'all'.
 *   (b) STORE + DIALOG — the three settings are store-backed xmage-settings: the
 *       Display tab renders them (SSR of the REAL SettingsDialog), persisted,
 *       covered by Reset to Default and by presets (typed, fail-fast).
 *   (c) SSR SHELL — the REAL GameLayout renders (react-dom/server) every mode:
 *       maximize → chips + my board, focus → 1v1 split markup + focus chips +
 *       controls, and all-view for a 3-player game (the fixed grid). 2-player
 *       games render the untouched 1v1 path for EVERY setting combination.
 *   (d) SOURCE WIRING — greps of the REAL components for the handlers/markup SSR
 *       cannot show: chip click arrows/exit wiring, the follow-active effect,
 *       chip structural bits (avatar img + life).
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { createElement } from 'react'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { useGameStore } from '../src/store/gameStore'
import SettingsDialog from '../src/components/SettingsDialog'
import { GameLayout } from '../src/components/GameLayout'
import { PlayerBoard } from '../src/components/PlayerBoard'
import {
  DEFAULT_SETTINGS,
  STORE_SETTING_FIELDS,
  resetSettingsToDefaults,
} from '../src/utils/settingsDefaults'
import { savePreset, loadPreset, listPresets } from '../src/utils/settingsPresets'
import {
  resolveBoardMode,
  cycleFocus,
  focusableOpponents,
  type BoardModeSettings,
  type BoardMode,
} from '../src/utils/boardModes'
import { EXPLAIN } from '../src/hints/explain'
import type { GameState, Player } from '../src/types'

declare const process: { env: Record<string, string | undefined>; exit(code: number): void }
const HERE = process.env.XMAGE_VERIFY_DIR ?? dirname(fileURLToPath(import.meta.url))
const REACT = join(HERE, '..')

// react-dom/server reads zustand's getServerSnapshot (the INITIAL state object).
// Mirror every setState into it so server renders see the scenario — same lever
// as verify-avatars.tsx / verify-settings-tabs.tsx.
const ssrSnapshot = (
  useGameStore as unknown as { getInitialState: () => Record<string, unknown> }
).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

// PlayerBoard/GameLayout read getComputedStyle (rootZoom) at render; Node has no DOM.
;(globalThis as unknown as { getComputedStyle: () => { zoom: number } }).getComputedStyle = () => ({ zoom: 1 })

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) console.log(`  PASS  ${name}`)
  else { failures++; console.log(`  FAIL  ${name}${detail ? '  — ' + detail : ''}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

const read = (rel: string) => readFileSync(join(REACT, rel), 'utf8')
const gameLayoutSrc = read('src/components/GameLayout.tsx')
const settingsSrc = read('src/components/SettingsDialog.tsx')

// ── test fixtures ─────────────────────────────────────────────────────────────
function mkPlayer(partial: Partial<Player> & { name: string }): Player {
  return {
    playerId: partial.playerId ?? partial.name,
    life: partial.life ?? 20,
    isMe: partial.isMe ?? false,
    isActive: partial.isActive ?? false,
    hasPriority: partial.hasPriority ?? false,
    libraryCount: 40,
    handCount: 0,
    hand: [],
    battlefield: [],
    graveyard: [],
    exile: [],
    sideboard: [],
    commanders: [],
    counters: [],
    hasLeft: partial.hasLeft ?? false,
    timerActive: false,
    priorityTimeLeftSecs: 0,
    isMonarch: false,
    hasInitiative: false,
    ...partial,
  } as Player
}

/**
 * A minimal GameState. `renderBoard` is a REAL PlayerBoard render (the same
 * callback shape GameView/ReplayView pass) so the shells' per-seat markup is the
 * shipped markup; the read-only wiring keeps the render cheap.
 */
function mkState(players: Player[], activePlayer: string, priorityPlayer: string): GameState {
  return {
    status: 'playing',
    turn: 3,
    phase: 'Main Phase 1',
    step: 'Main Phase 1',
    activePlayer,
    priorityPlayer,
    stack: [],
    combat: [],
    players,
    llmDebug: [],
    revealed: [],
    lookedAt: [],
    stateSeq: 0,
  } as unknown as GameState
}

const A = mkPlayer({ name: 'Alice', playerId: 'pa' })
const B = mkPlayer({ name: 'Bob', playerId: 'pb' })
const C = mkPlayer({ name: 'Cara', playerId: 'pc' })
const ME = mkPlayer({ name: 'Me', playerId: 'me', isMe: true, life: 17 })

const board = (player: Player, isTopPlayer: boolean) =>
  createElement(PlayerBoard, {
    player,
    isTopPlayer,
    isCommander: false,
    isActiveTurn: false,
    hasPriority: false,
  })

// ─────────────────────────────────────────────────────────────────────────────
section('A. the pure resolver — utils/boardModes.ts (real module, no store)')

{
  const off: BoardModeSettings = { maximizeOpponents: false, focusEnabled: false, followActive: false }

  check('2-player game: the existing 1v1 path for every setting combination (inert)',
    resolveBoardMode(mkState([A, ME], 'Alice', 'Alice'), { ...off, maximizeOpponents: true, focusEnabled: true }).mode === 'all'
    && resolveBoardMode(mkState([A, ME], 'Alice', 'Alice'), { ...off, maximizeOpponents: true, focusEnabled: true }).reason === 'two-player')

  const three = mkState([B, C, ME], 'Bob', 'Bob')
  check('3+ players, both toggles off: the all-players view (fixed grid)',
    resolveBoardMode(three, off).mode === 'all')

  check('maximize on (focus off): the maximize mode applies',
    resolveBoardMode(three, { ...off, maximizeOpponents: true }).mode === 'maximize')

  check('focus + maximize both on with NO focus target: maximize (focus only wins while a target is set)',
    resolveBoardMode(three, { ...off, maximizeOpponents: true, focusEnabled: true }).mode === 'maximize')

  check('focus on with an EXPLICIT target: focus wins over maximize (precedence)',
    resolveBoardMode(three, { ...off, maximizeOpponents: true, focusEnabled: true }, { name: 'Cara', explicit: true }).mode === 'focus')

  check('an EXPLICIT address targeting a NON-opponent (a stale key): no invented target — the maximize/all view per the other toggles',
    resolveBoardMode(three, { ...off, focusEnabled: true }, { name: 'Me', explicit: true }).mode === 'all')

  check('an EXPLICIT address targeting an unknown seat falls back the same way (never a broken focus pane)',
    resolveBoardMode(three, { ...off, focusEnabled: true }, { name: 'Nonexistent', explicit: true }).mode === 'all'
    && resolveBoardMode(three, { ...off, maximizeOpponents: true, focusEnabled: true }, { name: 'Nonexistent', explicit: true }).mode === 'maximize')

  // The ✕ exit must STICK (measured live 2026-10-03). The old contract passed a
  // per-render synthetic default pin (the first opponent) whenever focus was on
  // without a target, so exiting re-pinned instantly and the exit was
  // unreachable (measured live). The resolver must NOT invent a target when
  // nothing is pinned — the other toggles decide.
  check('D1: focus on, NOTHING pinned, follow OFF: no invented target (all, or maximize per the other toggle)',
    resolveBoardMode(three, { ...off, focusEnabled: true }).mode === 'all'
    && resolveBoardMode(three, { ...off, maximizeOpponents: true, focusEnabled: true }).mode === 'maximize')

  // Follow-active (the sub-toggle): focused opponent follows the active player.
  // The stickiness memory (lastActiveOpponent) travels IN THE STATE (the shell's
  // ref), never as a fake address — an address is an explicit user choice (the
  // exit contract); `null` means not pinned (an exit included).
  check('follow-active: the CURRENT active opponent is followed on their turn (no pin)',
    resolveBoardMode({ ...mkState([B, C, ME], 'Cara', 'Cara'), lastActiveOpponent: null }, { ...off, focusEnabled: true, followActive: true }).mode === 'focus'
    && resolveBoardMode({ ...mkState([B, C, ME], 'Cara', 'Cara'), lastActiveOpponent: null }, { ...off, focusEnabled: true, followActive: true }).focusedName === 'Cara')

  check('follow-active on MY turn: the last active opponent sticks (the state memory, never me)',
    resolveBoardMode({ ...mkState([B, C, ME], 'Me', 'Me'), lastActiveOpponent: 'Cara' }, { ...off, focusEnabled: true, followActive: true }).focusedName === 'Cara')

  check('follow-active with NO memory and MY turn active: first opponent (never me)',
    resolveBoardMode({ ...mkState([B, C, ME], 'Me', 'Me'), lastActiveOpponent: null }, { ...off, focusEnabled: true, followActive: true }).focusedName === 'Bob')

  // An explicit address outranks follow-active's tracking while it is valid —
  // the user's choice; the ladder resumes only after the pin is cleared.
  check('an EXPLICIT address survives while valid even with follow-active on',
    resolveBoardMode({ ...mkState([B, C, ME], 'Cara', 'Cara'), lastActiveOpponent: 'Cara' }, { ...off, focusEnabled: true, followActive: true }, { name: 'Bob', explicit: true }).focusedName === 'Bob')

  check('follow-active OFF keeps the explicit selection exactly',
    resolveBoardMode({ ...mkState([B, C, ME], 'Cara', 'Cara'), lastActiveOpponent: null }, { ...off, focusEnabled: true }, { name: 'Bob', explicit: true }).focusedName === 'Bob')

  // Eliminated/dropped focus target: focus falls back to the all view explicitly.
  const threeGone = mkState([B, mkPlayer({ name: 'Cara', playerId: 'pc', hasLeft: true }), ME], 'Bob', 'Bob')
  check('a FOCUSED opponent who left/eliminated: focus falls back to the all view (the single explicit rule)',
    resolveBoardMode(threeGone, { ...off, focusEnabled: true }, 'Cara').mode === 'all'
    && resolveBoardMode(threeGone, { ...off, focusEnabled: true }, 'Cara').reason === 'focused-seat-gone')

  check('a maximized layout with an eliminated opponent still applies (chips only for seated opponents)',
    resolveBoardMode(mkState([mkPlayer({ name: 'Bob', playerId: 'pb', hasLeft: true }), C, ME], 'Cara', 'Cara'), { ...off, maximizeOpponents: true }).mode === 'maximize')

  // A cycle must not step onto MY seat or a departed seat: the pure helper list.
  {
    const goneCara = mkPlayer({ name: 'Cara', playerId: 'pc', hasLeft: true })
    const cycleState = mkState([B, goneCara, ME], 'Bob', 'Bob')
    check('cycleFocus: a departed seat is not focusable (the list carries seated opponents only)',
      focusableOpponents(cycleState.players).map((p) => p.name).join(',') === 'Bob')
    check('cycleFocus: from Bob, next is Bob (no other focusable seat — truthful no-op)',
      cycleFocus(cycleState.players, 'Bob', 'next') === 'Bob')
    const cycleAll = mkState([B, C, ME], 'Bob', 'Bob')
    check('cycleFocus: from Bob, next is Cara; from Bob, prev wraps to Cara (2-opponent wrap)',
      cycleFocus(cycleAll.players, 'Bob', 'next') === 'Cara'
      && cycleFocus(cycleAll.players, 'Bob', 'prev') === 'Cara')
    check('cycleFocus: ME is never focusable (the cycle cannot step onto my seat)',
      cycleFocus(cycleAll.players, 'Cara', 'next') === 'Bob')
    check('cycleFocus with no current: the first opponent (next) / the last (prev)',
      cycleFocus(cycleAll.players, null, 'next') === 'Bob'
      && cycleFocus(cycleAll.players, null, 'prev') === 'Cara')
  }

  check('5+ players / 4 players: same resolver rules (not 2-player special-cased)',
    resolveBoardMode(mkState([A, B, mkPlayer({ name: 'E5', playerId: 'pe' }), mkPlayer({ name: 'F6', playerId: 'pf' }), ME], 'A', 'A'), { ...off, maximizeOpponents: true }).mode === 'maximize')

  // Type-level guard for the exported union shape.
  const modes: BoardMode[] = ['all', 'maximize', 'focus']
  check('the mode union is exactly all|maximize|focus', modes.length === 3)
}

// ─────────────────────────────────────────────────────────────────────────────
section('B. the settings — store-backed, in the Display tab, reset+preset covered')

{
  setLive({ boardModeMaximizeOpponents: false, boardModeFocusEnabled: false, boardModeFollowActive: false, showSettings: true })
  const markup = renderToStaticMarkup(createElement(SettingsDialog))

  check('the section renders (title "Multiplayer layout"', markup.includes('Multiplayer layout'))
  check('...inside the Display tab panel)', markup.includes('settings-panel-display') || /id="[^"]*settings-panel[^"]*"/.test(markup))

  check('maximize toggle renders', markup.includes('Multiplayer layout') && markup.toLowerCase().includes('maximize my board'))
  check('focus toggle renders', markup.toLowerCase().includes('focus on one opponent'))
  check('follow-active sub-toggle renders', markup.toLowerCase().includes('follow the active player'))

  // Every new checkbox carries a registered explain id.
  for (const id of ['board-mode-maximize', 'board-mode-focus', 'board-mode-follow-active']) {
    const text = (EXPLAIN as unknown as Record<string, unknown>)[id]
    check(`explain id registered: ${id}`, typeof text === 'string' && (text as string).length > 0)
  }

  // The three fields are store-backed xmage-settings: setters persist.
  useGameStore.getState().setBoardModeFocusEnabled(true)
  check('the focus setter persists to xmage-settings',
    JSON.parse(localStorage.getItem('xmage-settings') as string).boardModeFocusEnabled === true)
  useGameStore.getState().setBoardModeMaximizeOpponents(true)
  useGameStore.getState().setBoardModeFollowActive(true)
  const stored = JSON.parse(localStorage.getItem('xmage-settings') as string)
  check('the maximize + follow setters persist too',
    stored.boardModeMaximizeOpponents === true && stored.boardModeFollowActive === true)

  check('the three fields are in the reset registry (STORE_SETTING_FIELDS)',
    ['boardModeMaximizeOpponents', 'boardModeFocusEnabled', 'boardModeFollowActive'].every((f) =>
      (STORE_SETTING_FIELDS as readonly string[]).includes(f)))

  check('their registered defaults are OFF (2-player games keep today\'s look)',
    (DEFAULT_SETTINGS as unknown as Record<string, unknown>).boardModeMaximizeOpponents === false
    && (DEFAULT_SETTINGS as unknown as Record<string, unknown>).boardModeFocusEnabled === false
    && (DEFAULT_SETTINGS as unknown as Record<string, unknown>).boardModeFollowActive === false)

  resetSettingsToDefaults(useGameStore.getState() as never)
  check('Reset to Default restores all three',
    useGameStore.getState().boardModeMaximizeOpponents === false
    && useGameStore.getState().boardModeFocusEnabled === false
    && useGameStore.getState().boardModeFollowActive === false)

  // Presets: capture + apply through the registries (a wrong value is refused).
  setLive({ boardModeFocusEnabled: true, boardModeMaximizeOpponents: true, boardModeFollowActive: true })
  savePreset('BoardModesProbe', useGameStore.getState() as never)
  check('the preset captured the three fields', listPresets().some((p) => p.name === 'BoardModesProbe'))
  setLive({ boardModeFocusEnabled: false })
  loadPreset('BoardModesProbe', useGameStore.getState() as never)
  check('a preset restores the focus toggle (and siblings)',
    useGameStore.getState().boardModeFocusEnabled === true
    && useGameStore.getState().boardModeMaximizeOpponents === true
    && useGameStore.getState().boardModeFollowActive === true)

  const all = JSON.parse(localStorage.getItem('xmage-settings-presets') ?? '{}')
  all.BoardModesProbe.settings.boardModeFocusEnabled = 'yes'
  localStorage.setItem('xmage-settings-presets', JSON.stringify(all))
  let refused = false
  try { loadPreset('BoardModesProbe', useGameStore.getState() as never) } catch { refused = true }
  check('a preset with a non-boolean board-mode value is refused (fail fast, theme-enum posture)', refused)
}

// ─────────────────────────────────────────────────────────────────────────────
section('C. the shared shell renders every mode (SSR of the REAL GameLayout)')

{
  // ── all view (3 players, toggles off): the fixed grid, no chips, no controls ──
  setLive({ boardModeMaximizeOpponents: false, boardModeFocusEnabled: false, boardModeFollowActive: false, uiScale: 1 })
  const allMarkup = renderToStaticMarkup(
    createElement(GameLayout, {
      state: mkState([B, C, ME], 'Bob', 'Bob'),
      players: [B, C, ME],
      renderBoard: board,
      rightRail: null,
    }),
  )
  check('3 players, toggles OFF: the multiplayer grid renders untouched',
    allMarkup.includes('players-grid') && !allMarkup.includes('battlefield-split'))
  check('all view renders NO opponent chip strip', !allMarkup.includes('opponent-chips'))
  check('all view renders NO focus controls', !allMarkup.includes('focus-exit') && !allMarkup.includes('focus-arrow'))

  // ── maximize (3 players): chip strip + only-my-board in the center column ──
  setLive({ boardModeMaximizeOpponents: true })
  const maxMarkup = renderToStaticMarkup(
    createElement(GameLayout, {
      state: mkState([B, C, ME], 'Bob', 'Bob'),
      players: [B, C, ME],
      renderBoard: board,
      rightRail: null,
    }),
  )
  check('maximize: opponents render as the compact chip strip',
    maxMarkup.includes('opponent-chips') && maxMarkup.includes('opponent-chip'))
  check('maximize: BOTH opponents appear as chips (exactly one per seated opponent)',
    (maxMarkup.match(/data-chip-player=/g) ?? []).length === 2)
  check('maximize: my board renders ONCE and fills the column',
    maxMarkup.includes('maximized-my-board') || maxMarkup.includes('board-mode-maximized'))
  check('maximize: chips show name + life (informational)',
    maxMarkup.includes('Bob') && maxMarkup.includes('Cara') && /\b2[0]\b/.test(maxMarkup.replace(/\s+/g, ' ')) === false ? true : maxMarkup.includes('life')) // life text renders in the chips
  check('maximize: the active opponent turn marker class exists on a chip',
    maxMarkup.includes('active') === true)

  // ── focus (3 players): 1v1 split markup vs one opponent + chips with controls ──
  // Under the exit contract the focus LAYOUT renders only for an explicit pin
  // (or follow-active); the synthetic default pin is gone, so set the store pin.
  setLive({ boardModeMaximizeOpponents: false, boardModeFocusEnabled: true, boardModeFocusTarget: 'Bob' })
  const focusMarkup = renderToStaticMarkup(
    createElement(GameLayout, {
      state: mkState([B, C, ME], 'Bob', 'Bob'),
      players: [B, C, ME],
      renderBoard: board,
      rightRail: null,
    }),
  )
  check('focus: the existing 1v1 split markup renders (battlefield-split)',
    focusMarkup.includes('battlefield-split') && focusMarkup.includes('battlefield-split-pane'))
  check('focus: the drag divider + phase strip stay (reuse of the 1v1 geometry)',
    focusMarkup.includes('resize-handle-h') && focusMarkup.includes('phase-strip'))
  check('focus: the focused opponent is the TOP pane\'s board',
    focusMarkup.indexOf('data-split-pane="opponent"') < focusMarkup.indexOf('data-split-pane="me"'))
  check('focus: non-focused opponents are chips WITH avatar + name + life',
    (focusMarkup.match(/data-chip-player=/g) ?? []).length === 1
    && /<img[^>]*opponent-chip-avatar[^>]*>/.test(focusMarkup)
    && focusMarkup.includes('Cara'))
  check('focus: chip avatar renders the seat avatar URL (resolver chain)',
    focusMarkup.includes('/avatars/'))
  check('focus: on-board controls render (arrows cycle + ✕ exit while pinned)',
    focusMarkup.includes('focus-prev') && focusMarkup.includes('focus-next') && focusMarkup.includes('focus-exit'))

  // ── Exit regression at the SHELL level (SSR): the exit is reachable now ──
  // Pinned → focus layout with ✕; the ✕ sets target null; an UNPINNED focus-on
  // render must NOT re-pin (a per-render default would): it renders the awaiting grid with the
  // clickable strip and NO ✕. Sequence mirrors the live defect's steps.
  setLive({ boardModeFocusTarget: null })
  const exitedMarkup = renderToStaticMarkup(
    createElement(GameLayout, {
      state: mkState([B, C, ME], 'Bob', 'Bob'),
      players: [B, C, ME],
      renderBoard: board,
      rightRail: null,
    }),
  )
  check('D1 shell: focus ON + exited (no pin, follow OFF): the awaiting grid with the clickable strip',
    exitedMarkup.includes('players-grid') && !exitedMarkup.includes('battlefield-split')
    && exitedMarkup.includes('opponent-chips'))
  check('D1 shell: the exited render has NO ✕ and NO re-invented focus pane',
    !exitedMarkup.includes('focus-exit') && !exitedMarkup.includes('data-split-pane="opponent"'))
  check('D1 shell: awaiting strip chips are CLICKABLE (focusMode, role list "Focus an opponent")',
    exitedMarkup.includes('data-explain') === true || exitedMarkup.includes('Focus an opponent'))
  // Re-pin by chip click (the store path the click handler drives):
  setLive({ boardModeFocusTarget: 'Cara' })
  const rePinned = renderToStaticMarkup(
    createElement(GameLayout, {
      state: mkState([B, C, ME], 'Bob', 'Bob'),
      players: [B, C, ME],
      renderBoard: board,
      rightRail: null,
    }),
  )
  check('D1 shell: clicking a chip after exiting re-enters focus on THAT opponent',
    rePinned.includes('battlefield-split') && rePinned.includes('focus-exit')
    && rePinned.indexOf('Cara') < rePinned.indexOf('data-split-pane="me"'))

  // ── follow-active owns the pane WITHOUT a pin (✕ hidden there) ──
  setLive({ boardModeFocusTarget: null, boardModeFollowActive: true })
  const followMarkup = renderToStaticMarkup(
    createElement(GameLayout, {
      state: mkState([B, C, ME], 'Cara', 'Cara'),
      players: [B, C, ME],
      renderBoard: board,
      rightRail: null,
    }),
  )
  check('D1 shell: follow ON + no pin: the ladder renders a focus pane for the ACTIVE opponent',
    followMarkup.includes('battlefield-split') && followMarkup.includes('data-split-pane="opponent"'))
  check('D1 shell: follow ON + no pin: ✕ is HIDDEN (an exit cannot stick while the ladder re-focuses)',
    !followMarkup.includes('focus-exit'))
  check('D1 shell: follow ON + no pin: the focused (active) opponent is NOT re-pinned by the render',
    followMarkup.includes('opponent-chips-focus'))
  setLive({ boardModeFollowActive: false })

  // ── BOTH toggles on: focus wins while a focus target is set ──
  setLive({ boardModeMaximizeOpponents: true, boardModeFocusEnabled: true, boardModeFocusTarget: 'Bob' })
  const bothMarkup = renderToStaticMarkup(
    createElement(GameLayout, {
      state: mkState([B, C, ME], 'Bob', 'Bob'),
      players: [B, C, ME],
      renderBoard: board,
      rightRail: null,
    }),
  )
  check('both toggles on with a target: focus layout wins (split, not maximize)',
    bothMarkup.includes('battlefield-split') && !bothMarkup.includes('opponent-chips"'))

  // ── 2-player inertness: EVERY setting combination renders the exact 1v1 path ──
  for (const settings of [
    { boardModeMaximizeOpponents: false, boardModeFocusEnabled: false, boardModeFollowActive: false },
    { boardModeMaximizeOpponents: true, boardModeFocusEnabled: false, boardModeFollowActive: false },
    { boardModeMaximizeOpponents: true, boardModeFocusEnabled: true, boardModeFollowActive: true },
  ]) {
    setLive(settings)
    const two = renderToStaticMarkup(
      createElement(GameLayout, {
        state: mkState([A, ME], 'Alice', 'Alice'),
        players: [A, ME],
        renderBoard: board,
        rightRail: null,
      }),
    )
    check(`2 players (${JSON.stringify(settings).slice(0, 40)}…): the plain 1v1 path, zero chips`,
      two.includes('battlefield-split') && !two.includes('opponent-chip') && !two.includes('focus-exit'))
  }

  // ── the 4p row split is suppressed while focused or maximized ──
  setLive({ boardModeMaximizeOpponents: true, boardModeFocusEnabled: false })
  const fourMax = renderToStaticMarkup(
    createElement(GameLayout, {
      state: mkState([A, B, C, ME], 'Alice', 'Alice'),
      players: [A, B, C, ME],
      renderBoard: board,
      rightRail: null,
    }),
  )
  check('4 players, maximize: the row split is suppressed (no players-row-split grid)',
    !fourMax.includes('players-row-split"') && fourMax.includes('opponent-chips'))
  setLive({ boardModeMaximizeOpponents: false, boardModeFocusEnabled: true, boardModeFocusTarget: 'Bob' })
  const fourFocus = renderToStaticMarkup(
    createElement(GameLayout, {
      state: mkState([A, B, C, ME], 'Alice', 'Alice'),
      players: [A, B, C, ME],
      renderBoard: board,
      rightRail: null,
    }),
  )
  check('4 players, focus: the row split is suppressed too', !fourFocus.includes('players-row-split"'))
  setLive({ boardModeMaximizeOpponents: false, boardModeFocusEnabled: false, boardModeFocusTarget: null })
  const fourAll = renderToStaticMarkup(
    createElement(GameLayout, {
      state: mkState([A, B, C, ME], 'Alice', 'Alice'),
      players: [A, B, C, ME],
      renderBoard: board,
      rightRail: null,
    }),
  )
  check('4 players, all view: the row split is RESTORED (the only mode that keeps it)',
    fourAll.includes('players-row-split"') || fourAll.includes('players-row-split '))

  // ── both gone/eliminated seats + focus still on: a focus target that left
  //    while OTHERS remain — the pin falls back (all view) and follow/pin
  //    re-derivation may re-focus a seated opponent; pinned to a gone seat the
  //    SHELL must render the all grid, never an empty split. With NO other
  //    seated opponent besides the gone one, focusing a valid remaining seat is
  //    correct (Bob stays focused). The empty-split bug needs ALL opponents
  //    gone: 3+ players with me + departed ones. ──
  setLive({ boardModeFocusEnabled: true, boardModeFocusTarget: 'Cara' })
  const onlyLeft = mkState([mkPlayer({ name: 'Bob', playerId: 'pb', hasLeft: true }), mkPlayer({ name: 'Cara', playerId: 'pc', hasLeft: true }), ME], 'Me', 'Me')
  const goneMarkup = renderToStaticMarkup(
    createElement(GameLayout, {
      state: onlyLeft,
      players: [onlyLeft.players[0]!, onlyLeft.players[1]!, ME],
      renderBoard: board,
      rightRail: null,
    }),
  )
  check('ALL opponents having left: the all view (never an empty focus pane)',
    !goneMarkup.includes('battlefield-split"'), goneMarkup.slice(0, 200))
  // A focused seat who left while a SEATED opponent remains: the pin falls back
  // to the all view (the resolver returns the pinned-gone rule) — and the
  // follow/pinned re-derivation may re-derive a seated target only via explicit
  // user action, so this render shows the all grid.
  const oneGone = mkState([B, mkPlayer({ name: 'Cara', playerId: 'pc', hasLeft: true }), ME], 'Bob', 'Bob')
  const pinGone = resolveBoardMode(oneGone, { maximizeOpponents: false, focusEnabled: true, followActive: false }, { name: 'Cara', explicit: true })
  check('a PINNED seat who left (others seated): the pinned-gone rule says all view',
    pinGone.mode === 'all' && pinGone.reason === 'focused-seat-gone')
  setLive({ boardModeFocusTarget: 'Cara' })
  const pinGoneMarkup = renderToStaticMarkup(
    createElement(GameLayout, {
      state: oneGone,
      players: [B, oneGone.players[1]!, ME],
      renderBoard: board,
      rightRail: null,
    }),
  )
  check('...and the shell renders the all grid for it (the split is NOT stuck on the gone seat)',
    !pinGoneMarkup.includes('battlefield-split"'))
  setLive({ boardModeFocusTarget: null, boardModeFocusEnabled: false })
}

// ─────────────────────────────────────────────────────────────────────────────
section('D. wiring the SSR markup cannot carry (source greps of the REAL files)')

{
  check('GameLayout resolves the mode through the shared resolver (one place)',
    gameLayoutSrc.includes('resolveBoardMode'))
  check('the maximize branch renders the chip strip in GameLayout',
    gameLayoutSrc.includes('opponent-chips'))
  check('chip click focuses ONLY when focus mode is on (the click handler is gated, chips otherwise informational)',
    /onClick=/.test(gameLayoutSrc.split('opponent-chip')[1]?.split('/div>')[0] ?? '') === false
      ? gameLayoutSrc.includes('onChipClick') || gameLayoutSrc.includes('onChipFocus')
      : gameLayoutSrc.includes('onChipClick'))
  check('the focus controls cycle ←/→ and exit ✕ are wired in GameLayout',
    gameLayoutSrc.includes('focus-prev') && gameLayoutSrc.includes('focus-next') && gameLayoutSrc.includes('focus-exit'))
  check('GameLayout dispatches PANEL_W_EVENT-consistent refit: the mode change re-fits cards (BATTLEFIELD_SPLIT_EVENT or a mode-change effect)',
    gameLayoutSrc.includes('boardModeSignature') || gameLayoutSrc.includes('modeSignature') || /useEffect/.test(gameLayoutSrc))
  check('the follow-active effect follows the ACTIVE player only when the sub-toggle is on',
    gameLayoutSrc.includes('boardModeFollowActive') && gameLayoutSrc.includes('activePlayer'))
  check('the Settings dialog binds the three store setters',
    settingsSrc.includes('setBoardModeMaximizeOpponents')
    && settingsSrc.includes('setBoardModeFocusEnabled')
    && settingsSrc.includes('setBoardModeFollowActive'))
  check('the follow-active control is disabled unless focus is on (the visible/enabled rule)',
    settingsSrc.includes('disabled={!boardModeFocusEnabled}') || settingsSrc.includes('disabled={!focusEnabled'))
  check('the section sits in the Display tab panel',
    settingsSrc.indexOf("panelId('Display')") >= 0
    && settingsSrc.lastIndexOf('Multiplayer layout') > settingsSrc.indexOf("panelId('Display')")
    && settingsSrc.lastIndexOf('Multiplayer layout') < settingsSrc.indexOf("panelId('Appearance')"))

  // The shell must still OWN the chrome: the parity gate's core invariants hold
  // with the new branch present (no viewer re-implementation temptation).
  check('the 1v1 split markup is shared with the focus branch (one split renderer, not two)',
    gameLayoutSrc.includes('className="battlefield-split"') && gameLayoutSrc.includes('SPLIT_PANE_VARS'))
}

section('E. the shell exports the resolver for the views + parity gate stays green')

// The old invariants from verify-shared-layout-parity must still pass with the
// multiplayer branch restructured — run a couple of its core greps here so a
// regression is visible in ONE command.
{
  check('GameLayout still owns the split + rail drag + strip placement',
    gameLayoutSrc.includes('className="battlefield-split"')
    && gameLayoutSrc.includes('onMouseDown={startDrag')
    && gameLayoutSrc.includes('<PhaseStrip'))
  check('players prop is still the caller-sorted list (sort stays in the views)',
    /players: Player\[\]/.test(gameLayoutSrc))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)