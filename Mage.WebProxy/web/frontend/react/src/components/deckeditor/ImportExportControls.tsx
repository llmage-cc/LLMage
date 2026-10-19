import { useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import AddLandDialog from './AddLandDialog'

/**
 * Save / load / import / export controls for the current deck.
 *
 * File ops are pure browser (no server round-trip): export downloads a .dck,
 * import parses one.
 */
export default function ImportExportControls() {
  const currentDeck = useGameStore((s) => s.currentDeck)
  const isDirty = useGameStore((s) => s.isDirty)
  const savedDecks = useGameStore((s) => s.savedDecks)
  const currentDeckSavedId = useGameStore((s) => s.currentDeckSavedId)
  const newDeck = useGameStore((s) => s.newDeck)
  const saveDeck = useGameStore((s) => s.saveDeck)
  const saveDeckAs = useGameStore((s) => s.saveDeckAs)
  const deleteDeck = useGameStore((s) => s.deleteDeck)
  const openDeck = useGameStore((s) => s.openDeck)
  const exportDeckText = useGameStore((s) => s.exportDeckText)
  const importDeckText = useGameStore((s) => s.importDeckText)
  const showError = useGameStore((s) => s.showError)

  const [importText, setImportText] = useState('')
  const [showImportDialog, setShowImportDialog] = useState(false)
  const [showSavedList, setShowSavedList] = useState(false)
  const [newDeckName, setNewDeckName] = useState('')
  const [showSaveAs, setShowSaveAs] = useState(false)
  const [showAddLand, setShowAddLand] = useState(false)

  const handleExportDownload = (): void => {
    const text = exportDeckText()
    const blob = new Blob([text], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${currentDeck.name.replace(/[^a-z0-9_-]+/gi, '_')}.dck`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  const handleExportCopy = (): void => {
    const text = exportDeckText()
    navigator.clipboard.writeText(text).catch((err) => {
      showError(`Copy failed: ${(err as Error).message}`)
    })
  }

  const handleImportSubmit = (): void => {
    const result = importDeckText(importText)
    if (result.ok) {
      setShowImportDialog(false)
      setImportText('')
    } else {
      showError(`Import failed: ${result.error}`)
    }
  }

  const handleImportFile = (file: File): void => {
    const reader = new FileReader()
    reader.onload = () => {
      const result = importDeckText(String(reader.result ?? ''), file.name.replace(/\.(dck|txt|dec)$/i, ''))
      if (!result.ok) {
        showError(`Import failed: ${result.error}`)
      }
    }
    reader.readAsText(file)
  }

  const handleSaveAsSubmit = (): void => {
    if (!newDeckName.trim()) return
    saveDeckAs(newDeckName.trim())
    setShowSaveAs(false)
    setNewDeckName('')
  }

  return (
    <div className="importexport-panel">
      <h3>Deck</h3>

      <div className="importexport-row">
        <button type="button" onClick={() => newDeck()}
        data-explain="deck-new">New</button>
        <button type="button" onClick={() => saveDeck()} disabled={!isDirty && currentDeckSavedId !== null}
        data-explain="deck-save">
          Save{isDirty ? ' *' : ''}
        </button>
        <button type="button" onClick={() => { setShowSaveAs(true); setNewDeckName(currentDeck.name) }}
        data-explain="deck-save-as">
          Save as…
        </button>
        <button type="button" onClick={() => setShowSavedList(!showSavedList)}
        data-explain="deck-open">
          Open… ({savedDecks.length})
        </button>
      </div>

      <div className="importexport-row">
        <button type="button" onClick={handleExportCopy}
        data-explain="deck-copy-dck">Copy .dck</button>
        <button type="button" onClick={handleExportDownload}
        data-explain="deck-download-dck">Download .dck</button>
        <button type="button" onClick={() => setShowImportDialog(!showImportDialog)}
        data-explain="deck-import">Import…</button>
      </div>

      <div className="importexport-row">
        <button type="button" onClick={() => setShowAddLand(true)}
        data-explain="deck-add-land">Add Lands…</button>
      </div>

      {showAddLand && <AddLandDialog onClose={() => setShowAddLand(false)} />}

      {showSavedList && (
        <div className="importexport-saved-list">
          {savedDecks.length === 0 ? (
            <div className="deck-empty">No saved decks yet.</div>
          ) : (
            savedDecks.map((d) => (
              <div key={d.id} className="saved-deck-row">
                <button
                  type="button"
                  className="saved-deck-name"
                  onClick={() => { openDeck(d.id); setShowSavedList(false) }}
                  data-explain="deck-saved-open"
                >
                  {d.name}
                </button>
                <span className="saved-deck-date">
                  {new Date(d.updatedAt).toLocaleDateString()}
                </span>
                <button
                  type="button"
                  className="saved-deck-delete"
                  onClick={() => deleteDeck(d.id)}
                  data-explain="deck-saved-delete"
                  title="Delete"
                >
                  ✕
                </button>
              </div>
            ))
          )}
        </div>
      )}

      {showImportDialog && (
        <div className="importexport-import-dialog">
          <textarea
            value={importText}
            onChange={(e) => setImportText(e.target.value)}
            placeholder="Paste .dck here (with NAME:/SB: format)"
            rows={10}
          />
          <div className="importexport-import-actions">
            <input
              type="file"
              accept=".dck,.txt,.dec"
              onChange={(e) => {
                const f = e.target.files?.[0]
                if (f) handleImportFile(f)
              }}
            />
            <button type="button" onClick={handleImportSubmit}
            data-explain="deck-import-submit">Import</button>
            <button type="button" onClick={() => { setShowImportDialog(false); setImportText('') }}
            data-explain="deck-import-cancel">Cancel</button>
          </div>
        </div>
      )}

      {showSaveAs && (
        <div className="importexport-saved-as">
          <input
            type="text"
            value={newDeckName}
            onChange={(e) => setNewDeckName(e.target.value)}
            placeholder="New deck name"
          />
          <button type="button" onClick={handleSaveAsSubmit}
          data-explain="deck-save-as-submit">Save</button>
          <button type="button" onClick={() => setShowSaveAs(false)}
          data-explain="deck-save-as-cancel">Cancel</button>
        </div>
      )}
    </div>
  )
}
