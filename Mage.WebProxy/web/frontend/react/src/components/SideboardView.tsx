import { useState, useEffect, useMemo } from 'react'
import { useGameStore } from '@/store/gameStore'
import DeckCardTile from './deckeditor/DeckCardTile'
import type { DeckEntry, DeckEntryLite } from '@/types'

/** Convert a wire DeckEntryLite to the deck editor's DeckEntry shape. */
function liteToEntry(l: DeckEntryLite): DeckEntry {
  return { name: l.name, setCode: l.setCode, cardNumber: l.cardNumber, count: l.count }
}

/**
 * Sideboard view — between-games deck editing (SIDEBOARD / CONSTRUCT callbacks).
 *
 * The server fires this after each game in a multi-game match (sideboarding
 * allowed) or during limited deck construction. The player moves cards between
 * maindeck and sideboard; Submit sends `submit_deck` — the server's
 * TableController.submitDeck unblocks match.sideboard() and the next game
 * auto-starts when all players have submitted (endGameAndStartNextGame).
 *
 * Mirrors the stock client's DeckEditorPanel in SIDEBOARDING mode: "move all
 * to sideboard" (btnNew), submit when done. Timer shows the server-side
 * sideboard timeout (server auto-submits when it expires).
 */
export default function SideboardView() {
  const sideboardPrompt = useGameStore((s) => s.sideboardPrompt)
  const setSideboardPrompt = useGameStore((s) => s.setSideboardPrompt)
  const submitDeck = useGameStore((s) => s.submitDeck)
  const submitRejectedAt = useGameStore((s) => s.sideboardSubmitRejectedAt)

  const [cards, setCards] = useState<DeckEntry[]>([])
  const [sideboard, setSideboard] = useState<DeckEntry[]>([])
  const [loadedFor, setLoadedFor] = useState<string | null>(null)
  const [timeLeft, setTimeLeft] = useState<number | null>(null)
  const [submitted, setSubmitted] = useState(false)

  // Server REJECTED our submit_deck (proxy `error` while this modal is open —
  // e.g. deck below the format minimum). Drop the "Submitted — waiting" state
  // so the player can fix the deck and resubmit before the timer expires
  // (2026-09-18: a rejected 36-card maindeck left the modal stuck on Submitted).
  useEffect(() => {
    if (submitRejectedAt > 0) {
      setSubmitted(false)
    }
  }, [submitRejectedAt])

  // Load the prompt's deck into local editing state once per prompt; reset when
  // the prompt closes so the NEXT between-games prompt always reloads fresh.
  useEffect(() => {
    if (!sideboardPrompt) {
      setLoadedFor(null)
      setSubmitted(false)
      return
    }
    const key = `${sideboardPrompt.tableId}:${sideboardPrompt.construct}:${sideboardPrompt.cards.length}:${sideboardPrompt.sideboard.length}:${sideboardPrompt.time}`
    if (loadedFor !== key) {
      setCards(sideboardPrompt.cards.map(liteToEntry))
      setSideboard(sideboardPrompt.sideboard.map(liteToEntry))
      setTimeLeft(sideboardPrompt.time > 0 ? sideboardPrompt.time : null)
      setSubmitted(false)
      setLoadedFor(key)
    }
  }, [sideboardPrompt, loadedFor])

  // Countdown display (server owns the real timeout — this is informational).
  const timerActive = timeLeft != null && timeLeft > 0
  useEffect(() => {
    if (!timerActive) return
    const t = setInterval(() => setTimeLeft((v) => (v != null && v > 0 ? v - 1 : 0)), 1000)
    return () => clearInterval(t)
  }, [timerActive])

  const mainCount = useMemo(() => cards.reduce((sum, e) => sum + e.count, 0), [cards])
  const sbCount = useMemo(() => sideboard.reduce((sum, e) => sum + e.count, 0), [sideboard])

  if (!sideboardPrompt) return null

  /** Move one copy of an entry between lists. */
  const move = (from: 'main' | 'sb', entry: DeckEntry) => {
    const src = from === 'main' ? cards : sideboard
    const srcIdx = src.indexOf(entry)
    if (srcIdx < 0) return

    const newSrc = [...src]
    if (entry.count > 1) {
      newSrc[srcIdx] = { ...entry, count: entry.count - 1 }
    } else {
      newSrc.splice(srcIdx, 1)
    }

    const dst = from === 'main' ? sideboard : cards
    const newDst = [...dst]
    const existing = newDst.find((e) => e.name === entry.name && e.setCode === entry.setCode && e.cardNumber === entry.cardNumber)
    if (existing) {
      newDst[newDst.indexOf(existing)] = { ...existing, count: existing.count + 1 }
    } else {
      newDst.push({ ...entry, count: 1 })
    }

    if (from === 'main') { setCards(newSrc); setSideboard(newDst) }
    else { setSideboard(newSrc); setCards(newDst) }
  }

  /** Stock DeckEditorPanel btnNew in game mode: move all to sideboard. */
  const moveAllToSideboard = () => {
    const merged = [...sideboard]
    for (const e of cards) {
      const existing = merged.find((m) => m.name === e.name && m.setCode === e.setCode && m.cardNumber === e.cardNumber)
      if (existing) {
        merged[merged.indexOf(existing)] = { ...existing, count: existing.count + e.count }
      } else {
        merged.push({ ...e })
      }
    }
    setSideboard(merged)
    setCards([])
  }

  const handleSubmit = () => {
    submitDeck({
      cards: cards.map((e) => ({ name: e.name, setCode: e.setCode, cardNumber: e.cardNumber, count: e.count })),
      sideboard: sideboard.map((e) => ({ name: e.name, setCode: e.setCode, cardNumber: e.cardNumber, count: e.count })),
    })
    // Keep the view up until the server moves us forward (next game start or
    // new prompt clears it) — don't optimistically dismiss; errors surface
    // via the error toast and this view stays retryable.
    setSubmitted(true)
  }

  const close = () => setSideboardPrompt(null)

  const tileList = (list: DeckEntry[], from: 'main' | 'sb') => (
    <div className="sideboard-grid">
      {list.map((e) => (
        <div key={`${e.name}|${e.setCode}|${e.cardNumber}`} className="sideboard-tile">
          <DeckCardTile entry={e} sideboard={from === 'sb'} />
          <div className="sideboard-tile-actions">
            <button
              className="btn btn-small"
              title={from === 'main' ? 'Move to sideboard' : 'Move to maindeck'}
              onClick={() => move(from, e)}
              data-explain="sb-move"
              disabled={submitted}
            >
              {from === 'main' ? '→ SB' : '→ Main'}
            </button>
          </div>
        </div>
      ))}
      {list.length === 0 && <div className="sideboard-empty">No cards</div>}
    </div>
  )

  return (
    <div className="modal-overlay" style={{ display: 'flex' }}>
      <div className="modal-content" style={{ maxWidth: '900px', width: '90vw' }}>
        <div className="modal-title">
          {sideboardPrompt.construct ? 'Construct your deck' : 'Sideboard'}
          {timerActive && (
            <span style={{ marginLeft: '1rem', fontSize: '0.9rem', color: 'var(--text-secondary)' }}>
              Time left: {timeLeft}s
            </span>
          )}
        </div>

        <div style={{ display: 'flex', gap: '1rem', margin: '0.5rem 0 0.75rem', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
          <span>Maindeck: {mainCount}</span>
          <span>Sideboard: {sbCount}</span>
          <span>Deck: {sideboardPrompt.deckName}</span>
        </div>

        <div style={{ display: 'flex', gap: '1rem', maxHeight: '55vh', overflowY: 'auto' }}>
          <div style={{ flex: 1 }}>
            <div style={{ fontWeight: 'bold', marginBottom: '0.4rem' }}>Maindeck ({mainCount})</div>
            {tileList(cards, 'main')}
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontWeight: 'bold', marginBottom: '0.4rem' }}>Sideboard ({sbCount})</div>
            {tileList(sideboard, 'sb')}
          </div>
        </div>

        <div style={{ display: 'flex', gap: '0.5rem', marginTop: '1rem', alignItems: 'center' }}>
          <button className="btn btn-primary" onClick={handleSubmit}
          data-explain="sb-submit" disabled={submitted}>
            {submitted ? 'Submitted — waiting for other players…' : 'Submit deck'}
          </button>
          <button className="btn" onClick={moveAllToSideboard}
          data-explain="sb-move-all" disabled={submitted}>
            Move all to sideboard
          </button>
          {submitted && (
            <button className="btn" onClick={close} title="Hide this view — submission already sent"
            data-explain="sb-hide">
              Hide
            </button>
          )}
        </div>
      </div>
    </div>
  )
}