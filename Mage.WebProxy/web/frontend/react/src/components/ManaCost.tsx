/**
 * Mana symbol rendering — ported from game-renderer.js renderManaCost.
 * Renders inline mana symbols as <img> tags pointing to Scryfall CDN.
 */

const SCRYFALL_SYMBOLS_BASE = 'https://svgs.scryfall.io/card-symbols/'

interface ManaSymbolProps {
  /** Mana cost string like "{2}{U}{B/R}" */
  cost: string
}

/** Render mana symbols from a string like "{2}{U}{B/R}" as inline images. */
export function ManaCost({ cost }: ManaSymbolProps) {
  if (!cost) return null

  const re = /\{([^}]+)\}/g
  const symbols: { symbol: string; full: string }[] = []
  let match: RegExpExecArray | null
  while ((match = re.exec(cost)) !== null) {
    const symbol = match[1]!.replace(/\//g, '')
    symbols.push({ symbol, full: match[0] })
  }

  if (symbols.length === 0) {
    return <span>{cost}</span>
  }

  return (
    <span className="mana-cost">
      {symbols.map((s, i) => (
        <img
          key={i}
          className="mana-icon"
          src={`${SCRYFALL_SYMBOLS_BASE}${encodeURIComponent(s.symbol)}.svg`}
          alt={s.full}
          title={s.full}
        />
      ))}
    </span>
  )
}

/**
 * Render text with inline mana symbols and italic reminder text.
 * Ported from renderTextWithMana. {2}{U} → mana icons, (reminder text) → <i>.
 */
interface ManaTextProps {
  text: string
  cardName?: string | undefined
}

export function ManaText({ text, cardName }: ManaTextProps) {
  if (!text) return null

  // Clean engine markup first
  let cleaned = text
  if (cardName) {
    cleaned = cleaned.replace(/\{this\}/gi, cardName)
  }
  cleaned = cleaned.replace(/<hintstart\s*\/?>/gi, '')
  cleaned = cleaned.replace(/<br\s*\/?>/gi, '\n')
  cleaned = cleaned.replace(/<[^>]+>/g, '')
  cleaned = cleaned.replace(/&mdash;/g, '\u2014')
  cleaned = cleaned.replace(/&ndash;/g, '\u2013')
  cleaned = cleaned.replace(/&lt;/g, '<')
  cleaned = cleaned.replace(/&gt;/g, '>')
  cleaned = cleaned.replace(/&amp;/g, '&')
  cleaned = cleaned.replace(/ICON_REQUIRE/g, '')
  cleaned = cleaned.trim()

  // Match mana symbols {X} or reminder text (...)
  const re = /\{([^}]+)\}|(\([^)]+\))/g
  const parts: React.ReactNode[] = []
  let last = 0
  let match: RegExpExecArray | null
  let keyIdx = 0

  while ((match = re.exec(cleaned)) !== null) {
    if (match.index > last) {
      parts.push(<span key={keyIdx++}>{cleaned.substring(last, match.index)}</span>)
    }

    if (match[1] != null) {
      // Mana symbol
      const symbol = match[1].replace(/\//g, '')
      parts.push(
        <img
          key={keyIdx++}
          className="mana-icon"
          src={`${SCRYFALL_SYMBOLS_BASE}${encodeURIComponent(symbol)}.svg`}
          alt={match[0]}
          title={match[0]}
        />
      )
    } else if (match[2] != null) {
      // Reminder text — render italic with mana symbols inside
      const inner = match[2]
      const innerParts: React.ReactNode[] = []
      const manaRe = /\{([^}]+)\}/g
      let mLast = 0
      let mMatch: RegExpExecArray | null
      let innerIdx = 0

      while ((mMatch = manaRe.exec(inner)) !== null) {
        if (mMatch.index > mLast) {
          innerParts.push(<span key={innerIdx++}>{inner.substring(mLast, mMatch.index)}</span>)
        }
        const mSym = mMatch[1]!.replace(/\//g, '')
        innerParts.push(
          <img
            key={innerIdx++}
            className="mana-icon"
            src={`${SCRYFALL_SYMBOLS_BASE}${encodeURIComponent(mSym)}.svg`}
            alt={mMatch[0]}
            title={mMatch[0]}
          />
        )
        mLast = manaRe.lastIndex
      }
      if (mLast < inner.length) {
        innerParts.push(<span key={innerIdx++}>{inner.substring(mLast)}</span>)
      }

      parts.push(
        <i key={keyIdx++} style={{ color: '#888' }}>
          {innerParts}
        </i>
      )
    }

    last = re.lastIndex
  }

  if (last < cleaned.length) {
    parts.push(<span key={keyIdx++}>{cleaned.substring(last)}</span>)
  }

  return <span>{parts}</span>
}