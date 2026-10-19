import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useGameStore } from '@/store/gameStore'
import { getAutoTapRun, startAutoTapAllLands, subscribeAutoTapRun } from '@/store/slices/autoTapRun'
import { rootZoom, cssViewportWidth, cssViewportHeight } from '@/utils/viewport'
import { getPendingActionSourceId } from '@/store/slices/pendingActionSource'
import { PROMPT_POSITION_EVENT, PROMPT_POSITION_KEY } from '@/store/slices/priorityDing'
import { ManaText } from './ManaCost'
import { BooleanPrompt } from './prompts/BooleanPrompt'
import { SelectPrompt, selectChoicesKeptAsButtons } from './prompts/SelectPrompt'
import { TargetPrompt } from './prompts/TargetPrompt'
import { ChooseChoicePrompt } from './prompts/ChooseChoicePrompt'
import { MultiAmountPrompt } from './prompts/MultiAmountPrompt'
import type {
  ActionPrompt,
  ChooseAbilityPrompt as ChooseAbilityPromptType,
  PlayManaPrompt as PlayManaPromptType,
  AmountPrompt as AmountPromptType,
} from '@/types'

/**
 * DecisionPopup — THE mid-screen priority window (item 2: "make the
 * mulligan decision and pass priority and similar popups show up in a window
 * mid screen so they can't be missed").
 *
 * Anchor for a play_mana window: BOTTOM-RIGHT of the viewport (the mana auto-pay
 * window "can just be bottom right") — it is a control
 * panel, not a question, and it must not sit over the board where lands are
 * clicked. A choose_ability window that follows a CARD CLICK still anchors
 * above/below that card; every other prompt centres.
 */
interface PopupAnchor {
  x: number
  y: number
  placement: 'below' | 'above'
}

function findManaAnchor(): PopupAnchor | null {
  // Bottom-right corner anchor (root CSS px space; clamped later).
  return { x: cssViewportWidth(), y: cssViewportHeight(), placement: 'above' }
}

/**
 * Every action prompt renders here, centred over the board and framed in the
 * accent colour, so a prompt can no longer sit unnoticed in the right rail.
 * The right-rail ActionPromptBar keeps its context line and the meta controls
 * (Pass Priority, skips, Hold Priority / Rollback / Undo / Concede), and the
 * F2–F11 keyboard shortcuts keep operating on the store — nothing about the
 * ANSWER paths changed, only where the prompt is shown.
 *
 * Owns all nine prompt types (the whole ActionPrompt union):
 *   boolean · select · target (without lookAtCards) · choose_choice ·
 *   choose_ability · amount · play_mana · choose_pile · multi_amount
 *
 * EXCEPT the pass-priority WAIT: a `select` prompt whose every
 * choice is a board-clickable card, or that has no choices at all ("Nothing to
 * play — Pass Priority"), opens NO window — it would be pure noise, and the
 * ding + edge-glow screen flash already
 * announce the priority handoff. The rail keeps the message line, Pass Priority
 * and the skips. Combat selection (Declare Attackers) keeps the window, and so
 * does any select with a special button or non-card choices — see
 * isEmptySelectWindow().
 *
 * NOT rendered by this window (deliberately, the same split as before):
 *   - target WITH lookAtCards and choose_pile — CardSelectPopup owns those
 *     card-grid windows (see shouldShowCardSelectPopup); the window would only
 *     duplicate a grid that is already unmissable.
 *
 * NOT a modal overlay: casting, targeting and tapping mana all happen by
 * clicking cards/lands on the board while the window is up, so a backdrop
 * would break the very interaction the prompt asks for. Instead the window is
 * compact (≤480px, less for yes/no questions), draggable by its header, and
 * clamped so its header can never leave the screen.
 *
 * WINDOW LIFECYCLE: rendering follows the store's `actionPrompt` — the prompt
 * lifecycle (epoch guard, resolution, hide-on-answer, the autoTapRun bridge in
 * setActionPrompt) is untouched. The element is keyed per prompt identity, so
 * every new prompt remounts it: the window recentres and window-local state
 * (draft amounts, filters) starts fresh.
 *
 * PLAY_MANA EXCEPTION: instead of centre, the window anchors below the priority
 * player's mana-pool header (fallback: my own player header, then centre) —
 * during mana payment the eye is on the pool and the battlefield, not the
 * middle of the board. See findManaAnchor(). The anchor is captured once per
 * prompt identity, so the play_mana re-prompt loop keeps the window fixed at
 * the pool for the whole payment.
 *
 * DRAG + ZOOM: fixed left/top are ROOT CSS px while mouse deltas are REAL px
 * (html carries a CSS zoom — see utils/viewport.ts), so every delta is divided
 * by the zoom captured at drag start.
 */

/** Window title per prompt type — matches the engine's framing of the ask. */
function windowTitle(prompt: ActionPrompt): string {
  switch (prompt.type) {
    case 'boolean':
      return 'The game asks'
    case 'select':
      return prompt.isCombat ? 'Declare combat' : 'Choose what to play'
    case 'target':
      return prompt.triggerOrder ? 'Order triggers' : 'Choose target'
    case 'choose_choice':
      return 'Choose one'
    case 'choose_ability':
      return 'Choose ability'
    case 'amount':
      return 'Choose a number'
    case 'play_mana':
      return 'Pay mana'
    case 'choose_pile':
      return 'Choose a pile'
    case 'multi_amount':
      return 'Distribute'
  }
}

/** True when this prompt type is owned by CardSelectPopup (never render it here too). */
function ownedByCardSelect(prompt: ActionPrompt): boolean {
  if (prompt.type === 'choose_pile') return true
  return prompt.type === 'target' && (prompt.lookAtCards?.length ?? 0) > 0
}

/**
 * True when the prompt is the PASS-PRIORITY WAIT and must NOT open the window.
 *
 * A `select` prompt with no interactable content — every choice is a card the
 * player clicks on the board, or there are no choices at all (the "Nothing to
 * play — Pass Priority" family) — has NOTHING clickable inside the window: it
 * would be pure noise, the exact "every centered prompt window is annoying on
 * pass-priority waits" complaint now answered by the ding + edge-glow
 * flash (store/slices/priorityDing.ts). Those channels carry the notification
 * role; the rail still always shows the message line and Pass Priority.
 *
 * The "nothing clickable" test is the SAME predicate the rail's SelectPrompt
 * uses for its button list (selectChoicesKeptAsButtons) — if the rail would
 * render no buttons, the window has no content and stays closed. That keeps
 * combat selection (attack/block choices → buttons in the window) open, and a
 * graveyard/exile/command-zone 'activate' play whose card is NOT rendered
 * clickable anywhere (command zone objects, zones the board doesn't tile)
 * also opens the window — a flashback card in MY graveyard/exile now IS
 * clickable on the board tiles (offHandClickableCardIds), so it suppresses the
 * window exactly like a hand cast. Only a special button (All attack etc.)
 * needs its own early check — it is a rail-owned control, but Declare-Attackers
 * with one must keep the window so the attack declaration cannot be missed

 *
 * General rule — reassess when selection boxes pop up: a
 * select prompt whose kept-button choices are all duplicates of choices that
 * ARE clickable on the board (the same ability listed under two keys, a
 * main-card alias whose card is rendered) also stays closed — every kept
 * button has a board twin the player can click where they are looking. Only
 * when at least one kept button has NO board twin (commander cast, flashback,
 * off-board activate) does the window open. Combat keeps its window via the
 * specialButton check above and attack/block choices (never board-clickable
 * cast/land/activate actions).
 *
 * Exported for scripts/verify-pass-priority-window.tsx — the regression check
 * asserts this predicate directly beside the SSR render.
 */
export function isEmptySelectWindow(prompt: ActionPrompt): boolean {
  if (prompt.type !== 'select') return false
  if (prompt.specialButton) return false
  const gameState = useGameStore.getState().gameState
  const kept = selectChoicesKeptAsButtons(prompt, gameState)
  if (kept.length === 0) return true
  // Every kept button ALSO clickable on the board → nothing to click in the
  // window.
  const clickable = new Set<string>()
  if (gameState) {
    const me = gameState.players.find((p) => p.isMe)
    if (me) for (const c of me.hand) if (c.id) clickable.add(c.id)
    for (const p of gameState.players) for (const perm of p.battlefield) if (perm.id) clickable.add(perm.id)
  }
  return kept.every((c) => !c.id || clickable.has(c.id))
}

/**
 * Anchor preference for a choose_ability window that follows a CARD CLICK:
 * when a clicked card has a selectable ability, the window appears above or
 * below that card depending on where it is on screen. The click path is:
 * playable card click (select_choice) → engine
 * `chooseLandOrSpellAbility` → GAME_CHOOSE_ABILITY. `pendingActionSource`
 * still holds the clicked card's id (the prompt follows the click within one
 * round-trip, inside the source's TTL), so the window can sit beside the card:
 * BELOW it when the card is on the upper half of the board (the space under it
 * is free), ABOVE it when it is on the lower half. Null when there is no live
 * source (server-initiated ability prompt) or the card is not rendered — the
 * window then falls back to centre.
 */
function findCardAnchor(): PopupAnchor | null {
  const sourceId = getPendingActionSourceId()
  if (!sourceId) return null
  const scope = document.querySelector('.game-center')
  if (!scope) return null
  const cardEl = scope.querySelector(`[data-card-id="${CSS.escape(sourceId)}"]`)
  if (!cardEl) return null
  const rect = cardEl.getBoundingClientRect()
  const z = rootZoom()
  const cardCenterY = (rect.top + rect.bottom) / 2
  const screenCenterY = window.innerHeight / 2
  // Card on the upper half → open below it; lower half → above it (at exact
  // screen centre, below wins — the board sits high more often than low). The
  // chosen side is the one with room, and it keeps the window near the eye.
  const placement = cardCenterY <= screenCenterY ? 'below' : 'above'
  const anchorY = placement === 'below' ? rect.bottom / z : rect.top / z
  const anchorX = (rect.left + rect.right) / 2 / z
  return { x: anchorX, y: anchorY, placement }
}

export default function DecisionPopup() {
  const actionPrompt = useGameStore((s) => s.actionPrompt)
  const gameState = useGameStore((s) => s.gameState)

  // Spectators never answer prompts (GameView already hides the rail for them);
  // the replay viewer is gated in App.tsx (view !== 'replay').
  if (!actionPrompt || !gameState || gameState.isSpectator) return null
  if (ownedByCardSelect(actionPrompt)) return null
  // The pass-priority wait ("Nothing to play — Pass Priority" / a
  // card-click-only select) opens no window — the ding + screen flash carry the
  // notification, the rail carries Pass Priority.
  if (isEmptySelectWindow(actionPrompt)) return null

  return (
    <DecisionWindow
      // Keyed on the prompt TYPE: a same-type re-prompt (the play_mana loop
      // re-sends per tapped land, with the cost text shrinking) keeps THIS
      // window mounted — its Auto-Pay Pool / First Mana Ability toggles and
      // drag position survive the whole payment, exactly as the old
      // cursor-anchored popup did. A switch to another prompt kind remounts
      // and recentres.
      key={actionPrompt.type}
      prompt={actionPrompt}
      priorityPlayer={gameState.priorityPlayer}
    />
  )
}

/** The window itself — keyed per prompt type, so each kind recentres and resets. */
function DecisionWindow({
  prompt,
  priorityPlayer,
}: {
  prompt: ActionPrompt
  priorityPlayer: string
}) {
  const windowRef = useRef<HTMLDivElement | null>(null)
  // Drag offset in ROOT CSS px, from the base position. Reset (by remount) when
  // a different prompt kind arrives — unless "remember prompt position" is on,
  // in which case the LAST dragged offset for ANY prompt kind is restored ("the
  // popup window should remember where you last put it"). The remembered
  // offset is relative to the same base the drag started from — centre for
  // normal prompts, the mana-pool anchor for play_mana — so it transports
  // sensibly between the two anchoring modes.
  const rememberPosition = useGameStore((s) => s.priorityDing.rememberPromptPosition)
  const [dragOffset, setDragOffset] = useState(() => loadRememberedOffset())
  const [dragging, setDragging] = useState(false)
  // Measured after mount — render is positioned on the ASSUMED size, then
  // corrected once, so a tall choice list is centred on its real height.
  // Re-measured when the message changes (a re-prompt's text can grow/shrink
  // the window).
  const [size, setSize] = useState<{ w: number; h: number } | null>(null)

  /** Read the remembered offset (root CSS px) — {0,0} when off/absent/garbled. */
  function loadRememberedOffset(): { x: number; y: number } {
    if (!rememberPosition) return { x: 0, y: 0 }
    try {
      const raw = localStorage.getItem(PROMPT_POSITION_KEY)
      if (raw != null) {
        const parsed = JSON.parse(raw) as { x?: number; y?: number }
        if (typeof parsed.x === 'number' && typeof parsed.y === 'number') {
          return { x: parsed.x, y: parsed.y }
        }
      }
    } catch { /* fall through */ }
    return { x: 0, y: 0 }
  }

  /** Persist the current offset when memory is on (called at drag end). */
  function saveRememberedOffset(offset: { x: number; y: number }): void {
    if (!rememberPosition) return
    try {
      localStorage.setItem(PROMPT_POSITION_KEY, JSON.stringify(offset))
    } catch { /* localStorage unavailable (test harness) */ }
  }

  useEffect(() => {
    const el = windowRef.current
    if (!el) return
    const z = rootZoom()
    const rect = el.getBoundingClientRect()
    setSize({ w: rect.width / z, h: rect.height / z })
  }, [prompt.message, prompt.type])

  // Viewport shrunk while open: drop the offset so the window recentres rather
  // than sitting clamped off-centre (same recover-from-resize behaviour the
  // float windows get from useFloatWindow). The REMEMBERED offset (if
  // remember-position is on) is untouched — it comes back on the next prompt.
  useEffect(() => {
    const onWindowResize = () => setDragOffset({ x: 0, y: 0 })
    window.addEventListener('resize', onWindowResize)
    return () => window.removeEventListener('resize', onWindowResize)
  }, [])

  // Latest offset for the drag handlers without re-binding the listeners.
  const dragOffsetRef = useRef(dragOffset)
  dragOffsetRef.current = dragOffset

  const onDragStart = (e: React.MouseEvent) => {
    e.preventDefault()
    const startClient = { x: e.clientX, y: e.clientY }
    const startOffset = { ...dragOffsetRef.current }
    const zoom = rootZoom()
    setDragging(true)
    const move = (ev: MouseEvent) => {
      setDragOffset({
        x: startOffset.x + (ev.clientX - startClient.x) / zoom,
        y: startOffset.y + (ev.clientY - startClient.y) / zoom,
      })
    }
    const up = () => {
      setDragging(false)
      // Remember-position: persist the final offset (no-op when off) and tell
      // other surfaces (none today, but the event keeps the protocol honest).
      saveRememberedOffset(dragOffsetRef.current)
      window.dispatchEvent(new Event(PROMPT_POSITION_EVENT))
      document.removeEventListener('mousemove', move)
      document.removeEventListener('mouseup', up)
    }
    document.addEventListener('mousemove', move)
    document.addEventListener('mouseup', up)
  }

  // Base position. play_mana anchors at the BOTTOM-RIGHT viewport corner —
  // the window's bottom-right sits at (vw−MARGIN, vh−MARGIN) (the
  // mana auto-pay window "can just be bottom right"). A choose_ability prompt that
  // follows a CARD CLICK anchors above/below that card (the ability menu
  // belongs next to the card; above for cards on the lower half, below for
  // the upper half). Every other prompt kind centres. All paths clamp so at
  // least a header-sized slice always stays reachable on a tiny viewport.
  const MARGIN = 12
  const vw = cssViewportWidth()
  const vh = cssViewportHeight()
  const w = size?.w ?? 360
  const h = size?.h ?? 200
  const manaAnchor = prompt.type === 'play_mana' ? findManaAnchor() : null
  const cardAnchor = prompt.type === 'choose_ability' ? findCardAnchor() : null
  const anchor = manaAnchor ?? cardAnchor
  const baseX = anchor
    ? Math.max(MARGIN, Math.min(anchor.x - w / 2, vw - w - MARGIN))
    : Math.max(MARGIN, Math.min((vw - w) / 2, vw - w - MARGIN))
  let rawY: number
  if (manaAnchor) {
    // Anchor point is the corner: pull the window fully inside it.
    rawY = manaAnchor.y - h - MARGIN
  } else if (cardAnchor) {
    // 'below' anchors the window's TOP at the card's bottom (+gap); 'above'
    // anchors the window's BOTTOM at the card's top (−gap −height).
    rawY = cardAnchor.placement === 'below' ? cardAnchor.y + 12 : cardAnchor.y - h - 12
    // Not enough room on the chosen side (card tight against its edge): flip.
    if (rawY < MARGIN || rawY + h > vh - MARGIN) rawY = cardAnchor.placement === 'below' ? cardAnchor.y - h - 12 : cardAnchor.y + 12
  } else {
    // Centre vertically, then lift the window so its BOTTOM sits just above the
    // mid-board phase strip (bug-hunt #3: the window's lower edge hid the phase
    // chips — "…rd Step, Cleanup"). The strip lives at the vertical middle
    // between the two boards, so a centre-placed window's lower half covers it;
    // placing the bottom at the midline (minus a small gap) keeps every chip
    // readable at any window height. A tall prompt is clamped to the top margin.
    const centreY = (vh - h) / 2
    const stripClearY = vh / 2 - h - 8
    rawY = Math.min(centreY, stripClearY)
  }
  const baseY = Math.max(MARGIN, Math.min(rawY, vh - h - MARGIN))

  // Keep the dragged window on screen: bound the offset so part of the window
  // (at least the header) remains inside the viewport.
  const MARGIN_DRAG = 48
  const offX = Math.max(MARGIN_DRAG - w - baseX, Math.min(vw - MARGIN_DRAG - baseX, dragOffset.x))
  const offY = Math.max(MARGIN_DRAG - h - baseY, Math.min(vh - MARGIN_DRAG - baseY, dragOffset.y))

  // Compact rendering for the yes/no family — a mulligan question must not
  // obscure the whole board.
  const compact = prompt.type === 'boolean'

  return (
    <div
      ref={windowRef}
      className={`decision-popup${compact ? ' is-compact' : ''}`}
      data-prompt-type={prompt.type}
      data-dragging={dragging ? 'true' : 'false'}
      style={{
        position: 'fixed',
        left: baseX + offX,
        top: baseY + offY,
        zIndex: 700,
      }}
      role="dialog"
      aria-label={windowTitle(prompt)}
    >
      <div className="decision-popup-header" onMouseDown={onDragStart}>
        <span className="decision-popup-kind">{windowTitle(prompt)}</span>
        <span className="decision-popup-status">
          {priorityPlayer === '' ? 'waiting' : `Priority: ${priorityPlayer}`}
        </span>
      </div>

      <div className="decision-popup-body">
        <div className="decision-popup-msg">{prompt.message}</div>
        <PromptBody prompt={prompt} />
      </div>

      <div className="decision-popup-footer">
        Pass Priority, skips and Hold/Rollback stay in the right rail · F2 answers Yes / first choice
      </div>
    </div>
  )
}

/** Dispatch to the per-type body — the SAME components the rail used. */
function PromptBody({ prompt }: { prompt: ActionPrompt }) {
  switch (prompt.type) {
    case 'boolean':
      return <BooleanPrompt prompt={prompt} />
    case 'select':
      return <SelectPrompt prompt={prompt} />
    case 'target':
      return <TargetPrompt prompt={prompt} />
    case 'choose_choice':
      return <ChooseChoicePrompt prompt={prompt} />
    case 'choose_ability':
      return <ChooseAbilityBody prompt={prompt} />
    case 'amount':
      return <AmountBody prompt={prompt} />
    case 'play_mana':
      return <PlayManaBody prompt={prompt} />
    case 'multi_amount':
      return <MultiAmountPrompt prompt={prompt} />
  }
}

/** choose_ability body — identical rendering to the pre-window popup. */
function ChooseAbilityBody({ prompt }: { prompt: ChooseAbilityPromptType }) {
  const selectChoice = useGameStore((s) => s.selectChoice)
  const booleanChoice = useGameStore((s) => s.booleanChoice)
  const hideActionPrompt = useGameStore((s) => s.hideActionPrompt)
  // Cancel = booleanChoice(false) — the desktop client's AbilityPicker cancels
  // the same way (popupMenuCanceled → sendPlayerBoolean(gameId, false)), and
  // the engine treats a non-UUID answer as "activate nothing" (the waiting
  // HumanPlayer reads only response.getUUID; null → no ability activated).
  const handleCancel = () => {
    hideActionPrompt()
    booleanChoice(false)
  }
  return (
    <div className="prompt-choices">
      {prompt.choices.map((choice, idx) => (
        <button
          key={idx}
          className="btn-prompt"
          onClick={() => selectChoice(choice.index)}
        >
          <div>{choice.name}</div>
          <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
            <ManaText text={choice.abilityText} />
          </div>
        </button>
      ))}
      <button
        className="btn btn-secondary btn-sm"
        onClick={handleCancel}
        title="Cancel this activation — nothing is played"
      >
        Cancel
      </button>
    </div>
  )
}

/** play_mana body — the LIVE mana-payment UI (DecisionPopup owns play_mana). */
function PlayManaBody({ prompt }: { prompt: PlayManaPromptType }) {
  const booleanChoice = useGameStore((s) => s.booleanChoice)
  const toggleManaAutoPay = useGameStore((s) => s.toggleManaAutoPay)
  const toggleUseFirstMana = useGameStore((s) => s.toggleUseFirstMana)
  const specialMana = useGameStore((s) => s.specialMana)
  const [autoPayEnabled, setAutoPayEnabled] = useState(true)
  const [useFirstMana, setUseFirstMana] = useState(false)
  // The run's own state — see store/slices/autoTapRun.ts. `notice` is the honest reason a press
  // could not finish the cost; `active` means a press is still driving the sequence.
  const run = useSyncExternalStore(subscribeAutoTapRun, getAutoTapRun, getAutoTapRun)

  // ONE press of "Auto-Tap Lands" pays as much of the cost as the available sources allow.
  // The press taps the first land and opens a RUN; the store's prompt hook continues it on every
  // `play_mana` re-prompt the server sends after each tap_mana (one tap per prompt — the engine
  // consumes exactly one answer, so a burst would land on later prompts as misdelivered answers).
  //
  // Invariant (2026-09-12, 2026-09-13, 2026-09-14, 2026-09-16): only a land KNOWN
  // to produce a needed colour is ever tapped, the same land
  // is never tapped twice inside one press, and `booleanChoice(...)` is NEVER sent from here —
  // during GAME_PLAY_MANA that CANCELS the payment, the server re-offers the identical cast, and
  // the client loops forever (six full cycles captured live). Cancelling is the explicit
  // Cancel Spell button; a run that cannot continue stops and says why.
  const autoPayMana = () => startAutoTapAllLands(useGameStore.getState())

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
          onClick={autoPayMana}
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
          title="Tap the land's FIRST mana ability. On a dual land this is how a colour is chosen — the server activates the ability in list order."
        >
          {useFirstMana ? '✓ First Mana Ability' : 'First Mana Ability'}
        </button>
        {prompt.specialAvailable && (
          <button className="btn btn-secondary btn-sm" onClick={() => specialMana()}>
            Special Mana
          </button>
        )}
        {/* The only path that cancels the pending cast/payment. Explicit user action —
            G4: the old "Done Paying" button sent booleanChoice(true), which cancels
            EXACTLY like false during GAME_PLAY_MANA (WebProxySession.handleBooleanChoice:
            "During GAME_PLAY_MANA, sendPlayerBoolean cancels the payment regardless of
            true/false"), so it was a second mislabelled cancel. It is removed: the
            server dismisses this prompt itself as soon as the cost is paid. */}
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

/** amount body — identical rendering to the pre-window popup. */
function AmountBody({ prompt }: { prompt: AmountPromptType }) {
  const amountChoice = useGameStore((s) => s.amountChoice)
  const booleanChoice = useGameStore((s) => s.booleanChoice)
  const hideActionPrompt = useGameStore((s) => s.hideActionPrompt)
  const [value, setValue] = useState(prompt.defaultValue)

  const handleCancel = () => {
    hideActionPrompt()
    booleanChoice(false)
  }

  return (
    <div className="prompt-amount">
      <div className="prompt-amount-row">
        <input
          type="number"
          value={value}
          min={prompt.min}
          max={prompt.max}
          step={1}
          className="prompt-amount-input"
          autoFocus
          onChange={(e) => setValue(parseInt(e.target.value) || 0)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') amountChoice(value)
          }}
        />
        <button
          className="btn btn-primary btn-sm"
          onClick={() => amountChoice(value)}
        >
          Submit
        </button>
        <button
          className="btn btn-secondary btn-sm"
          onClick={handleCancel}
          title="Cancel"
        >
          Cancel
        </button>
      </div>
      <div className="prompt-amount-hint">
        Range: {prompt.min}–{prompt.max}
      </div>
    </div>
  )
}

/** Re-export for type narrowing consumers. */
export type { ActionPrompt }
