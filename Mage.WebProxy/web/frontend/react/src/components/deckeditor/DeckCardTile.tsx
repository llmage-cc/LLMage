import { useEffect, useRef, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import type { CardSearchResult, DeckEntry } from '@/types'
import { rootZoom, cssViewportWidth, cssViewportHeight } from '@/utils/viewport'

/**
 * Renders a single deck-entry as a card image tile. Reads imageUrl straight from
 * the store entry — hydrated URLs written by hydrateDeckImages re-render this tile.
 */
export default function DeckCardTile({
  entry,
  sideboard,
}: {
  entry: DeckEntry
  sideboard: boolean
}) {
  const openContextMenu = useGameStore((s) => s.setDeckCardContextMenu)
  // Read imageUrl directly from the store — this re-renders when hydrateDeckImages
  // patches the entry. Local state was the original bug: hydrated URLs never showed.
  const imageUrl = entry.imageUrl

  return (
    <div
      className="deck-card-tile"
      onContextMenu={(e) => {
        e.preventDefault()
        openContextMenu({ entry, sideboard, x: e.clientX, y: e.clientY })
      }}
      title={`${entry.name} [${entry.setCode}:${entry.cardNumber}]\nRight-click for options`}
    >
      {imageUrl ? (
        <img src={imageUrl} alt={entry.name} loading="lazy" />
      ) : (
        <div className="deck-card-tile-fallback">{entry.name}</div>
      )}
      <div className="deck-card-tile-count">{entry.count}×</div>
    </div>
  )
}

/**
 * Right-click context menu for a deck or sideboard card.
 * Actions come from the deck slice.
 */
export function DeckCardContextMenu() {
  const menu = useGameStore((s) => s.deckCardContextMenu)
  const setMenu = useGameStore((s) => s.setDeckCardContextMenu)
  const duplicateEntry = useGameStore((s) => s.duplicateEntry)
  const setCardCount = useGameStore((s) => s.setCardCount)
  const removeCard = useGameStore((s) => s.removeCard)
  const moveToSideboard = useGameStore((s) => s.moveToSideboard)
  const moveToMain = useGameStore((s) => s.moveToMain)
  const setArtPicker = useGameStore((s) => s.setArtPickerFor)
  const menuRef = useRef<HTMLDivElement>(null)

  // Close on click outside
  useEffect(() => {
    if (!menu) return
    const onDocClick = (e: MouseEvent): void => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenu(null)
      }
    }
    const onEsc = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') setMenu(null)
    }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onEsc)
    return () => {
      document.removeEventListener('mousedown', onDocClick)
      document.removeEventListener('keydown', onEsc)
    }
  }, [menu, setMenu])

  if (!menu) return null

  const { entry, sideboard, x, y } = menu
  const close = (): void => setMenu(null)

  // x/y arrive in REAL px from the context-menu event; the fixed left/top
  // style renders in ROOT CSS px (base.css zooms <html>), so convert here
  // and clamp so the ~220px-wide/~200px-tall menu can't run off-screen.
  const z = rootZoom()
  let left = x / z
  let top = y / z
  if (left + 220 > cssViewportWidth()) left = cssViewportWidth() - 230
  if (top + 200 > cssViewportHeight()) top = cssViewportHeight() - 210

  return (
    <div ref={menuRef} className="deck-context-menu" style={{ position: 'fixed', left, top }}>
      <div className="deck-context-menu-title">{entry.name}</div>

      <button type="button" onClick={() => { setArtPicker({ entry, sideboard }); close() }}
      data-explain="deck-ctx-change-art">
        🎨 Change art…
      </button>

      <button type="button" onClick={() => { duplicateEntry(entry, sideboard); close() }}
      data-explain="deck-ctx-copy">
        +1 Copy
      </button>
      <button
        type="button"
        onClick={() => { setCardCount(entry.name, entry.setCode, entry.cardNumber, entry.count - 1, sideboard); close() }}
        data-explain="deck-ctx-minus"
        disabled={entry.count <= 1}
      >
        −1 Copy
      </button>

      {sideboard ? (
        <button type="button" onClick={() => { moveToMain(entry.name, entry.setCode, entry.cardNumber); close() }}
        data-explain="deck-ctx-to-main">
          → Main deck
        </button>
      ) : (
        <button type="button" onClick={() => { moveToSideboard(entry.name, entry.setCode, entry.cardNumber); close() }}
        data-explain="deck-ctx-to-sideboard">
          → Sideboard
        </button>
      )}

      <button type="button" className="danger" onClick={() => { removeCard(entry.name, entry.setCode, entry.cardNumber, sideboard); close() }}
      data-explain="deck-ctx-remove">
        Remove all copies
      </button>
    </div>
  )
}

/**
 * Modal showing all printings of a card so the user can pick a different art.
 */
export function ArtPickerModal() {
  const artPickerFor = useGameStore((s) => s.artPickerFor)
  const setArtPicker = useGameStore((s) => s.setArtPickerFor)
  const lookupCardVersions = useGameStore((s) => s.lookupCardVersions)
  const changeArt = useGameStore((s) => s.changeArt)
  const [versions, setVersions] = useState<CardSearchResult[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!artPickerFor) {
      setVersions(null)
      return
    }
    setVersions(null)
    setError(null)
    lookupCardVersions(artPickerFor.entry.name)
      .then(setVersions)
      .catch((e) => setError((e as Error).message))
  }, [artPickerFor, lookupCardVersions])

  if (!artPickerFor) return null

  const { entry, sideboard } = artPickerFor

  return (
    <div className="art-picker-overlay" onClick={() => setArtPicker(null)}>
      <div className="art-picker-modal" onClick={(e) => e.stopPropagation()}>
        <div className="art-picker-header">
          <h3>Change art — {entry.name}</h3>
          <button type="button" className="art-picker-close" onClick={() => setArtPicker(null)}
          data-explain="art-picker-close">✕</button>
        </div>
        {error && <div className="legality-error">Error: {error}</div>}
        {!versions && !error && <div className="deck-empty">Loading printings…</div>}
        {versions && (
          <div className="art-picker-grid">
            {versions.map((v) => {
              const isCurrent = v.setCode === entry.setCode && v.cardNumber === entry.cardNumber
              return (
                <button
                  key={`${v.setCode}:${v.cardNumber}`}
                  type="button"
                  className={`art-picker-card ${isCurrent ? 'current' : ''}`}
                  data-explain="art-picker-card"
                  onClick={() => {
                    if (!isCurrent) {
                      changeArt(entry, { setCode: v.setCode, cardNumber: v.cardNumber, imageUrl: v.imageUrl }, sideboard)
                    }
                    setArtPicker(null)
                  }}
                >
                  {v.imageUrl ? (
                    <img src={v.imageUrl} alt={v.setCode} loading="lazy" />
                  ) : (
                    <div className="deck-card-tile-fallback">{v.setCode} #{v.cardNumber}</div>
                  )}
                  <div className="art-picker-card-meta">
                    <div>{v.setCode} #{v.cardNumber}</div>
                    <div className="art-picker-card-rarity">{v.rarity ?? ''}</div>
                  </div>
                </button>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
