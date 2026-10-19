/**
 * Card image resolution — ported from game-renderer.js.
 * Fallback chain: explicit imageUrl (CDN uuid URL from the proxy) → cardImages
 * map (replay) → MDFC back face → token cache. NO live-API named? fallback: a
 * map miss on the proxy side means "no art exists", and the caller renders the
 * card-text placeholder.
 */
import type { Card, Permanent } from '@/types'

type CardLike = Card | Permanent | { imageUrl?: string; originalCard?: string; back_face?: boolean; backFace?: boolean } | null

/**
 * Image URL host allowlist. Card images come from the Scryfall
 * CDN (cards.scryfall.io) via the XMage proxy's offline bulk-data map; the
 * legacy api.scryfall.com query-form URLs may still appear for exact-printing
 * paths (looked-at cards). Anything else — a tampered imageUrl pointing at a
 * tracking pixel or a non-HTTPS origin — is blanked so it never reaches an
 * <img src>.
 */
const ALLOWED_IMAGE_HOSTS = new Set([
  'api.scryfall.com',
  'cards.scryfall.io',
  'img.scryfall.io',
])

/** Validate a card image URL against the allowlist; null means "don't render". */
export function isAllowedImageUrl(url: string | undefined | null): boolean {
  if (!url) return false
  try {
    const u = new URL(url)
    if (u.protocol !== 'https:') return false
    return ALLOWED_IMAGE_HOSTS.has(u.hostname.toLowerCase())
  } catch {
    return false
  }
}

/**
 * Re-target an image URL at another size.
 *
 * Two forms in the wild:
 *  - CDN uuid URLs (cards.scryfall.io/{size}/{front|back}/…): the size is a
 *    path segment, so swap it.
 *  - Legacy query-form URLs (api.scryfall.com/…&version=small): swap the
 *    query param.
 *
 * Unknown shapes are returned unchanged (they fail safe downstream).
 */
export function swapImageSize(url: string, version: string): string {
  if (url.includes('cards.scryfall.io/')) {
    return url.replace(
      /\/(small|normal|large|png)\//,
      `/${version}/`,
    )
  }
  return url.replace(/version=(small|normal|large|png)/, `version=${version}`)
}

/**
 * Direct-render path: components that render card.imageUrl
 * directly (popups with `src={card.imageUrl || resolveCardImage(...)}`) bypass
 * resolveCardImage's allowlist. This helper validates the explicit URL and
 * falls back to the resolver chain when it is missing/untrusted — use it
 * anywhere a raw imageUrl would otherwise reach an <img src>.
 */
export function safeCardImageUrl(
  card: { imageUrl?: string },
  fallback: () => string,
): string {
  if (card.imageUrl && isAllowedImageUrl(card.imageUrl)) return card.imageUrl
  return fallback()
}

/**
 * Engine rules text → plain display text.
 *
 * The engine's `rules` strings are display markup, not prose: `<i>` reminder
 * text, `<br>` line breaks, `&mdash;`/`&bull` entities, and `{this}` as a
 * placeholder for the card's own name. Showing them raw ("Spaceflight <i>(…)</i>",
 * "Choose one &mdash;<br>&bull Untap …") is what made the fallback box unreadable
 * Measured against the cards DB: 43,323
 * cards carry `{this}`, 20,155 carry `<i>`, 7,208 carry `&mdash;`, 1,948 `&bull`,
 * 4,455 `<br>` variants, 46 `<b>` (leveler blocks).
 *
 * Order is load-bearing:
 *  1. `<br>` → newline FIRST — after tag-stripping these breaks are gone.
 *  2. Strip remaining tags (inline tags must vanish, not become spaces).
 *  3. Insert the card name for `{this}`/`{source}` — only once tags are gone,
 *     so a name containing "<" cannot turn into markup.
 *  4. Decode entities LAST (and `&amp;` after everything else, per the
 *     stripServerHtml convention: decoding it first would let `&amp;mdash;`
 *     become an em dash).
 */
function cleanRuleText(raw: string, cardName: string | undefined): string {
  const name = cardName && cardName.trim() !== '' ? cardName : null
  let t = String(raw ?? '')
  t = t.replace(/<\s*br\s*\/?>/gi, '\n')
  t = t.replace(/<[^>]*>/g, '')
  // `{this}`/`{source}` are the engine's placeholder for the card's own name — the
  // desktop client substitutes it too (GamePanel: `replace("{this}", name)`).
  // Without a name there is nothing to substitute with; leave the engine notation
  // alone rather than inventing words.
  if (name !== null) {
    t = t.replace(/\{this\}/gi, name)
    t = t.replace(/\{source\}/gi, name)
  }
  t = t.replace(/&mdash;?/gi, '\u2014')
  t = t.replace(/&ndash;?/gi, '\u2013')
  t = t.replace(/&bull;?/gi, '\u2022')
  t = t.replace(/&hellip;?/gi, '\u2026')
  t = t.replace(/&nbsp;/gi, ' ')
  t = t.replace(/&quot;/gi, '"')
  t = t.replace(/&(?:apos|#39);/gi, "'")
  t = t.replace(/&lt;/gi, '<').replace(/&gt;/gi, '>')
  t = t.replace(/&#(x[0-9a-f]+|\d+);/gi, (m: string, num: string) => {
    const cp = num[0]?.toLowerCase() === 'x' ? parseInt(num.slice(1), 16) : parseInt(num, 10)
    return Number.isNaN(cp) || cp < 0 || cp > 0x10ffff ? m : String.fromCodePoint(cp)
  })
  // `&amp;` LAST: decoding it earlier would let `&amp;mdash;` turn into an em dash.
  t = t.replace(/&amp;/gi, '&')
  // Tidy: per-line trim (a <br> at a line end leaves a space) + collapse runs.
  // The box renders with `white-space: pre-line`, so \n is a real line break.
  t = t.split('\n').map((l) => l.trim()).join('\n')
  return t.replace(/[ \t]{2,}/g, ' ').replace(/\n{3,}/g, '\n\n').trim()
}

/**
 * Card text for the fallback box (weight-1 cutover, 2026-09-19; engine-markup
 * cleaning 2026-09-24): the rules / ability text a fallen-back thumb shows,
 * cleaned so it reads like a card, not like HTML. Front-face rules win over
 * ability_text (they are the printed text); empty string means "name only"
 * (e.g. an unknown card with no text at all).
 */
export function cardFallbackText(
  card: {
    name?: string | undefined
    rules?: string[] | string | undefined
    ability_text?: string | undefined
  } | null,
): string {
  if (!card) return ''
  const name = typeof card.name === 'string' ? card.name : undefined
  const cleaned = (raw: string[] | string | undefined): string => {
    const list = Array.isArray(raw) ? raw : typeof raw === 'string' ? [raw] : []
    return list
      .map((r) => (typeof r === 'string' ? cleanRuleText(r, name) : ''))
      .filter((r) => r !== '')
      .join('\n')
  }
  // Serializer: array joined by \n (most cards); legacy string-valued rules pass
  // straight through the same cleaning.
  if ('rules' in card && card.rules) {
    const rules = cleaned(card.rules)
    if (rules !== '') return rules
  }
  if (card.ability_text && typeof card.ability_text === 'string') {
    const ability = cleaned(card.ability_text)
    if (ability !== '') return ability
  }
  return ''
}

/**
 * Whole-line budget for the fallback box's rules text: how many lines of
 * `lineHeight` px fit in `available` px. CardThumb sets this as
 * `--fallback-text-lines`, and the CSS clamps the text to exactly that many
 * lines (`-webkit-line-clamp`) so a long card ENDS on a whole line with an
 * ellipsis instead of being cut mid-word at the tile edge ("just be readable").
 * Always at least 1 line; a non-finite measurement
 * falls back to 1 line (never "clamp to junk").
 */
export function fallbackClampLines(available: number, lineHeight: number): number {
  if (!Number.isFinite(available) || !Number.isFinite(lineHeight) || lineHeight <= 0) return 1
  // +1e-6 absorbs float noise on an exact fit (e.g. 88 / 11 = 7.999…).
  return Math.max(1, Math.floor(available / lineHeight + 1e-6))
}

/** Cache for token image lookups (cardName → imageUrl or null). Consulted by
 * resolveCardImage (Priority 3) so a second tile of the same token does not
 * re-hit the proxy; the writer side is gone with fetchTokenImage's removal —
 * the map is now the proxy's, not the browser's. */
const _tokenImageCache = new Map<string, string | null>()

/**
 * Resolve a card's image URL.
 *
 * Fallback chain: explicit imageUrl (CDN uuid URL from the proxy) → cardImages
 * map (replay) → MDFC back face → token cache. NO live-API named? fallback: a
 * map miss on the proxy side means "no art exists", and the caller renders
 * the card-text placeholder.
 *
 * @param cardName The card name
 * @param cardObj The card object (may have explicit imageUrl)
 * @param cardImages Optional lookup map (replay mode — null in live mode)
 * @param version Image version: "small" or "normal"
 */
export function resolveCardImage(
  cardName: string,
  cardObj: CardLike,
  cardImages: Record<string, string> | null,
  version: string = 'small'
): string {
  const isBackFace = !!(cardObj && (cardObj as Record<string, unknown>).back_face)

  // Priority 1: explicit imageUrl on the card (live mode) — validated against
  // the Scryfall host allowlist; invalid hosts fall through.
  if (cardObj && cardObj.imageUrl) {
    const candidate = swapImageSize(cardObj.imageUrl, version)
    if (isAllowedImageUrl(candidate)) {
      return candidate
    }
  }

  // Priority 2: cardImages lookup map (replay mode)
  if (cardImages && cardImages[cardName]) {
    return swapImageSize(cardImages[cardName]!, version)
  }

  // Priority 2b: MDFC/transform back face — the CDN back-face URL is a path
  // swap on the same uuid (/front/ -> /back/).
  if (isBackFace && cardImages && cardObj && 'originalCard' in cardObj && cardObj.originalCard && cardImages[cardObj.originalCard]) {
    return swapImageSize(cardImages[cardObj.originalCard]!, version).replace('/front/', '/back/')
  }

  // Priority 3: cached token image
  const cached = _tokenImageCache.get(cardName)
  if (cached) {
    return swapImageSize(cached, version)
  }

  // No image known: empty string sends the tile to the card-text placeholder.
  // NO live api.scryfall.com named? fallback.
  return ''
}

/**
 * Detect if a stack item is an ability (not a spell) and extract metadata.
 * Returns { isAbility, sourceCard, abilityText } or { isAbility: false }.
 */
export function parseStackAbility(
  name: string,
  cardObj: Card | Permanent | null
): { isAbility: true; sourceCard: string | null; abilityText: string } | { isAbility: false } {
  function stripHtml(s: string): string {
    return s.replace(/<[^>]+>/g, '')
  }

  function clean(text: string, src: string | null): string {
    let t = stripHtml(text)
    t = t.replace(/\{this\}/gi, src || 'this')
    return t
  }

  // New format: has explicit source_card field (from GameStateSerializer)
  if (cardObj && 'source_card' in cardObj && cardObj.source_card) {
    const raw = ('ability_text' in cardObj && cardObj.ability_text) ? cardObj.ability_text : name
    return { isAbility: true, sourceCard: cardObj.source_card, abilityText: clean(raw, cardObj.source_card) }
  }

  // Live observer format: ability_text present
  if (cardObj && 'ability_text' in cardObj && cardObj.ability_text) {
    return { isAbility: true, sourceCard: name, abilityText: clean(cardObj.ability_text, name) }
  }

  // isAbility flag from serializer
  if (cardObj && 'isAbility' in cardObj && cardObj.isAbility) {
    let rulesText = ''
    if (cardObj.rules && !Array.isArray(cardObj.rules)) {
      rulesText = String(cardObj.rules)
    } else if (cardObj.rules && cardObj.rules.length > 0) {
      rulesText = cardObj.rules.join('\n')
    }
    if (rulesText) {
      return { isAbility: true, sourceCard: name, abilityText: clean(rulesText, name) }
    }
  }

  // Backward compat: parse from "stack ability (...)" name (old exports)
  const abilityMatch = name.match(/^stack ability \((.+)\)$/)
  if (abilityMatch) {
    return { isAbility: true, sourceCard: null, abilityText: stripHtml(abilityMatch[1]!) }
  }

  return { isAbility: false }
}