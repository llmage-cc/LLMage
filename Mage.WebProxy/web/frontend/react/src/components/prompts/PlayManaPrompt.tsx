import { useState, useSyncExternalStore } from 'react'
import type { PlayManaPrompt as PlayManaPromptType } from '@/types'
import { useGameStore } from '@/store/gameStore'
import { getAutoTapRun, startAutoTapAllLands, subscribeAutoTapRun } from '@/store/slices/autoTapRun'

/**
 * PlayManaPrompt — right-rail renderer for a `play_mana` prompt.
 *
 * NOT CURRENTLY RENDERED: ActionPromptBar.tsx deliberately routes `play_mana` to
 * DecisionPopup (`PlayManaBody`), which is the live UI. This file is kept in sync with
 * DecisionPopup's mana body because it is reachable the moment anyone re-enables it, and
 * a divergent copy is exactly how the duplicated `/\{([WUBRG])\}/g` regex bug
 * survived. Both files now drive the SAME "Auto-Tap Lands" run in
 * `store/slices/autoTapRun.ts`, so they cannot diverge on behaviour.
 *
 * Auto-tap invariant (2026-09-12, 2026-09-13, 2026-09-14, 2026-09-16):
 *   1. only a permanent KNOWN to produce a needed colour may be tapped,
 *   2. exactly one tap per server re-prompt (never a burst),
 *   3. never the same land twice inside one press, and
 *   4. never send booleanChoice(false) as a fallback — that CANCELS the cast during
 *      GAME_PLAY_MANA, the server re-offers the identical cast, and an automated client
 *      spins forever. Cancelling is an explicit user action only.
 */
export function PlayManaPrompt({ prompt }: { prompt: PlayManaPromptType }) {
  const booleanChoice = useGameStore((s) => s.booleanChoice)
  const toggleManaAutoPay = useGameStore((s) => s.toggleManaAutoPay)
  const toggleUseFirstMana = useGameStore((s) => s.toggleUseFirstMana)
  const [autoPayEnabled, setAutoPayEnabled] = useState(true)
  const [useFirstMana, setUseFirstMana] = useState(false)
  // The run's own state: 'notice' is the honest reason a press could not finish the cost.
  const run = useSyncExternalStore(subscribeAutoTapRun, getAutoTapRun, getAutoTapRun)

  const specialMana = useGameStore((s) => s.specialMana)

  return (
    <div className="prompt-mana">
      <div className="prompt-mana-hint">
        Click untapped lands to tap for mana, or use the buttons below.
      </div>
      {run.notice && (
        <div className="prompt-mana-notice" role="status">
          {run.notice}
        </div>
      )}
      <div className="prompt-mana-buttons">
        {/* The label stays EXACTLY "Auto-Tap Lands" for the whole run — automated UI tests
            locate this control by its text (skill refs: browser-ui-test-harness,
            webui-testing-recipes). Progress is exposed as data-autotap-active instead of by
            changing the label mid-run. */}
        <button
          className="btn btn-primary btn-sm"
          data-autotap-active={run.active ? 'true' : 'false'}
          title="Tap every untapped land that is known to produce a needed colour for this cost, in one go."
          onClick={() => startAutoTapAllLands(useGameStore.getState())}
        >
          Auto-Tap Lands
        </button>
        <button
          className={`btn btn-secondary btn-sm${autoPayEnabled ? ' active' : ''}`}
          onClick={() => {
            const newVal = !autoPayEnabled
            setAutoPayEnabled(newVal)
            toggleManaAutoPay(newVal)
          }}
          title="Toggle server-side auto-payment from mana pool"
        >
          {autoPayEnabled ? '✓ Auto-Pay Pool' : 'Auto-Pay Pool'}
        </button>
        <button
          className={`btn btn-secondary btn-sm${useFirstMana ? ' active' : ''}`}
          onClick={() => {
            const newVal = !useFirstMana
            setUseFirstMana(newVal)
            toggleUseFirstMana(newVal)
          }}
          title="Use first mana ability when tapping lands with multiple abilities (e.g. dual lands). This is how you choose WHICH colour a dual produces: the server taps the land's first mana ability."
        >
          {useFirstMana ? '✓ First Mana Ability' : 'First Mana Ability'}
        </button>
        {prompt.specialAvailable && (
          <button className="btn btn-secondary btn-sm" onClick={() => specialMana()}>
            Special Mana
          </button>
        )}
        {/* Cancel is an EXPLICIT user action. It is the only path that sends
            booleanChoice(false) during GAME_PLAY_MANA. */}
        <button
          className="btn btn-danger btn-sm"
          onClick={() => booleanChoice(false)}
          title="Give up the pending cast/payment. The spell is not cast."
        >
          Cancel Spell
        </button>
      </div>
    </div>
  )
}
