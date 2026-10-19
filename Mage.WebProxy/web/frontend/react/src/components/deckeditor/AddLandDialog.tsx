import { useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import { BASIC_LANDS, BASIC_LAND_MAX, clampLandCount } from '@/utils/basicLands'

/**
 * Add Lands… dialog — the web counterpart of the swing client's
 * AddLandDialog (Mage.Client/src/main/java/mage/client/dialog/AddLandDialog.java).
 *
 * Scope mirrors the Java dialog's land rows: the five basics plus Wastes, each
 * with a count. It deliberately does NOT port the Java-only extras that depend
 * on the desktop client's set browser (`From set:` picker, "Suggest lands",
 * "Only full art lands"): the web editor has no set-of-basics notion, and the
 * dialog's job here is the one the desktop dialog names — get N basics into the
 * maindeck. Printings come from the server's card DB at add time (see
 * `addBasicLands`), never from a hardcoded set/number.
 *
 * Cancel closes without touching the deck. Add lands sends the counts and
 * closes on success; a land the server cannot resolve keeps the dialog open
 * with the error (Fail Fast — no partial silent add).
 */
export default function AddLandDialog({ onClose }: { onClose: () => void }) {
  const addBasicLands = useGameStore((s) => s.addBasicLands)
  const basicLandsError = useGameStore((s) => s.basicLandsError)

  const [counts, setCounts] = useState<Record<string, number>>({})
  const [busy, setBusy] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)

  const total = BASIC_LANDS.reduce((a, l) => a + (counts[l.name] ?? 0), 0)

  const setCount = (name: string, next: number): void => {
    setCounts((c) => ({ ...c, [name]: clampLandCount(next) }))
  }

  const handleAdd = async (): Promise<void> => {
    if (total === 0) return
    setBusy(true)
    setLocalError(null)
    try {
      await addBasicLands(counts)
      onClose()
    } catch (e) {
      // The store already recorded this; keep the dialog open so the numbers
      // are not lost and the reason is visible next to them.
      setLocalError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const error = localError ?? basicLandsError

  return (
    <div className="addland-dialog-overlay" onClick={onClose}>
      <div className="addland-dialog" onClick={(e) => e.stopPropagation()}>
        <div className="addland-dialog-header">
          <h3>Add lands</h3>
          <button type="button" className="addland-dialog-close" onClick={onClose}
          data-explain="addland-close">✕</button>
        </div>

        <p className="addland-dialog-hint">
          Basic lands are added to the maindeck. The printing is chosen by the server&rsquo;s
          card database.
        </p>

        <div className="addland-rows">
          {BASIC_LANDS.map((land) => {
            const n = counts[land.name] ?? 0
            return (
              <div key={land.name} className="addland-row">
                <span className={`addland-symbol addland-symbol-${land.symbol}`}>{land.symbol}</span>
                <span className="addland-name">{land.name}</span>
                <div className="addland-stepper">
                  <button
                    type="button"
                    className="addland-step"
                    onClick={() => setCount(land.name, n - 1)}
                    disabled={n <= 0}
                    data-explain="addland-minus"
                  >
                    −
                  </button>
                  <input
                    type="number"
                    min={0}
                    max={BASIC_LAND_MAX}
                    value={n}
                    onChange={(e) => setCount(land.name, parseInt(e.target.value, 10))}
                    aria-label={`${land.name} count`}
                    data-explain="addland-count"
                  />
                  <button
                    type="button"
                    className="addland-step"
                    onClick={() => setCount(land.name, n + 1)}
                    data-explain="addland-plus"
                  >
                    +
                  </button>
                </div>
              </div>
            )
          })}
        </div>

        {error && <div className="legality-error addland-error">{error}</div>}

        <div className="addland-actions">
          <span className="addland-total">{total} land{total === 1 ? '' : 's'}</span>
          <button type="button" className="addland-add" onClick={() => void handleAdd()}
          disabled={total === 0 || busy}
          data-explain="addland-submit">
            {busy ? 'Adding…' : 'Add lands'}
          </button>
          <button type="button" className="addland-cancel" onClick={onClose}
          data-explain="addland-cancel">Cancel</button>
        </div>
      </div>
    </div>
  )
}
