import { memo, useState, useCallback, useEffect, useLayoutEffect, useRef, type MouseEvent } from 'react'
import type { Permanent, Card, Commander } from '@/types'
import { resolveCardImage, cardFallbackText, fallbackClampLines, isAllowedImageUrl } from '@/utils/cardImage'
import { hasPT, formatPT, isTokenCard, isLikelyLand } from '@/utils/cardTypes'
import { menuOwnedByTile } from '@/utils/menuScope'
import { useGameStore } from '@/store/gameStore'
import { useContextMenu } from '@/hooks/useContextMenu'
import {
  fetchTokenVersions,
  getTokenVersions,
  setTokenVersions,
  subscribeTokenArt,
  tokenArtFor,
  engineTokenSetCode,
} from '@/utils/tokenArt'

/**
 * Card thumbnail — ported from game-renderer.js makeCardThumbnail.
 * Renders a card image with overlays for P/T, counters, tap state, etc.
 *
 * Props:
 * - card: The card/permanent/commander object
 * - isTapped: Whether the card is tapped (rotated 90°)
 * - isPlayable: Whether the card can be played (highlighted border)
 * - isCombatSelectable: Whether the card can be selected for combat
 * - isCombatDeclared: Whether the card is ALREADY declared in combat — clicking
 *   it again undeclares it (GameView.handleCardClick combatDeclaredIds path);
 * - isTarget: Whether the card is a valid target for the current action
 * - onClick: Click handler for card selection
 * - ownerPlayerId / isMine: who controls this card — resolves token-art preferences
 */
interface CardThumbProps {
  card: Permanent | Card | Commander
  isTapped?: boolean | undefined
  isPlayable?: boolean | undefined
  isCombatSelectable?: boolean | undefined
  isCombatDeclared?: boolean | undefined
  isTarget?: boolean | undefined
  isManaTappable?: boolean | undefined
  onClick?: ((cardId: string | null) => void) | undefined
  className?: string | undefined
  ownerPlayerId?: string | null | undefined
  isMine?: boolean | undefined
}

/**
 * How many candidate printings a token may try before giving up and showing the
 * card-back placeholder. XMage ships up to 45 arts for a common token (Soldier);
 * probing all of them would be a burst of requests for a token whose every art is
 * missing. 12 covers the realistic "the engine's first choice 404s" case; past
 * that, the Scryfall-expanded list has already been consulted.
 */
const MAX_CANDIDATE_PROBES = 12

function CardThumbInner({
  card,
  isTapped = false,
  isPlayable = false,
  isCombatSelectable = false,
  isCombatDeclared = false,
  isTarget = false,
  isManaTappable = false,
  onClick,
  className = '',
  ownerPlayerId,
  isMine = false,
}: CardThumbProps) {
  const cardName = card.name || 'Unknown'
  const cardId = card.id ?? null
  const isToken = isTokenCard(card)
  const [imgError, setImgError] = useState(false)
  /**
   * Candidate printings this tile has tried and failed, and the one that loaded.
   * Kept per-tile (not global) because "this art 404'd" is a property of the URL;
   * a module-level set would leak memory over a long session.
   */
  const [failedUrls, setFailedUrls] = useState<string[]>(() => [])
  const [loadedCandidate, setLoadedCandidate] = useState<string | null>(null)
  /** True while a token's printings are still being probed. */
  const [resolving, setResolving] = useState(false)
  /** One-shot guard, so the token's printings are requested at most once per tile. */
  const phase1Ref = useRef(false)
  const setCardPreview = useGameStore((s) => s.setCardPreview)
  // Font-scale preference: --font-scale remaps the root font size, which changes
  // this box's line-height WITHOUT changing the tile's size (the tile is sized by
  // --card-width). The clamp measurement below therefore has to re-run when it
  // changes — a ResizeObserver alone would leave a stale line count.
  const fontScale = useGameStore((s) => s.fontScale)
  // Shared context-menu registry: ONE menu store-wide, and CardPreview
  // suppresses itself for any of them (no per-menu flag anymore).
  const tokenArtMenu = useContextMenu('token-art')
  const cardMenu = useContextMenu('card')

  // If this tile unmounts while ITS menu is open (board re-render, zone
  // change), the store entry must not stay stuck — close it with the tile.
  // The registry is GLOBAL, so the cleanup must be scoped to the menu this
  // tile owns: the unscoped version ("any tile unmounts while any menu is
  // open") closed other tiles' menus — measured during replay playback, an
  // unrelated Morska unmount closed the Clue Token art menu at a board push.
  //
  //
  // Card menus are instance-scoped (the payload carries the card object), so
  // they still close with their tile. Token-art menus are NAME-scoped: a board
  // push may re-key the tile (new token id, same name) within the same commit,
  // so the close is deferred past the commit and skipped while any tile of
  // that name remains on the board.
  const tileRef = useRef({ name: cardName, id: cardId })
  tileRef.current = { name: cardName, id: cardId }
  useEffect(() => {
    return () => {
      const me = tileRef.current
      const open = useGameStore.getState().openContextMenu
      if (!menuOwnedByTile(open, me.name, me.id)) return
      if (open?.kind === 'card') {
        useGameStore.getState().setOpenContextMenu(null)
        return
      }
      window.setTimeout(() => {
        const cur = useGameStore.getState().openContextMenu
        if (!(cur?.kind === 'token-art' && cur.tokenName === tileRef.current.name)) return
        const anchored = Array.from(document.querySelectorAll('.card-thumb')).some(
          (el) => (el.getAttribute('data-card-name') || '') === tileRef.current.name,
        )
        if (!anchored) useGameStore.getState().setOpenContextMenu(null)
      }, 0)
    }
  }, [])

  // Re-render when preferences change (a token art pick, or a relayed choice).
  const [, forcePrefsRender] = useState(0)
  useEffect(() => subscribeTokenArt(() => forcePrefsRender((n) => n + 1)), [])

  // The engine's own URL for this card (a CDN uuid URL from the proxy's
  // offline map; null on a map miss, which the placeholder covers).
  const engineUrl = resolveCardImage(cardName, card, null, 'small')

  /**
   * Resolve which URL this tile should render.
   *
   * Order:
   *  1. a preference that applies here (my art, or the owner's shared choice) —
   *     see tokenArtFor / preferTokenArt.
   *  2. the token art this tile proved loads (loadedCandidate).
   *  3. the engine's intended art — the printing from the token's OWN set, as
   *     chosen by the proxy from the offline map (`defaultUrl`).
   *  4. the engine URL (a CDN uuid URL, or null on a map miss).
   *
   * All token candidates come from the proxy as REAL printings. No URL is ever built
   * from the engine's `tokenImageNumber` — that index is not a Scryfall collector
   * number and renders a different token (see utils/tokenArt.ts).
   *
   * A URL that already failed is never returned again (failedUrls), so an <img>
   * whose src 404s cannot loop.
   */
  const preferred = isToken ? tokenArtFor(cardName, ownerPlayerId, isMine, undefined) : undefined
  const candidateChain: string[] = []
  if (isToken) {
    if (loadedCandidate) candidateChain.push(loadedCandidate)
    // Real printings from the proxy, in the proxy's order. The engine's intended art
    // (its own set) is listed first so a token with no preference opens on the right
    // set's art even before the general list is walked.
    const versions = getTokenVersions(cardName)
    if (versions) {
      if (versions.defaultUrl) candidateChain.push(versions.defaultUrl)
      for (const v of versions.versions) {
        candidateChain.push(v.url)
        if (candidateChain.length >= MAX_CANDIDATE_PROBES) break
      }
    }
  }
  candidateChain.push(engineUrl)

  const firstUsable = candidateChain.find((u) => !!u && !failedUrls.includes(u) && isAllowedImageUrl(u))
  // A preference that has already failed must NOT be re-served — without this guard the
  // tile retries the same broken URL on every render (the <img> errors, the handler
  // records it, and the preference would hand it straight back).
  const usablePreference = preferred && !failedUrls.includes(preferred) ? preferred : undefined
  const imageUrl = usablePreference ?? firstUsable ?? null
  // The other face (transform/MDFC/meld/flip). 'secondFace' in card guards
  // the Commander union member, which does not carry it.
  const secondFace = 'secondFace' in card ? card.secondFace : undefined
  const canFlip = !!secondFace && !!cardId

  /**
   * An image failed to load.
   *
   * Real cards: fall straight through to the placeholder (there is only one URL).
   * Tokens: advance the candidate chain and ask the proxy for this token's printings
   * if it has not already. The card-back placeholder renders meanwhile, so the tile is
   * never a blank line.
   *
   * One request per tile (`phase1Ref`). Candidates are cached per NAME, so the other
   * tiles of this token — and every later game — are served without a request, and the
   * proxy caches its Scryfall search too.
   */
  const handleImgError = useCallback(() => {
    const failed = imageUrl
    if (failed) {
      setFailedUrls((prev) => (prev.includes(failed) ? prev : [...prev, failed]))
    }

    if (!isToken) {
      setImgError(true)
      return
    }

    const versions = getTokenVersions(cardName)
    // Any candidate this tile has not yet tried? The failed one was just recorded, so
    // this excludes it without a second lookup.
    const remaining = versions
      ? versions.versions.some((v) => !failedUrls.includes(v.url) && v.url !== failed)
      : false

    // Fetch the token's printings once. Every candidate is a real Scryfall
    // printing from the proxy's offline map, so the list is complete (or
    // honestly empty) in one round-trip; a miss means no art exists and the
    // placeholder renders.
    if (!versions && !phase1Ref.current) {
      phase1Ref.current = true
      setResolving(true)
      fetchTokenVersions(cardName, engineTokenSetCode(card), sendWsMessage)
        .then((r) => {
          setTokenVersions(r)
          setResolving(false)
          if (r.versions.length === 0) {
            setImgError(true)
          }
        })
        .catch(() => setResolving(false))
      return
    }

    if (!remaining) setImgError(true)
  }, [isToken, imageUrl, card, cardName, failedUrls])

  // Mark the loaded candidate when an image succeeds, so the tile stops probing.
  const handleImgLoad = useCallback(() => {
    if (imageUrl) setLoadedCandidate(imageUrl)
    setImgError(false)
  }, [imageUrl])

  // Flip suppression: after the flip button opens the OTHER face, the tile's
  // own mouseenter must not overwrite it with the front face again (the
  // cursor is still over the tile). Cleared on mouse leave — leaving the tile
  // closes the preview; re-entering shows the front again.
  const [flipPreviewActive, setFlipPreviewActive] = useState(false)

  const handleMouseEnter = useCallback((e: MouseEvent) => {
    if (flipPreviewActive) return
    // The hover preview resolves token art through the same preference layers
    // as this tile, so it needs the owner context (see utils/tokenArt.ts).
    setCardPreview({ card, face: 'front', x: e.clientX, y: e.clientY, ownerPlayerId, isMine })
  }, [card, setCardPreview, flipPreviewActive, ownerPlayerId, isMine])

  const handleMouseLeave = useCallback(() => {
    setFlipPreviewActive(false)
    setCardPreview(null)
  }, [setCardPreview])

  // Flip button (top-left corner): opens the OTHER face as a hover preview.
  // stopPropagation keeps the tile's own click (card selection) quiet.
  const handleFlipClick = useCallback((e: MouseEvent) => {
    e.stopPropagation()
    e.preventDefault()
    if (!secondFace) return
    const rect = (e.currentTarget as HTMLElement).closest('.card-thumb')?.getBoundingClientRect()
    const x = rect ? rect.right + 8 : e.clientX
    const y = rect ? rect.top : e.clientY
    // Preview payload = the other face: its name/art/rules/stats, with the
    // front card's copy markers stripped so the "(copy of …)" line can't
    // leak into the flipped view.
    setFlipPreviewActive(true)
    setCardPreview({
      card: {
        ...card,
        name: secondFace.name,
        imageUrl: secondFace.imageUrl,
        typeLine: secondFace.typeLine ?? '',
        manaCost: secondFace.manaCost ?? '',
        rules: secondFace.rules ?? [],
        power: secondFace.power,
        toughness: secondFace.toughness,
        loyalty: secondFace.loyalty,
        defense: secondFace.defense,
        originalCard: undefined,
        copy: undefined,
      },
      face: 'back',
      x,
      y,
    })
  }, [card, secondFace, setCardPreview])

  const text = cardFallbackText(card)
  const classes = [
    'card-thumb',
    isTapped ? 'tapped' : '',
    isPlayable ? 'card-playable' : '',
    isCombatSelectable ? 'combat-selectable' : '',
    isCombatDeclared ? 'combat-declared' : '',
    isTarget ? 'card-target' : '',
    isManaTappable ? 'card-mana-tappable' : '',
    className,
  ].filter(Boolean).join(' ')

  const showPT = hasPT(card)
  // Planeswalker check for the loyalty badge + 'loyalty' counter pip dedupe below.
  const isPlaneswalker = card.typeLine.includes('Planeswalker')
  // Bottom-badge presence, computed once so the clamp measurement (below) can
  // re-run when a badge appears/disappears.
  const loyaltyText = 'loyalty' in card && typeof card.loyalty === 'string' ? card.loyalty.trim() : ''
  const showLoyalty = isPlaneswalker && loyaltyText !== ''
  const showSummoningSick = 'summoningSick' in card && !!card.summoningSick

  // The placeholder shows when there is no renderable art: either an image failed,
  // or there is no usable URL at all. It is a card-shaped card back with the name
  // (and rules text, when there is any) overlaid.
  const showPlaceholder = !imageUrl || imgError
  const placeholderBare = text.trim() === ''

  // Official card back (2026-10-04): a faceDown card (morph/manifest/cloak/
  // disguise/manual) renders the REAL card back — the tile the desktop draws
  // via drawCardBackTexture — not the text-fallback box. The proxy nulls
  // imageUrl for face-down cards, so `faceDown && no art` is the normal
  // shape; an imageUrl must never win here (the flag is engine truth, the
  // URL would be a Scryfall-printing guess on a sanitised name).
  // 'faceDown' in card guards the Commander union member, which does not
  // carry the flag.
  const isFaceDown = 'faceDown' in card && !!card.faceDown
  const showCardBack = isFaceDown && (!imageUrl || imgError)

  // Clamp budget for the fallback box's rules text. Long rules used to be cut
  // mid-word at the tile edge with only a faint fade marking the cut; the box now
  // ends on a WHOLE line with an ellipsis, so the last visible line is always
  // readable. How many lines fit depends on the tile's actual
  // size — viewport width, the 1v1 pane split, and the card-scale preference all
  // change it — so it is MEASURED here (fallbackClampLines) and re-measured
  // whenever the tile resizes (ResizeObserver), never hardcoded.
  const fallbackTextRef = useRef<HTMLDivElement | null>(null)
  useLayoutEffect(() => {
    const el = fallbackTextRef.current
    if (!el) return
    const measure = () => {
      const box = el.parentElement
      if (!box) return
      const cs = getComputedStyle(el)
      const fontSize = parseFloat(cs.fontSize) || 0
      const lineHeight = parseFloat(cs.lineHeight) || fontSize * 1.25
      // Space the text may use, in the TILE's frame. The tile's overlay badges
      // (P/T, loyalty, summoning sickness) are siblings of this box, so a badge
      // sitting in the band the text can reach must be reserved — otherwise the
      // clamped last line (and its ellipsis) hides behind it. Geometry is read in
      // the WRAPPER frame (offsetTop/offsetHeight, transform-free): rotation is a
      // rigid transform, so a badge that does not overlap the text block there
      // cannot overlap it visually either. This matters on a tapped tile, where
      // the P/T + loyalty badges move to the wrapper TOP (bottom: auto — they
      // display at the visual bottom-right via the tile's rotate(90deg)); a
      // getComputedStyle 'bottom' test cannot see that, because Chrome reports
      // the used value, never 'auto'.
      const padBottom = parseFloat(getComputedStyle(box).paddingBottom) || 0
      const textTop = box.offsetTop + el.offsetTop
      const limit = box.offsetTop + box.clientHeight - padBottom
      let bottomInset = padBottom
      const tile = box.parentElement
      if (tile) {
        for (const sel of ['.card-thumb-pt', '.card-overlay-loyalty', '.card-overlay-summoning-sick']) {
          const badge = tile.querySelector<HTMLElement>(sel)
          if (!badge || badge.offsetHeight === 0) continue // absent or display:none
          const badgeTop = badge.offsetTop
          // Only a badge that actually reaches into the text's band steals lines.
          if (badgeTop + badge.offsetHeight <= textTop + 4 || badgeTop >= limit - 4) continue
          bottomInset = Math.max(bottomInset, box.offsetTop + box.clientHeight - badgeTop)
        }
      }
      // Expressed in whole lines only — a partial line would cut mid-word again.
      const available = box.clientHeight - el.offsetTop - bottomInset
      el.style.setProperty('--fallback-text-lines', String(fallbackClampLines(available, lineHeight)))
    }
    measure()
    // The font-scale preference is applied by App in ITS effect, which runs
    // AFTER a child's layout effect (React is bottom-up) — so the first measure
    // can still see the previous font. One follow-up measurement on the next
    // frame captures it. Cheap: only on text/size/preference changes.
    let rafId = 0
    if (typeof requestAnimationFrame === 'function') {
      rafId = requestAnimationFrame(measure)
    }
    if (typeof ResizeObserver === 'undefined') {
      return () => { if (rafId) cancelAnimationFrame(rafId) }
    }
    const box = el.parentElement
    if (!box) return () => { if (rafId) cancelAnimationFrame(rafId) }
    const observer = new ResizeObserver(measure)
    observer.observe(box)
    return () => {
      if (rafId) cancelAnimationFrame(rafId)
      observer.disconnect()
    }
  }, [showPlaceholder, text, cardName, showPT, showLoyalty, showSummoningSick, isTapped, fontScale])

  return (
    <div
      className={classes}
      data-card-id={cardId ?? undefined}
      data-card-name={cardName}
      // Additive accessible playable markup (bug-hunt #7): the tile already
      // carries the `card-playable` class when castable, but that is CSS-only —
      // a selector/AT consumer had no attribute to read. `data-playable` is
      // emitted ONLY when the card is actually playable (absent otherwise), so
      // `.hand-cards-row [data-card-id][data-playable]` distinguishes castable
      // cards without inventing a false positive.
      data-playable={isPlayable ? 'true' : undefined}
      onClick={onClick ? () => onClick(cardId) : undefined}
      onContextMenu={(e) => {
        // Tokens have art to pick (the art menu); everything else gets the
        // generic card menu. Either way the browser menu never shows.
        //
        // The token's name + engine set travel IN THE PAYLOAD so the panel can be
        // mounted once (GameView/ReplayView) instead of per tile — the previous
        // shape rendered one menu per CardThumb, all sharing the registry's global
        // `isOpen`.
        e.preventDefault()
        e.stopPropagation()
        if (isToken) {
          tokenArtMenu.openAt(e, { tokenName: cardName, engineSet: engineTokenSetCode(card) })
        } else {
          cardMenu.openAt(e, { card, onClick, isPlayable })
        }
      }}
      onMouseEnter={handleMouseEnter}
      onMouseLeave={handleMouseLeave}
      style={onClick ? { cursor: 'pointer' } : undefined}
    >
      {!showPlaceholder && imageUrl && (
        <img
          src={imageUrl}
          alt={cardName}
          draggable={false}
          onError={handleImgError}
          onLoad={handleImgLoad}
        />
      )}

      {showCardBack ? (
        /* Face-down tile (2026-10-04, official card back): the real Magic card
         * back, no text overlay — exactly what the desktop's
         * drawCardBackTexture renders for morph/manifest/cloak/disguise and
         * manual face-down. The controller's own face-down card keeps its flip
         * button (secondFace below) to peek the real face. */
        <div className="card-thumb-facedown" />
      ) : showPlaceholder ? (
        /* Card-back placeholder: a card-SHAPED blank — card back,
         * name + rules text overlaid — replacing the grey box that used to collapse
         * to a sliver because the removed <img> was the tile's only in-flow content.
         * The tile's own aspect-ratio (game-renderer.css) keeps it card-shaped. */
        <div
          className={`card-thumb-fallback card-art-fallback${placeholderBare ? ' is-bare' : ''}${resolving ? ' card-art-resolving' : ''}`}
        >
          <div className="card-art-fallback-name">{cardName}</div>
          {!placeholderBare && (
            <div className="card-art-fallback-text" ref={fallbackTextRef}>{text}</div>
          )}
        </div>
      ) : null}

      {/* Flip button — other face of a transform/MDFC/meld/flip card.
          Title names the hidden face so hovering the button is self-explanatory. */}
      {canFlip && secondFace && (
        <button
          type="button"
          className="card-flip-btn"
          title={`Show other side: ${secondFace.name}`}
          onMouseDown={(e) => e.stopPropagation()}
          onClick={handleFlipClick}
        >
          ⟳
        </button>
      )}

      {showPT && (
        <span className="card-thumb-pt">{formatPT(card)}</span>
      )}

      {/* Planeswalker loyalty badge — current loyalty overlays the card image */}
      {showLoyalty && (
        <span className="card-overlay-loyalty" title="Loyalty">{loyaltyText}</span>
      )}

      {/* Counters overlay */}
      {card && 'counters' in card && card.counters && typeof card.counters === 'object' &&
        Object.keys(card.counters).length > 0 && !isLikelyLand(card) && (
          <div className="card-overlay-counters">
            {Object.entries(card.counters).map(([name, count]) => (
              // Planeswalkers: the loyalty badge above already shows current
              // loyalty, so skip the duplicate 'loyalty' counter pip
              // (CounterType.LOYALTY serializes its name lowercase).
              count > 0 && !(isPlaneswalker && name === 'loyalty') && (
                <span key={name} className="counter-pip" title={name}>
                  {count}
                </span>
              )
            ))}
          </div>
        )}

      {/* Damage overlay — the truthy guard also keeps a falsy 0 out of the JSX,
          which would otherwise render as a phantom "0" text node below the card
          art and stretch every card tile taller than its box-sizing height. */}
      {card && 'damage' in card && card.damage != null && Number(card.damage) > 0 && (
        <div className="card-overlay-damage">-{card.damage}</div>
      )}

      {/* Summoning sickness overlay */}
      {showSummoningSick && (
        <div className="card-overlay-summoning-sick" title="Summoning Sickness">S</div>
      )}

      {/* A "custom art" marker: this token is rendered with a chosen art (mine or the
          owner's shared choice) rather than the engine's default. Small and
          top-right so it never collides with the flip button (top-left) or the P/T
          badge (bottom-right). */}
      {isToken && !!preferred && (
        <span className="card-overlay-custom-art" title="Custom token art">✎</span>
      )}

      {/* Attacking/blocking indicators — from combat state, not on card directly */}
      {/* These are handled by the parent via combat data */}

      {/* The token-art menu is NOT rendered here — one mount point in
          GameView/ReplayView reads the token from the store payload. Rendering it
          per tile gave every tile its own panel (see the onContextMenu comment). */}
    </div>
  )
}

/**
 * Send a message over the browser WebSocket.
 *
 * CardThumb renders far from the socket-owning code, and threading a sender through
 * every zone would be a large prop-chain change for one feature. The store already
 * holds the live socket (`GameStore.ws`, the same handle every slice's actions use via
 * `get().ws`), so read it directly — the pattern the keyboard-shortcut handlers use
 * with `useGameStore.getState()`.
 */
function sendWsMessage(msg: Record<string, unknown>): void {
  const ws = useGameStore.getState().ws as
    | { send?: (m: Record<string, unknown>) => void }
    | null
    | undefined
  if (ws && typeof ws.send === 'function') {
    ws.send(msg)
  }
}

/** Memoized CardThumb — prevents unnecessary re-renders when only other cards change. */
export const CardThumb = memo(CardThumbInner)
