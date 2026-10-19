import { useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import { isAllowedImageUrl } from '@/utils/cardImage'
import type { CardSearchResult } from '@/types'

const COLOR_OPTIONS = [
  { value: 'white', label: 'W' },
  { value: 'blue',  label: 'U' },
  { value: 'black', label: 'B' },
  { value: 'red',   label: 'R' },
  { value: 'green', label: 'G' },
]

const TYPE_OPTIONS = ['CREATURE', 'INSTANT', 'SORCERY', 'ENCHANTMENT', 'ARTIFACT', 'LAND', 'PLANESWALKER', 'TRIBAL', 'BATTLES']
const RARITY_OPTIONS = ['COMMON', 'UNCOMMON', 'RARE', 'MYTHIC', 'SPECIAL', 'BONUS']

/**
 * Search panel — filter form + result grid. Click a card to add it to the
 * current deck. Right-click adds it to sideboard.
 */
export default function SearchPanel() {
  const criteria = useGameStore((s) => s.searchCriteria)
  const setCriteria = useGameStore((s) => s.setSearchCriteria)
  const results = useGameStore((s) => s.searchResults)
  const total = useGameStore((s) => s.searchTotal)
  const loading = useGameStore((s) => s.searchLoading)
  const error = useGameStore((s) => s.searchError)
  const runSearch = useGameStore((s) => s.runSearch)
  const addCard = useGameStore((s) => s.addCard)
  const addSearchResultToCollection = useGameStore((s) => s.addSearchResultToCollection)

  const collection = useGameStore((s) => s.collection)
  const currentDeck = useGameStore((s) => s.currentDeck)

  const [showAdvanced, setShowAdvanced] = useState(false)

  const handleColorToggle = (color: string): void => {
    const cur = new Set(criteria.colors ?? [])
    if (cur.has(color)) cur.delete(color)
    else cur.add(color)
    setCriteria({ colors: Array.from(cur) })
  }

  const handleTypeToggle = (type: string): void => {
    const cur = new Set(criteria.types ?? [])
    if (cur.has(type)) cur.delete(type)
    else cur.add(type)
    setCriteria({ types: Array.from(cur) })
  }

  const handleRarityToggle = (rarity: string): void => {
    const cur = new Set(criteria.rarities ?? [])
    if (cur.has(rarity)) cur.delete(rarity)
    else cur.add(rarity)
    setCriteria({ rarities: Array.from(cur) })
  }

  const handleSubmit = (e: React.FormEvent): void => {
    e.preventDefault()
    void runSearch()
  }

  /**
   * Card type for the tile's meta line — the search payload carries `types` and the
   * checklist expects it on tiles, but nothing rendered it (N6). Show the primary type
   * (first entry, engine order: e.g. CREATURE, INSTANT); the full list is in the tooltip.
   */
  const typeLabel = (card: CardSearchResult): string => {
    if (card.types.length === 0) return ''
    return card.types[0] ?? ''
  }

  const inDeckCount = (card: CardSearchResult): number => {
    return currentDeck.cards.reduce(
      (acc, e) => (e.name === card.name && e.setCode === card.setCode && e.cardNumber === card.cardNumber ? acc + e.count : acc),
      0,
    )
  }

  const inSideboardCount = (card: CardSearchResult): number => {
    return currentDeck.sideboard.reduce(
      (acc, e) => (e.name === card.name && e.setCode === card.setCode && e.cardNumber === card.cardNumber ? acc + e.count : acc),
      0,
    )
  }

  const inCollection = (card: CardSearchResult): number => {
    return collection[`${card.setCode}:${card.cardNumber}`] ?? 0
  }

  return (
    <div className="deck-search-panel">
      <h3 className="deck-search-title">Card Search</h3>
      <form onSubmit={handleSubmit} className="deck-search-form">
        <input
          type="text"
          className={`deck-search-name ${loading ? 'searching' : ''}`}
          placeholder="Card name (searches as you type)"
          value={criteria.name ?? ''}
          onChange={(e) => setCriteria({ name: e.target.value })}
        />
        <input
          type="text"
          className="deck-search-rules"
          placeholder="Rules text contains"
          value={criteria.rules ?? ''}
          onChange={(e) => setCriteria({ rules: e.target.value })}
        />

        <div className="deck-search-colors">
          {COLOR_OPTIONS.map((opt) => (
            <button
              type="button"
              key={opt.value}
              className={`deck-search-color ${criteria.colors?.includes(opt.value) ? 'active' : ''}`}
              onClick={() => handleColorToggle(opt.value)}
              data-explain="deck-search-color"
              title={opt.value}
            >
              {opt.label}
            </button>
          ))}
          <label className="deck-search-colorless">
            <input
              type="checkbox"
              checked={criteria.colorless ?? false}
              onChange={(e) => setCriteria({ colorless: e.target.checked })}
            />
            Colorless
          </label>
        </div>

        <button
          type="button"
          className="deck-search-advanced-toggle"
          onClick={() => setShowAdvanced(!showAdvanced)}
          data-explain="deck-search-advanced"
        >
          {showAdvanced ? '▼' : '▶'} Advanced filters
        </button>

        {showAdvanced && (
          <div className="deck-search-advanced">
            <div className="deck-search-row">
              <label>Types</label>
              <div className="deck-search-checklist">
                {TYPE_OPTIONS.map((t) => (
                  <label key={t}>
                    <input
                      type="checkbox"
                      checked={criteria.types?.includes(t) ?? false}
                      onChange={() => handleTypeToggle(t)}
                    />
                    {t}
                  </label>
                ))}
              </div>
            </div>
            <div className="deck-search-row">
              <label>Rarities</label>
              <div className="deck-search-checklist">
                {RARITY_OPTIONS.map((r) => (
                  <label key={r}>
                    <input
                      type="checkbox"
                      checked={criteria.rarities?.includes(r) ?? false}
                      onChange={() => handleRarityToggle(r)}
                    />
                    {r}
                  </label>
                ))}
              </div>
            </div>
            <div className="deck-search-row">
              <label>Set codes (comma-separated)</label>
              <input
                type="text"
                value={criteria.setCodes?.join(',') ?? ''}
                onChange={(e) =>
                  setCriteria({
                    setCodes: e.target.value.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean),
                  })
                }
                placeholder="e.g. MH3, OTJ, BLB"
              />
            </div>
            <div className="deck-search-row">
              <label>Mana value</label>
              <input
                type="number"
                min={0}
                value={criteria.manaValue ?? ''}
                onChange={(e): void =>
                  setCriteria({ manaValue: e.target.value ? parseInt(e.target.value, 10) : undefined })
                }
              />
            </div>
            <div className="deck-search-row">
              <label>Sort</label>
              <select
                value={criteria.sortBy ?? ''}
                onChange={(e) => setCriteria({ sortBy: e.target.value || undefined })}
              >
                <option value="">Default (set order)</option>
                <option value="name">Name</option>
                <option value="rarity">Rarity</option>
                <option value="color">Color</option>
                <option value="manaValue">Mana value</option>
              </select>
            </div>
          </div>
        )}

        {/* No explicit Search button — typing debounces into runSearch via the slice. */}
      </form>

      {error && <div className="deck-search-error">Error: {error}</div>}

      <div className="deck-search-results">
        {total > 0 && <div className="deck-search-count">{total} cards</div>}
        <div className="deck-search-grid">
          {results.map((card) => {
            const inDeck = inDeckCount(card)
            const inSb = inSideboardCount(card)
            const inCol = inCollection(card)
            return (
              <div
                key={`${card.setCode}:${card.cardNumber}`}
                className="deck-search-card"
                title={`${card.types.join(' ')}${card.types.length ? ' · ' : ''}Click: add to deck · Right-click: add to sideboard · +1 button: add to collection`}
                onClick={() => addCard(card)}
                onContextMenu={(e) => {
                  e.preventDefault()
                  addCard(card, true)
                }}
              >
                {card.imageUrl && isAllowedImageUrl(card.imageUrl) ? (
                  <img src={card.imageUrl} alt={card.name} loading="lazy" />
                ) : (
                  <div className="deck-search-card-fallback">{card.name}</div>
                )}
                <button
                  type="button"
                  className="deck-search-card-addcol"
                  title="Add 1 copy to your collection"
                  data-explain="deck-search-add-collection"
                  onClick={(e) => {
                    // Add-to-collection entry point. Without its own control the only
                    // way to fill a collection was the CollectionViewer's own +1, which
                    // renders nothing while the collection is empty — circular, so the
                    // first card could never be added (N5).
                    e.stopPropagation()
                    addSearchResultToCollection(card)
                  }}
                >
                  +Col
                </button>
                <div className="deck-search-card-name">{card.name}</div>
                <div className="deck-search-card-meta">
                  <span>{card.setCode} #{card.cardNumber}</span>
                  <span>{typeLabel(card)}</span>
                  <span>{card.rarity ?? ''}</span>
                </div>
                {(inDeck > 0 || inSb > 0 || inCol > 0) && (
                  <div className="deck-search-card-badges">
                    {inDeck > 0 && <span className="badge badge-deck">Deck ×{inDeck}</span>}
                    {inSb > 0 && <span className="badge badge-sb">SB ×{inSb}</span>}
                    {inCol > 0 && <span className="badge badge-col">Col ×{inCol}</span>}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}
