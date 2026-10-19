import { useEffect, useRef, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import SearchPanel from './SearchPanel'
import DeckPanel from './DeckPanel'
import SideboardPanel from './SideboardPanel'
import LegalityPanel from './LegalityPanel'
import CollectionViewer from './CollectionViewer'
import ImportExportControls from './ImportExportControls'
import { rootZoom } from '@/utils/viewport'

const MIN_LEFT = 240
const MAX_LEFT = 800
const MIN_RIGHT = 280
const MAX_RIGHT = 800

const DEFAULT_LEFT = 320
const DEFAULT_RIGHT = 380

const LS_LEFT_PX = 'xmage.web.deckeditor.leftPx'
const LS_RIGHT_PX = 'xmage.web.deckeditor.rightPx'

function loadLeftPx(): number {
  const v = Number(localStorage.getItem(LS_LEFT_PX))
  return Number.isFinite(v) && v >= MIN_LEFT && v <= MAX_LEFT ? v : DEFAULT_LEFT
}
function loadRightPx(): number {
  const v = Number(localStorage.getItem(LS_RIGHT_PX))
  return Number.isFinite(v) && v >= MIN_RIGHT && v <= MAX_RIGHT ? v : DEFAULT_RIGHT
}

/**
 * Deck editor root view — three-column layout mirroring the swing DeckEditorPanel:
 *   left  = search + filters           (resizable)
 *   main  = deck / sideboard           (flexible)
 *   right = legality + save/load       (resizable)
 *
 * Drag handles on the borders adjust column widths (persisted in localStorage).
 */
export default function DeckEditorView() {
  const activeTab = useGameStore((s) => s.activeEditorTab)
  const setActiveTab = useGameStore((s) => s.setEditorTab)
  const closeEditor = useGameStore((s) => s.closeEditor)
  const currentDeck = useGameStore((s) => s.currentDeck)
  const isDirty = useGameStore((s) => s.isDirty)
  const runLegality = useGameStore((s) => s.runLegality)

  const [leftPx, setLeftPx] = useState(loadLeftPx())
  const [rightPx, setRightPx] = useState(loadRightPx())

  const editorRef = useRef<HTMLDivElement>(null)
  const dragData = useRef<{ which: 'left' | 'right'; startX: number; startWidth: number; zoom: number } | null>(null)

  const onDragStart = (which: 'left' | 'right', e: React.MouseEvent): void => {
    e.preventDefault()
    dragData.current = {
      which,
      startX: e.clientX,
      startWidth: which === 'left' ? leftPx : rightPx,
      zoom: rootZoom(),
    }
  }

  useEffect(() => {
    const onMouseMove = (e: MouseEvent): void => {
      if (!dragData.current) return
      const { which, startX, startWidth, zoom } = dragData.current
      // clientX deltas are REAL screen px; column widths are ROOT CSS px
      const delta = (e.clientX - startX) / zoom
      if (which === 'left') {
        const next = Math.max(MIN_LEFT, Math.min(MAX_LEFT, startWidth + delta))
        setLeftPx(next)
      } else {
        // Right column: dragging left-increases width
        const next = Math.max(MIN_RIGHT, Math.min(MAX_RIGHT, startWidth - delta))
        setRightPx(next)
      }
    }
    const onMouseUp = (): void => {
      if (dragData.current) {
        localStorage.setItem(LS_LEFT_PX, String(leftPx))
        localStorage.setItem(LS_RIGHT_PX, String(rightPx))
        dragData.current = null
      }
    }
    window.addEventListener('mousemove', onMouseMove)
    window.addEventListener('mouseup', onMouseUp)
    return () => {
      window.removeEventListener('mousemove', onMouseMove)
      window.removeEventListener('mouseup', onMouseUp)
    }
  }, [leftPx, rightPx])

  // Trigger an initial legality check on mount (debounce handled in slice).
  const didInitial = useRef(false)
  useEffect(() => {
    if (didInitial.current) return
    didInitial.current = true
    if (currentDeck.cards.length > 0 || currentDeck.sideboard.length > 0) {
      void runLegality()
      // Also backfill any missing card images (e.g. after a text import)
      void useGameStore.getState().hydrateDeckImages()
    }
  }, [currentDeck.cards.length, currentDeck.sideboard.length, runLegality])

  return (
    <div className="deck-editor" ref={editorRef}>
      <div className="deck-editor-header">
        <button className="deck-editor-btn" onClick={closeEditor}
        data-explain="deck-editor-back">← Back to Lobby</button>
        <h2 className="deck-editor-title">
          Deck Editor — {currentDeck.name}{isDirty ? ' *' : ''}
        </h2>
        <div className="deck-editor-tabs">
          <button
            className={`deck-editor-tab ${activeTab === 'build' ? 'active' : ''}`}
            onClick={() => setActiveTab('build')}
            data-explain="deck-editor-build-tab"
          >
            Build
          </button>
          <button
            className={`deck-editor-tab ${activeTab === 'collection' ? 'active' : ''}`}
            onClick={() => setActiveTab('collection')}
            data-explain="deck-editor-collection-tab"
          >
            Collection
          </button>
        </div>
      </div>

      <div
        className="deck-editor-body"
        style={{ gridTemplateColumns: `${leftPx}px 6px 1fr 6px ${rightPx}px` }}
      >
        {/* Left: Search */}
        <div className="deck-editor-left" style={{ width: leftPx }}>
          <SearchPanel />
        </div>

        {/* Left resize handle */}
        <div
          className="deck-editor-resize-handle"
          onMouseDown={(e) => onDragStart('left', e)}
          title={`Drag to resize (currently ${leftPx}px)`}
        />

        {/* Center: current deck or collection */}
        <div className="deck-editor-main">
          {activeTab === 'build' ? (
            <>
              <DeckPanel />
              <SideboardPanel />
            </>
          ) : (
            <CollectionViewer />
          )}
        </div>

        {/* Right resize handle */}
        <div
          className="deck-editor-resize-handle"
          onMouseDown={(e) => onDragStart('right', e)}
          title={`Drag to resize (currently ${rightPx}px)`}
        />

        {/* Right: legality + actions */}
        <div className="deck-editor-right" style={{ width: rightPx }}>
          <LegalityPanel />
          <ImportExportControls />
        </div>
      </div>
    </div>
  )
}
