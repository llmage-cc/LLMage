import { useState, useEffect, useCallback, useSyncExternalStore } from 'react'
import { useGameStore } from '@/store/gameStore'
import type { ActionPrompt } from '@/types'
import { armedSkipButton, skipClickIntent, SKIP_WIRE_TYPE, type SkipButtonId } from '@/utils/skipButtons'
import { actionPromptClass } from '@/utils/phaseTint'
import {
  formatKeySpec,
  isCaptureActive,
  isTypingTarget,
  keyFor,
  matchHotkeyAction,
  keybindingsRevision,
  subscribeKeybindings,
} from '@/utils/keybindings'
import { isCombatChoice } from '@/utils/combatClick'

/**
 * Action prompt bar — ported from index.html action-prompt div.
 * Right-rail context + meta controls. Since the centred DecisionPopup window
 * took over prompt rendering, the per-type prompt BODIES no
 * longer render here: this bar keeps the message line (always present),
 * Pass Priority, the skip buttons, Hold Priority / Rollback / Undo / Concede and
 * the F2–F11 keyboard shortcuts, which operate on the store and are unaffected
 * by where the prompt body is shown.
 *
 * The block is tinted by the CURRENT PHASE (Java HelperPanel colours its
 * feedback panel the same way — blue main / red combat / green other). Prompts
 * carry no phase of their own, so the tint follows gameState.phase; with no
 * state or an unknown phase it stays on the subtle 'other' tint. See
 * utils/phaseTint.ts.
 */
export function ActionPromptBar() {
  const actionPrompt = useGameStore((s) => s.actionPrompt)
  const gameState = useGameStore((s) => s.gameState)
  const passPriority = useGameStore((s) => s.passPriority)
  const passPending = useGameStore((s) => s.passPending)
  const undo = useGameStore((s) => s.undo)
  const concede = useGameStore((s) => s.concede)
  const concedeMatch = useGameStore((s) => s.concedeMatch)
  const skipPriority = useGameStore((s) => s.skipPriority)
  const booleanChoice = useGameStore((s) => s.booleanChoice)
  const selectChoice = useGameStore((s) => s.selectChoice)
  const playCard = useGameStore((s) => s.playCard)
  const holdPriority = useGameStore((s) => s.holdPriority)
  const rollbackTurns = useGameStore((s) => s.rollbackTurns)
  // Optimistic echo of a skip click, valid only until the next state push.
  // The engine sets the flag when the action packet lands
  // (PlayerImpl.sendPlayerAction), so the highlight appears immediately; the
  // SERVER's flag (armedFromServer below) takes over as soon as state arrives,
  // and wins if the two ever disagree. Keyed on stateSeq so it cannot outlive
  // the state it was clicked against — deliberately NOT a timer.
  const [skipEcho, setSkipEcho] = useState<{ id: SkipButtonId; seq: number | null } | null>(null)
  const [showConcedeConfirm, setShowConcedeConfirm] = useState(false)
  const [holdingPriority, setHoldingPriority] = useState(false)

  // ── Skip state: SERVER-owned ──
  // The engine knows whether a skip is armed (it clears the flag the moment the
  // skip fires), and that flag now reaches us on every state push
  // (GameStateSerializer → Player.passed*). Derive the highlight from it — the
  // same source the desktop client uses for its skip buttons
  // (GamePanel.updatePressState). `skipEcho` only bridges the gap between the
  // click and the first state push carrying the flag, so the button lights up
  // instantly; it is dropped the moment state moves on and never overrides the
  // server.
  const stateSeq = gameState?.stateSeq ?? null
  const me = gameState?.players.find((p) => p.isMe) ?? null
  const armedFromServer = armedSkipButton(me)
  const optimistic = skipEcho != null && skipEcho.seq === stateSeq ? skipEcho.id : null
  const activeSkip: SkipButtonId | null = armedFromServer ?? optimistic
  const isSpectator = gameState?.isSpectator === true
  const isMyPriority = me != null && gameState != null && gameState.priorityPlayer === me.name

  // Live key bindings (utils/keybindings.ts): the skip buttons' titles must
  // show the key that is actually bound, not the shipped default — a user who
  // rebinds Skip EOT to 'E' must see "E" on the button. `useSyncExternalStore`
  // is the same subscription pattern SettingsDialog uses for token art; the
  // 3-arg form is required because the harnesses render with react-dom/server.
  const keybindingsRev = useSyncExternalStore(subscribeKeybindings, keybindingsRevision, keybindingsRevision)
  void keybindingsRev // the revision is the trigger; these reads are cheap and current
  const titleFor = (id: Parameters<typeof keyFor>[0]): string => formatKeySpec(keyFor(id))

  // ── Undo availability (F4) ──
  // Same rule the stock Swing client applies in GamePanel.select() (2139) —
  // which is the only path that ever ENABLES the button (every other update
  // calls feedbackPanel.disableUndo()):
  //   * the local player has a stored undo bookmark (statesSavedSize > 0;
  //     PlayerView.getStatesSavedSize = Player.getStoredBookmark, and
  //     GameImpl.undo() no-ops when it is -1), and
  //   * the stack is empty ("undo is allowed only for empty stack" — save-state
  //     bug workaround, GamePanel.java:2137).
  // Both conditions are read from live state, so the button is only offered when
  // the server would actually honour it. GameHeader gates its own Undo button
  // identically; this rail previously offered Undo unconditionally.
  const canUndo =
    !isSpectator &&
    gameState != null &&
    gameState.stack.length === 0 &&
    (me?.statesSavedSize ?? 0) > 0

  const toggleSkip = useCallback((btnId: SkipButtonId) => {
    // Read LIVE state (not the render closure): the keyboard shortcut handler is
    // bound once and would otherwise decide on stale values.
    const live = useGameStore.getState()
    const livePlayer = live.gameState?.players.find((p) => p.isMe) ?? null
    // The click's meaning comes from the SERVER's flag, not local state: if the
    // server no longer reports this skip as armed, the skip it belonged to has
    // already fired and the click must ARM a fresh skip instead of sending the
    // no-op cancel_skip that was reported.
    if (skipClickIntent(armedSkipButton(livePlayer), btnId) === 'cancel') {
      live.cancelSkip()
      setSkipEcho(null)
    } else {
      live.skipPriority(SKIP_WIRE_TYPE[btnId])
      setSkipEcho({ id: btnId, seq: live.gameState?.stateSeq ?? null })
    }
  }, [])

  const clearSkips = useCallback(() => {
    useGameStore.getState().cancelSkip()
    setSkipEcho(null)
  }, [])

  // Keyboard shortcuts — the bindings come from utils/keybindings.ts (the
  // rebindable registry), whose defaults match the Java
  // PreferencesDialog "Game - Hotkeys" tab and this rail's shipped keys:
  //   F2  = confirm (yes/first choice)
  //   F3  = cancel all active skip actions (NOT pass priority — matches Java)
  //   F4  = skip to next turn
  //   F5  = skip to end of turn
  //   F6  = skip to next turn, skip stack
  //   F7  = skip to next main phase
  //   F8  = (Java: toggle macro — N/A for web; no web action exists, so no row)
  //   F9  = skip to my next turn
  //   F10 = skip until stack resolved
  //   F11 = skip to end step before my turn
  //   F12 = (Java: switch chat — N/A for web single-chat; no web action exists)
  // F8/F12 stay unmapped because there is no web action to bind them to — the
  // registry only carries shortcuts that already exist (the keybinding rule). A user
  // may still rebind one of the actions above onto F8/F12.
  //
  // The skip keys go through the same toggleSkip() the buttons use, so a key
  // press on an already-armed skip cancels it and a press after the skip fired
  // re-arms — the Java client's skip buttons behave the same way (activate/cancel
  // are the same control; GamePanel.updatePressState mirrors the live flag).
  //
  // The action a keypress means is resolved by matchHotkeyAction(), which owns
  // the guards too (rebind capture armed / settings dialog open / replay view
  // up / typing target / open context menu — see keybindings.ts). Live state is
  // read from the store INSIDE the handler (not the render closure), which is
  // what keeps the skip re-arm semantics: the handler is bound once and
  // must decide on the values in force at the keypress.
  useEffect(() => {
    function handleKeydown(e: KeyboardEvent) {
      const live = useGameStore.getState()
      const ws = live.ws
      if (!ws) return
      const action = matchHotkeyAction(e, {
        capturing: isCaptureActive(),
        settingsOpen: live.showSettings,
        replayOpen: live.inReplay,
        typing: isTypingTarget(e.target as { tagName?: string; isContentEditable?: boolean } | null),
        contextMenuOpen: live.openContextMenu != null,
      }, 'game')
      if (action === null) return
      e.preventDefault()
      const prompt = live.actionPrompt
      switch (action) {
        case 'confirm':
          if (!prompt) return
          if (prompt.type === 'boolean') {
            booleanChoice(true)
          } else if (prompt.type === 'select' && prompt.choices.length > 0) {
            // Combat choices answer by creature UUID (the engine renumbers the
            // list after every declare; see utils/combatClick). Same routing as
            // a board click.
            const first = prompt.choices[0]!
            if (isCombatChoice(first)) playCard(first.id)
            else selectChoice(first.index)
          }
          break
        case 'cancel-skips':
          // Cancel all active skip actions (matches Java client)
          clearSkips()
          break
        case 'skip-turn':
          toggleSkip('skip-turn')
          break
        case 'skip-eot':
          toggleSkip('skip-eot')
          break
        case 'skip-turn-stack':
          // Skip to next turn, skip stack (PASS_PRIORITY_UNTIL_NEXT_TURN_SKIP_STACK)
          // Not a rail button — there is no armed-flag twin to toggle against
          // (the engine's passedTurnSkipStack is not part of the skip buttons),
          // so this stays a one-way arm.
          skipPriority('next_turn_skip_stack')
          break
        case 'skip-main':
          toggleSkip('skip-main')
          break
        case 'skip-my-turn':
          toggleSkip('skip-my-turn')
          break
        case 'skip-stack':
          // Skip until stack resolved (matches Java client)
          toggleSkip('skip-stack')
          break
        case 'skip-end-step':
          toggleSkip('skip-end-step')
          break
        default:
          // keybindings.ts holds ONLY the rail's game actions plus the fixed
          // replay rows, which matchHotkeyAction('game') can never return — a
          // new action id reaching here means the registry and this switch
          // drifted apart. Fail Fast (AGENTS.md) instead of a silent no-op.
          throw new Error(`ActionPromptBar: no handler for keybinding action '${action satisfies never}'`)
      }
    }
    document.addEventListener('keydown', handleKeydown)
    return () => document.removeEventListener('keydown', handleKeydown)
  }, [booleanChoice, selectChoice, toggleSkip, clearSkips, skipPriority])

  // ── The rail is ALWAYS rendered ──
  // It used to `return null` when no prompt was outstanding, which removed every
  // control — Pass Priority included — even while the header said it was this
  // player's priority (after a rejoin that produced a ~120 s stretch of an
  // unplayable-looking board with no feedback). Two states now:
  //   * `actionPrompt` present  → the prompt body + action buttons (as before);
  //   * no prompt, my priority  → Pass Priority (+ skips/undo/concede) so the
  //                               player can always act on a held priority;
  //   * no prompt, not my turn  → an explicit waiting indicator naming who the
  //                               server is waiting for (its absence is what
  //                               made a stall easy to misdiagnose).
  if (!actionPrompt && !gameState) return null // not in a game at all

  // Which "mode" the button block is in — drives the gating below. With no
  // prompt the player still holds priority, so the block is a plain priority
  // pass (no mana/ability dialog gating applies).
  const promptType = actionPrompt?.type ?? null
  const requiredTarget = actionPrompt?.type === 'target' && actionPrompt.required

  return (
    <div className={actionPromptClass(gameState?.phase)}>
      {actionPrompt ? (
        <div className="prompt-message">{actionPrompt.message}</div>
      ) : isMyPriority ? (
        <div className="prompt-message">Your priority</div>
      ) : (
        <div className="prompt-message prompt-waiting">
          Waiting for {gameState?.priorityPlayer || 'the other player'}…
        </div>
      )}

      {/* Per-type prompt BODIES moved to DecisionPopup (the centred window —
          item 2): every prompt type renders mid-screen now, so it
          cannot be missed in the rail. This bar keeps the context line above
          (always rendered) and the meta controls below — Pass
          Priority, skips, Hold/Rollback, Concede. Keyboard shortcuts are
          unaffected: they read the store, not this component's DOM. */}
      {/* Unused prompt-body imports removed; the bodies live in
          components/DecisionPopup.tsx (same sub-components, centred window). */}

      {/* Action buttons */}
      <div className="action-buttons">
        {/* Pass Priority — offered whenever passing is legal: on any non-mana,
            non-required-target prompt, and whenever the server says the
            priority is OURS even with no prompt rendered. Hidden during mana
            payment (must use Cancel Spell) and during required target prompts
            (can't pass — must choose a target), matching the Java client, which
            disables skip/pass during modal dialogs via isUserImputActive().
            Never offered when it is not our priority — the server would ignore
            it and the player would be clicking into the void.

            Once a pass is out, a second click in the same priority window
            is dropped by the prompt-lifecycle epoch guard, so the button used to
            look live while doing nothing (the trigger window sat that way for
            2 min 27 s while the engine was simply waiting to resolve the ability
            on the player's own pass). Show the truth instead of a dead control:
            the pass is sent, the server has not produced new state yet. */}
        {promptType !== 'play_mana' && !requiredTarget && (actionPrompt != null || isMyPriority) && (
          passPending && actionPrompt == null ? (
            <div className="prompt-hint" style={{ marginBottom: '0.4rem' }}>
              Pass sent — waiting for the server…
            </div>
          ) : (
            <button className="btn btn-primary" onClick={() => passPriority()} data-explain="pass-priority">Pass Priority</button>
          )
        )}
        {/* Undo — gated on the stock client's rule (empty stack + an actual
            stored bookmark); see canUndo above. */}
        {canUndo && (
          <button className="btn btn-secondary btn-sm" onClick={() => undo()} data-explain="undo-action">Undo</button>
        )}
        {/* Skip buttons — hidden during mana payment (can't skip) and dialogs that require a response */}
        {promptType !== 'play_mana' && promptType !== 'choose_ability' && (
          <>
            <div style={{ display: 'flex', gap: '0.3rem' }}>
              <button
                className={`skip-btn${activeSkip === 'skip-eot' ? ' skip-active' : ''}`}
                onClick={() => toggleSkip('skip-eot')}
                data-explain="skip-eot"
                style={{ flex: 1 }}
                title={titleFor('skip-eot')}
              >
                Skip EOT
              </button>
              <button
                className={`skip-btn${activeSkip === 'skip-main' ? ' skip-active' : ''}`}
                onClick={() => toggleSkip('skip-main')}
                data-explain="skip-main"
                style={{ flex: 1 }}
                title={titleFor('skip-main')}
              >
                Skip Main
              </button>
            </div>
            <div style={{ display: 'flex', gap: '0.3rem' }}>
              <button
                className={`skip-btn${activeSkip === 'skip-turn' ? ' skip-active' : ''}`}
                onClick={() => toggleSkip('skip-turn')}
                data-explain="skip-turn"
                style={{ flex: 1 }}
                title={titleFor('skip-turn')}
              >
                Skip Turn
              </button>
              <button
                className={`skip-btn${activeSkip === 'skip-my-turn' ? ' skip-active' : ''}`}
                onClick={() => toggleSkip('skip-my-turn')}
                data-explain="skip-my-turn"
                style={{ flex: 1 }}
                title={titleFor('skip-my-turn')}
              >
                Skip to My Turn
              </button>
            </div>
            <div style={{ display: 'flex', gap: '0.3rem' }}>
              <button
                className={`skip-btn${activeSkip === 'skip-stack' ? ' skip-active' : ''}`}
                onClick={() => toggleSkip('skip-stack')}
                data-explain="skip-stack"
                style={{ flex: 1 }}
                title={titleFor('skip-stack')}
              >
                Skip Stack
              </button>
              <button
                className={`skip-btn${activeSkip === 'skip-end-step' ? ' skip-active' : ''}`}
                onClick={() => toggleSkip('skip-end-step')}
                data-explain="skip-end-step"
                style={{ flex: 1 }}
                title={titleFor('skip-end-step')}
              >
                Skip End Step
              </button>
            </div>
            <button
              className="btn btn-secondary btn-sm"
              onClick={clearSkips}
              data-explain="cancel-skips"
              title={`${titleFor('cancel-skips')} — cancel all active skips`}
              style={{ width: '100%' }}
            >
              Cancel All Skips
            </button>
          </>
        )}
        {/* Hold Priority / Rollback — hidden during mana payment */}
        {promptType !== 'play_mana' && promptType !== 'choose_ability' && (
          <>
            <button
              className="btn btn-secondary btn-sm"
              onClick={() => {
                const newHold = !holdingPriority
                setHoldingPriority(newHold)
                holdPriority(newHold)
              }}
              data-explain="hold-priority"
              title="Hold Priority — don't auto-pass after casting/activating (Ctrl+click in Java client)"
              style={{ width: '100%' }}
            >
              {holdingPriority ? '✓ Holding Priority' : 'Hold Priority'}
            </button>
            <button
              className="btn btn-secondary btn-sm"
              onClick={() => rollbackTurns()}
              data-explain="rollback-turn"
              title="Rollback to start of current turn (if allowed by game settings)"
              style={{ width: '100%' }}
            >
              Rollback Turn
            </button>
          </>
        )}
        <button
          className="btn btn-danger btn-sm"
          onClick={() => setShowConcedeConfirm(true)}
          data-explain="concede"
        >
          Concede
        </button>
      </div>

      {/* Concede confirmation modal */}
      {showConcedeConfirm && (
        <div className="modal-overlay" style={{ display: 'flex' }}>
          <div className="modal-content">
            <div className="modal-title">Concede?</div>
            <div className="modal-body" style={{ color: 'var(--text-secondary)', fontSize: '0.9rem' }}>
              Choose how to concede:
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <button
                className="btn btn-danger btn-sm"
                onClick={() => {
                  setShowConcedeConfirm(false)
                  concede()
                }}
                data-explain="concede-game"
              >
                Concede Game
              </button>
              <button
                className="btn btn-danger btn-sm"
                onClick={() => {
                  setShowConcedeConfirm(false)
                  concedeMatch()
                }}
                data-explain="concede-match"
              >
                Concede Match
              </button>
              <button
                className="btn btn-secondary btn-sm"
                onClick={() => setShowConcedeConfirm(false)}
                data-explain="cancel-action"
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

/** Re-export for GameView to use */
export type { ActionPrompt }