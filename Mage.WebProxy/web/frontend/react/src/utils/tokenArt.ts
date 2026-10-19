/**
 * Token art: print-version selection, per-player preferences, and the resolution
 * chain that walks candidate printings until one actually loads.
 *
 * ## The problem this solves
 *
 * A token could not be rendered at all from its name:
 *   - `named?exact=Soldier` resolves to an unrelated card (`Goblin // Soldier`),
 *     so a "Soldier Token" got someone else's art, or nothing.
 *   - `named?exact=Soldier Token` is a 404 — Scryfall does not use XMage's
 *     " Token" suffix.
 * And `isTokenCard()` used to test `name.includes(' Token')`, which misses the
 * 116 XMage tokens with no suffix. Every such token fell through to the broken
 * named lookup and rendered as an empty tile.
 *
 * ## What the engine's token printing fields mean (and do not mean)
 *
 * `GameStateSerializer` sends `tokenSetCode` and `tokenImageNumber` for a token.
 *
 *   - `tokenSetCode` is the set the engine recorded — the SOURCE CARD's set. It is
 *     RELIABLE, and it is what makes "a Soldier from a Ravnica card shows a Ravnica
 *     Soldier" possible.
 *   - `tokenImageNumber` is an XMage-internal index. It is NOT a Scryfall collector
 *     number, and pasting it into `cards/t<set>/<n>` renders a DIFFERENT TOKEN:
 *     `RTR|Soldier|` → `trtr/1` is "Bird" (the real Ravnica Soldier is `trtr/3`), and
 *     `WAR|Servo|` → `twar/1` is "Spirit". 1937 of 2529 rows carry an empty index, so
 *     they all collapsed to `<set>/1` — whatever creature that happens to be.
 *
 * So the set is used to CHOOSE art and never to BUILD a URL; the proxy returns real
 * printings from a Scryfall name search. Do not reintroduce a URL built from
 * `tokenImageNumber` — a wrong token's art is still a valid image, so the mistake does
 * not look like a failure, it looks like a different creature.
 *
 * ## Preference model
 *
 * Two distinct layers, deliberately not the same thing:
 *
 * 1. **Per-player, shared** — the art a player picks for a token they control.
 *    Broadcast to the table through the proxy, so `defaultsFor[tokenName]` lets
 *    the other players see the owner's chosen art.
 * 2. **Viewer-local** — what *I* want to look at. `ownDefault` is my art for a
 *    token type regardless of who controls it. `overrideOpponent` decides whether
 *    that beats the owner's shared choice:
 *      - override ON  → my art wins everywhere, including on the opponent's board.
 *      - override OFF → the owner's shared choice wins on their board, and my art
 *        applies only where no owner choice exists ("set a default for the
 *        opponent if they haven't set their own").
 *
 * This is a display preference of the viewer, so it lives in localStorage and is
 * never relayed — relaying it would let one player restyle another player's board
 * out from under them.
 */

/** One candidate art for a token, as returned by the proxy. */
export interface TokenArtVersion {
  url: string
  setCode?: string | undefined
  collectorNumber?: string | undefined
  /** Human-readable set name, for the picker ("Return to Ravnica Tokens"). */
  setName?: string | undefined
  /** Release date of the printing, for sorting/labelling. */
  releasedAt?: string | undefined
  /** 'scryfall-token' = a token printing; 'scryfall-card' = a token copying a card. */
  source?: string | undefined
}

/** The proxy's answer to a `token_versions` request. */
export interface TokenVersionsResult {
  tokenName: string
  versions: TokenArtVersion[]
  /** The engine's intended art — the printing from the token's own set, when it has one. */
  defaultUrl?: string | undefined
  /** True when Scryfall could not be reached — "unknown", not "no art exists". */
  fetchFailed?: boolean | undefined
}

/** One player's shared choice, as relayed by the proxy. */
export interface SharedTokenPref {
  playerId: string
  tokenName: string
  url: string
  setCode?: string | undefined
  collectorNumber?: string | undefined
  playerName?: string | undefined
}

// ── localStorage keys ──

/** `{ [tokenName]: url }` — my preferred art per token type. */
export const TOKEN_ART_OWN_KEY = 'xmage-token-art-own'

/** `{ [ownerPlayerId]: { [tokenName]: url } }` — choices relayed from the table. */
export const TOKEN_ART_SHARED_KEY = 'xmage-token-art-shared'

/** '1' = my art overrides the owner's shared choice on their board too. */
export const TOKEN_ART_OVERRIDE_KEY = 'xmage-token-art-override'

/** Cap on stored preferences, so a long-running client cannot grow unbounded. */
const MAX_PREFS = 2000

// ── In-memory state ──
//
// A plain module-level store (not the Zustand store) because this is read by
// CardThumb, which renders for every card on the board and is memoized: routing
// a change through the store would re-render every tile. Readers subscribe via
// the change notifier instead, and only tiles whose resolved URL actually
// changed re-render.

type OwnPrefs = Record<string, string>
type SharedPrefs = Record<string, Record<string, string>>

let ownPrefs: OwnPrefs = {}
let sharedPrefs: SharedPrefs = {}
let overrideOpponent = false

/** Candidate lists fetched from the proxy, keyed by token name. */
const versionsCache = new Map<string, TokenVersionsResult>()

/** In-flight `token_versions` requests, keyed by `name|expand`. */
const inflight = new Map<string, Promise<TokenVersionsResult>>()

/** Listeners notified when preferences or resolved art change. */
const listeners = new Set<() => void>()

/**
 * Bumped on every change so `useSyncExternalStore` can detect one. React compares the
 * snapshot by identity, so this must be a primitive that changes — returning a fresh
 * object would re-render forever.
 */
let revision = 0

/** The current revision, as a `useSyncExternalStore` snapshot. */
export function tokenArtRevision(): number {
  return revision
}

function notify(): void {
  revision++
  for (const l of listeners) {
    try {
      l()
    } catch {
      /* a listener must not break the others */
    }
  }
}

/** Subscribe to preference/resolution changes. Returns an unsubscribe function. */
export function subscribeTokenArt(fn: () => void): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

// ── Persistence ──

function readJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return fallback
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? (parsed as T) : fallback
  } catch {
    return fallback
  }
}

function writeJson(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    /* localStorage unavailable (privacy mode) — preferences stay in memory */
  }
}

/** Load persisted preferences. Called once at app start. */
export function loadTokenArtPrefs(): void {
  ownPrefs = readJson<OwnPrefs>(TOKEN_ART_OWN_KEY, {})
  sharedPrefs = readJson<SharedPrefs>(TOKEN_ART_SHARED_KEY, {})
  overrideOpponent = readOverrideFlag()
  notify()
}

/** The override flag is a bare '1' string, not JSON — read it directly. */
function readOverrideFlag(): boolean {
  try {
    return localStorage.getItem(TOKEN_ART_OVERRIDE_KEY) === '1'
  } catch {
    return false
  }
}

/** Reset to defaults (Settings → Reset). */
export function resetTokenArtPrefs(): void {
  ownPrefs = {}
  sharedPrefs = {}
  overrideOpponent = false
  try {
    localStorage.removeItem(TOKEN_ART_OWN_KEY)
    localStorage.removeItem(TOKEN_ART_SHARED_KEY)
    localStorage.removeItem(TOKEN_ART_OVERRIDE_KEY)
  } catch {
    /* ignore */
  }
  notify()
}

// ── Snapshot / restore (settings presets) ──
//
// A settings preset must capture this module's THREE preference layers and
// restore them through the module (writing localStorage alone would leave the
// live in-memory state — what CardThumb actually reads — stale until reload).
// export/import is the minimal pair for that; the reset path above stays the
// settingsDefaults registry's integration point.

/** Full token-art preference state, as captured by a settings preset. */
export interface TokenArtSnapshot {
  /** `{ [tokenName]: url }` — my preferred art per token type. */
  own: Record<string, string>
  /** `{ [ownerPlayerId]: { [tokenName]: url } }` — choices relayed from the table. */
  shared: Record<string, Record<string, string>>
  /** 'always use my art' flag. */
  overrideOpponent: boolean
}

/** Snapshot the three layers (deep copy — a preset must not alias live state). */
export function exportTokenArtPrefs(): TokenArtSnapshot {
  return {
    own: { ...ownPrefs },
    shared: Object.fromEntries(Object.entries(sharedPrefs).map(([pid, byName]) => [pid, { ...byName }])),
    overrideOpponent,
  }
}

function isPlainRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}

/** Validate a `{ [name]: url }` map — Fail Fast on anything that is not one. */
function requireStringMap(v: unknown, label: string): Record<string, string> {
  if (!isPlainRecord(v)) throw new Error(`token art: ${label} is not an object`)
  const out: Record<string, string> = {}
  for (const [k, val] of Object.entries(v)) {
    if (typeof val !== 'string') throw new Error(`token art: ${label}["${k}"] is not a string`)
    out[k] = val
  }
  return out
}

/**
 * Restore a snapshot taken by exportTokenArtPrefs: live module state, the
 * localStorage keys, then notify subscribers so the board re-renders.
 *
 * Fail Fast: a malformed snapshot throws instead of silently restoring
 * nothing (a preset load that quietly applied half its settings is the
 * failure mode this guards against).
 */
export function importTokenArtPrefs(snap: TokenArtSnapshot): void {
  const src = snap as { own?: unknown; shared?: unknown; overrideOpponent?: unknown } | null | undefined
  if (!isPlainRecord(src)) throw new Error('token art: import needs a snapshot from exportTokenArtPrefs')
  const own = requireStringMap(src.own, 'own')
  if (!isPlainRecord(src.shared)) throw new Error('token art: shared is not an object')
  const shared: SharedPrefs = {}
  for (const [pid, byName] of Object.entries(src.shared)) {
    shared[pid] = requireStringMap(byName, `shared["${pid}"]`)
  }
  ownPrefs = own
  sharedPrefs = shared
  writeJson(TOKEN_ART_OWN_KEY, ownPrefs)
  writeJson(TOKEN_ART_SHARED_KEY, sharedPrefs)
  // Reuses the existing setter, which owns the flag's persistence + notify.
  setOverrideOpponent(src.overrideOpponent === true)
}

// ── Own (viewer) preferences ──

export function getOwnTokenArt(tokenName: string): string | undefined {
  return ownPrefs[tokenName]
}

export function getAllOwnTokenArt(): Readonly<OwnPrefs> {
  return ownPrefs
}

/** How many token types I have a preferred art for (Settings dialog display). */
export function countOwnTokenArt(): number {
  return Object.keys(ownPrefs).length
}

export function isOverrideOpponent(): boolean {
  return overrideOpponent
}

export function setOverrideOpponent(on: boolean): void {
  overrideOpponent = on
  try {
    if (on) localStorage.setItem(TOKEN_ART_OVERRIDE_KEY, '1')
    else localStorage.removeItem(TOKEN_ART_OVERRIDE_KEY)
  } catch {
    /* ignore */
  }
  notify()
}

/**
 * Set (or clear, with `url === null`) my preferred art for a token type.
 * Returns true when it was stored; false when full and the entry was dropped.
 */
export function setOwnTokenArt(tokenName: string, url: string | null): boolean {
  if (url === null) {
    delete ownPrefs[tokenName]
    writeJson(TOKEN_ART_OWN_KEY, ownPrefs)
      notify()
    return true
  }
  if (!ownPrefs[tokenName] && Object.keys(ownPrefs).length >= MAX_PREFS) {
    return false
  }
  ownPrefs[tokenName] = url
  writeJson(TOKEN_ART_OWN_KEY, ownPrefs)
  notify()
  return true
}

// ── Shared (relayed) preferences ──

export function replaceSharedTokenArts(prefs: SharedTokenPref[]): void {
  const next: SharedPrefs = {}
  for (const p of prefs) {
    if (!p || !p.playerId || !p.tokenName || !p.url) continue
    const byName = next[p.playerId] ?? (next[p.playerId] = {})
    byName[p.tokenName] = p.url
  }
  sharedPrefs = next
  writeJson(TOKEN_ART_SHARED_KEY, sharedPrefs)
  notify()
}

/** The art the token's owner chose for this token type, if shared. */
export function getSharedTokenArt(ownerPlayerId: string | null | undefined, tokenName: string): string | undefined {
  if (!ownerPlayerId) return undefined
  return sharedPrefs[ownerPlayerId]?.[tokenName]
}

/** Every shared choice for a token name, with the owning player's name. */
export function listSharedTokenArts(tokenName: string): Array<{ playerId: string; url: string }> {
  const out: Array<{ playerId: string; url: string }> = []
  for (const [playerId, byName] of Object.entries(sharedPrefs)) {
    const url = byName[tokenName]
    if (url) out.push({ playerId, url })
  }
  return out
}

// ── Resolution ──

/**
 * The art URL to actually render for a token, applying the preference layers in
 * priority order:
 *
 *   1. my preference, when it should win here — i.e. it is my token, or
 *      `overrideOpponent` is on, or the owner shared nothing.
 *   2. the owner's shared choice ("default for the opponent if they haven't set
 *      their own" is the inverse: their choice is used precisely when I have none).
 *   3. whatever the caller's fallback resolves to (the engine default / chain).
 *
 * @param tokenName       the token's name
 * @param ownerPlayerId   the player who controls the token (null when unknown)
 * @param isMine          true when the viewing player controls this token
 */
export function preferTokenArt(
  tokenName: string,
  ownerPlayerId: string | null | undefined,
  isMine: boolean,
): string | undefined {
  const mine = ownPrefs[tokenName]
  const theirs = getSharedTokenArt(ownerPlayerId, tokenName)
  if (mine && (isMine || overrideOpponent || !theirs)) return mine
  if (theirs && !isMine) return theirs
  return undefined
}

/** Candidate URLs already resolved for a token name (from the proxy). */
export function getTokenVersions(tokenName: string): TokenVersionsResult | undefined {
  return versionsCache.get(tokenName)
}

/**
 * The engine's set code for a token, as a Scryfall token-set code.
 *
 * `GameStateSerializer` sends `tokenSetCode` — the set the engine recorded when it
 * created the token (the SOURCE CARD's set, per `TokenImpl.generateTokenInfo`).
 *
 * IMPORTANT: this is used ONLY to choose which candidate art is the default. It is
 * never concatenated into a URL. The engine's companion field `tokenImageNumber` is an
 * XMage-internal index, NOT a Scryfall collector number, and pasting it into
 * `cards/t<set>/<n>` renders a different token's art (RTR's image 1 is "Bird", not
 * "Soldier"). See the TokenArtService class comment.
 */
export function engineTokenSetCode(card: unknown): string | undefined {
  if (!card || typeof card !== 'object') return undefined
  const c = card as { token?: boolean | undefined; tokenSetCode?: string | undefined }
  if (!c.token) return undefined
  const setCode = c.tokenSetCode
  if (!setCode) return undefined
  return setCode
}

/**
 * Record a `token_versions_result` payload from the proxy.
 */
export function setTokenVersions(result: TokenVersionsResult): void {
  versionsCache.set(result.tokenName, result)
  for (const key of [...inflight.keys()]) {
    if (key.startsWith(`${result.tokenName}|`)) inflight.delete(key)
  }
  notify()
}

/**
 * Fetch candidate arts for a token from the proxy.
 *
 * ONE request per token name: the proxy searches Scryfall by name (cached per name on
 * its side, so every tile of the same token and every later game cost nothing) and
 * answers with real printings plus the engine's intended default.
 *
 * `engineSetCode` is the token's own set; the proxy uses it only to decide which
 * candidate is the default art, so a Soldier from a Ravnica card opens on a Ravnica
 * Soldier.
 */
export function fetchTokenVersions(
  tokenName: string,
  engineSetCode: string | undefined,
  send: (msg: Record<string, unknown>) => void,
): Promise<TokenVersionsResult> {
  const key = `${tokenName}|${engineSetCode ?? ''}`
  const existing = inflight.get(key)
  if (existing) return existing

  // Served from the proxy's cache regardless of which set asked for it: the candidate
  // list is per NAME, and the default art is derived here from `defaultUrl`.
  const cached = versionsCache.get(tokenName)
  if (cached) return Promise.resolve(cached)

  const requestId = `tok-${tokenName}-${Date.now()}`
  const p = new Promise<TokenVersionsResult>((resolve) => {
    pendingRequests.set(requestId, { resolve, tokenName })
    const msg: Record<string, unknown> = { type: 'token_versions', tokenName, requestId }
    if (engineSetCode) msg.engineSetCode = engineSetCode
    try {
      send(msg)
    } catch {
      pendingRequests.delete(requestId)
      resolve({ tokenName, versions: [], fetchFailed: true })
    }
  })
  inflight.set(key, p)
  return p
}

/** Requests awaiting a `token_versions_result`, keyed by requestId. */
const pendingRequests = new Map<string, { resolve: (r: TokenVersionsResult) => void; tokenName: string }>()

/** Route a `token_versions_result` message from the proxy. */
export function handleTokenVersionsResult(msg: {
  requestId?: string | undefined
  result?: TokenVersionsResult | undefined
  error?: string | undefined
}): void {
  const result = msg.result
  if (result) {
    setTokenVersions(result)
  }
  if (msg.requestId) {
    const pending = pendingRequests.get(msg.requestId)
    if (pending) {
      pendingRequests.delete(msg.requestId)
      pending.resolve(
        result ?? { tokenName: pending.tokenName, versions: [], fetchFailed: true },
      )
    }
  }
}

/**
 * The art URL to render for a token right now, or undefined when the caller must
 * fall through to its own chain (engine art → candidates → placeholder).
 *
 * `loadedUrl` is the candidate the loader has already proven loads (see
 * `CardThumb`); a preference, when one applies here, wins over it.
 */
export function tokenArtFor(
  tokenName: string,
  ownerPlayerId: string | null | undefined,
  isMine: boolean,
  loadedUrl: string | undefined,
): string | undefined {
  const preferred = preferTokenArt(tokenName, ownerPlayerId, isMine)
  if (preferred) return preferred
  return loadedUrl
}
