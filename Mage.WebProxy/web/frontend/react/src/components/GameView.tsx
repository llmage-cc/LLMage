import { useMemo, useCallback, useEffect, useRef, useSyncExternalStore } from 'react'
import { useGameStore } from '@/store/gameStore'
import { selectMyUntappedLands } from '@/store/selectors'
import { GameHeader } from './GameHeader'
import { GameLayout } from './GameLayout'
import { PlayerBoard } from './PlayerBoard'
import { HandSwitch } from './HandSwitch'
import {
  getHandSwitchKey,
  handView,
  subscribeHandSwitch,
} from '@/store/slices/handSwitch'
import { RightRail } from './RightRail'
import { ActionPromptBar } from './ActionPromptBar'
import { GameEndDialog } from './GameEndDialog'
import { ZonePopup } from './ZonePopup'
import { RevealedPopup } from './RevealedPopup'
import { WatchedHandsView } from './WatchedHandsView'
import { CardContextMenu } from './CardContextMenu'
import { PhaseContextMenu } from './PhaseContextMenu'
import { PlayerContextMenu } from './PlayerContextMenu'
import { TokenArtMenu } from './TokenArtMenu'
import { useContextMenu } from '@/hooks/useContextMenu'
import { useEventAnimation } from '@/hooks/useEventAnimation'
import { planCardClick } from '@/utils/combatClick'
import type { Card, Player } from '@/types'

/**
 * Game view — the main game board.
 * Ported from index.html game-container + app.js handleGameState rendering.
 *
 * The LAYOUT (3-column grid: left rail = stack, center = player boards, right
 * rail = log/chat/LLM, plus the 1v1 battlefield split, rail drag and phase
 * strip) is owned by the SHARED `GameLayout`, which the replay viewer mounts
 * too — see GameLayout's header for why the shell stopped being per-view.
 *
 * This file owns what is live-game-specific: the header's undo/concede/stop-
 * watching, the action-prompt → board wiring (playable / combat / target /
 * mana-tappable sets), the click dispatch, the revealed auto-open, and the
 * live-only context menus and dialogs.
 */
export default function GameView() {
  const gameState = useGameStore((s) => s.gameState)
  const concede = useGameStore((s) => s.concede)
  const undo = useGameStore((s) => s.undo)
  const stopWatching = useGameStore((s) => s.stopWatching)
  const useMana = useGameStore((s) => s.useMana)
  const actionPrompt = useGameStore((s) => s.actionPrompt)
  const selectChoice = useGameStore((s) => s.selectChoice)
  const playCard = useGameStore((s) => s.playCard)
  const selectTarget = useGameStore((s) => s.selectTarget)
  const tapMana = useGameStore((s) => s.tapMana)
  const gameEnd = useGameStore((s) => s.gameEnd)
  const setZonePopup = useGameStore((s) => s.setZonePopup)
  const setRevealedPopup = useGameStore((s) => s.setRevealedPopup)
  // Player-header context menu opener (shared registry — see useContextMenu).
  const playerMenu = useContextMenu('player')

  // Event animation hook — triggers CSS keyframe animations on card elements.
  // Live-only: it reads `eventLog`, which only live `event` frames fill (a
  // replay emits none).
  useEventAnimation()

  // Handle revealed/lookedAt sections — auto-open popup for new groups.
  // Reset the shown-groups set when entering a new game (turn resets to 1).
  //
  // The reopen BUTTONS live in the shared GameLayout; only the live game
  // AUTO-OPENS. A replay steps frames on demand (1 s per tick), so a popup
  // per step would fight the transport controls.
  const shownRevealedGroups = useMemo(() => new Set<string>(), [])
  const prevTurnRef = useRef(gameState?.turn)
  useEffect(() => {
    if (!gameState) return
    // New game: clear the set so revealed cards from the previous game don't
    // suppress popups in the current game.
    if (prevTurnRef.current != null && gameState.turn < prevTurnRef.current) {
      shownRevealedGroups.clear()
    }
    prevTurnRef.current = gameState.turn

    const groups: { name: string; cards: Card[] }[] = []
    if (gameState.revealed) {
      for (const group of gameState.revealed) {
        if (group.cards && group.cards.length > 0) {
          groups.push({ name: group.name || 'Revealed Cards', cards: group.cards })
        }
      }
    }
    if (gameState.lookedAt) {
      for (const group of gameState.lookedAt) {
        if (group.cards && group.cards.length > 0) {
          groups.push({ name: group.name || 'Looked At Cards', cards: group.cards })
        }
      }
    }
    for (const group of groups) {
      const key = group.name + '::' + group.cards.length
      if (!shownRevealedGroups.has(key)) {
        shownRevealedGroups.add(key)
        setRevealedPopup({ name: group.name, cards: group.cards })
      }
    }
  }, [gameState, shownRevealedGroups, setRevealedPopup])

  // Sort players: opponents first (top), current player last (bottom)
  const sortedPlayers = useMemo(() => {
    if (!gameState) return []
    return [...gameState.players].sort((a, b) => {
      if (a.isMe && !b.isMe) return 1
      if (!a.isMe && b.isMe) return -1
      return 0
    })
  }, [gameState])

  // Detect Commander game type
  const isCommander = useMemo(() => {
    if (!gameState) return false
    return gameState.players.some((p) => p.commanders.length > 0)
  }, [gameState])

  // Compute playable/combat/target/mana-tappable card ID sets from action prompt
  const { playableCardIds, combatChoiceIds, targetIds, manaTappableIds, combatDeclaredIds } = useMemo(() => {
    const playable = new Set<string>()
    const combat = new Set<string>()
    const targets = new Set<string>()
    const manaTappable = new Set<string>()
    const declared = new Set<string>()

    if (actionPrompt) {
      if (actionPrompt.type === 'select') {
        for (const choice of actionPrompt.choices) {
          if (choice.id) {
            playable.add(choice.id)
            if (choice.action === 'attack' || choice.action === 'block') {
              combat.add(choice.id)
            }
          }
        }
      } else if (actionPrompt.type === 'target') {
        for (const target of actionPrompt.targets) {
          if (target.id) targets.add(target.id)
        }
      } else if (actionPrompt.type === 'play_mana') {
        // Highlight MY untapped lands as tappable — same set the mana prompt's
        // tap buttons use (selectMyUntappedLands), so what glows is exactly
        // what is legal to tap.
        if (gameState) {
          for (const card of selectMyUntappedLands(useGameStore.getState())) {
            if (card.id) manaTappable.add(card.id)
          }
        }
      }
    }

    // Combat UNDECLARE set — creatures I have ALREADY declared this combat and
    // can take back by clicking them again ("undeclaring blockers is a
    // huge pain and really doesn't work correctly").
    //
    // How undeclare works: HumanPlayer.selectAttackers/selectBlockers consume
    // ONE response UUID per loop iteration and re-fire the GAME_SELECT prompt
    // after each declaration. A responded creature that is now ATTACKING falls
    // through FilterAttackingCreature → removeAttackerIfPossible; a creature
    // now BLOCKING falls through FilterBlockingCreature → game.getCombat()
    //   .removeBlocker — the desktop client undeclares exactly this way
    // (DefaultActionCallback forwards ANY clicked UUID; the server interprets
    // "already declared" as "take it back").
    //
    // The creatures are NOT in the prompt's choices: the engine's
    // possibleAttackers/possibleBlockers lists exclude declared ones
    // (FilterCreatureForCombat has not(attacking); FilterCreatureForCombatBlock's
    // CanBlockPredicate drops a blocker whose blocking count hit maxBlocks —
    // maxBlocks.is 1 for ordinary creatures). The web client saw a dead click.
    //
    // The current declared state arrives with every state push in
    // gameState.combat (CombatGroupView attackers/blockers — each
    // fireSelectEvent is accompanied by a fresh game_update), so the sets below
    // are exact, filtered by which role the CURRENT prompt owns:
    //   * declare_attackers → groups' ATTACKER ids (my creatures),
    //   * declare_blockers → groups' BLOCKER ids (my creatures).
    // Opponent creatures are deliberately not included: they are committed and
    // not ours to undeclare.
    if (gameState && actionPrompt?.type === 'select') {
      const mine = actionPrompt.context === 'declare_attackers'
        ? 'attackers'
        : actionPrompt.context === 'declare_blockers'
          ? 'blockers'
          : null
      if (mine) {
        for (const group of gameState.combat) {
          for (const entry of group[mine]) {
            if (entry.id) declared.add(entry.id)
          }
        }
      }
    }

    return { playableCardIds: playable, combatChoiceIds: combat, targetIds: targets, manaTappableIds: manaTappable, combatDeclaredIds: declared }
  }, [actionPrompt, gameState])

  // Card click handler — dispatched based on current action prompt type.
  //
  // Every branch goes through answerKeys.* (the store's epoch guard) rather than
  // the raw action: a double-click on a playable hand card used to send
  // `select_choice` twice, and the second one hit the proxy after the choice had
  // been consumed, surfacing a red "Invalid choice index: N" toast for a normal
  // action.
  // The guard is per (prompt epoch, answer key), so repeat activations of the
  // SAME card are dropped until the server moves the prompt on, while a
  // different card (or the same card on the next prompt) still goes out.
  const handleCardClick = (cardId: string | null) => {
    if (!cardId || !actionPrompt) return

    if (actionPrompt.type === 'select') {
      // Routing lives in utils/combatClick.planCardClick:
      //   * COMBAT choices (action attack/block) answer by creature UUID via
      //     play_card — never by list index. The engine renumbers the choice
      //     list after every declare (the declared creature drops out), and the
      //     browser can be one render behind, so an index computed from an
      //     older prompt resolves to a DIFFERENT creature or falls outside the
      // shrunken list. Measured live on a token board:
      //     - 30 ms between two clicks → clicked 48154915…, engine declared
      //       01e9234a… (different creature);
      //     - 15 ms after a prompt bump → select_choice:2/3 sent while the live
      //       list held those creatures at 0/1 → "Invalid choice index" toasts;
      //     - 0 ms burst → 2nd select_choice hit the proxy after the answer was
      //       consumed → silent stale-drop.
      //     The engine consumes a responded UUID by IDENTITY (selectDefender /
      //     removeAttackerIfPossible; selectCombatGroup / removeBlocker), so a
      //     renumbered list cannot misdeliver it — the desktop client answers
      //     combat exactly this way (DefaultActionCallback).
      //   * non-combat choices → index (unchanged);
      //   * a declared combat creature → UUID (undeclare, as before);
      //   * a non-combat priority prompt → UUID play attempt (as before).
      const plan = planCardClick(actionPrompt, cardId, combatDeclaredIds)
      if (!plan) return
      if (plan.kind === 'uuid') {
        playCard(plan.cardId)
      } else {
        selectChoice(plan.index)
      }
    } else if (actionPrompt.type === 'target') {
      // Check if the card is a valid target
      const target = actionPrompt.targets.find((t) => t.id === cardId)
      if (target) {
        selectTarget(target.id)
      }
    } else if (actionPrompt.type === 'play_mana') {
      // Tap mana — only for untapped lands (the CardThumb won't show as tapped)
      tapMana(cardId)
    }
  }

  // Graveyard/exile click handler — opens zone popup
  const handleZoneClick = useCallback((zone: string, playerName: string, cards: Card[]) => {
    setZonePopup({ zone, playerName, cards })
  }, [setZonePopup])

  // Player-header right-click: open the shared player context menu for that
  // seat. The LLM sub-tab index is resolved HERE (name → llmDebug index) so
  // the menu only offers "View LLM reasoning" for seats that have an entry.
  const openPlayerMenu = useCallback((player: Player) => (e: React.MouseEvent) => {
    const llmIdx = useGameStore.getState().gameState?.llmDebug.findIndex(
      (l) => l.playerName === player.name,
    )
    playerMenu.openAt(e, {
      playerName: player.name,
      llmPlayerIdx: llmIdx != null && llmIdx >= 0 ? llmIdx : undefined,
    })
  }, [playerMenu])

  // ── Hand switch ──
  // Every hand this viewer may look at (own + controlled + watched) and the
  // EFFECTIVE selection, derived per render — never stored — so a hand that
  // disappeared (control ended, permission revoked) silently collapses the view
  // back to your own hand, exactly like GamePanel's chosenHandKey fallback.
  const requestedHandKey = useSyncExternalStore(subscribeHandSwitch, getHandSwitchKey, getHandSwitchKey)
  const hand = useMemo(() => handView(gameState, requestedHandKey), [gameState, requestedHandKey])

  // One seat's board — the ONLY thing the live game hands the shared shell that
  // the replay viewer does not: playable/target/combat sets, the click
  // dispatch, and the player context menu. The shell supplies `isTopPlayer`
  // (1v1: the top pane's seat; multiplayer: never).
  const renderBoard = useCallback((player: Player, isTopPlayer: boolean) => {
    // Only the seat the control governs renders a foreign hand (and, below,
    // carries the switch control). Every other board — opponents, watchers —
    // renders exactly what it did before.
    const isSwitchOwner = hand.visible && hand.ownerName === player.name
    return (
      <PlayerBoard
        player={player}
        isTopPlayer={isTopPlayer}
        isCommander={isCommander}
        isActiveTurn={player.name === gameState?.activePlayer}
        hasPriority={player.name === gameState?.priorityPlayer}
        playableCardIds={playableCardIds}
        combatChoiceIds={combatChoiceIds}
        combatDeclaredIds={combatDeclaredIds}
        targetIds={targetIds}
        manaTappableIds={manaTappableIds}
        onCardClick={handleCardClick}
        onManaClick={(color, playerId) => useMana(color, playerId)}
        onPlayerContextMenu={openPlayerMenu(player)}
        displayedHand={isSwitchOwner ? hand.foreignHand ?? undefined : undefined}
        handIsForeign={isSwitchOwner && hand.foreignHand != null}
        handSwitchControl={isSwitchOwner
          ? <HandSwitch options={hand.options} currentKey={hand.key ?? ''} />
          : undefined}
        onZoneClick={(zone) => {
          const cards = zone === 'graveyard' ? player.graveyard
            : zone === 'exile' ? player.exile
            : zone === 'sideboard' ? (player.sideboard ?? [])
            : []
          handleZoneClick(zone, player.name, cards)
        }}
      />
    )
  }, [isCommander, gameState, playableCardIds, combatChoiceIds, combatDeclaredIds, targetIds, manaTappableIds, handleCardClick, useMana, handleZoneClick, openPlayerMenu, hand])

  if (!gameState) {
    return <div style={{ padding: '20px' }}>Loading game...</div>
  }

  return (
    <div className="game-container">
      <GameHeader
        gameState={gameState}
        // Neutral branding in stock builds.
        gameTitle={__LLMAGE__ ? 'LLMage Game' : 'XMage Game'}
        onConcede={() => concede()}
        onUndo={() => undo()}
        onStopWatching={() => stopWatching()}
      />

      <GameLayout
        state={gameState}
        players={sortedPlayers}
        renderBoard={renderBoard}
        rightRail={
          <>
            <RightRail />
            {!gameState.isSpectator && <ActionPromptBar />}
          </>
        }
      />

      {/* Floating popups */}
      <ZonePopup />
      <RevealedPopup />
      {/* Hands granted to this viewer — renders nothing without a
          grant, so an ungranted session sees no new chrome. */}
      <WatchedHandsView />

      {/* Right-click context menus (shared registry — one open at a time) */}
      <CardContextMenu />
      <TokenArtMenu />
      <PhaseContextMenu />
      <PlayerContextMenu />

      {/* Game end dialog */}
      {gameEnd && <GameEndDialog result={gameEnd.result} />}
    </div>
  )
}
