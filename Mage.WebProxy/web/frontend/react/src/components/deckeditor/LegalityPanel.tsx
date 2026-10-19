import { useGameStore } from '@/store/gameStore'
import type { LegalityBracket, LegalityFormatResult } from '@/types'

/**
 * Legality panel — badge grid mirroring DeckLegalityPanel. Each badge is a format;
 * color reflects legal/partly/illegal; tooltip lists errors. EDH power level &
 * Bracket 1–5 are shown as additional rows beneath.
 */
export default function LegalityPanel() {
  const legality = useGameStore((s) => s.legality)
  const legalityLoading = useGameStore((s) => s.legalityLoading)
  const legalityError = useGameStore((s) => s.legalityError)
  const runLegality = useGameStore((s) => s.runLegality)

  const handleRefresh = (): void => {
    // Force a fresh check by clearing the stored hash first
    useGameStore.setState({ legalityDeckHash: null })
    void runLegality()
  }

  return (
    <div className="legality-panel">
      <div className="legality-header">
        <h3>Legality</h3>
        <button type="button" className="legality-refresh" onClick={handleRefresh}
        data-explain="legality-refresh" disabled={legalityLoading}>
          {legalityLoading ? '…' : '↻'}
        </button>
      </div>

      {legalityError && <div className="legality-error">Error: {legalityError}</div>}
      {legalityLoading && !legality && <div className="legality-loading">Checking…</div>}

      {/* A check is in flight for the CURRENT deck, so what is on screen describes the
          PREVIOUS revision. Say so — the 2026-09-12 run showed 56-card legality for a
          60-card deck for ~44 s with no indication it was stale (N3). */}
      {legalityLoading && legality && (
        <div className="legality-stale">Checking this deck… badges below are from the previous version</div>
      )}

      {!legality && !legalityLoading && !legalityError && (
        <div className="legality-empty">Add cards to see legality.</div>
      )}

      {legality && (
        <>
          <div className={`legality-grid ${legalityLoading ? 'legality-stale-badges' : ''}`}>
            {legality.formats.map((f) => (
              <FormatBadge key={f.format} format={f} />
            ))}
          </div>

          {legality.edhPower && (
            <div className="legality-edh">
              <h4>EDH Power Level</h4>
              {legality.edhPower.error ? (
                <div className="legality-edh-error">{legality.edhPower.error}</div>
              ) : (
                <div className="legality-edh-level">
                  Level {legality.edhPower.level ?? '—'}
                  {legality.edhPower.powerCards && legality.edhPower.powerCards.length > 0 && (
                    <span className="legality-edh-count">
                      ({legality.edhPower.powerCards.length} power cards)
                    </span>
                  )}
                </div>
              )}
            </div>
          )}

          {legality.brackets && (
            <div className="legality-brackets">
              <h4>Commander Brackets</h4>
              <div className="legality-brackets-grid">
                {legality.brackets.map((b) => (
                  <BracketBadge key={b.level} bracket={b} />
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}

function FormatBadge({ format }: { format: LegalityFormatResult }) {
  const statusClass =
    format.status === 'legal' ? 'legal' :
    format.status === 'partly' ? 'partly' :
    format.status === 'illegal' ? 'illegal' :
    'unknown'

  const tooltipText = format.errors.length
    ? format.errors.map((e) => `${e.group}: ${e.message}`).join('\n')
    : (format.error ?? (format.status === 'legal' ? 'Legal' : 'No errors'))

  return (
    <div
      className={`legality-badge legality-${statusClass}`}
      title={tooltipText}
    >
      {format.shortName}
    </div>
  )
}

function BracketBadge({ bracket }: { bracket: LegalityBracket }) {
  const ok = bracket.legal
  const tooltipText = bracket.badCards.length
    ? `Bad cards for B${bracket.level}: ${bracket.badCards.join(', ')}`
    : `OK for Bracket ${bracket.level}`

  return (
    <div
      className={`legality-badge legality-bracket legality-${ok ? 'legal' : 'illegal'}`}
      title={tooltipText}
    >
      B{bracket.level}
    </div>
  )
}
