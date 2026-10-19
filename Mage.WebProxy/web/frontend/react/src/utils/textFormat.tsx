/**
 * Server HTML text utilities — ported from spectator-runtime.js.
 * The XMage backend sends HTML-formatted messages with <font> tags for colors,
 * <br> for line breaks, and [set] abbreviations. These functions clean and
 * colorize them for React rendering.
 */

/** Player color CSS classes — matches game-renderer.js PLAYER_COLORS. */
export const PLAYER_COLORS = ['player-0', 'player-1', 'player-2', 'player-3'] as const

/** Map of player name → color index (0-3). */
export type PlayerColorMap = Record<string, number>

/** Build a player color map from an array of player names. */
export function buildPlayerColorMap(playerNames: string[]): PlayerColorMap {
  const map: PlayerColorMap = {}
  playerNames.forEach((name, index) => {
    if (name) map[name] = index % 4
  })
  return map
}

/**
 * Decode the HTML entities XMage escapes server-side.
 *
 * The server runs `HtmlEscape.escapeHtml4(message)` on TALK/WHISPER chat
 * (MageServerImpl.sendChatMessage) before it ever reaches us, so entities
 * arrive escaped and must be decoded or the user sees `&amp;`/`&#39;`
 * literally. Game-log text arrives as RAW `<font>`/`<br>` tags instead.
 *
 * `&amp;` must be decoded LAST — decoding it first would turn `&amp;lt;`
 * into `&lt;`, which a later pass would then wrongly decode to `<`.
 */
export function decodeEntities(value: string): string {
  const text = String(value || '')
  // Named entities: expand the fixed ASCII table via the browser's own HTML4
  // decoder so the full named-entity space (&mdash; &eacute; &uuml; etc.)
  // round-trips. A <textarea> is used because its value is parsed as text —
  // decoded entities never become markup (no XSS surface).
  const decoded = text.indexOf('&') === -1 ? text : decodeNamedEntities(text)
  return decoded
    .replace(/&#(x?[0-9a-fA-F]+);/g, (entity, num: string) => {
      const cp = num[0]?.toLowerCase() === 'x' ? parseInt(num.slice(1), 16) : parseInt(num, 10)
      return Number.isNaN(cp) ? entity : String.fromCodePoint(cp)
    })
}

let entityDecoder: HTMLTextAreaElement | null = null
function decodeNamedEntities(value: string): string {
  if (entityDecoder === null) entityDecoder = document.createElement('textarea')
  entityDecoder.innerHTML = value
  return entityDecoder.value
}

/**
 * Strip HTML tags and [set] abbreviations from server text, then decode the
 * entities the server escaped.
 *
 * ORDER IS LOAD-BEARING: strip raw tags FIRST, decode entities SECOND.
 * - Game log arrives as raw HTML (`<font ...>`, `<br>`): the strip removes it.
 * - Chat arrives escaped (`&lt;`, `&amp;`): there are no raw tags to strip, so
 *   the text survives and the decode restores what the user actually typed.
 * Decoding first would turn chat's escaped `&lt;b&gt;` into a real `<b>` for
 * the strip pass to delete — silently eating the user's message.
 *
 * Decoded output is rendered as React text nodes (never innerHTML), so
 * restoring `<`/`>` here is display-only and cannot become markup.
 */
export function stripServerHtml(s: string): string {
  return decodeEntities(
    String(s || '')
      // EDGE break/block tags vanish entirely — their separator would be a stray
      // leading/trailing space ("<br>Seats: 2/2" must stay "Seats: 2/2").
      .replace(/^\s*<\s*(?:br\s*\/?|p|div|li|tr|ul|ol|table|thead|tbody|h[1-6]|blockquote|pre|hr)\b[^>]*>\s*/gi, '')
      .replace(/\s*<\s*\/?\s*(?:br\s*\/?|p|div|li|tr|ul|ol|table|thead|tbody|h[1-6]|blockquote|pre|hr)\b[^>]*>\s*$/gi, '')
      // INTERIOR break/block tags are separators: the server builds multi-part
      // text by concatenation with them, so deleting them glued the words on
      // either side. The prompt cases showed the same
      // defect: `"Choose spell or ability to play" + "<br>" + card name`
      // (HumanPlayer.java:2357) rendered as "…to playArid Mesa", and
      // ManaUtil's `…"{W}" + "<div …>Guide of Souls</div>"` (ManaUtil.java:458)
      // as "Pay {W}Guide of Souls". (The match-score cases are
      // covered by the same rule.)
      // Their surrounding whitespace is consumed too, so one break yields one
      // space.
      .replace(/\s*<\s*br\s*\/?\s*>\s*/gi, ' ')
      .replace(/\s*<\s*\/?(?:p|div|li|tr|ul|ol|table|thead|tbody|h[1-6]|blockquote|pre|hr)\b[^>]*>\s*/gi, ' ')
      // INLINE tags (<font color=...>, <b>, <i>) must still strip to NOTHING:
      // replacing those with a space would split words the server never
      // separated ("<font>x</font>y" is "xy", not "x y").
      .replace(/<[^>]+>/g, '')
      // Adjacent break tags ("a<br><br>b") each contributed a space above; collapse
      // the runs. Some panels render with `white-space: pre-wrap`, so this also
      // matches what an HTML renderer would have shown.
      .replace(/[ \t]{2,}/g, ' ')
      .replace(/\s\[[a-z0-9]{2,4}\]/gi, '')
  )
}

/** Escape HTML entities in text (for safe React rendering). */
export function escapeHtml(value: string): string {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * Strip server HTML and colorize player names by wrapping them in colored spans.
 * Returns an array of React nodes (strings and <span> elements) for inline rendering.
 *
 * This replaces the Alpine colorizePlayerNames which returned an HTML string
 * used with innerHTML. In React we return nodes instead to avoid dangerouslySetInnerHTML.
 */
import type { ReactNode } from 'react'

export function colorizePlayerNames(message: string, colorMap: PlayerColorMap): ReactNode[] {
  const stripped = stripServerHtml(message)

  // If no player names to colorize, just return the plain text
  const names = Object.keys(colorMap)
  if (names.length === 0) return [stripped]

  // Sort by length descending so longer names match before shorter ones
  names.sort((a, b) => b.length - a.length)

  // Build a regex that matches any player name (case-sensitive, whole word)
  const nameRegex = new RegExp(
    `(${names.map((n) => n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})`,
    'g'
  )

  const parts: ReactNode[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null
  let key = 0

  while ((match = nameRegex.exec(stripped)) !== null) {
    // Add preceding text
    if (match.index > lastIndex) {
      parts.push(stripped.substring(lastIndex, match.index))
    }

    // Add colored name
    const name = match[0]!
    const colorIdx = colorMap[name]
    if (colorIdx !== undefined) {
      parts.push(
        <span key={key++} className={`action-${PLAYER_COLORS[colorIdx]}`}>
          {name}
        </span>
      )
    } else {
      parts.push(name)
    }

    lastIndex = nameRegex.lastIndex
  }

  // Add remaining text
  if (lastIndex < stripped.length) {
    parts.push(stripped.substring(lastIndex))
  }

  return parts
}