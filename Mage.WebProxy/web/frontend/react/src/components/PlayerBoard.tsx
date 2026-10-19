import { memo } from 'react'
import { useGameStore } from '@/store/gameStore'
import type { Card, Player } from '@/types'
import { BattlefieldZone } from './BattlefieldZone'
import { CardThumb } from './CardThumb'
import { avatarIdForSeat, avatarUrlFor } from '@/utils/avatars'
import { counterTooltip, splitPlayerCounters } from '@/utils/playerCounters'

/**
 * Player board — ported from game-renderer.js renderPlayers (per-player section).
 * Renders the player header (name, life, mana pool, library/hand counts),
 * side zones (library, commander, graveyard, exile), and main zones (battlefield, hand).
 *
 * Props:
 * - player: The player data
 * - isTopPlayer: Whether this is the opponent (top of screen, zones reordered)
 * - isCommander: Whether this is a Commander game (show commander zone)
 * - isActiveTurn: Whether it's this player's turn (active border)
 * - hasPriority: Whether this player has priority (highlighted name)
 * - playableCardIds: Set of card IDs that can be played
 * - combatChoiceIds: Set of card IDs selectable for combat
 * - combatDeclaredIds: Set of card IDs ALREADY declared in this combat
 *   (click again to undeclare — see GameView.handleCardClick)
 * - targetIds: Set of card IDs that are valid targets
 * - onCardClick: Click handler for card selection
 * - onManaClick: Click handler for mana pool usage
 */
interface PlayerBoardProps {
  player: Player
  isTopPlayer: boolean
  isCommander: boolean
  isActiveTurn: boolean
  hasPriority: boolean
  playableCardIds?: Set<string> | undefined
  combatChoiceIds?: Set<string> | undefined
  combatDeclaredIds?: Set<string> | undefined
  targetIds?: Set<string> | undefined
  manaTappableIds?: Set<string> | undefined
  onCardClick?: ((cardId: string | null) => void) | undefined
  onManaClick?: ((color: string, playerId: string) => void) | undefined
  onZoneClick?: ((zone: string) => void) | undefined
  /** Right-click on the player header — opens the player context menu. */
  onPlayerContextMenu?: ((e: React.MouseEvent) => void) | undefined
  /**
   * Hand switch: the cards to render in MY hand area instead of `player.hand`
   * — a controlled player's hand or a hand this user was granted permission to
   * see. `undefined` keeps the normal single-hand rendering (GameView computes
   * the effective hand; see store/slices/handSwitch.ts).
   */
  displayedHand?: Card[] | undefined
  /** True when `displayedHand` is a FOREIGN hand (title shows its count). */
  handIsForeign?: boolean | undefined
  /** The switch control, rendered in MY hand section's title row. */
  handSwitchControl?: React.ReactNode
}

const MANA_COLORS = ['white', 'blue', 'black', 'red', 'green', 'colorless'] as const

function PlayerBoardInner({
  player,
  isTopPlayer,
  isCommander,
  isActiveTurn,
  hasPriority,
  playableCardIds,
  combatChoiceIds,
  combatDeclaredIds,
  targetIds,
  manaTappableIds,
  onCardClick,
  onManaClick,
  onZoneClick,
  onPlayerContextMenu,
  displayedHand,
  handIsForeign,
  handSwitchControl,
}: PlayerBoardProps) {
  /**
   * Who owns this battlefield, for token-art preferences (see utils/tokenArt.ts).
   * `player.isMe` comes from the serializer (`GameStateSerializer.serializePlayer`
   * sets it by comparing against the viewing player's id), so it is authoritative —
   * do not infer it from the seat order.
   */
  const owner = { ownerPlayerId: player.playerId ?? null, isMine: !!player.isMe }
  /**
   * My own chosen avatar — the fallback for MY seat when the payload carries
   * no `avatarId` (a replay saved before the field existed, or the first frames
   * after connect). Other seats without the field draw the default, which is the
   * desktop's own behavior for a seat it has no user data for.
   */
  const myAvatarId = useGameStore((s) => s.avatarId)
  const seatAvatarId = avatarIdForSeat(player.avatarId, !!player.isMe, myAvatarId)
  const playerClasses = [
    'player-card',
    isActiveTurn ? 'active-turn' : '',
    player.hasLeft ? 'eliminated' : '',
  ].filter(Boolean).join(' ')

  const counters = (player.counters || []).filter((c) => c.count > 0)
  // Split the dedicated desktop counters (poison/energy/experience/rad/
  // ticket — PlayerPanelExt.java:553-676) from the generic remainder, so the
  // five get their own labelled fields instead of hiding in one `name: count`
  // line. Pure split lives in utils/playerCounters.
  const { dedicated: dedicatedCounters, others: otherCounters } = splitPlayerCounters(player.counters)

  /**
   * Hand switch: the cards this seat's hand area actually renders.
   *
   * `displayedHand` is set ONLY for the seat whose hand the hand-switch control
   * governs (see GameView) — your own seat for a player, the first seat for a
   * spectator — and only while a FOREIGN hand is selected. Everything else keeps
   * the original `player.hand` rendering untouched.
   */
  const handCards = displayedHand ?? player.hand
  const handCount = handIsForeign ? handCards.length : player.handCount
  const handTitle = (
    <div className="zone-title">
      Hand ({handCount}){handIsForeign ? ' — controlled/visible hand' : ''}
    </div>
  )
  const handSection = (
    <div className="hand-section">
      {/* A switchable hand gets its title row wrapped so the control can sit at
          the right end of it; every other board keeps the exact previous DOM
          (the wrapper is added only when a control is passed). */}
      {handSwitchControl ? (
        <div className="hand-title-row">
          {handTitle}
          {handSwitchControl}
        </div>
      ) : (
        handTitle
      )}
      <div className="hand-cards-row">
        {handCards.map((card) => (
          <CardThumb
            key={card.id ?? card.name}
            card={card}
            className="card-thumb-sm"
            isPlayable={playableCardIds?.has(card.id ?? '')}
            onClick={onCardClick}
          />
        ))}
      </div>
    </div>
  )

  return (
    <article className={playerClasses}>
      {/* Header */}
      <div className="player-header" data-player-name={player.name} onContextMenu={onPlayerContextMenu}>
        {/* Seat avatar — desktop parity with PlayerPanelExt's avatar button:
            the player's own UserData avatar, rendered from /avatars/<id>.jpg.
            `avatarIdForSeat` guarantees a shipped image for every id, so no
            broken-image path exists (see utils/avatars.ts). */}
        <img
          className="player-avatar"
          src={avatarUrlFor(seatAvatarId)}
          alt=""
          width={32}
          height={32}
          data-avatar-id={seatAvatarId}
          title={`${player.name}'s avatar`}
        />
        <div
          className={`player-name ${hasPriority ? 'has-priority' : ''}`}
          title={onPlayerContextMenu ? 'Right-click for player menu' : undefined}
        >
          {player.name}
        </div>
        {player.isMonarch && (
          <span className="player-badge player-badge-monarch" title="Monarch">
            👑
          </span>
        )}
        {player.hasInitiative && (
          <span className="player-badge player-badge-initiative" title="Initiative">
            ⚔
          </span>
        )}
      </div>

      {/* Stats bar: life, mana pool, library, hand */}
      <div className="player-stats-bar">
        <div className="player-stats-life">{player.life}</div>

        {player.manaPool && (
          <div className="player-mana-pool">
            {MANA_COLORS.map((color) => {
              const count = player.manaPool![color] || 0
              return (
                <div
                  key={color}
                  className={`player-mana ${color}${count === 0 ? ' zero' : ''}`}
                  onClick={count > 0 && onManaClick
                    ? () => onManaClick(color, player.playerId || player.name)
                    : undefined
                  }
                  style={count > 0 && onManaClick ? { cursor: 'pointer' } : undefined}
                >
                  {count}
                </div>
              )
            })}
          </div>
        )}

        <div className="player-stat-badges">
          <div className="player-stat-badge">
            Lib: <strong>{player.libraryCount}</strong>
          </div>
          <div className="player-stat-badge">
            Hand: <strong>{player.handCount}</strong>
          </div>
          {(player.priorityTimeLeftSecs > 0 || player.timerActive) && (
            <div className="player-stat-badge">
              {Math.floor(player.priorityTimeLeftSecs / 60)}:
              {String(player.priorityTimeLeftSecs % 60).padStart(2, '0')}
            </div>
          )}
        </div>
      </div>

      {/* Counters — dedicated labelled fields for the desktop's five
          counters (poison/energy/experience/rad/ticket, PlayerPanelExt.java:
          553-676; wired at :290-294) plus the generic remainder, so the five
          are readable at a glance rather than buried in one name:count line. */}
      {(dedicatedCounters.length > 0 || counters.length > 0) && (
        <div className="player-counters">
          {dedicatedCounters.map((c) => (
            <span
              key={c.counter}
              className={`player-counter player-counter--${c.counter}`}
              data-counter={c.counter}
              title={counterTooltip(c.label, c.count)}
            >
              {c.glyph} {c.label}: {c.count}
            </span>
          ))}
          {otherCounters.length > 0 && (
            <span className="player-counters-generic">
              {otherCounters.map((c) => `${c.name}: ${c.count}`).join(' | ')}
            </span>
          )}
        </div>
      )}

      {/* Body: side zones + main zones */}
      <div className="player-body">
        {/* Side zones column */}
        <div className="side-zones">
          {/* Library card-back with count + optional top card */}
          <div className="zone library-zone">
            <div className="card-thumb card-thumb-sm library-card-back">
              <div className="library-card-back-face" />
              <span className="library-count">{player.libraryCount}</span>
            </div>
            {player.topCard && (
              <div className="library-top-card" title={`Top: ${player.topCard.name}`}>
                <CardThumb card={player.topCard} className="card-thumb-sm" />
              </div>
            )}
          </div>

          {/* Sideboard (only for my player, limited formats) */}
          {player.isMe && player.sideboard && player.sideboard.length > 0 && (
            <div className="zone sideboard-zone">
              <div
                className="zone-title clickable-zone-title"
                onClick={() => onZoneClick?.('sideboard')}
                style={onZoneClick ? { cursor: 'pointer' } : undefined}
              >
                Sideboard ({player.sideboard.length})
              </div>
            </div>
          )}

          {/* Commander zone (if Commander game) */}
          {isCommander && player.commanders.length > 0 && (
            <div className="zone commander-zone">
              <div className="zone-title">Commander ({player.commanders.length})</div>
              <div className="cards-row cards-grid cards-grid-sm">
                {player.commanders.map((cmd) => (
                  <CardThumb
                    key={cmd.name}
                    card={cmd}
                    className="card-thumb-sm"
                    isPlayable={playableCardIds?.has(cmd.id ?? '') ?? false}
                    onClick={onCardClick}
                  />
                ))}
              </div>
            </div>
          )}

          {/* Emblems (command zone, non-card objects) */}
          {player.emblems && player.emblems.length > 0 && (
            <div className="zone emblem-zone">
              <div className="zone-title">Emblem{player.emblems.length > 1 ? 's' : ''} ({player.emblems.length})</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.3rem' }}>
                {player.emblems.map((emb) => {
                  // An emblem CAN be playable: Momir Basic's emblem is exactly
                  // that ("{X}, Discard a card: create a token that's a copy of a
                  // random creature card with mana value X"), and the proxy emits
                  // its activation as a select choice whose id IS the emblem's id
                  // (WebProxyClient.handleGameSelect, command-object branch).
                  // playableCardIds is built from actionPrompt.choices ids, so the
                  // same set the commander thumbs and hand cards use marks it —
                  // no extra store wiring. Clicking must reach GameView's
                  // handleCardClick (choice lookup → selectChoice → select_choice
                  // → sendPlayerUUID), the same path the desktop client uses.
                  const emblemId = emb.id ?? null
                  const isPlayable = playableCardIds?.has(emblemId ?? '') ?? false
                  return (
                    <span
                      key={emb.id ?? emb.name}
                      className={`player-badge player-badge-emblem${isPlayable ? ' player-badge-playable' : ''}`}
                      title={emb.rules?.join('\n') ?? emb.name}
                      role={isPlayable ? 'button' : undefined}
                      tabIndex={isPlayable ? 0 : undefined}
                      onClick={isPlayable ? () => onCardClick?.(emblemId) : undefined}
                      onKeyDown={isPlayable
                        ? (e) => {
                            if (e.key === 'Enter' || e.key === ' ') {
                              e.preventDefault()
                              onCardClick?.(emblemId)
                            }
                          }
                        : undefined}
                      style={isPlayable ? { cursor: 'pointer' } : undefined}
                    >
                      🛡 {emb.name}
                    </span>
                  )
                })}
              </div>
            </div>
          )}

          {/* Planes (command zone, non-card objects) */}
          {player.planes && player.planes.length > 0 && (
            <div className="zone plane-zone">
              <div className="zone-title">Plane{player.planes.length > 1 ? 's' : ''} ({player.planes.length})</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.3rem' }}>
                {player.planes.map((pl) => (
                  <span key={pl.id ?? pl.name} className="player-badge player-badge-plane" title={pl.rules?.join('\n') ?? pl.name}>
                    ✈ {pl.name}
                  </span>
                ))}
              </div>
            </div>
          )}

          {/* Graveyard */}
          {player.graveyard.length > 0 && (
            <div className="zone graveyard-zone">
              <div
                className="zone-title clickable-zone-title"
                onClick={() => onZoneClick?.('graveyard')}
                style={onZoneClick ? { cursor: 'pointer' } : undefined}
              >
                Graveyard ({player.graveyard.length})
              </div>
              <div className="cards-row cards-grid cards-grid-sm">
                {player.graveyard.map((card) => (
                  // Off-hand play affordance (
                  // no-popups.md): a flashback / play-from-graveyard choice arrives with the
                  // card's own UUID (WebProxyClient.handleGameSelect — findCardViewById
                  // resolves graveyard cards), and playableCardIds already carries it, so
                  // the tile gets the SAME highlight + click the hand gets. Clicking calls
                  // onCardClick → GameView.handleCardClick → selectChoice — interface
                  // selection is primary, the rail button and the zone window are fallback.
                  <CardThumb
                    key={card.id ?? card.name}
                    card={card}
                    className="card-thumb-sm"
                    isPlayable={playableCardIds?.has(card.id ?? '') ?? false}
                    onClick={onCardClick}
                  />
                ))}
              </div>
            </div>
          )}

          {/* Exile */}
          {player.exile.length > 0 && (
            <div className="zone exile-zone">
              <div
                className="zone-title clickable-zone-title"
                onClick={() => onZoneClick?.('exile')}
                style={onZoneClick ? { cursor: 'pointer' } : undefined}
              >
                Exile ({player.exile.length})
              </div>
              <div className="cards-row cards-grid cards-grid-sm">
                {player.exile.map((card) => (
                  // Off-hand play affordance, exile twin of the graveyard tiles above:
                  // play-from-exile choices carry the card's own UUID in
                  // playableCardIds, so the tile highlights and clicks exactly like a
                  // hand card. An opponent's exile renders the same affordance surface
                  // but can never light up — the engine never offers its card ids.
                  <CardThumb
                    key={card.id ?? card.name}
                    card={card}
                    className="card-thumb-sm"
                    isPlayable={playableCardIds?.has(card.id ?? '') ?? false}
                    onClick={onCardClick}
                  />
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Main column (battlefield + hand) — independent scroll sections */}
        <div className={`main-zones${isTopPlayer ? ' top-player' : ''}`}>
          {isTopPlayer ? (
            <>
              {/* Top player: hand on top, battlefield below */}
              {handSection}
              <div className="battlefield-section">
                <BattlefieldZone
                  cards={player.battlefield}
                  isTopPlayer
                  playableCardIds={playableCardIds}
                  combatChoiceIds={combatChoiceIds}
                  combatDeclaredIds={combatDeclaredIds}
                  targetIds={targetIds}
                  manaTappableIds={manaTappableIds}
                  onCardClick={onCardClick}
                  {...owner}
                />
              </div>
            </>
          ) : (
            <>
              {/* Bottom player: battlefield on top, hand below */}
              <div className="battlefield-section">
                <BattlefieldZone
                  cards={player.battlefield}
                  isTopPlayer={false}
                  playableCardIds={playableCardIds}
                  combatChoiceIds={combatChoiceIds}
                  combatDeclaredIds={combatDeclaredIds}
                  targetIds={targetIds}
                  manaTappableIds={manaTappableIds}
                  onCardClick={onCardClick}
                  {...owner}
                />
              </div>
              {handSection}
            </>
          )}
        </div>
      </div>
    </article>
  )
}

export const PlayerBoard = memo(PlayerBoardInner)