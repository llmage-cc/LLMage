import { useEffect, useRef } from 'react'
import { useGameStore } from '@/store/gameStore'
import { isAllowedImageUrl } from '@/utils/cardImage'
import type { CardSearchResult } from '@/types'

/**
 * Collection viewer — browse your owned cards (`localStorage.xmage.web.collection`)
 * as a grid, with →Deck / +1 / −1 actions per card.
 *
 * Metadata comes from `loadCollectionCards` (a `card_lookup` on the owned
 * `${set}:${num}` keys). The previous implementation fired a set-only
 * `card_search` here, which could not work: it asked for "any 200 cards in these
 * sets" rather than the cards the user owns, so an owned printing past the
 * 200-result cap never came back and the grid stayed empty.
 */
export default function CollectionViewer() {
  const collection = useGameStore((s) => s.collection)
  const collectionCards = useGameStore((s) => s.collectionCards)
  const collectionLoading = useGameStore((s) => s.collectionLoading)
  const collectionError = useGameStore((s) => s.collectionError)
  const collectionMissing = useGameStore((s) => s.collectionMissing)
  const removeFromCollection = useGameStore((s) => s.removeFromCollection)
  const clearCollection = useGameStore((s) => s.clearCollection)
  const addCard = useGameStore((s) => s.addCard)
  const addSearchResultToCollection = useGameStore((s) => s.addSearchResultToCollection)
  const loadCollectionCards = useGameStore((s) => s.loadCollectionCards)

  // Resolve owned printings on mount. StrictMode double-invokes effects in dev, so a
  // bare `void loadCollectionCards()` sent the request twice (N7). A ref survives the
  // StrictMode remount, making this mount-once even though the effect runs twice — and
  // each request is a real DB query, so the duplicate was pure waste.
  const didLoad = useRef(false)
  useEffect(() => {
    if (didLoad.current) return
    didLoad.current = true
    void loadCollectionCards()
  }, [loadCollectionCards])

  const ownedKeys = Object.keys(collection).filter((k) => (collection[k] ?? 0) > 0)
  const inCollection = (setCode: string, cardNumber: string): number =>
    collection[`${setCode}:${cardNumber}`] ?? 0

  // Group resolved cards by set for display
  const bySet = new Map<string, CardSearchResult[]>()
  for (const card of Object.values(collectionCards)) {
    if (!inCollection(card.setCode, card.cardNumber)) continue
    const arr = bySet.get(card.setCode) ?? []
    arr.push(card)
    bySet.set(card.setCode, arr)
  }

  const handleAddToDeck = (card: CardSearchResult): void => {
    addCard(card)
  }

  const handleAddCopyToCollection = (card: CardSearchResult): void => {
    // Same path as the search panel's +1: adds and caches metadata in one step.
    addSearchResultToCollection(card, 1)
  }

  const handleRemoveCopy = (card: CardSearchResult): void => {
    removeFromCollection(card.setCode, card.cardNumber, 1)
  }

  return (
    <div className="collection-viewer">
      <div className="collection-header">
        <h3>Collection</h3>
        <span className="collection-count">{ownedKeys.length} unique cards</span>
        {ownedKeys.length > 0 && (
          <button type="button" className="collection-clear" onClick={() => clearCollection()}
          data-explain="collection-clear">
            Clear all
          </button>
        )}
      </div>

      {ownedKeys.length === 0 ? (
        <div className="deck-empty">
          Your collection is empty. Add cards with the <strong>+1</strong> button on any
          search result in the Build tab, or import a deck and use →Collection.
        </div>
      ) : collectionLoading && bySet.size === 0 ? (
        <div className="deck-empty">Loading collection cards…</div>
      ) : collectionError ? (
        <div className="legality-error">Could not load collection: {collectionError}</div>
      ) : bySet.size === 0 ? (
        <div className="deck-empty">
          None of your {ownedKeys.length} collection entries could be resolved on this
          server — the card database does not contain
          {collectionMissing.length > 0 ? ` ${collectionMissing.join(', ')}` : ' those printings'}.
          Clear the collection and re-add cards from search.
        </div>
      ) : (
        <div className="collection-sets">
          {collectionMissing.length > 0 && (
            <div className="legality-error">
              Not on this server: {collectionMissing.join(', ')}
            </div>
          )}
          {[...bySet.entries()].sort().map(([setCode, cards]) => (
            <div key={setCode} className="collection-set">
              <h4 className="collection-set-code">{setCode} ({cards.length})</h4>
              <div className="collection-set-grid">
                {cards.map((card) => {
                  const owned = inCollection(card.setCode, card.cardNumber)
                  return (
                    <div key={`${card.setCode}:${card.cardNumber}`} className="collection-card">
                      {card.imageUrl && isAllowedImageUrl(card.imageUrl) ? (
                        // aspect-ratio on the wrapper (CSS) keeps the tile's box sized
                        // before the image loads — a 0-height tile never intersects the
                        // lazy-load viewport and its src is never fetched (N1).
                        <img src={card.imageUrl} alt={card.name} loading="lazy" />
                      ) : (
                        <div className="collection-card-fallback">{card.name}</div>
                      )}
                      <div className="collection-card-meta">
                        <span className="collection-card-name">{card.name}</span>
                        <span className="collection-card-count">×{owned}</span>
                      </div>
                      <div className="collection-card-actions">
                        <button type="button" onClick={() => handleAddToDeck(card)}
                        data-explain="collection-to-deck">→ Deck</button>
                        <button type="button" onClick={() => handleAddCopyToCollection(card)}
                        data-explain="collection-plus">+1</button>
                        <button type="button" onClick={() => handleRemoveCopy(card)}
                        data-explain="collection-minus">−1</button>
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
