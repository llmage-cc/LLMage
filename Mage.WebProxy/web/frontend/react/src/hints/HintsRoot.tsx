import { useEffect } from 'react'
import { HintLayer } from './HintLayer'
import { Explainer } from './Explainer'
import { explainCoverageReport } from './explain'
import { getHintsState, resetHints, subscribeHints, turnOffAllHints, dismissHint, HINTS } from './hints'

/**
 * HintsRoot — the single mount point for the hint system, added to App.tsx as one line.
 *
 * It renders the onboarding tips (HintLayer) and the shared button-explanation tooltip
 * (Explainer) and exposes the browser-test hooks the evidence scripts use.
 *
 * DEV ASSERTION: in dev builds it walks the DOM shortly after mount and warns loudly when a
 * VISIBLE control has no explanation — the same walk as `explainCoverageReport`, so a missing
 * explanation shows up while the relevant screen is open instead of at review time. The full
 * report is printed by the harness script (scripts/verify-explain-coverage.sh), which drives
 * both the lobby and a synthetic board and prints every count.
 */
export function HintsRoot() {
  useEffect(() => {
    // Dev-only coverage assertion. It reports rather than throws: a scene mid-transition can
    // legitimately have a control appear before its explanation rule is relevant (and a throw
    // would take the whole client down over a tooltip). The audit script is the hard gate.
    if (!import.meta.env.DEV) return
    const timer = window.setTimeout(() => {
      const report = explainCoverageReport(document)
      if (report.missing.length > 0) {
        console.warn(
          `[hints] ${report.missing.length} visible control(s) have no explanation ` +
          `(${report.explained}/${report.total} explained). Run scripts/verify-explain-coverage.sh for the list.`,
          report.missing,
        )
      }
    }, 2000)
    return () => window.clearTimeout(timer)
  }, [])

  useEffect(() => {
    // Harness hooks. `window.__xmageHints` is how the evidence scripts drive the tip controls
    // and prove that dismissals survive a reload (the state is re-read from localStorage at
    // module load, so a reload must still see it).
    window.__xmageHints = {
      report: () => explainCoverageReport(document),
      state: () => getHintsState(),
      ids: () => HINTS.map((h) => h.id),
      dismiss: dismissHint,
      off: turnOffAllHints,
      // Reset is test-only — see hints.ts. There is deliberately no UI for it.
      reset: resetHints,
      subscribe: subscribeHints,
    }
    return () => { delete window.__xmageHints }
  }, [])

  return (
    <>
      <HintLayer />
      <Explainer />
    </>
  )
}
