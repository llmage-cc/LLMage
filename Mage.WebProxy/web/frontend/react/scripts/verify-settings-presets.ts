/**
 * Settings-presets behavioural check (Java reference:
 * PreferencesDialog's named presets).
 *
 * Run with:  bash scripts/verify-settings-presets.sh
 *
 * Drives the REAL store + REAL registry modules under Node against the
 * localStorage stub from scripts/verify-stubs.mjs. No browser.
 *
 * What this pins, and why each part is here:
 *
 *   (a) COVERAGE — a preset captures EXACTLY what resetSettingsToDefaults
 *       covers: every STORE_SETTING_FIELDS entry, every
 *       STANDALONE_LOCALSTORAGE_DEFAULTS key and both module blobs. The
 *       harness derives the expected key set from those registries, so a
 *       setting added to the registry is either captured by the next save (and
 *       its captured value must round-trip here) or this check fails.
 *   (b) ROUND-TRIP — save from a mutated store, mutate everything again, load,
 *       and assert EVERY key came back: store fields, standalone keys, ding
 *       settings, token art. This is the "preset misses a setting" defect.
 *   (c) MIRROR REFRESH — after Load the store's priorityDing mirror must be
 *       re-read from the module (applyPreset ends with markSettingsReset), so
 *       the dialog does not show stale values.
 *   (d) FAIL FAST — corrupt stored JSON THROWS (never a silent reset to "no
 *       presets"), and a stored preset missing a registry key throws too.
 *   (e) listPresets/deletePreset/savePreset lifecycle, incl. name overwrite.
 */
import { useGameStore } from '../src/store/gameStore'
import {
  DEFAULT_SETTINGS,
  STORE_SETTING_FIELDS,
  STANDALONE_LOCALSTORAGE_DEFAULTS,
} from '../src/utils/settingsDefaults'
import {
  SETTINGS_PRESETS_KEY,
  applyPreset,
  capturePreset,
  deletePreset,
  listPresets,
  loadPreset,
  savePreset,
} from '../src/utils/settingsPresets'
import { getDingSettings, resetDingSettings, setDingSettings } from '../src/store/slices/priorityDing'
import {
  TOKEN_ART_OWN_KEY,
  TOKEN_ART_OVERRIDE_KEY,
  TOKEN_ART_SHARED_KEY,
  exportTokenArtPrefs,
  getAllOwnTokenArt,
  isOverrideOpponent,
  resetTokenArtPrefs,
  setOwnTokenArt,
  setOverrideOpponent,
  replaceSharedTokenArts,
} from '../src/utils/tokenArt'

let failures = 0
function check(name: string, cond: boolean, detail = ''): void {
  if (!cond) failures++
  console.log(`  [${cond ? 'PASS' : 'FAIL'}] ${name}${detail ? ' :: ' + detail : ''}`)
}
function section(t: string): void {
  console.log(`\n## ${t}`)
}

/**
 * Wipe every persisted key the settings surfaces own. The shared stub
 * (scripts/verify-stubs.mjs) implements getItem/setItem/removeItem only — no
 * clear()/length/key() — deliberately: this harness names the keys instead of
 * reaching for a broader API the real browser would have and the stub would
 * have to fake.
 */
function resetStorage(): void {
  localStorage.removeItem('xmage-settings')
  localStorage.removeItem('xmage-priority-ding')
  localStorage.removeItem(SETTINGS_PRESETS_KEY)
  localStorage.removeItem(TOKEN_ART_OWN_KEY)
  localStorage.removeItem(TOKEN_ART_SHARED_KEY)
  localStorage.removeItem(TOKEN_ART_OVERRIDE_KEY)
  for (const { key } of STANDALONE_LOCALSTORAGE_DEFAULTS) localStorage.removeItem(key)
}

const store = useGameStore

/** The store surface a preset needs — exactly what SettingsDialog passes. */
const storeSurface = () => store.getState()

// ── Fixture: mutate every registered setting to a NON-default value ──

const OTHER_LANDSCAPE = { r: 12, g: 34, b: 56 }

function mutateAllSettings(): void {
  const s = store.getState()
  s.setAutoScrollLog(!DEFAULT_SETTINGS.autoScrollLog)
  s.setAutoScrollChat(!DEFAULT_SETTINGS.autoScrollChat)
  s.setAutoScrollLLM(!DEFAULT_SETTINGS.autoScrollLLM)
  s.setFontScale(1.25)
  s.setCardScale(1.6)
  s.setUiScale(0.9)
  s.setTheme('light')
  s.setCardPreviewScale(2.2)
  s.setAutoFitBattlefield(!DEFAULT_SETTINGS.autoFitBattlefield)
  s.setHighlightPlayable(OTHER_LANDSCAPE)
  s.setHighlightCombat({ r: 1, g: 2, b: 3 })
  s.setHighlightTarget({ r: 4, g: 5, b: 6 })
  s.setHighlightManaTappable({ r: 7, g: 8, b: 9 })
  s.setHighlightActiveTurn({ r: 10, g: 11, b: 12 })
  // Event sounds — non-default so a preset that misses them is caught.
  s.setAudioEnabled(false)
  s.setAudioVolume(0.35)
  // Standalone localStorage keys (what GameView/GameLayout own).
  localStorage.setItem('xmage-left-col-w', '321')
  localStorage.setItem('xmage-right-col-w', '654')
  localStorage.setItem('xmage-battlefield-split', '37')
  // Module blobs.
  s.setPriorityDingEnabled(true)
  s.setPriorityDingVolume(0.15)
  s.setPriorityDingVariant('chime')
  s.setPriorityDingFocusMode('muted-unfocused')
  s.setPriorityDingFlashEnabled(false)
  s.setPriorityDingFlashIntensity(0.85)
  s.setPriorityDingRememberPosition(true)
  setOwnTokenArt('Soldier Token', 'https://cards.scryfall.io/token-soldier.jpg')
  replaceSharedTokenArts([
    { playerId: 'p2', tokenName: 'Soldier Token', url: 'https://cards.scryfall.io/p2-soldier.jpg' },
  ])
  setOwnTokenArt('Treasure Token', 'https://cards.scryfall.io/token-treasure.jpg')
  setOverrideOpponent(true)
}

/** Everything a preset must have captured, read from LIVE state. */
interface LiveSnapshot {
  settings: Record<string, unknown>
  standalone: Record<string, string | null>
  ding: unknown
  tokenArt: unknown
}

function liveSnapshot(): LiveSnapshot {
  const s = store.getState() as unknown as Record<string, unknown>
  const settings: Record<string, unknown> = {}
  for (const f of STORE_SETTING_FIELDS) settings[f] = JSON.parse(JSON.stringify(s[f]))
  const standalone: Record<string, string | null> = {}
  for (const { key } of STANDALONE_LOCALSTORAGE_DEFAULTS) standalone[key] = localStorage.getItem(key)
  return {
    settings,
    standalone,
    ding: JSON.parse(JSON.stringify(getDingSettings())),
    tokenArt: JSON.parse(JSON.stringify(exportTokenArtPrefs())),
  }
}

/** A snapshot different from `other` in EVERY captured key (a true "mutate again"). */
function flipSnapshot(other: LiveSnapshot): LiveSnapshot {
  const s = store.getState()
  const flips: Array<[string, unknown]> = [
    ['autoScrollLog', !s.autoScrollLog],
    ['autoScrollChat', !s.autoScrollChat],
    ['autoScrollLLM', !s.autoScrollLLM],
    ['fontScale', 3.3],
    ['cardScale', 0.55],
    ['uiScale', 4.2],
    ['theme', s.theme === 'dark' ? 'light' : 'dark'],
    ['cardPreviewScale', 3.9],
    ['autoFitBattlefield', !s.autoFitBattlefield],
    ['highlightPlayable', { r: 200, g: 201, b: 202 }],
    ['highlightCombat', { r: 203, g: 204, b: 205 }],
    ['highlightTarget', { r: 206, g: 207, b: 208 }],
    ['highlightManaTappable', { r: 209, g: 210, b: 211 }],
    ['highlightActiveTurn', { r: 212, g: 213, b: 214 }],
    // Event sounds — flip both halves of the captured pair.
    ['audioEnabled', !s.audioEnabled],
    ['audioVolume', s.audioVolume === 0.35 ? 0.9 : 0.35],
  ]
  for (const [field, value] of flips) {
    // Go through the store's own setter for the field (the registry's applier).
    const setterName = {
      autoScrollLog: 'setAutoScrollLog', autoScrollChat: 'setAutoScrollChat',
      autoScrollLLM: 'setAutoScrollLLM', fontScale: 'setFontScale', cardScale: 'setCardScale',
      uiScale: 'setUiScale', theme: 'setTheme', cardPreviewScale: 'setCardPreviewScale',
      autoFitBattlefield: 'setAutoFitBattlefield', highlightPlayable: 'setHighlightPlayable',
      highlightCombat: 'setHighlightCombat', highlightTarget: 'setHighlightTarget',
      highlightManaTappable: 'setHighlightManaTappable', highlightActiveTurn: 'setHighlightActiveTurn',
      audioEnabled: 'setAudioEnabled', audioVolume: 'setAudioVolume',
    }[field]!
    ;(store.getState() as unknown as Record<string, (v: unknown) => void>)[setterName]!(value)
  }
  localStorage.setItem('xmage-left-col-w', '999')
  localStorage.setItem('xmage-right-col-w', '888')
  localStorage.removeItem('xmage-battlefield-split')
  setDingSettings({
    enabled: false, volume: 1, variant: 'soft-click', focusMode: 'unfocused',
    flashEnabled: true, flashIntensity: 0, rememberPromptPosition: false,
  })
  resetTokenArtPrefs()
  const next = liveSnapshot()
  // Prove the two snapshots really differ on every captured key.
  const same: string[] = []
  for (const f of STORE_SETTING_FIELDS) {
    if (JSON.stringify(next.settings[f]) === JSON.stringify(other.settings[f])) same.push(f)
  }
  for (const { key } of STANDALONE_LOCALSTORAGE_DEFAULTS) {
    if (next.standalone[key] === other.standalone[key]) same.push(`standalone:${key}`)
  }
  if (JSON.stringify(next.ding) === JSON.stringify(other.ding)) same.push('ding')
  if (JSON.stringify(next.tokenArt) === JSON.stringify(other.tokenArt)) same.push('tokenArt')
  if (same.length) {
    console.log(`  [WARN] flipSnapshot did not change: ${same.join(', ')} — the round-trip check below may be vacuous for those keys`)
  }
  return next
}

// ── (a) Coverage: the captured preset holds every registered key ──

section('a. coverage — a preset captures exactly the reset registry')
resetStorage()
resetDingSettings()
resetTokenArtPrefs()
mutateAllSettings()
const savedLive = liveSnapshot()
const captured = capturePreset('coverage-probe', storeSurface())

for (const field of STORE_SETTING_FIELDS) {
  check(
    `captured store setting "${field}"`,
    JSON.stringify(captured.settings[field]) === JSON.stringify(savedLive.settings[field]),
    JSON.stringify(captured.settings[field]),
  )
}
for (const { key } of STANDALONE_LOCALSTORAGE_DEFAULTS) {
  check(
    `captured standalone key "${key}"`,
    (captured.standalone[key] ?? null) === (savedLive.standalone[key] ?? null),
    String(captured.standalone[key]),
  )
}
check('captured the priority-ding blob', JSON.stringify(captured.ding) === JSON.stringify(savedLive.ding))
check('captured the token-art blob', JSON.stringify(captured.tokenArt) === JSON.stringify(savedLive.tokenArt))
check(
  'captured key set == registry key set (no extra, none missing)',
  JSON.stringify(Object.keys(captured.settings).sort()) === JSON.stringify([...STORE_SETTING_FIELDS].sort()),
)

// ── (b) Round-trip: save → mutate everything → load ──

section('b. round-trip — save, mutate again, load, every key restored')
resetStorage()
resetDingSettings()
resetTokenArtPrefs()
mutateAllSettings()
const savedSnap = liveSnapshot()
savePreset('Workbench', storeSurface())
check('savePreset made the preset listable', listPresets().some((p) => p.name === 'Workbench'))

const mutatedSnap = flipSnapshot(savedSnap)
check(
  'the second mutation really differs from the saved state',
  JSON.stringify(mutatedSnap) !== JSON.stringify(savedSnap),
)

loadPreset('Workbench', storeSurface())
const afterLoad = liveSnapshot()

for (const field of STORE_SETTING_FIELDS) {
  check(
    `store setting "${field}" restored`,
    JSON.stringify(afterLoad.settings[field]) === JSON.stringify(savedSnap.settings[field]),
    `expected ${JSON.stringify(savedSnap.settings[field])}, got ${JSON.stringify(afterLoad.settings[field])}`,
  )
}
for (const { key } of STANDALONE_LOCALSTORAGE_DEFAULTS) {
  check(
    `standalone key "${key}" restored`,
    (afterLoad.standalone[key] ?? null) === (savedSnap.standalone[key] ?? null),
    `expected ${String(savedSnap.standalone[key])}, got ${String(afterLoad.standalone[key])}`,
  )
}
check(
  'priority-ding settings restored (module)',
  JSON.stringify(afterLoad.ding) === JSON.stringify(savedSnap.ding),
  JSON.stringify(afterLoad.ding),
)
check(
  'token-art preferences restored (own + shared + override)',
  JSON.stringify(afterLoad.tokenArt) === JSON.stringify(savedSnap.tokenArt),
  JSON.stringify(afterLoad.tokenArt),
)
check(
  'token-art restore reached the live accessors, not just localStorage',
  getAllOwnTokenArt()['Soldier Token'] === 'https://cards.scryfall.io/token-soldier.jpg' &&
    isOverrideOpponent() === true,
  JSON.stringify(getAllOwnTokenArt()),
)

// ── (c) Mirror refresh after Load ──

section('c. mirror refresh — the store mirror is re-read after Load')
check(
  'store.priorityDing mirror matches the module after Load',
  JSON.stringify(store.getState().priorityDing) === JSON.stringify(getDingSettings()),
  JSON.stringify(store.getState().priorityDing),
)
check('settingsResetAt was bumped by Load', store.getState().settingsResetAt > 0)

// ── (d) Fail Fast on corrupt / skewed stored data ──

section('d. fail fast — corrupt JSON throws, skewed presets throw')
localStorage.setItem(SETTINGS_PRESETS_KEY, '{ this is not json')
let corruptThrew = false
try {
  listPresets()
} catch (err) {
  corruptThrew = true
  check('corrupt JSON error message is surfaced', String(err).includes('JSON'), String(err))
}
check('corrupt stored JSON THROWS (no silent reset)', corruptThrew)
check(
  'the corrupt blob is left in place (not silently replaced)',
  localStorage.getItem(SETTINGS_PRESETS_KEY) === '{ this is not json',
)

resetStorage()
mutateAllSettings()
savePreset('Skewed', storeSurface())
{
  const raw = JSON.parse(localStorage.getItem(SETTINGS_PRESETS_KEY)!) as Record<string, { settings: Record<string, unknown> }>
  delete raw['Skewed']!.settings.fontScale
  localStorage.setItem(SETTINGS_PRESETS_KEY, JSON.stringify(raw))
  let skewThrew = false
  try {
    listPresets()
  } catch {
    skewThrew = true
  }
  check('a preset missing a registry key THROWS instead of half-applying', skewThrew)
}

// applyPreset must not accept a snapshot whose token-art block is garbage.
resetStorage()
let badArtThrew = false
try {
  applyPreset(storeSurface(), {
    version: 1, name: 'bad', savedAt: 1,
    settings: capturePreset('x', storeSurface()).settings,
    standalone: capturePreset('x', storeSurface()).standalone,
    ding: getDingSettings(),
    tokenArt: { own: null, shared: {}, overrideOpponent: false } as never,
  })
} catch {
  badArtThrew = true
}
check('applyPreset rejects a malformed token-art block', badArtThrew)

// ── (e) list / delete / overwrite lifecycle ──

section('e. lifecycle — list, overwrite by name, delete')
resetStorage()
resetDingSettings()
resetTokenArtPrefs()
mutateAllSettings()
savePreset('Alpha', storeSurface())
savePreset('Beta', storeSurface())
check('both presets listable', listPresets().length === 2, JSON.stringify(listPresets()))
check('list is newest-first', listPresets().map((p) => p.name).join(',') === 'Beta,Alpha' || listPresets()[0]!.savedAt >= listPresets()[1]!.savedAt, JSON.stringify(listPresets()))
check('blank name rejected', (() => { try { savePreset('   ', storeSurface()); return false } catch { return true } })())
check('unknown name on load throws', (() => { try { loadPreset('nope', storeSurface()); return false } catch { return true } })())
check('deletePreset(unknown) returns false', deletePreset('nope') === false)
check('deletePreset(Alpha) returns true', deletePreset('Alpha') === true)
check('deleted preset is gone', listPresets().map((p) => p.name).join(',') === 'Beta')

// Overwrite: save under the SAME name after a change, load, get the NEW values.
store.getState().setFontScale(7.7)
savePreset('Beta', storeSurface())
check('overwrite keeps one entry', listPresets().filter((p) => p.name === 'Beta').length === 1)
store.getState().setFontScale(1.0)
loadPreset('Beta', storeSurface())
check('overwrite loads the NEW values', store.getState().fontScale === 7.7, String(store.getState().fontScale))

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
