/**
 * Standalone behavioural check for the card-art fallback box ("It should just be
 * a grey box the size of a card with the card text on it"; on map misses the
 * requirement was: "Probably should do a placeholder with the card
 * text on it. Doesn't have to look fancy, just be readable.").
 *
 * Run with:  bash scripts/verify-art-fallback.sh
 *
 * Covers, with NO browser and NO server:
 *  (a) cardFallbackText — text selection (rules array, string rules,
 *      ability_text precedence, empties) AND engine-markup cleaning: the
 *      engine's rules strings are display HTML (`<i>`, `<br>`, `<b>`, `&mdash;`,
 *      `&bull`) with `{this}` as a name placeholder, and the box renders with
 *      `white-space: pre-line`, so cleaning must produce readable text. Fixtures
 *      are verbatim rows from the cards DB (X-Wing, Emerald Charm, Hexdrinker).
 *  (b) the shipped CardThumb fallback branch renders the box with the card NAME
 *      and its TEXT for BOTH tokens and non-tokens (no token special-casing).
 *  (c) the resolver chain still yields a URL for art-less tokens (the fallback
 *      is the onError path, not a resolver change),
 *  (d) game-renderer.css keeps the box card-shaped and the text legible
 *      (aspect-ratio on the tile, card-back + scrim, name/text classes).
 *
 * Same harness pattern as verify-mulligan-hide (.sh bundles the .ts via
 * rolldown; Node runs the assertions).
 */
// Runs bundled, not from scripts/: derive the react/ root from the script's
// own location INSIDE the repo, not import.meta (rolldown rewrites it).
import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { cardFallbackText, fallbackClampLines, resolveCardImage } from '../src/utils/cardImage'
// The build tool bundles this file, so import.meta is rewritten to /tmp —
// WITHOUT file names preserved. That kills the '@/...' alias import above too?
// No — the dist bundle keeps the '../..' argument as a runtime module id, and
// node:fs calls below need a REAL repo path: walk up from process.cwd() (the
// .sh script cds to react/) and assert the anchor file exists. If the script
// was run from a different cwd, fail loudly rather than assert against a
// missing file.
const ROOT = ['src/components/CardThumb.tsx', 'game-renderer.css']
  .map((rel) => { let d = process.cwd(); while (d !== '/' && !existsSync(resolve(d, rel))) d = resolve(d, '..'); return d })
  .find((d) => existsSync(resolve(d, 'src/components/CardThumb.tsx')))
if (!ROOT) throw new Error('cannot locate react/ root — run verify-art-fallback.sh from the repo')

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }

section('cardFallbackText — pure text selection')
{
  check('rules array joined with newlines', cardFallbackText({ rules: ['Flying', 'Haste'] }) === 'Flying\nHaste',
    JSON.stringify(cardFallbackText({ rules: ['Flying', 'Haste'] })))
  check('ability_text stripped of tags', cardFallbackText({ ability_text: '<b>{this}</b> gains haste.' }) === '{this} gains haste.',
    JSON.stringify(cardFallbackText({ ability_text: '<b>{this}</b> gains haste.' })))
  check('legacy string rules pass through', cardFallbackText({ rules: 'Flying, first strike' }) === 'Flying, first strike')
  check('blank array -> empty (name-only box)', cardFallbackText({ rules: ['', ' '] }) === '')
  check('null card -> empty', cardFallbackText(null) === '')
  check('no text at all -> empty', cardFallbackText({}) === '')
  check('rules array beats ability_text (front-face rules are the printed text)',
    cardFallbackText({ rules: ['Vigilance'], ability_text: 'ignored' }) === 'Vigilance')
}

section('cardFallbackText — engine markup cleaning (verbatim DB fixtures)')
{
  // X-Wing (SWS): the engine emits <i> reminder text and {this} for the card's
  // own name. Shown raw, the box read "Spaceflight <i>(…)</i>" and
  // "{W}: {this} gains vigilance until end of turn."
  check('real X-Wing rules render as readable text',
    cardFallbackText({ name: 'X-Wing', rules: [
      'Spaceflight <i>(This creature can only block or be blocked by creatures with spaceflight.)</i>',
      '{W}: {this} gains vigilance until end of turn.',
    ] }) ===
      'Spaceflight (This creature can only block or be blocked by creatures with spaceflight.)\n' +
      '{W}: X-Wing gains vigilance until end of turn.',
    JSON.stringify(cardFallbackText({ name: 'X-Wing', rules: ['Spaceflight <i>(x)</i>', '{W}: {this} gains vigilance until end of turn.'] })))

  check('{this} is replaced with the card name', cardFallbackText({ name: 'Sun Titan', rules: ['{this} attacks.'] }) === 'Sun Titan attacks.')
  check('{this} left alone when the card has no name', cardFallbackText({ rules: ['{this} attacks.'] }) === '{this} attacks.')

  // Emerald Charm: &mdash; / &bull entities and <br> bullets — three lines, or
  // the box shows raw "&bull" markers glued together.
  check('real Emerald Charm entities + breaks render as bullet lines',
    cardFallbackText({ name: 'Emerald Charm', rules: [
      'Choose one &mdash;<br>&bull Untap target permanent.<br>&bull Destroy target non-Aura enchantment.',
    ] }) ===
      'Choose one \u2014\n\u2022 Untap target permanent.\n\u2022 Destroy target non-Aura enchantment.')

  // Hexdrinker: <b> level blocks with <br>: tags stripped, breaks kept.
  check('real Hexdrinker level blocks render as lines, not tags',
    cardFallbackText({ name: 'Hexdrinker', rules: ['<b>LEVEL 3-7</b><br>4/4<br>Protection from instants'] }) ===
      'LEVEL 3-7\n4/4\nProtection from instants')

  check('&amp; decoded last (no double-decode into entities)',
    cardFallbackText({ rules: ['&amp;mdash; stays literal'] }) === '&mdash; stays literal',
    JSON.stringify(cardFallbackText({ rules: ['&amp;mdash; stays literal'] })))
  check('numeric entities decoded', cardFallbackText({ rules: ['&#8212; and &#x2014;'] }) === '\u2014 and \u2014')
  check('no tag survives cleaning (no < left from markup)',
    cardFallbackText({ rules: ['<i>a</i><br><b>b</b>'] }) === 'a\nb')
  check('a <br> at line end leaves no trailing space',
    cardFallbackText({ rules: ['line<br>'] }) === 'line')
}

section('fallbackClampLines — the text ends on a WHOLE line, never mid-word')
{
  // "just be readable". A long card's rules used to be cut
  // mid-word at the tile edge (measured live: Sun Titan lost 2.5 lines, last
  // visible line "…target permanent card with mana value 3 or less fro" with
  // only a faint fade). The box now clamps to whole lines with an ellipsis.
  check('an exact fit keeps every line (88px / 11px = 8)', fallbackClampLines(88, 11) === 8, String(fallbackClampLines(88, 11)))
  check('a partial line is NOT counted (93 / 11 = 8.45 -> 8)', fallbackClampLines(93, 11) === 8, String(fallbackClampLines(93, 11)))
  check('just under 2 lines fits 1', fallbackClampLines(21, 11) === 1, String(fallbackClampLines(21, 11)))
  check('float noise on an exact fit still counts the line (76.999… / 11)',
    fallbackClampLines(76.99999999, 11) === 7, String(fallbackClampLines(76.99999999, 11)))
  check('a tall tile clamps to its own budget, not a constant', fallbackClampLines(187, 11) === 17, String(fallbackClampLines(187, 11)))
  check('degenerate measurements clamp to 1 line, never junk', fallbackClampLines(NaN, 11) === 1 && fallbackClampLines(60, 0) === 1 && fallbackClampLines(-5, 11) === 1)
}

section('resolver — art-less tokens have NO live-API fallback (fail-fast decision)')
{
  // 224ca4ba95 (2026-09-24) dropped the frontend's live api.scryfall.com
  // named? fallbacks: a map miss on the proxy side means "no art exists", and
  // the caller renders the card-text placeholder. This check previously
  // asserted the named-URL fallback, which that commit deliberately removed —
  // it was missed in the same sweep.
  const url = resolveCardImage('Soldier Token', { name: 'Soldier Token' } as never, null, 'small')
  check('a map miss resolves to the placeholder sentinel (empty string), not a live API URL',
    url === '', JSON.stringify(url))
}

section('CardThumb — fallback branch contract (real source, real class names)')
{
  const src = readFileSync(resolve(ROOT, 'src/components/CardThumb.tsx'), 'utf8')
  check('fallback renders in the same branch as a missing URL', src.includes('!imageUrl || imgError'))
  check('box uses the shared fallback class + art class', src.includes('card-thumb-fallback card-art-fallback'))
  check('box carries the card NAME', src.includes('card-art-fallback-name'))
  check('box carries the card TEXT (rules/ability text)', src.includes('card-art-fallback-text'))
  check('text comes from cardFallbackText(card)', src.includes('cardFallbackText(card)'))
  check('the text node is measured + gets the line budget (ref + fallbackClampLines + ResizeObserver)',
    src.includes('fallbackTextRef') && src.includes('fallbackClampLines') &&
    src.includes('--fallback-text-lines') && src.includes('ResizeObserver'),
    'the clamp must follow the tile size, not a hardcoded line count')
  check('no token-only fallback branch left (identical box for tokens and cards)',
    !src.includes("' token-fallback'") && !src.includes('token-label') && !src.includes('token-name'),
    'token special-casing must be gone')
}

section('game-renderer.css — the box is the size of the card, styled like a card')
{
  const css = readFileSync(resolve(ROOT, '../game-renderer.css'), 'utf8')
  // Neutral card-back + scrim design: the tile keeps its aspect via .card-thumb,
  // and the box paints the CSS-only neutral back (gradient stack, no image
  // asset) under a scrim with the name + text on top. The earlier design's flat
  // grey fill is gone — asserting it would be stale (this assertion was, until
  // 2026-09-24).
  check('tile is card-shaped (aspect-ratio on .card-thumb)', /\.card-thumb\s*\{[^}]*aspect-ratio:\s*146\s*\/\s*204/.test(css))
  check('fallback CSS block present', /\.card-thumb-fallback\.card-art-fallback\s*\{/.test(css))
  check('it inherits the thumb box via the shared inset:0 base rule', /\.card-thumb-fallback\s*\{[^}]*inset:\s*0/.test(css))
  check('card-back + scrim background is the CSS-only neutral stack (no image asset)',
    /\.card-thumb-fallback\.card-art-fallback\s*\{[^}]*background-image:[^}]*linear-gradient[^}]*var\(--card-back-neutral\)/.test(css) &&
    !/cardback\.(jpe?g|png|webp)/i.test(css),
    'the fallback must not depend on a bundled card-back image')
  check('the neutral back is a drawn gradient stack (dark base + sigil), not a URL',
    /--card-back-neutral:[^;]*radial-gradient[^;]*linear-gradient[^;]*;/.test(css) &&
    !/--card-back-neutral:[^;]*url\(/.test(css))
  check('name + text classes styled', css.includes('.card-art-fallback-name') && css.includes('.card-art-fallback-text'))
  check('text is clamped inside small thumbs', /\.card-art-fallback-text\s*\{[^}]*overflow:\s*hidden/.test(css))
  check('clamp is WHOLE LINES + ellipsis, driven by the measured line budget (not a bare crop)',
    /\.card-art-fallback-text\s*\{[^}]*display:\s*-webkit-box/.test(css) &&
    /\.card-art-fallback-text\s*\{[^}]*-webkit-box-orient:\s*vertical/.test(css) &&
    /\.card-art-fallback-text\s*\{[^}]*-webkit-line-clamp:\s*var\(--fallback-text-lines/.test(css))
  check('the old mid-word fade mask is gone', !/\.card-art-fallback-text\s*\{[^}]*mask-image/.test(css))
  check('line breaks in cleaned text are honoured', /\.card-art-fallback-text\s*\{[^}]*white-space:\s*pre-line/.test(css))
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
process.exit(failures === 0 ? 0 : 1)
