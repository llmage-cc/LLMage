/**
 * Player-avatar behavioural check (Java reference:
 * mage.client.game.PlayerPanelExt.updateAvatar + PreferencesDialog "My Avatar").
 *
 * Run with:  bash scripts/verify-avatars.sh
 *
 * Encodes, against the REAL shipped modules (no browser):
 *
 *  (a) RESOLVER — `utils/avatars.ts` is the single clamp: a selectable id maps
 *      to `/avatars/<id>.jpg`, the computer seat's 64 maps to i64.jpg, and every
 *      id it cannot draw (null/undefined — a replay or older payload; 0 — the
 *      previous hardcoded wire value; the special range ≥ 1000; any other number)
 *      maps to the DEFAULT avatar. "Unknown id → default, never a broken image"
 *      is therefore a property of the resolver, not an onError patch.
 *  (b) ASSETS — every id the resolver can RETURN has a file on disk under
 *      web/frontend/avatars/, and the picker's list (AVATAR_IDS, 10…32) matches
 *      what is shipped exactly. A missing/extra file is a failure.
 *  (c) PICKER — Settings → Appearance renders one button per selectable avatar
 *      (react-dom/server), marks the current one, and the click handler goes
 *      through the store setter.
 *  (d) PERSISTENCE — picking stores the id in `xmage-settings`; a reload reads
 *      it back; a corrupt/stale stored value (0, 999, "12") is repaired to the
 *      default on READ instead of wedging the picker.
 *  (e) WIRE — the connect payload carries `avatarId`; a live pick sends
 *      `set_avatar`; the proxy's `avatar_set` answer is adopted WITHOUT echoing
 *      another set_avatar back (the loop guard).
 *  (f) BOARD — PlayerBoard's header renders the seat's own avatar from the
 *      payload, MY seat falls back to the locally chosen avatar when the payload
 *      predates the field, an unknown seat id draws the default, and a computer
 *      seat draws i64.jpg (desktop parity: PlayerPanelExt:359-377).
 *  (g) RESET/PRESET — Reset to Default restores the avatar (through the same
 *      setter, so a live session is pushed the default), and settings presets
 *      capture/apply/validate the field (an unrenderable preset value is refused).
 *  (h) EXPLAIN — the picker carries a registered explain id (the gate is also run
 *      standalone by verify-explain-coverage.sh).
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { createElement } from 'react'
import { readdirSync, existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { useGameStore } from '../src/store/gameStore'
import { handleMessage } from '../src/network/messageHandlers'
import { PlayerBoard } from '../src/components/PlayerBoard'
import SettingsDialog from '../src/components/SettingsDialog'
import { buildConnectRequest } from '../src/utils/connectPayload'
import {
  AVATAR_IDS,
  AVATAR_MIN_ID,
  AVATAR_MAX_ID,
  COMPUTER_AVATAR_ID,
  DEFAULT_AVATAR_ID,
  avatarIdForSeat,
  avatarUrlFor,
  isPickableAvatarId,
  readStoredAvatarId,
  resolveAvatarId,
} from '../src/utils/avatars'
import { DEFAULT_SETTINGS, STORE_SETTING_FIELDS, resetSettingsToDefaults } from '../src/utils/settingsDefaults'
import { savePreset, loadPreset, listPresets } from '../src/utils/settingsPresets'
import { EXPLAIN } from '../src/hints/explain'
import type { Player } from '../src/types'

declare const process: { exit(code: number): void; cwd(): string }

// react-dom/server reads zustand's getServerSnapshot (the INITIAL state object).
// Mirror every setState into it so server renders see the scenario — same lever
// as verify-settings-tabs.tsx / verify-emblem-badge-clickable.tsx.
const ssrSnapshot = (useGameStore as unknown as { getInitialState: () => Record<string, unknown> }).getInitialState()
const setLive = (patch: Record<string, unknown>) => {
  useGameStore.setState(patch as never)
  Object.assign(ssrSnapshot, patch)
}

// BattlefieldZone/CardThumb read getComputedStyle (rootZoom); Node has no DOM.
;(globalThis as unknown as { getComputedStyle: () => { zoom: number } }).getComputedStyle = () => ({ zoom: 1 })

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) console.log(`  PASS  ${name}`)
  else { failures++; console.log(`  FAIL  ${name}${detail ? '  — ' + detail : ''}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

// ── wire capture (the REAL ws.send path) ──
const sent: Array<Record<string, unknown>> = []
useGameStore.getState().setWebSocket({
  send: (m: unknown) => { sent.push(m as Record<string, unknown>) },
  onStateVersionReceived: () => {},
  requestResync: () => {},
  clearResyncFlag: () => {},
  disconnect: () => {},
  noteConnectAccepted: () => {},
} as never)

// ─────────────────────────────────────────────────────────────────────────────
section('(a) resolver — one clamp, no id escapes it')

check('a selectable id resolves to itself', resolveAvatarId(17) === 17)
check('the first selectable id is drawable', resolveAvatarId(AVATAR_MIN_ID) === AVATAR_MIN_ID)
check('the last selectable id is drawable', resolveAvatarId(AVATAR_MAX_ID) === AVATAR_MAX_ID)
check('the computer seat keeps its own avatar', resolveAvatarId(COMPUTER_AVATAR_ID) === COMPUTER_AVATAR_ID)
check('the previous hardcoded 0 repairs to the default', resolveAvatarId(0) === DEFAULT_AVATAR_ID)
check('null (a replay/older payload) repairs to the default', resolveAvatarId(null) === DEFAULT_AVATAR_ID)
check('undefined repairs to the default', resolveAvatarId(undefined) === DEFAULT_AVATAR_ID)
check('NaN repairs to the default', resolveAvatarId(Number.NaN) === DEFAULT_AVATAR_ID)
check('an id below the range repairs to the default', resolveAvatarId(9) === DEFAULT_AVATAR_ID)
check('an id above the range repairs to the default', resolveAvatarId(33) === DEFAULT_AVATAR_ID)
check("the desktop's special range is not served", resolveAvatarId(1000) === DEFAULT_AVATAR_ID)
check('a negative id repairs to the default', resolveAvatarId(-1) === DEFAULT_AVATAR_ID)

check('the URL is /avatars/<id>.jpg', avatarUrlFor(21) === '/avatars/21.jpg')
check('an unknown id produces the DEFAULT url (never a broken path)',
  avatarUrlFor(999) === `/avatars/${DEFAULT_AVATAR_ID}.jpg`)
check('the computer seat produces i64.jpg', avatarUrlFor(64) === '/avatars/i64.jpg')

check('isPickableAvatarId accepts the range ends',
  isPickableAvatarId(AVATAR_MIN_ID) && isPickableAvatarId(AVATAR_MAX_ID))
check('isPickableAvatarId refuses 0 / 9 / 33 / 64 / non-integers',
  !isPickableAvatarId(0) && !isPickableAvatarId(9) && !isPickableAvatarId(33)
  && !isPickableAvatarId(64) && !isPickableAvatarId(12.5))

// ─────────────────────────────────────────────────────────────────────────────
section('(b) assets — every drawable id has a file, and the picker list matches')

const AVATARS_DIR = join(process.cwd(), '..', 'avatars')
const shipped = existsSync(AVATARS_DIR) ? readdirSync(AVATARS_DIR).sort() : []
check('the avatar directory exists (web/frontend/avatars)', existsSync(AVATARS_DIR), AVATARS_DIR)
for (const id of [...AVATAR_IDS, COMPUTER_AVATAR_ID]) {
  const file = id === COMPUTER_AVATAR_ID ? 'i64.jpg' : `${id}.jpg`
  check(`asset present: ${file}`, shipped.includes(file))
}
check(`the picker list is exactly ${AVATAR_IDS.length} ids (10…32)`,
  AVATAR_IDS.length === AVATAR_MAX_ID - AVATAR_MIN_ID + 1
  && AVATAR_IDS[0] === AVATAR_MIN_ID && AVATAR_IDS[AVATAR_IDS.length - 1] === AVATAR_MAX_ID)
check('no unlisted id is shipped (list and directory agree — no dead assets)',
  shipped.length === AVATAR_IDS.length + 1, `shipped=${shipped.join(',')}`)

// ─────────────────────────────────────────────────────────────────────────────
section('(c) picker — Settings → Appearance renders the grid and marks the current id')

setLive({ showSettings: true, avatarId: 21 })
const dialogHtml = renderToStaticMarkup(createElement(SettingsDialog as never))
const pickCount = (dialogHtml.match(/data-explain="settings-avatar-pick"/g) ?? []).length
check('every selectable avatar renders a pick button', pickCount === AVATAR_IDS.length, String(pickCount))
check('the picker renders image thumbs from /avatars/',
  dialogHtml.includes('src="/avatars/10.jpg"') && dialogHtml.includes('src="/avatars/32.jpg"'))
check('the CURRENT avatar is the pressed one',
  (dialogHtml.match(/aria-pressed="true"/g) ?? []).length === 1
  && /data-avatar-id="21"[^>]*aria-pressed="true"|aria-pressed="true"[^>]*data-avatar-id="21"/.test(dialogHtml)
  || dialogHtml.includes('aria-pressed="true"'),
  'avatar-pressed')
{
  // The marked button must contain the id-21 thumb — match the whole button
  // element (data-avatar-id rides the child <img>, so the opening tag alone
  // cannot prove which avatar is marked).
  const pressed = dialogHtml.match(/<button[^>]*aria-pressed="true"[^>]*>.*?<\/button>/s) ?? []
  check('...and the pressed button is id 21',
    pressed.length === 1 && pressed[0]!.includes('data-avatar-id="21"'), pressed.join('').slice(0, 120))
}
check('the picker explains itself with a REGISTERED explain id',
  dialogHtml.includes('data-explain="settings-avatar-pick"')
  && typeof (EXPLAIN as Record<string, string>)['settings-avatar-pick'] === 'string')

// ─────────────────────────────────────────────────────────────────────────────
section('(d) persistence — the pick is stored, read back, and repaired on read')

sent.length = 0
useGameStore.getState().setAvatarId(27)
check('picking stores the id in the store', useGameStore.getState().avatarId === 27)
{
  const blob = JSON.parse(localStorage.getItem('xmage-settings') ?? '{}')
  check('picking persists the id to xmage-settings', blob.avatarId === 27, JSON.stringify(blob.avatarId))
}
check('picking sends set_avatar with the id',
  sent.length === 1 && sent[0]!.type === 'set_avatar' && sent[0]!.avatarId === 27,
  JSON.stringify(sent))

// The read ladder is the untrusted-input path (localStorage is user-writable):
// a stale/hand-edited value must be repaired, never wedge the picker.
for (const bad of [0, 999, 33, '12', null, undefined, {}, 12.5, -1]) {
  check(`readStoredAvatarId(${JSON.stringify(bad) ?? 'undefined'}) repairs to the default`,
    readStoredAvatarId(bad) === DEFAULT_AVATAR_ID, String(readStoredAvatarId(bad)))
}
check('readStoredAvatarId keeps a legal id', readStoredAvatarId(18) === 18)
check('readStoredAvatarId keeps the range ends',
  readStoredAvatarId(AVATAR_MIN_ID) === AVATAR_MIN_ID && readStoredAvatarId(AVATAR_MAX_ID) === AVATAR_MAX_ID)

// ...and loadSettings must actually GO THROUGH that ladder — a wiring grep,
// because loadSettings() is module-init-only (no store seam to re-invoke).
{
  const src = readFileSync(join(process.cwd(), 'src', 'store', 'slices', 'uiSlice.ts'), 'utf8')
  check('loadSettings repairs the stored avatar through readStoredAvatarId',
    /avatarId:\s*readStoredAvatarId\(parsed\.avatarId\)/.test(src), 'uiSlice wiring')
  check('the persisted blob carries the avatarId field',
    /avatarId:\s*state\.avatarId/.test(src) || /avatarId: state\.avatarId/.test(src), 'save path')
}

// The setter refuses a value the picker cannot produce (Fail Fast, not silent repair).
let threw = false
try { useGameStore.getState().setAvatarId(64) } catch { threw = true }
check('the setter refuses an unselectable id (a programming error, not a wire value)', threw)

// ─────────────────────────────────────────────────────────────────────────────
section('(e) wire — connect carries it, picks send set_avatar, the answer is adopted without echo')

{
  const req = buildConnectRequest({
    hideServerTarget: true, server: '', port: '', username: 'qa', password: 'pw',
    authToken: '', avatarId: 23,
  }) as unknown as Record<string, unknown>
  check('the connect request carries avatarId', req.avatarId === 23, JSON.stringify(req.avatarId))
  const reqBad = buildConnectRequest({
    hideServerTarget: true, server: '', port: '', username: 'qa', password: 'pw',
    authToken: '', avatarId: 999,
  }) as unknown as Record<string, unknown>
  check('an unrenderable connect avatarId is clamped before the wire (never sent raw)',
    reqBad.avatarId === DEFAULT_AVATAR_ID, JSON.stringify(reqBad.avatarId))
}

sent.length = 0
setLive({ avatarId: 19 })
handleMessage({ type: 'avatar_set', avatarId: 19 } as never, useGameStore.getState())
check('the proxy answer is adopted', useGameStore.getState().avatarId === 19)
check('...and NOT echoed back as another set_avatar (no loop)',
  sent.filter((m) => m.type === 'set_avatar').length === 0, JSON.stringify(sent))

sent.length = 0
handleMessage({ type: 'avatar_set', avatarId: 777 } as never, useGameStore.getState())
check('a repaired answer (out-of-range wire value) is clamped on adopt',
  useGameStore.getState().avatarId === DEFAULT_AVATAR_ID, String(useGameStore.getState().avatarId))

// ─────────────────────────────────────────────────────────────────────────────
section('(f) board — each seat renders its own avatar, mine falls back locally')

function player(overrides: Partial<Player> = {}): Player {
  return {
    name: 'QA-A', playerId: 'p1', life: 24, isMe: true, libraryCount: 33, handCount: 0,
    isActive: true, hasPriority: true, hasLeft: false, timerActive: false, priorityTimeLeftSecs: 0,
    isMonarch: false, hasInitiative: false, passedTurn: false, passedUntilEndOfTurn: false,
    passedUntilNextMain: false, passedAllTurns: false, passedUntilStackResolved: false,
    passedUntilEndStepBeforeMyTurn: false, manaPool: null, counters: [], commanders: [],
    emblems: [], planes: [], battlefield: [], hand: [], graveyard: [], exile: [],
    ...overrides,
  } as Player
}
const renderBoard = (p: Player, isTop = false) => renderToStaticMarkup(
  createElement(PlayerBoard as never, { player: p, isTopPlayer: isTop, isCommander: false, isActiveTurn: false, hasPriority: false } as never))

function avatarTag(html: string): string {
  const i = html.indexOf('player-avatar')
  if (i < 0) return ''
  return html.slice(html.lastIndexOf('<img', i), html.indexOf('>', i) + 1)
}

{
  const mine = renderBoard(player({ avatarId: 24, isMe: true }))
  check('my seat renders MY payload avatar', avatarTag(mine).includes('src="/avatars/24.jpg"'), avatarTag(mine))
  check('the rendered seat id is visible for debugging', avatarTag(mine).includes('data-avatar-id="24"'))

  const opp = renderBoard(player({ name: 'AI-1', playerId: 'p2', isMe: false, avatarId: 13 }))
  check("the opponent's own payload avatar renders for that seat",
    avatarTag(opp).includes('src="/avatars/13.jpg"'), avatarTag(opp))

  const computer = renderBoard(player({ name: 'Computer', playerId: 'p3', isMe: false, avatarId: COMPUTER_AVATAR_ID }))
  check('a computer seat renders i64.jpg (desktop parity)', avatarTag(computer).includes('src="/avatars/i64.jpg"'), avatarTag(computer))
}

{
  // A payload from BEFORE the additive field: my seat uses my local choice, the
  // other seat draws the default — never a broken image and never a blank.
  setLive({ avatarId: 28 })
  const mine = renderBoard(player({ avatarId: undefined, isMe: true }))
  check('my seat falls back to my LOCAL avatar when the payload predates the field',
    avatarTag(mine).includes('src="/avatars/28.jpg"'), avatarTag(mine))
  const opp = renderBoard(player({ name: 'AI-1', playerId: 'p2', isMe: false, avatarId: undefined }))
  check("another seat with no payload avatar draws the DEFAULT (its choice is unknown to me)",
    avatarTag(opp).includes(`src="/avatars/${DEFAULT_AVATAR_ID}.jpg"`), avatarTag(opp))

  const bogus = renderBoard(player({ name: 'X', playerId: 'p4', isMe: false, avatarId: 4242 }))
  check('a corrupt seat id draws the default — no broken image path',
    avatarTag(bogus).includes(`src="/avatars/${DEFAULT_AVATAR_ID}.jpg"`), avatarTag(bogus))
}

{
  const me = player({ avatarId: 16, isMe: true })
  check('avatarIdForSeat: the payload wins when present',
    avatarIdForSeat(16, true, 30) === 16)
  check('avatarIdForSeat: my local choice when the payload is absent',
    avatarIdForSeat(undefined, true, 30) === 30)
  check('avatarIdForSeat: the default for another seat with no payload',
    avatarIdForSeat(null, false, 30) === DEFAULT_AVATAR_ID)
}

// ─────────────────────────────────────────────────────────────────────────────
section('(g) reset + presets cover the avatar')

check('the avatar is in the reset registry',
  (STORE_SETTING_FIELDS as readonly string[]).includes('avatarId'))
check('its registered default is the desktop default',
  (DEFAULT_SETTINGS as unknown as Record<string, unknown>).avatarId === DEFAULT_AVATAR_ID)

sent.length = 0
setLive({ avatarId: 30 })
resetSettingsToDefaults(useGameStore.getState() as never)
check('Reset to Default restores the avatar', useGameStore.getState().avatarId === DEFAULT_AVATAR_ID)
check('...through the setter, so a live session is pushed the default too',
  sent.some((m) => m.type === 'set_avatar' && m.avatarId === DEFAULT_AVATAR_ID), JSON.stringify(sent))

setLive({ avatarId: 31 })
savePreset('AvatarProbe', useGameStore.getState() as never)
check('the preset was saved', listPresets().some((p) => p.name === 'AvatarProbe'))
setLive({ avatarId: 11 })
loadPreset('AvatarProbe', useGameStore.getState() as never)
check('a preset restores the captured avatar', useGameStore.getState().avatarId === 31,
  String(useGameStore.getState().avatarId))

{
  // An unrenderable avatar in a stored preset must be REFUSED, not silently
  // applied (same fail-fast posture as the theme enum).
  const all = JSON.parse(localStorage.getItem('xmage-settings-presets') ?? '{}')
  all.AvatarProbe.settings.avatarId = 4321
  localStorage.setItem('xmage-settings-presets', JSON.stringify(all))
  let refused = false
  try { loadPreset('AvatarProbe', useGameStore.getState() as never) } catch { refused = true }
  check('a preset with an unrenderable avatar is refused (fail fast)', refused)
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
