import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { useGameStore } from '@/store/gameStore'
import { resolveCardImage, swapImageSize, isAllowedImageUrl } from '@/utils/cardImage'
import { isTokenCard, hasPT, formatPT } from '@/utils/cardTypes'
import {
  DEFAULT_PREVIEW_MODE,
  applyPreviewHotkey,
  alternateSourceOf,
  hasAlternateSource,
  previewHotkey,
  previewDisplayName,
  previewPanelGeometry,
  type PreviewMode,
} from '@/utils/cardPreviewHotkeys'
import {
  fetchTokenVersions,
  getTokenVersions,
  setTokenVersions,
  preferTokenArt,
  subscribeTokenArt,
  engineTokenSetCode,
} from '@/utils/tokenArt'
import { rootZoom, cssViewportWidth, cssViewportHeight } from '@/utils/viewport'
import { ManaCost, ManaText } from './ManaCost'

/** Gap between the panel and the viewport edge, in ROOT CSS px. */
const MARGIN = 12

/** Place a panel of the given size next to the cursor, fully inside the
 *  (zoom-compensated) viewport. */
function fitPanel(left: number, top: number, w: number, h: number, vw: number, vh: number) {
  return {
    left: Math.max(MARGIN, Math.min(left, vw - MARGIN - w)),
    top: Math.max(MARGIN, Math.min(top, vh - MARGIN - h)),
  }
}

/**
 * How many token-printing candidates the preview will try before giving up and
 * showing the card-back placeholder. A hover is transient — probing a full
 * 45-art list on a broken token would be a request burst for a panel the user
 * is about to mouse away from. The tile (CardThumb) covers the exhaustive case;
 * 5 is more than enough to skip a stale preference and a couple of dead arts.
 */
const MAX_TOKEN_PREVIEW_PROBES = 5

/**
 * The preview's candidate image URLs, in priority order, resolved to ONE URL
 * (or null = render the card-back placeholder).
 *
 * The chain mirrors the board tile's (CardThumb) because the panel must show
 * what the tile shows:
 *   1. a token-art preference that applies here (my art, or the owner's shared
 *      choice) — the layer the right-click picker just changed;
 *   2. the proxy's cached printings for a token: the engine's intended art
 *      (defaultUrl) first, then the general list;
 *   3. the offline-map URL for the card (engineUrl; '' on a map miss).
 *
 * URLs are size-swapped to 'normal' — the proxy hands out 'small' tile URLs and
 * the CDN size is a path segment. Every candidate must pass the image-host
 * allowlist, and a URL that already failed (failedUrls) is never served again.
 *
 * Exported PURE so the behavioural harness asserts the exact chain the panel
 * renders — no DOM, no React (same convention as fallbackClampLines and
 * replayLogSliceFor).
 */
export function previewArtCandidates(input: {
  isToken: boolean
  preferredUrl: string | undefined
  tokenVersions: { versions: Array<{ url: string }>; defaultUrl?: string | undefined } | undefined
  /** Offline-map URL for the card; '' means a map miss. */
  engineUrl: string
  failedUrls: readonly string[]
}): string | null {
  const candidates: string[] = []
  if (input.isToken) {
    if (input.preferredUrl) candidates.push(swapImageSize(input.preferredUrl, 'normal'))
    if (input.tokenVersions) {
      if (input.tokenVersions.defaultUrl) {
        candidates.push(swapImageSize(input.tokenVersions.defaultUrl, 'normal'))
      }
      for (const v of input.tokenVersions.versions) {
        candidates.push(swapImageSize(v.url, 'normal'))
        if (candidates.length >= MAX_TOKEN_PREVIEW_PROBES) break
      }
    }
  }
  if (input.engineUrl) candidates.push(input.engineUrl)
  return candidates.find((u) => !!u && isAllowedImageUrl(u) && !input.failedUrls.includes(u)) ?? null
}

/**
 * Send a message over the browser WebSocket (same shape as CardThumb's helper):
 * the store holds the live socket, and this component renders far from the
 * socket-owning code, so read it directly rather than threading a sender prop.
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

/**
 * Card preview hover panel — ported from index.html #card-preview div
 * and game-renderer.js showPreview/hidePreview.
 *
 * Shows a card image + metadata on hover. Positioned relative to cursor.
 * The store's cardPreview field controls visibility.
 *
 * Sizing lives in game-renderer.css (#card-preview: 540px wide, 220px image
 * column). Because the rendered height depends on the card's rules text, the
 * position is not derived from a height estimate: the panel is measured after
 * render (layout effect — before paint, so no visible jump) and re-clamped to
 * the viewport. An estimate would let rules-heavy cards hang off the bottom of
 * the screen.
 *
 * The Hover Preview Size setting (store cardPreviewScale, 0.5–4.0) scales the
 * panel WIDTH (and its image column proportionally), so image AND rules text
 * grow/shrink together. The measured re-clamp handles the oversized cases;
 * nothing here needs its own viewport math.
 *
 * TOKEN ART: a token's name is not addressable on Scryfall
 * (`named?exact=Soldier Token` 404s; the generic chain has no entry), so the
 * old single `resolveCardImage` call left the panel's image blank. The preview
 * now walks the SAME chain as the board tile (utils/tokenArt.ts): a preference
 * that applies here → the proxy's cached printings (defaultUrl first) → the
 * engine URL — and fires the shared one-shot `fetchTokenVersions` on a cold
 * cache so a hover warms the cache every tile of that token reads. When no
 * candidate is usable the panel shows the card-back placeholder (name over the
 * card back), exactly like the tile — never a broken image box.
 */
export default function CardPreview() {
  const cardPreview = useGameStore((s) => s.cardPreview)
  const setCardPreview = useGameStore((s) => s.setCardPreview)
  // ANY right-click context menu (token art, card menu, phase menu, …) is modal
  // over the board: the hover panel renders ABOVE menus (#card-preview z-index
  // 9999 vs the shared shell's 3000) and would cover them, so while one is open
  // the preview does not render at all. The preview DATA stays in the store —
  // closing the menu restores the panel with no re-hover.
  const contextMenuOpen = useGameStore((s) => !!s.openContextMenu)
  const cardPreviewScale = useGameStore((s) => s.cardPreviewScale)
  const panelRef = useRef<HTMLDivElement | null>(null)
  const [fitted, setFitted] = useState<{ left: number; top: number } | null>(null)
  /**
   * Token-printing candidates this panel has tried and failed (a stale
   * preference uuid, a dead art). Kept per panel instance: a URL that failed
   * must never be re-served, or the <img> would retry it on every render.
   */
  const [failedUrls, setFailedUrls] = useState<string[]>([])
  /**
   * Alt+E / Alt+S preview modes. Session-local to this panel instance:
   * enlarged (bigger panel) and alternate source (the card's other face / copy
   * source). Reset whenever the hovered card changes so a mode toggled for one
   * card does not silently carry to the next.
   */
  const [previewMode, setPreviewMode] = useState<PreviewMode>(DEFAULT_PREVIEW_MODE)
  // Re-render when preferences/printings change (a pick, a relay, a fetch landing).
  const [, bumpArt] = useState(0)
  useEffect(() => subscribeTokenArt(() => bumpArt((n) => n + 1)), [])

  const card = cardPreview?.card ?? null
  const isToken = !!card && isTokenCard(card)
  const cardName = card?.name || 'Unknown'
  const tokenVersions = isToken ? getTokenVersions(cardName) : undefined

  // A URL that failed for one card must not suppress a candidate for the NEXT
  // one: reset the failed list whenever the hovered card changes. The Alt+E /
  // Alt+S preview modes are reset with it — a mode toggled for one card must not
  // silently apply to the next.
  useEffect(() => {
    setFailedUrls([])
    setPreviewMode(DEFAULT_PREVIEW_MODE)
  }, [cardName])

  /**
   * Alt+E enlarge / Alt+S alternate-source hotkeys. A document-level
   * keydown listener (the preview panel does not hold focus — the board does),
   * active only while a preview is showing. `preventDefault` stops the browser's
   * own Alt+E / Alt+S default actioning while the panel is up. The two keys are
   * TOGGLES, so pressing again returns to the normal view (the desktop wheel
   * behaves the same way: scroll back = NORMAL).
   */
  useEffect(() => {
    if (!cardPreview) return
    const onKeyDown = (e: KeyboardEvent) => {
      const hk = previewHotkey(e.key, e.altKey)
      if (!hk) return
      // Alt+S with no source on THIS card is a no-op (never a dead toggle that
      // claims to have changed something).
      if (hk === 'alternate' && !hasAlternateSource(card)) return
      e.preventDefault()
      setPreviewMode((m) => applyPreviewHotkey(m, hk))
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [cardPreview, card])

  /**
   * Warm the token-art cache on the first hover, once per name: the same
   * `token_versions` request the tiles make, deduped by utils/tokenArt's
   * in-flight map, so a hover before the tiles have resolved is not a second
   * request — and the tiles that render next read this result. Effect (not
   * render-time) so React never fires a request during render.
   */
  useEffect(() => {
    if (!cardPreview || !card || !isToken || tokenVersions) return
    fetchTokenVersions(cardName, engineTokenSetCode(card), sendWsMessage)
      .then((r) => setTokenVersions(r))
      .catch(() => {
        /* nothing to report — the placeholder covers a failed lookup */
      })
  }, [cardPreview, card, isToken, tokenVersions, cardName])

  // cursor position (REAL px) -> ROOT CSS px; base position before fitting.
  const z = rootZoom()
  const baseLeft = cardPreview ? cardPreview.x / z : 0
  const baseTop = cardPreview ? cardPreview.y / z : 0

  useLayoutEffect(() => {
    const el = panelRef.current
    if (!el) {
      setFitted(null)
      return
    }
    const fit = () => {
      const r = el.getBoundingClientRect()
      const next = fitPanel(baseLeft, baseTop, r.width / z, r.height / z, cssViewportWidth(), cssViewportHeight())
      // Re-measure loop guard: only store when the position actually moved.
      setFitted((prev) =>
        prev && Math.abs(prev.left - next.left) < 0.5 && Math.abs(prev.top - next.top) < 0.5 ? prev : next)
    }
    fit()
    // The card bitmap arrives after layout: re-fit once it lands. The declared
    // aspect-ratio in game-renderer.css reserves the box in the common case;
    // this covers an off-ratio asset.
    const img = el.querySelector('#preview-image')
    img?.addEventListener('load', fit)
    return () => img?.removeEventListener('load', fit)
  })

  if (!cardPreview || !card || contextMenuOpen) return null

  const isBackFace = cardPreview.face === 'back'
  // The "(copy of …)" marker. Read through a narrow cast: the cardPreview
  // payload is a union that includes Commander, which does not declare
  // `originalCard`, so a bare `'originalCard' in card` check leaves the value
  // `unknown` under the union.
  const originalCardName =
    typeof (card as { originalCard?: unknown }).originalCard === 'string'
      ? (card as { originalCard?: string }).originalCard
      : undefined

  /**
   * The alternate source for Alt+S (the card's other face / its copy
   * source) and the name the panel header shows for the CURRENT mode. When no
   * alternate is available Alt+S is inert (see the keydown effect); a
   * toggled-on mode with nothing to show falls back to the card's own name.
   */
  const alternate = alternateSourceOf(card)
  const alternateActive = previewMode.alternate && alternate != null
  const displayName = previewDisplayName(card, previewMode)

  /**
   * The candidate chain (see previewArtCandidates): preference → proxy
   * printings → engine URL, resolved to one URL or null (placeholder). With the
   * alternate source active the chain resolves against that NAME (Scryfall's
   * exact= lookup resolves every alternate face name — the serializer sends
   * `secondFace`/`originalCard` for exactly this), so Alt+S shows the other
   * face's art, not the current one's.
   */
  const ownerPlayerId = cardPreview.ownerPlayerId ?? null
  const isMine = !!cardPreview.isMine
  const imageUrl = alternateActive
    ? resolveCardImage(alternate.name, null, null, 'normal')
    : previewArtCandidates({
        isToken,
        preferredUrl: isToken ? preferTokenArt(cardName, ownerPlayerId, isMine) : undefined,
        tokenVersions,
        engineUrl: resolveCardImage(cardName, card, null, 'normal'),
        failedUrls,
      })
  const showPlaceholder = imageUrl === null || imageUrl === ''

  const showPT = hasPT(card)

  // Hover Preview Size: scale the PANEL. Width is the CSS default
  // min(540px, vw-24px) times the setting, and the image column scales with
  // it (220/540 of the panel) so the layout keeps its proportions — image
  // fills its column, rules text reflows in the wider/taller panel. Alt+E
  // multiplies both by ENLARGE_FACTOR on top. The useLayoutEffect fit
  // measures the REAL rendered panel and re-clamps, so oversized previews stay
  // inside the window.
  const { panelWidth, imageCol: scaledImageCol } = previewPanelGeometry(cardPreviewScale, previewMode)

  const stats: string[] = []
  if (showPT) stats.push(formatPT(card))
  if ('loyalty' in card && card.loyalty) stats.push(`Loyalty ${card.loyalty}`)
  if ('defense' in card && card.defense) stats.push(`Defense ${card.defense}`)
  if ('damage' in card && card.damage && Number(card.damage) > 0) stats.push(`Damage ${card.damage}`)

  return (
    <div
      ref={panelRef}
      id="card-preview"
      className={`card-preview${previewMode.enlarged ? ' card-preview--enlarged' : ''}${alternateActive ? ' card-preview--alternate' : ''}`}
      data-preview-mode={previewMode.enlarged ? 'enlarged' : 'normal'}
      data-preview-source={alternateActive ? 'alternate' : 'normal'}
      onMouseLeave={() => setCardPreview(null)}
      style={{
        left: fitted?.left ?? baseLeft,
        top: fitted?.top ?? baseTop,
        width: panelWidth,
        gridTemplateColumns: `${scaledImageCol}px 1fr`,
      }}
    >
      {showPlaceholder ? (
        /* Card-back placeholder (same contract as the board tile): a failed or
           unknown image shows the card back with the name, never a broken
           <img>. With the alternate source active the name shown is THAT
           source's. */
        <div className="preview-art-fallback" id="preview-image">
          <span>{String(displayName)}</span>
        </div>
      ) : (
        <img
          src={imageUrl}
          alt={displayName}
          id="preview-image"
          onError={() => {
            // Advance the chain: record the dead URL so the next render serves
            // the following candidate (or the placeholder when none is left).
            if (imageUrl) {
              setFailedUrls((prev) => (prev.includes(imageUrl) ? prev : [...prev, imageUrl]))
            }
          }}
        />
      )}
      <div className="card-meta">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'start' }}>
          <div className="card-name" id="preview-name">{displayName}</div>
          {card.manaCost && !alternateActive && (
            <div className="card-cost" id="preview-cost">
              <ManaCost cost={card.manaCost} />
            </div>
          )}
        </div>
        {/* With Alt+S (alternate source) active the meta panel names which
            source is being shown, so the swap is never ambiguous. */}
        {alternateActive && (
          <div className="card-type" id="preview-source" style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>
            {alternate.kind === 'face' ? '(other side)' : `(copy source: ${alternate.name})`}
          </div>
        )}
        {!alternateActive && originalCardName && (
          <div className="card-type" style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>
            (copy of {originalCardName})
          </div>
        )}
        {!alternateActive && isBackFace && (
          <div className="card-type" style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>
            (other side)
          </div>
        )}
        {!alternateActive && card.typeLine && (
          <div className="card-type" id="preview-type">{card.typeLine}</div>
        )}
        {!alternateActive && card.rules && card.rules.length > 0 && (
          <pre className="card-rules" id="preview-rules">
            {card.rules.map((rule, idx) => (
              <div key={idx}>
                <ManaText text={rule} cardName={cardName} />
              </div>
            ))}
          </pre>
        )}
        {!alternateActive && stats.length > 0 && (
          <div className="card-stats" id="preview-stats">
            {stats.join(' | ')}
          </div>
        )}
        {/* Hotkey hint — always visible so the keys are discoverable, not a
            hidden feature. A card with no alternate source greys the Alt+S half
            out rather than advertising a key that does nothing. */}
        <div className="card-preview-hotkeys" style={{ marginTop: '0.3rem', fontSize: '0.68rem', color: 'var(--text-muted)' }}>
          <span title="Enlarge preview">Alt+E enlarge{previewMode.enlarged ? ' (on)' : ''}</span>
          {' · '}
          {alternate ? (
            <span title="Show the alternate source">Alt+S alternate{alternateActive ? ' (on)' : ''}</span>
          ) : (
            <span style={{ opacity: 0.5 }} title="This card has no alternate source">Alt+S alternate (n/a)</span>
          )}
        </div>
      </div>
    </div>
  )
}
