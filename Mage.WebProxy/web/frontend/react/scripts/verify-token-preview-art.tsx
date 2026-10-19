/**
 * Standalone behavioural check: the hover preview shows TOKEN ART
 * ("doesn't show as the large preview on hover, instead showing a
 * blank space"). Run with:  bash scripts/verify-token-preview-art.sh
 *
 * The defect (two halves, both pinned here):
 *   1. `CardPreview` resolved a token through the GENERIC card route only
 *      (`resolveCardImage(cardName, card, null, 'normal')`). The offline
 *      Scryfall name map has no `Soldier Token` key — XMage's runtime token
 *      names carry the " Token" suffix, Scryfall's do not — so the panel's
 *      image was blank for any token the map missed.
 *   2. The panel never consulted the token-art preference chain, so a chosen
 *      art showed on the tile but NOT in the preview.
 *
 * The panel now walks the same chain as the board tile: preference → proxy
 * printings (defaultUrl first) → engine URL, size-swapped to 'normal', with a
 * card-back placeholder (never a broken <img>) when nothing is usable.
 *
 * Layers:
 *   (a) `previewArtCandidates` — the PURE chain (import from the real module).
 *   (b) SSR renders of the REAL CardPreview with real store + real tokenArt
 *       module state (react-dom/server — no browser needed).
 *   (c) .sh grep layer: wiring on the real render sites + CSS + payload types.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import { useGameStore } from '../src/store/gameStore'
import CardPreview, { previewArtCandidates } from '../src/components/CardPreview'
import { setTokenVersions, setOwnTokenArt, resetTokenArtPrefs } from '../src/utils/tokenArt'
import type { Card, Permanent } from '../src/types'

// rootZoom() reads getComputedStyle(document.documentElement).zoom; the Node
// stubs provide documentElement but not getComputedStyle — identity zoom (1)
// is what an unzoomed page reports.
;(globalThis as unknown as { getComputedStyle: () => { zoom: number } }).getComputedStyle =
  () => ({ zoom: 1 })

// react-dom/server reads the store's INITIAL state object via getInitialState
// (zustand v5 wires the third useSyncExternalStore arg to it). Mutate it in
// lockstep with setState so each render sees the delivered scenario — same
// technique as verify-token-menu-hides-preview.tsx.
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

// The CDN shape the proxy hands out for tiles (small) and what the preview
// must request (the same uuid at /normal/).
const SMALL_A = 'https://cards.scryfall.io/small/front/0/a/0a7c8ad6-b6c6-41ef-939b-ce3ec261a50b.jpg'
const SMALL_B = 'https://cards.scryfall.io/small/front/f/4/f4ea1cc1-0b4e-4fbb-a34b-6e0a19b0d7b0.jpg'
const NORMAL_A = SMALL_A.replace('/small/', '/normal/')
const NORMAL_B = SMALL_B.replace('/small/', '/normal/')

// ─────────────────────────────────────────────────────────────────────────────
section('previewArtCandidates — the pure chain')

check('a non-token card resolves its engine URL',
  previewArtCandidates({ isToken: false, preferredUrl: undefined, tokenVersions: undefined,
    engineUrl: 'https://cards.scryfall.io/normal/front/d/c/dce15387-4114-4b3e-91aa-5b42b45c44ac.jpg',
    failedUrls: [] }) === 'https://cards.scryfall.io/normal/front/d/c/dce15387-4114-4b3e-91aa-5b42b45c44ac.jpg')

check('a token with NO art anywhere resolves null (placeholder, not blank)',
  previewArtCandidates({ isToken: true, preferredUrl: undefined, tokenVersions: undefined,
    engineUrl: '', failedUrls: [] }) === null)

check('a token with cached printings uses the defaultUrl (engine set) FIRST',
  previewArtCandidates({ isToken: true, preferredUrl: undefined,
    tokenVersions: { versions: [{ url: SMALL_A }, { url: SMALL_B }], defaultUrl: SMALL_B },
    engineUrl: '', failedUrls: [] }) === NORMAL_B)

check('a chosen art (preference) wins over the cached printings — preview matches the tile',
  previewArtCandidates({ isToken: true, preferredUrl: SMALL_A,
    tokenVersions: { versions: [{ url: SMALL_B }], defaultUrl: SMALL_B },
    engineUrl: '', failedUrls: [] }) === NORMAL_A)

check('a FAILED preference advances to the next candidate (stale uuid cannot loop)',
  previewArtCandidates({ isToken: true, preferredUrl: SMALL_A,
    tokenVersions: { versions: [{ url: SMALL_B }], defaultUrl: SMALL_B },
    engineUrl: '', failedUrls: [NORMAL_A] }) === NORMAL_B)

check('every candidate failed resolves null (placeholder contract)',
  previewArtCandidates({ isToken: true, preferredUrl: SMALL_A,
    tokenVersions: { versions: [{ url: SMALL_B }], defaultUrl: SMALL_B },
    engineUrl: '', failedUrls: [NORMAL_A, NORMAL_B] }) === null)

check('size swap: the proxy\'s small tile URLs render at normal in the panel',
  previewArtCandidates({ isToken: true, preferredUrl: undefined,
    tokenVersions: { versions: [{ url: SMALL_A }], defaultUrl: SMALL_A },
    engineUrl: '', failedUrls: [] }) === NORMAL_A)

check('a non-allowlisted host is never served (even as a preference)',
  previewArtCandidates({ isToken: true, preferredUrl: 'https://evil.example.com/a.jpg',
    tokenVersions: undefined, engineUrl: '', failedUrls: [] }) === null)

check('a token falls through to its engine URL when the map HAS one',
  previewArtCandidates({ isToken: true, preferredUrl: undefined, tokenVersions: undefined,
    engineUrl: 'https://cards.scryfall.io/normal/front/d/c/dce15387-4114-4b3e-91aa-5b42b45c44ac.jpg',
    failedUrls: [] }) === 'https://cards.scryfall.io/normal/front/d/c/dce15387-4114-4b3e-91aa-5b42b45c44ac.jpg')

check('the probe budget keeps a broken 45-art token from a request burst',
  previewArtCandidates({ isToken: true, preferredUrl: undefined,
    tokenVersions: { versions: Array.from({ length: 45 }, (_, i) => ({
      url: i === 0 ? SMALL_A : SMALL_A.replace('0a7c8ad6', `0a7c8ad${(i + 10).toString(16)}`),
    })) },
    engineUrl: '', failedUrls: [] }) === NORMAL_A,
  'returns the first candidate, not a probe of all 45')

// ─────────────────────────────────────────────────────────────────────────────
section('CardPreview SSR — the panel markup with real store + real tokenArt state')

const SOLDIER_TOKEN: Permanent = {
  id: 'token-1',
  name: 'Soldier Token',
  typeLine: 'Token Creature — Soldier',
  imageUrl: '',
  manaCost: '',
  rules: [],
  power: '1',
  toughness: '1',
  token: true,
} as unknown as Permanent

const render = (): string => renderToStaticMarkup(<CardPreview />)

function resetScenario() {
  setLive({ cardPreview: null, openContextMenu: null })
  resetTokenArtPrefs()
}

// A token with proxy printings: the panel must use them (this is the exact
// case that used to blank: 'Soldier Token' is not a key in the offline map).
resetScenario()
setTokenVersions({ tokenName: 'Soldier Token', versions: [{ url: SMALL_A }, { url: SMALL_B }], defaultUrl: SMALL_B })
setLive({ cardPreview: { card: SOLDIER_TOKEN, face: 'front', x: 100, y: 100, ownerPlayerId: 'p-1', isMine: true } })
{
  const html = render()
  check('token panel renders the proxy default art (not blank)',
    html.includes(`src="${NORMAL_B}"`), html.slice(0, 200))
  check('token panel does NOT fall back to the placeholder while art exists',
    !html.includes('preview-art-fallback'))
  check('token panel carries the card-back ratio contract via #card-preview',
    html.includes('id="card-preview"'))
}

// A chosen art: the preview must match the tile (preference layer).
resetScenario()
setTokenVersions({ tokenName: 'Soldier Token', versions: [{ url: SMALL_A }], defaultUrl: SMALL_A })
setOwnTokenArt('Soldier Token', SMALL_B)
setLive({ cardPreview: { card: SOLDIER_TOKEN, face: 'front', x: 100, y: 100, ownerPlayerId: 'p-1', isMine: true } })
{
  const html = render()
  check('a chosen token art shows in the hover preview (preference layer)',
    html.includes(`src="${NORMAL_B}"`), html.slice(0, 200))
}

// A token with NO art anywhere: placeholder, never a blank/broken image.
resetScenario()
setLive({ cardPreview: { card: { ...SOLDIER_TOKEN, name: 'Artless Token' }, face: 'front', x: 100, y: 100 } })
{
  const html = render()
  check('an art-less token shows the card-back placeholder (no blank space)',
    html.includes('preview-art-fallback'), html.slice(0, 200))
  check('the placeholder carries the token name',
    html.includes('Artless Token'))
  check('no <img> is rendered without a usable URL',
    !html.includes('<img'))
}

// A non-token card keeps its old behaviour (engine URL at the requested size).
resetScenario()
setLive({ cardPreview: { card: { id: 'c-1', name: 'Lightning Bolt',
  imageUrl: SMALL_A, typeLine: 'Instant', manaCost: '{R}', rules: [] } as unknown as Card,
  face: 'front', x: 10, y: 10 } })
{
  const html = render()
  check('a non-token card renders its engine URL, size-swapped to normal',
    html.includes(`src="${NORMAL_A}"`), html.slice(0, 200))
}

// No preview data at all still renders nothing.
resetScenario()
check('no cardPreview renders nothing', render() === '')

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
