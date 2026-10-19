/**
 * Window surface for the hint system's browser-test hooks.
 *
 * Declared in the hints directory so the feature owns its whole footprint: `__xmageHints` is
 * installed by HintsRoot (like the store's dev-only `__gameStore`) and is how the evidence
 * scripts drive the tip controls and re-read persisted dismissals after a reload.
 */
import type { CoverageReport } from './explain'
import type { HintId } from './hints'

declare global {
  interface Window {
    __xmageHints?: {
      /** Coverage of the button-explanation registry against the live DOM. */
      report: () => CoverageReport
      /** Current persisted hint state ({ off, dismissed }). */
      state: () => { off: boolean; dismissed: HintId[] }
      /** Every tip id, in offer order. */
      ids: () => HintId[]
      dismiss: (id: HintId) => void
      off: () => void
      /** Test-only re-arm (no UI for it — see hints.ts). */
      reset: () => void
      subscribe: (listener: () => void) => () => void
    }
  }
}

export {}
