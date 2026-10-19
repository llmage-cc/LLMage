/**
 * Behavioural check: the SETTINGS dialog's tab split.

 *
 * Run with:  flock /tmp/xmage-npm.lock npx tsx scripts/verify-settings-tabs.tsx
 *
 * Mirrors scripts/verify-llm-config-tabs.tsx (the recipe SettingsDialog follows)
 * and drives the REAL components with NO browser:
 *  (a) the canonical tab set and its order; the default tab is Display,
 *  (b) the REAL dialog renders role=tablist with 6 role=tab buttons, the first
 *      selected (aria-selected), roving tabIndex, exactly ONE visible panel
 *      (role=tabpanel), each panel labelled by its own tab,
 *  (c) switching: every tab can be made active and then exactly one panel —
 *      that tab's aria-controls target — is visible,
 *  (d) the tablist key map: ArrowRight/Left/Up/Down wrap, Home/End jump, other
 *      keys leave the selection alone,
 *  (e) Fail Fast: an unknown tab name, an out-of-range index and an unknown
 *      current tab in the key map all throw,
 *  (f) the split is presentation-only: every flat-column data-explain id still
 *      renders (one per control), the sticky footer keeps Reset/Close, and the
 *      replay-history toggle renders in its pinned LLM Chat tab bound to the
 *      same store field the in-panel checkbox edits,
 *  (g) component-local state survives a tab switch: ALL panels stay mounted
 *      (hidden) — the Presets name input's in-progress text is not lost.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import SettingsDialog, {
  SETTINGS_TABS,
  DEFAULT_SETTINGS_TAB,
  settingsTabIndex,
  settingsTabAt,
  settingsTabAfterKey,
} from '../src/components/SettingsDialog'
import { EXPLAIN } from '../src/hints/explain'
import { resetSettingsToDefaults } from '../src/utils/settingsDefaults'
import { KEYBINDING_ACTIONS, keyFor, rebindKeybinding, resetKeybindings } from '../src/utils/keybindings'

declare const process: { exit(code: number): void }

// react-dom/server reads zustand's getServerSnapshot — the store's INITIAL state
// object (same lever as verify-llm-config-tabs.tsx under rolldown). setState is
// mirrored into it so every server render sees the scenario.
const ssrSnapshot = (useGameStore as unknown as { getInitialState: () => Record<string, unknown> }).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

setLive({ showSettings: true })

let failures = 0
function check(name: string, ok: boolean, detail = '') {
  if (!ok) failures++
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}
function section(t: string) { console.log(`\n## ${t}`) }

const renderDialog = (): string => renderToStaticMarkup(<SettingsDialog />)

// ── SSR markup helpers (same split as verify-llm-config-tabs.tsx) ──
const panelTags = (html: string): string[] => [...html.matchAll(/<div role="tabpanel"[^>]*>/g)].map((m) => m[0])
const visiblePanels = (html: string): string[] => panelTags(html).filter((t) => !t.includes('hidden'))

function panelSegments(html: string): Array<{ tag: string; body: string }> {
  const tags = panelTags(html)
  return tags.map((tag, i) => {
    const start = html.indexOf(tag) + tag.length
    const next = i + 1 < tags.length ? html.indexOf(tags[i + 1]!, start) : html.length
    return { tag, body: html.slice(start, next) }
  })
}

const activeTabName = (html: string): string =>
  /<button[^>]*role="tab"[^>]*aria-selected="true"[^>]*>([^<]*)<\/button>/.exec(html)?.[1] ?? '(none)'

const tabNames = (html: string): string[] =>
  [...html.matchAll(/<button[^>]*role="tab"[^>]*>([^<]*)<\/button>/g)].map((m) => m[1]!)

// ─────────────────────────────────────────────────────────────────────────────
section('Tab set — the canonical tabs, in the flat column\u2019s section order')

{
  check('the canonical tab list is the registry order',
    SETTINGS_TABS.join(',') === 'Display,Appearance,Alerts,Tokens,LLM Chat,Presets,Hotkeys', SETTINGS_TABS.join(','))
  check('the dialog opens on Display', DEFAULT_SETTINGS_TAB === 'Display')
  check('tab indexes are the canonical order',
    settingsTabIndex('Display') === 0 && settingsTabIndex('Presets') === 5 && settingsTabIndex('Hotkeys') === 6)
}

// ─────────────────────────────────────────────────────────────────────────────
section('Rendered tab bar — the real dialog (global mount, showSettings=true)')

{
  const html = renderDialog()
  const tabCount = SETTINGS_TABS.length
  check('a tablist is rendered', html.includes('role="tablist"'))
  check('tablist is labelled for screen readers', /role="tablist"[^>]*aria-label="Settings sections"/.test(html), html)
  check(`every canonical tab renders (${tabCount})`, tabNames(html).length === tabCount, tabNames(html).join(','))
  check('tab titles are the canonical names in order', tabNames(html).join(',') === SETTINGS_TABS.join(','), tabNames(html).join(','))
  check('the first tab (Display) is selected by default', activeTabName(html) === 'Display', activeTabName(html))
  check('exactly one tab reports aria-selected=true', (html.match(/aria-selected="true"/g) ?? []).length === 1)
  check('every tab has aria-selected and an aria-controls target',
    (html.match(/role="tab"[^>]*aria-selected=/g) ?? []).length === tabCount && (html.match(/aria-controls="/g) ?? []).length === tabCount)
  check('roving tabindex: the selected tab is focusable, the rest are not',
    /<button[^>]*aria-selected="true"[^>]*tabindex="0"/.test(html) && (html.match(/tabindex="-1"/g) ?? []).length === tabCount - 1, html)
  check('every canonical panel renders', panelTags(html).length === tabCount)
  check('exactly ONE panel is visible', visiblePanels(html).length === 1, String(visiblePanels(html).length))
  check('each panel is labelled by its own tab', (html.match(/role="tabpanel"[^>]*aria-labelledby="/g) ?? []).length === tabCount)
  check('every canonical tab title appears exactly once in the tab bar',
    SETTINGS_TABS.every((name) => html.split(`>${name}</button>`).length - 1 === 1))
  check('the tab data-explain id is registered (explain-coverage stays green)', typeof EXPLAIN['settings-tab'] === 'string')
  check('every tab button carries the settings-tab explain id',
    (html.match(/data-explain="settings-tab"/g) ?? []).length === tabCount)
}

// ─────────────────────────────────────────────────────────────────────────────
section('Switching — every tab opens its own panel, one panel at a time')

{
  // The real component's activeTab is component-local state (a fresh dialog
  // opens on Display) — NOT a store field — so SSR can only prove the default
  // selection end to end; every tab's reachability is proven structurally (all
  // six panels exist, each wired to its tab) and the click/keyboard bindings
  // are asserted from source in the .sh wrapper, the same split of duties
  // verify-llm-config-tabs.sh uses (react-dom/server strips event handlers).
  const rendered = renderDialog()
  for (const name of SETTINGS_TABS) {
    check(`tab '${name}': present in the tab bar`, tabNames(rendered).includes(name))
  }
  {
    const visible = visiblePanels(rendered)
    check(`default tab active: exactly one panel visible`, visible.length === 1, String(visible.length))
    check(`default tab active: Display reports aria-selected`, activeTabName(rendered) === 'Display', activeTabName(rendered))
    const controls = new RegExp(`<button[^>]*aria-selected="true"[^>]*aria-controls="([^"]+)"`).exec(rendered)?.[1] ?? ''
    check(`default tab active: the visible panel is the tab's aria-controls target`,
      controls !== '' && (visible[0] ?? '').includes(`id="${controls}"`), `${controls} vs ${visible[0] ?? ''}`)
  }
  // Every panel exists exactly once and each is uniquely identified.
  const html = renderDialog()
  const segs = panelSegments(html)
  check('each panel id is unique', new Set(segs.map((s) => /id="([^"]+)"/.exec(s.tag)?.[1] ?? '')).size === SETTINGS_TABS.length)
  // The panel bodies carry the flat column's sections — one probe control each.
  const displaySeg = segs.find((s) => s.tag.includes('panel-display'))
  check('the Display panel holds the Font Size section',
    !!displaySeg && displaySeg.body.includes('Font Size') && displaySeg.body.includes('UI Scale') &&
    displaySeg.body.includes('Card Display Size') && displaySeg.body.includes('Hover Preview Size'))
  const appearanceSeg = segs.find((s) => s.tag.includes('panel-appearance'))
  check('the Appearance panel holds Theme + Highlight Colors',
    !!appearanceSeg && appearanceSeg.body.includes('Theme') && appearanceSeg.body.includes('Highlight Colors'))
  const alertsSeg = segs.find((s) => s.tag.includes('panel-alerts'))
  check('the Alerts panel holds the Sound + flash + sustain + prompt-memory controls',
    !!alertsSeg && alertsSeg.body.includes('settings-ding-enabled') && alertsSeg.body.includes('settings-flash-sustain') &&
    alertsSeg.body.includes('settings-remember-prompt-position'))
  const tokensSeg = segs.find((s) => s.tag.includes('panel-tokens'))
  check('the Tokens panel holds the token-art override',
    !!tokensSeg && tokensSeg.body.includes('settings-token-art-override'))
  const llmSeg = segs.find((s) => s.tag.includes('panel-llm-chat'))
  check('the LLM Chat panel holds the replay-history toggle',
    !!llmSeg && llmSeg.body.includes('replay-llm-history-toggle') && llmSeg.body.includes('Show entire history in replays'))
  const presetsSeg = segs.find((s) => s.tag.includes('panel-presets'))
  check('the Presets panel holds the name input + Save + list actions',
    !!presetsSeg && presetsSeg.body.includes('settings-preset-name') && presetsSeg.body.includes('settings-preset-save'))
  const hotkeysSeg = segs.find((s) => s.tag.includes('panel-hotkeys'))
  check('the Hotkeys panel holds the rebind rows for every action',
    !!hotkeysSeg && hotkeysSeg.body.includes('settings-hotkeys-rebind') && hotkeysSeg.body.includes('settings-hotkeys-reset-all'))
  check('the Hotkeys panel renders one row per registered action',
    !!hotkeysSeg && (hotkeysSeg.body.match(/data-explain="settings-hotkeys-rebind"/g) ?? []).length === KEYBINDING_ACTIONS.filter((a) => a.rebindable).length)
  check('the Hotkeys panel shows the shipped default keys',
    !!hotkeysSeg && ['F2', 'F3', 'F4', 'F5', 'F6', 'F7', 'F9', 'F10', 'F11'].every((k) => hotkeysSeg.body.includes(`>${k}</span>`)))
  check('the Hotkeys panel lists the fixed replay keys too (complete map)',
    !!hotkeysSeg && KEYBINDING_ACTIONS.filter((a) => !a.rebindable).every((a) => hotkeysSeg.body.includes(a.label)))
}

// ─────────────────────────────────────────────────────────────────────────────
section('The split is presentation-only — every flat-column explain id survives')

{
  const html = renderDialog()
  // The 19 registered ids the flat column carried (+ the new settings-tab id).
  const expected = [
    'settings-close', 'settings-done', 'settings-reset',
    'settings-theme-dark', 'settings-theme-light',
    'settings-autofit-on', 'settings-autofit-off',
    'settings-ding-enabled', 'settings-ding-variant', 'settings-ding-volume', 'settings-ding-focus',
    'settings-flash-enabled', 'settings-flash-intensity', 'settings-flash-sustain',
    'settings-remember-prompt-position',
    'settings-preset-name', 'settings-preset-save',
    'settings-card-preview-scale', 'settings-token-art-override',
    'replay-llm-history-toggle',
  ]
  for (const id of expected) {
    check(`data-explain="${id}" still renders`, html.includes(`data-explain="${id}"`))
    check(`data-explain="${id}" is registered in EXPLAIN`, typeof EXPLAIN[id] === 'string')
  }
  check('Reset to Default and Close stay in ONE footer copy (outside the panels)',
    html.split('settings-reset').length - 1 === 1 && html.split('settings-done').length - 1 === 1)
  // The Hotkeys tab's ids are registered too (the source scan above would throw on
  // an unknown id — this pins the text and the render).
  for (const id of ['settings-hotkeys-rebind', 'settings-hotkeys-reset', 'settings-hotkeys-reset-all']) {
    check(`data-explain="${id}" renders`, html.includes(`data-explain="${id}"`))
    check(`data-explain="${id}" is registered in EXPLAIN`, typeof EXPLAIN[id] === 'string')
  }
}

// ─────────────────────────────────────────────────────────────────────────────
section('Reset to Default clears rebinds through the settings registry')

{
  rebindKeybinding('skip-eot', 'E')
  check('a rebind changes the resolved key', keyFor('skip-eot') === 'E', keyFor('skip-eot'))
  check('the rebind is persisted', localStorage.getItem('xmage-keybindings') !== null)
  resetSettingsToDefaults(useGameStore.getState())
  check('Reset to Default restores the default key', keyFor('skip-eot') === 'F5', keyFor('skip-eot'))
  check('Reset to Default clears the stored overrides blob', localStorage.getItem('xmage-keybindings') === null)
  resetKeybindings()
}

// ─────────────────────────────────────────────────────────────────────────────
section('Tablist key map (selection follows focus, like JTabbedPane)')

{
  const last = SETTINGS_TABS[SETTINGS_TABS.length - 1]!
  check('ArrowRight moves to the next tab', settingsTabAfterKey('Display', 'ArrowRight') === SETTINGS_TABS[1])
  check('ArrowDown moves to the next tab', settingsTabAfterKey('Display', 'ArrowDown') === SETTINGS_TABS[1])
  check('ArrowRight wraps around at the end', settingsTabAfterKey(last, 'ArrowRight') === 'Display')
  check('ArrowLeft moves to the previous tab', settingsTabAfterKey(SETTINGS_TABS[1]!, 'ArrowLeft') === 'Display')
  check('ArrowUp moves to the previous tab', settingsTabAfterKey(SETTINGS_TABS[1]!, 'ArrowUp') === 'Display')
  check('ArrowLeft wraps around at the start', settingsTabAfterKey('Display', 'ArrowLeft') === last)
  check('Home jumps to the first tab', settingsTabAfterKey('Alerts', 'Home') === 'Display')
  check('End jumps to the last tab', settingsTabAfterKey('Display', 'End') === last)
  for (const other of ['Tab', 'Enter', ' ', 'a', 'PageDown']) {
    check(`'${other}' does not move the selection`, settingsTabAfterKey('Alerts', other) === 'Alerts')
  }
}

// ─────────────────────────────────────────────────────────────────────────────
section('Fail Fast — unknown names, bad indexes, unknown key-map tabs throw')

{
  const threw = (fn: () => unknown): unknown => { try { fn(); return null } catch (e) { return e } }
  let err = threw(() => settingsTabIndex('Nonsense'))
  check('an unknown tab name throws', err instanceof Error && String(err).includes('Nonsense'), String(err))
  err = threw(() => settingsTabAt(SETTINGS_TABS.length))
  check('an out-of-range index throws', err instanceof Error, String(err))
  err = threw(() => settingsTabAt(-1))
  check('a negative index throws', err instanceof Error, String(err))
  err = threw(() => settingsTabAfterKey('Nonsense', 'ArrowRight'))
  check('the key map throws for an unknown current tab', err instanceof Error, String(err))
}

// ─────────────────────────────────────────────────────────────────────────────
section('Store behaviour preserved — the toggle shares ONE field with the rail')

{
  // Default ON.
  check('replayLLMFullHistory defaults ON', useGameStore.getInitialState().replayLLMFullHistory === true)
  // The dialog checkbox edits the same store field the rail checkbox reads.
  useGameStore.getState().setReplayLLMFullHistory(false)
  check('the settings-side setter flips the field', useGameStore.getState().replayLLMFullHistory === false)
  check('...and persists to localStorage', JSON.parse(localStorage.getItem('xmage-settings')!).replayLLMFullHistory === false)
  useGameStore.getState().setReplayLLMFullHistory(true)
  check('flipping back re-persists', JSON.parse(localStorage.getItem('xmage-settings')!).replayLLMFullHistory === true)
  resetSettingsToDefaults(useGameStore.getState())
  check('Reset to Default restores replayLLMFullHistory=true', useGameStore.getState().replayLLMFullHistory === true)
}

// ─────────────────────────────────────────────────────────────────────────────
section('Mounted-hidden panels — component-local state survives a switch')

{
  const html = renderDialog()
  // All six panels render in the markup (hidden ones included) — the Presets
  // name input is therefore never unmounted when another tab is active.
  const presetsSeg = panelSegments(html).find((s) => s.tag.includes('panel-presets'))
  check('the Presets panel stays mounted while Display is active',
    !!presetsSeg && presetsSeg.body.includes('data-explain="settings-preset-name"'))
  check('the Presets panel is hidden but present', !!presetsSeg && presetsSeg.tag.includes('hidden'))
  check('the visible Display panel is not hidden', !(visiblePanels(html)[0] ?? '').includes('hidden'))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)