/**
 * Keyboard-shortcut rebinding behavioural check (Java
 * reference: PreferencesDialog's "Game - Hotkeys" tab).
 *
 * Run with:  bash scripts/verify-hotkeys.sh
 *
 * Drives the REAL registry (utils/keybindings.ts), the REAL store + message
 * handler, the REAL ActionPromptBar and the REAL ReplayView hotkey matrix under
 * Node with the browser stubs — no browser, no live stack. The rail's keydown
 * handler itself is document-level and cannot be fired from a server render, so
 * the split of duties is the repo's usual one: the RESOLUTION semantics are
 * driven through the same functions the handler calls (matchHotkeyAction with
 * the identical guard context), and the handler's WIRING (live-state read,
 * registry consumption, no hardcoded keys left) is asserted from the real
 * component source.
 *
 * Encodes:
 *  (a) DEFAULT RESOLUTION — every shipped key resolves to its action in the
 *      'game' scope (F5 → skip-eot), the replay keys resolve in 'replay', and
 *      F8/F12 resolve to NOTHING (deliberately unmapped: no web action exists);
 *  (b) REBIND — a rebind changes resolution (F5 stops skipping, 'E' starts),
 *      and the change is persisted under xmage-keybindings;
 *  (c) CONFLICT — a duplicate binding is REFUSED with the owning action, the
 *      old binding survives, and a saved duplicate in localStorage is refused
 *      at load (never applied);
 *  (d) RESET — per-row reset and the settings registry's "Reset to Default"
 *      (settingsDefaults.ts MODULE_RESETS) both restore every default key;
 *  (e) GUARDS — a typing target, an open context menu, the settings dialog, an
 *      armed rebind capture and the replay view all suspend the game hotkeys;
 *  (f) CAPTURE — Escape cancels, modifier-only keys are ignored, unusable keys
 *      are reported, and capture suspension is idempotent (token model);
 *  (g) REPLAY INTERPLAY — ReplayView's own matrix is untouched (Space/arrows/
 *      Escape, dialog/menu/typing hand-back), isTypingTarget is the shared
 *      definition, and the rail stands down inside a replay;
 *  (h) COMPONENT WIRING — the rail's handler reads live state, resolves through
 *      matchHotkeyAction, carries no hardcoded F-key cases, and its button
 *      titles render the LIVE key (a rebound key shows on the button);
 *  (i) FAIL FAST on the API — unknown action ids and rebinding a fixed replay
 *      row throw; the settings tab renders a row per registered action.
 */
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import {
  KEYBINDING_ACTIONS,
  KEYBINDINGS_STORAGE_KEY,
  beginCapture,
  captureKeyDecision,
  defaultKeyFor,
  endCapture,
  findConflict,
  formatKeySpec,
  getBindings,
  getKeybindingLoadProblems,
  isCaptureActive,
  isKeybindingModified,
  isTypingTarget,
  keyFor,
  matchHotkeyAction,
  normalizeKeySpec,
  parseStoredOverrides,
  rebindKeybinding,
  resetKeybinding,
  resetKeybindings,
  type GameKeybindingActionId,
  type HotkeyGuardContext,
} from '../src/utils/keybindings'
import { ActionPromptBar } from '../src/components/ActionPromptBar'
import { replayHotkey } from '../src/components/ReplayView'
import { EXPLAIN } from '../src/hints/explain'
import { resetSettingsToDefaults } from '../src/utils/settingsDefaults'
import SettingsDialog, { SETTINGS_TABS } from '../src/components/SettingsDialog'
import type { GameState } from '../src/types'

declare const process: { env: Record<string, string | undefined>; exit(code: number): void }
const HERE = process.env.XMAGE_VERIFY_DIR ?? dirname(fileURLToPath(import.meta.url))
const REACT = join(HERE, '..')
const read = (rel: string) => readFileSync(join(REACT, rel), 'utf8')

let failures = 0
function check(name: string, cond: boolean, detail = ''): void {
  if (!cond) failures++
  console.log(`  [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' :: ' + detail : ''}`)
}
function section(t: string): void {
  console.log(`\n## ${t}`)
}

/** The idle guard context: nothing owns the keyboard. */
const IDLE: HotkeyGuardContext = {
  typing: false,
  contextMenuOpen: false,
  settingsOpen: false,
  replayOpen: false,
  capturing: false,
}

const resolve = (key: string, ctx: HotkeyGuardContext = IDLE): GameKeybindingActionId | null =>
  matchHotkeyAction({ key }, ctx, 'game')

/** Wipe the module + storage back to defaults before each scenario group. */
function hardReset(): void {
  resetKeybindings()
  try {
    localStorage.removeItem(KEYBINDINGS_STORAGE_KEY)
  } catch { /* ignore */ }
}

// ─────────────────────────────────────────────────────────────────────────────
section('a. default resolution — the shipped key map')

hardReset()

{
  const expected: Array<[string, GameKeybindingActionId]> = [
    ['F2', 'confirm'],
    ['F3', 'cancel-skips'],
    ['F4', 'skip-turn'],
    ['F5', 'skip-eot'],
    ['F6', 'skip-turn-stack'],
    ['F7', 'skip-main'],
    ['F9', 'skip-my-turn'],
    ['F10', 'skip-stack'],
    ['F11', 'skip-end-step'],
  ]
  for (const [key, id] of expected) {
    check(`${key} resolves to '${id}'`, resolve(key) === id, String(resolve(key)))
    check(`...and '${id}' is bound to ${key}`, keyFor(id) === key, keyFor(id))
  }
  check('F8 resolves to nothing (no web action exists — deliberately unmapped)', resolve('F8') === null)
  check('F12 resolves to nothing (no web action exists — deliberately unmapped)', resolve('F12') === null)
  check('no registered action defaults to F8 or F12',
    KEYBINDING_ACTIONS.every((a) => (a.defaultKey as string) !== 'F8' && (a.defaultKey as string) !== 'F12'))
  check('every registered action has a unique default key',
    new Set(KEYBINDING_ACTIONS.map((a) => a.defaultKey)).size === KEYBINDING_ACTIONS.length)
  check('F1 resolves to nothing (not a shipped web shortcut)', resolve('F1') === null)
  // The registry carries exactly the nine shipped game actions + four replay rows.
  check('nine game actions are registered',
    KEYBINDING_ACTIONS.filter((a) => a.scope === 'game').length === 9,
    KEYBINDING_ACTIONS.filter((a) => a.scope === 'game').map((a) => a.id).join(','))
  // Replay scope resolves through the same map — for the keys the registry can
  // match. Escape is DELIBERATELY not resolvable here: it is the one key whose
  // meaning is context-dependent (dialog close / menu dismiss / viewer exit),
  // which is exactly why the viewer owns it through replayHotkey() rather than
  // the registry. It is still REGISTERED (see the conflict checks below).
  check('Space resolves to replay-play in the replay scope',
    matchHotkeyAction({ key: ' ' }, IDLE, 'replay') === 'replay-play')
  check('ArrowRight resolves to replay-next',
    matchHotkeyAction({ key: 'ArrowRight' }, IDLE, 'replay') === 'replay-next')
  check('ArrowLeft resolves to replay-previous',
    matchHotkeyAction({ key: 'ArrowLeft' }, IDLE, 'replay') === 'replay-previous')
  check('Escape is not a bindable spec (dialog/focus key — replayHotkey owns it)', normalizeKeySpec('Escape') === null)
  check('...but it is still REGISTERED as the viewer\u2019s exit key (shown in the tab, reserved for conflicts)',
    KEYBINDING_ACTIONS.some((a) => a.id === 'replay-exit' && a.defaultKey === 'Escape'))
  check('findConflict reserves Escape for the viewer', findConflict('Escape', null)?.id === 'replay-exit')
  check('a replay key does NOT fire a game action (scopes are separate)', resolve(' ') === null)
  check('a game key does NOT fire a replay action',
    matchHotkeyAction({ key: 'F5' }, IDLE, 'replay') === null)
}

// ─────────────────────────────────────────────────────────────────────────────
section('b. key spec parsing / formatting')

{
  check("'F5' normalises to 'F5'", normalizeKeySpec('F5') === 'F5')
  check("'f5' normalises to 'F5' (case-insensitive)", normalizeKeySpec('f5') === 'F5')
  check("'f 12' style junk is rejected", normalizeKeySpec('f 12') === null)
  check("F13+ is rejected (dialog/focus keys stay out)", normalizeKeySpec('F13') === null)
  check("'g' normalises to 'G' (letters uppercased)", normalizeKeySpec('g') === 'G')
  check("'7' normalises to '7' (digits bindable)", normalizeKeySpec('7') === '7')
  check("' ' normalises to 'Space'", normalizeKeySpec(' ') === 'Space')
  check("'Space' normalises to 'Space'", normalizeKeySpec('Space') === 'Space')
  check("'ArrowLeft' normalises to 'ArrowLeft'", normalizeKeySpec('ArrowLeft') === 'ArrowLeft')
  check("'Escape' is NOT bindable", normalizeKeySpec('Escape') === null)
  check("'Enter' is NOT bindable", normalizeKeySpec('Enter') === null)
  check("'Tab' is NOT bindable", normalizeKeySpec('Tab') === null)
  check("'Backspace' is NOT bindable", normalizeKeySpec('Backspace') === null)
  check("'' is rejected", normalizeKeySpec('') === null)
  check("'   ' is rejected", normalizeKeySpec('   ') === null)
  check("'Space' formats as 'Space'", formatKeySpec('Space') === 'Space')
  check("'F5' formats as 'F5'", formatKeySpec('F5') === 'F5')
  check('every registered default key round-trips through normalize (Escape excepted — it is not a bindable spec)',
    KEYBINDING_ACTIONS.every((a) => normalizeKeySpec(a.defaultKey) === (a.defaultKey === 'Escape' ? null : a.defaultKey)))
}

// ─────────────────────────────────────────────────────────────────────────────
section('c. rebind — a rebind changes resolution and persists')

hardReset()

{
  const before = resolve('F5')
  check('F5 skips EOT before the rebind', before === 'skip-eot', String(before))
  const result = rebindKeybinding('skip-eot', 'E')
  check('rebindKeybinding accepts a free key', result.ok === true)
  check('resolution follows the rebind: E → skip-eot', resolve('E') === 'skip-eot', String(resolve('E')))
  check('resolution follows the rebind: F5 is now free', resolve('F5') === null, String(resolve('F5')))
  check('keyFor reports the new key', keyFor('skip-eot') === 'E')
  check('the row reports itself as modified', isKeybindingModified('skip-eot'))
  check('the override is persisted under its own key', localStorage.getItem(KEYBINDINGS_STORAGE_KEY) !== null)
  {
    const stored = JSON.parse(localStorage.getItem(KEYBINDINGS_STORAGE_KEY)!) as { version: number; overrides: Record<string, string> }
    check('the stored blob carries only the override', JSON.stringify(stored.overrides) === JSON.stringify({ 'skip-eot': 'E' }), JSON.stringify(stored.overrides))
    check('the stored blob carries the schema version', stored.version === 1, String(stored.version))
  }
  check('parseStoredOverrides round-trips the persisted map',
    parseStoredOverrides(localStorage.getItem(KEYBINDINGS_STORAGE_KEY)!).overrides['skip-eot'] === 'E')
  check('the other defaults are untouched', keyFor('skip-main') === 'F7' && keyFor('confirm') === 'F2')
  // Rebinding back to the default removes the override entirely (not a diff).
  rebindKeybinding('skip-eot', 'F5')
  check('rebinding back to the default clears the override', !isKeybindingModified('skip-eot'))
  check('the stored blob is removed when no overrides remain', localStorage.getItem(KEYBINDINGS_STORAGE_KEY) === null)
}

// ─────────────────────────────────────────────────────────────────────────────
section('d. conflict — a duplicate binding is refused, never silently accepted')

hardReset()

{
  rebindKeybinding('skip-eot', 'E')
  const clash = rebindKeybinding('skip-main', 'E')
  check('the second rebind is refused', clash.ok === false && clash.reason === 'conflict', JSON.stringify(clash))
  check('the refusal names the owning action', !clash.ok && clash.reason === 'conflict' && clash.conflict.id === 'skip-eot',
    !clash.ok && clash.reason === 'conflict' ? clash.conflict.id : '(none)')
  check('the refused key did NOT move: skip-main is still F7', keyFor('skip-main') === 'F7', keyFor('skip-main'))
  check('the existing binding is untouched: skip-eot is still E', keyFor('skip-eot') === 'E')
  check('F5 (the skipped default) is still free after the refusal', resolve('F5') === null)
  check('findConflict reports the owner', findConflict('E', null)?.id === 'skip-eot')
  check('findConflict ignores the row itself', findConflict('E', 'skip-eot') === null)
  // Conflicts are strict ACROSS scopes: Space is taken by the replay viewer.
  const spaceClash = rebindKeybinding('skip-turn', 'Space')
  check('binding a game action onto the replay viewer\u2019s Space is refused',
    spaceClash.ok === false && spaceClash.reason === 'conflict', JSON.stringify(spaceClash))
  check('...and the refusal names the replay action',
    !spaceClash.ok && spaceClash.reason === 'conflict' && spaceClash.conflict.id === 'replay-play')
  // An unusable key is refused as invalid-key, not stored.
  const invalid = rebindKeybinding('skip-turn', 'Escape')
  check('Escape is refused as invalid-key', invalid.ok === false && invalid.reason === 'invalid-key', JSON.stringify(invalid))
  check('the invalid key changed nothing', keyFor('skip-turn') === 'F4')

  // A duplicate saved by an older/corrupt client is refused at LOAD.
  localStorage.setItem(KEYBINDINGS_STORAGE_KEY, JSON.stringify({
    version: 1,
    overrides: { 'skip-eot': 'X', 'skip-main': 'X' },
  }))
  const parsed = parseStoredOverrides(localStorage.getItem(KEYBINDINGS_STORAGE_KEY)!)
  check('a saved duplicate is refused at load', parsed.overrides['skip-main'] === undefined, JSON.stringify(parsed.overrides))
  check('...the first saved entry survives', parsed.overrides['skip-eot'] === 'X')
  check('...and the refusal is reported as a problem', parsed.problems.some((p) => p.includes('X') && p.includes('both')), parsed.problems.join(' | '))
  // Corrupt/unusable blobs: defaults stay in force, problems are surfaced.
  const junk = parseStoredOverrides('{ not json')
  check('unparseable JSON falls back to defaults with a problem', Object.keys(junk.overrides).length === 0 && junk.problems.length === 1, junk.problems.join(' | '))
  const badKey = parseStoredOverrides(JSON.stringify({ version: 1, overrides: { 'skip-eot': 'Escape' } }))
  check('a saved unusable key falls back to the default with a problem', badKey.overrides['skip-eot'] === undefined && badKey.problems.length === 1)
  const badId = parseStoredOverrides(JSON.stringify({ version: 1, overrides: { 'not-an-action': 'Z' } }))
  check('a saved unknown action id is refused with a problem', badId.problems.some((p) => p.includes('not-an-action')))
  const badVersion = parseStoredOverrides(JSON.stringify({ version: 99, overrides: { 'skip-eot': 'X' } }))
  check('an unsupported stored version falls back to defaults', Object.keys(badVersion.overrides).length === 0 && badVersion.problems.length === 1)
  const fixedRow = parseStoredOverrides(JSON.stringify({ version: 1, overrides: { 'replay-play': 'P' } }))
  check('a saved rebind of a fixed replay row is refused', fixedRow.overrides['replay-play'] === undefined && fixedRow.problems.some((p) => p.includes('fixed')))
}

// ─────────────────────────────────────────────────────────────────────────────
section('e. reset — per row and through the settings registry')

hardReset()

{
  rebindKeybinding('skip-eot', 'E')
  rebindKeybinding('skip-main', 'Q')
  check('two overrides are live', keyFor('skip-eot') === 'E' && keyFor('skip-main') === 'Q')
  const one = resetKeybinding('skip-eot')
  check('per-row reset succeeds', one.ok === true)
  check('per-row reset restores exactly that default', keyFor('skip-eot') === 'F5', keyFor('skip-eot'))
  check('the other override survives the per-row reset', keyFor('skip-main') === 'Q')
  check('the row no longer reports modified', !isKeybindingModified('skip-eot'))
  check('the other row still reports modified', isKeybindingModified('skip-main'))
  // The settings registry path — exactly what SettingsDialog's footer calls.
  resetSettingsToDefaults(useGameStore.getState())
  check('Reset to Default restores EVERY key', KEYBINDING_ACTIONS.every((a) => keyFor(a.id) === a.defaultKey),
    KEYBINDING_ACTIONS.map((a) => `${a.id}=${keyFor(a.id)}`).join(','))
  check('Reset to Default clears the stored blob', localStorage.getItem(KEYBINDINGS_STORAGE_KEY) === null)
  check('Reset to Default leaves no row in the modified state', KEYBINDING_ACTIONS.every((a) => !isKeybindingModified(a.id)))
  // A per-row reset whose default was handed to another action is refused.
  // (Move the owner off its default FIRST — rebinding is conflict-checked, so a
  // live default cannot be stolen; only a vacated one can be taken.)
  rebindKeybinding('skip-main', 'Q') // vacate F7
  const steal = rebindKeybinding('skip-eot', 'F7')
  check('a vacated default can be taken by another action', steal.ok === true, JSON.stringify(steal))
  check('...resolution follows: F7 now skips EOT', resolve('F7') === 'skip-eot', String(resolve('F7')))
  const restore = resetKeybinding('skip-main') // its default F7 is owned by skip-eot now
  check('per-row reset refuses a default another action now owns', restore.ok === false && restore.reason === 'conflict', JSON.stringify(restore))
  check('...naming the owner', !restore.ok && restore.reason === 'conflict' && restore.conflict.id === 'skip-eot')
  check('...and does not create a double binding', keyFor('skip-main') === 'Q')
  resetSettingsToDefaults(useGameStore.getState())
  check('Reset to Default clears the conflict case too', keyFor('skip-eot') === 'F5' && keyFor('skip-main') === 'F7')
}

// ─────────────────────────────────────────────────────────────────────────────
section('f. guards — typing, menus, settings, replay, capture all suspend the hotkeys')

hardReset()

{
  check('idle: F5 skips EOT', resolve('F5') === 'skip-eot')
  check('typing target: F5 is handed back (the field owns the keyboard)', resolve('F5', { ...IDLE, typing: true }) === null)
  check('open context menu: F5 stands down (the menu owns Escape/keys)', resolve('F5', { ...IDLE, contextMenuOpen: true }) === null)
  check('settings dialog open: F5 stands down (it owns the keyboard)', resolve('F5', { ...IDLE, settingsOpen: true }) === null)
  check('replay view up: F5 stands down (the viewer has its own keys)', resolve('F5', { ...IDLE, replayOpen: true }) === null)
  check('no guard set: F5 resolves again', resolve('F5') === 'skip-eot')
  // isTypingTarget is the SHARED predicate (re-exported by ReplayView).
  check('INPUT is a typing target', isTypingTarget({ tagName: 'INPUT' }))
  check('TEXTAREA is a typing target', isTypingTarget({ tagName: 'TEXTAREA' }))
  check('SELECT is a typing target', isTypingTarget({ tagName: 'SELECT' }))
  check('contenteditable is a typing target', isTypingTarget({ tagName: 'DIV', isContentEditable: true }))
  check('a plain div is not', !isTypingTarget({ tagName: 'DIV' }))
  check('null target is not', !isTypingTarget(null))
  // Capture suspension — the armed rebind.
  check('no capture armed at rest', !isCaptureActive())
  const token = beginCapture()
  check('an armed capture suspends the hotkeys', isCaptureActive() && resolve('F5', { ...IDLE, capturing: true }) === null)
  endCapture(token)
  check('releasing the capture restores them', !isCaptureActive() && resolve('F5') === 'skip-eot')
  // The token model is idempotent: releasing twice, or releasing a stale token
  // after a newer capture was armed, never drops the NEWER capture.
  const a = beginCapture()
  const b = beginCapture()
  endCapture(a) // stale token — must be a no-op
  check('a stale release does not drop a newer capture', isCaptureActive())
  endCapture(b)
  check('the owner release does', !isCaptureActive())
  endCapture(b) // double release
  check('double release is harmless', !isCaptureActive())
}

// ─────────────────────────────────────────────────────────────────────────────
section('g. capture decisions — Escape cancels, modifiers ignored, junk reported')

{
  check('Escape cancels the capture', captureKeyDecision('Escape').kind === 'cancel')
  for (const mod of ['Shift', 'Control', 'Alt', 'Meta', 'CapsLock']) {
    check(`${mod} alone is ignored (keep waiting)`, captureKeyDecision(mod).kind === 'ignore')
  }
  const bind = captureKeyDecision('f5')
  check("'f5' binds as 'F5'", bind.kind === 'bind' && bind.key === 'F5', JSON.stringify(bind))
  const junk = captureKeyDecision('Enter')
  check('Enter is reported as invalid (not bound, not silently swallowed)', junk.kind === 'invalid', JSON.stringify(junk))
  check('...carrying the pressed key for the message', junk.kind === 'invalid' && junk.raw === 'Enter')
}

// ─────────────────────────────────────────────────────────────────────────────
section('h. replay interplay — the viewer\u2019s contract is untouched')

{
  const idle = { showSettings: false, contextMenuOpen: false, typing: false }
  check('idle: Space toggles play', replayHotkey(' ', idle) === 'play')
  check('idle: ArrowRight steps forward', replayHotkey('ArrowRight', idle) === 'next')
  check('idle: ArrowLeft steps back', replayHotkey('ArrowLeft', idle) === 'previous')
  check('idle: Escape exits the viewer', replayHotkey('Escape', idle) === 'exit')
  check('dialog open: Escape CLOSES THE DIALOG', replayHotkey('Escape', { ...idle, showSettings: true }) === 'close-settings')
  check('dialog open: Space stands down', replayHotkey(' ', { ...idle, showSettings: true }) === null)
  check('typing: Space reaches the field', replayHotkey(' ', { ...idle, typing: true }) === null)
  check('context menu open: Escape is the MENU\u2019s', replayHotkey('Escape', { ...idle, contextMenuOpen: true }) === null)
  // The replay rows are FIXED — an attempted rebind throws (API level).
  let threw = false
  try { rebindKeybinding('replay-play' as never, 'P') } catch { threw = true }
  check('rebinding a fixed replay row throws (fail fast)', threw)
  let resetThrew = false
  try { resetKeybinding('replay-exit' as never) } catch { resetThrew = true }
  check('resetting a fixed replay row throws (fail fast)', resetThrew)
  let unknownThrew = false
  try { rebindKeybinding('nope' as never, 'Q') } catch { unknownThrew = true }
  check('an unknown action id throws (fail fast)', unknownThrew)
  // ...and the replay keys still resolve to their own actions (the registry did
  // not take them away).
  check('the viewer\u2019s Space still resolves to replay-play',
    matchHotkeyAction({ key: ' ' }, IDLE, 'replay') === 'replay-play')
  check('the viewer\u2019s Escape still reaches its exit path (context-dependent — replayHotkey owns it)',
    replayHotkey('Escape', { showSettings: false, contextMenuOpen: false, typing: false }) === 'exit')
}

// ─────────────────────────────────────────────────────────────────────────────
section('i. rail wiring — registry consumption, live state, live button titles')

{
  const barSrc = read('src/components/ActionPromptBar.tsx')
  check('the rail resolves keys through matchHotkeyAction', /matchHotkeyAction\(/.test(barSrc))
  check('...with the capture guard', /capturing: isCaptureActive\(\)/.test(barSrc))
  check('...with the settings-dialog guard', /settingsOpen: live\.showSettings/.test(barSrc))
  check('...with the replay guard', /replayOpen: live\.inReplay/.test(barSrc))
  check('...with the typing guard (shared predicate)', /typing: isTypingTarget\(/.test(barSrc))
  check('...with the context-menu guard', /contextMenuOpen: live\.openContextMenu != null/.test(barSrc))
  check('the handler reads LIVE state (not the render closure)', /const live = useGameStore\.getState\(\)/.test(barSrc))
  check('the handler is bound once (guard reads live values)', /document\.addEventListener\('keydown', handleKeydown\)/.test(barSrc))
  check('NO hardcoded F-key switch cases remain', !/case 'F\d+'/.test(barSrc), (barSrc.match(/case 'F\d+'/g) ?? []).join(','))
  check('the handler switches on registry action ids', /switch \(action\)/.test(barSrc))
  check('the F8/F12 "unmapped" rationale is documented where the keys were', /F8[\s\S]{0,400}F12/.test(barSrc))
  check('the rail subscribes to binding changes (button titles follow a rebind)', /useSyncExternalStore\(subscribeKeybindings/.test(barSrc))
  check('...and the button titles come from the registry', /title=\{titleFor\('skip-eot'\)\}/.test(barSrc) && /title=\{titleFor\('skip-main'\)\}/.test(barSrc))
  check('no hardcoded title="F5" style labels survive', !/title="F\d/.test(barSrc))
  // The replay component still owns its hand-back, now via the shared predicate.
  const replaySrc = read('src/components/ReplayView.tsx')
  check('ReplayView still routes through replayHotkey()', /replayHotkey\(/.test(replaySrc))
  check('ReplayView re-exports the SHARED isTypingTarget (one definition)', /export \{ isTypingTarget \}/.test(replaySrc))
  check('ReplayView no longer defines its own copy', (replaySrc.match(/function isTypingTarget/g) ?? []).length === 0)
  check('the settings dialog renders a Hotkeys tab', SETTINGS_TABS.includes('Hotkeys' as never))
}

// ─────────────────────────────────────────────────────────────────────────────
section('j. rendered rail — the LIVE key shows on the button (SSR)')

{
  const ssrSnapshot = (useGameStore as unknown as { getInitialState: () => Record<string, unknown> }).getInitialState()
  const setLive = (patch: Record<string, unknown>) => {
    useGameStore.setState(patch as never)
    Object.assign(ssrSnapshot, patch)
  }
  setLive({
    actionPrompt: null,
    inReplay: false,
    showSettings: false,
    openContextMenu: null,
    gameState: {
      status: 'DUELING', turn: 3, phase: 'M1', step: 'PRECOMBAT_MAIN',
      activePlayer: 'QA-A', priorityPlayer: 'QA-A', stack: [], combat: [], players: [],
      llmDebug: [], revealed: [], lookedAt: [], stateSeq: 1,
    } as unknown as GameState,
  })
  const render = () => renderToStaticMarkup(createElement(ActionPromptBar))
  hardReset()
  {
    const html = render()
    check('the rail renders the default key on Skip EOT', html.includes('title="F5"'), html.slice(0, 0) + 'F5')
    check('the rail renders the default key on Skip Main', html.includes('title="F7"'))
    rebindKeybinding('skip-eot', 'E')
    const rebound = render()
    check('after a rebind the button shows the NEW key', rebound.includes('title="E"'))
    check('...and no longer shows the old one on that button', !rebound.includes('title="F5"'))
  }
  hardReset()
  check('the rail restores the default titles after a reset', render().includes('title="F5"'))
  // The dialog's own render must still work with the new tab present.
  setLive({ showSettings: true })
  const dialogHtml = renderToStaticMarkup(createElement(SettingsDialog))
  check('the settings dialog renders the Hotkeys panel', dialogHtml.includes('settings-panel-hotkeys'))
  check('...with the rebind control for every game action',
    (dialogHtml.match(/data-explain="settings-hotkeys-rebind"/g) ?? []).length === KEYBINDING_ACTIONS.filter((a) => a.rebindable).length)
  check('the three new explain ids are registered', ['settings-hotkeys-rebind', 'settings-hotkeys-reset', 'settings-hotkeys-reset-all'].every((id) => typeof EXPLAIN[id] === 'string'))
  setLive({ showSettings: false })
}

// ─────────────────────────────────────────────────────────────────────────────
section('k. load problems surface to the dialog (fail-visible, not fail-silent)')

{
  check('a clean start reports no problems (after all the resets above)', getKeybindingLoadProblems().length === 0,
    getKeybindingLoadProblems().join(' | '))
  check('getBindings covers every registered action', Object.keys(getBindings()).length === KEYBINDING_ACTIONS.length)
  check('defaultKeyFor returns the shipped default', defaultKeyFor('skip-eot') === 'F5')
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
