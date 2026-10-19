import { useEffect, useState, useCallback } from 'react'
import { useGameStore } from '@/store/gameStore'
import { useContextMenu, ContextMenuShell } from '@/hooks/useContextMenu'
import { vhToCssPx } from '@/utils/viewport'
import {
  fetchTokenVersions,
  getTokenVersions,
  getOwnTokenArt,
  setOwnTokenArt,
  listSharedTokenArts,
  isOverrideOpponent,
  setOverrideOpponent,
  type TokenArtVersion,
} from '@/utils/tokenArt'

/**
 * Right-click menu for a token: pick which printing (art) to render.
 *
 * MOUNTED ONCE, in GameView and ReplayView (next to <CardContextMenu>) — it reads
 * the token and its engine set from the store's context-menu payload, exactly like
 * CardContextMenu/PhaseContextMenu/PlayerContextMenu do. It must NOT be rendered by
 * CardThumb: the registry's `isOpen` is global, so a per-tile render site put one
 * panel on every tile of the board — 34 simultaneous menus, each titled with its
 * own card's name, and the tapped ones (transform → their tile becomes the
 * containing block for `position: fixed`) landing off-screen. See

 *
 * Three distinct actions, matching the preference layers in utils/tokenArt.ts:
 *
 *  - **My art** — stored locally for the token TYPE, so every token of this name I
 *    control uses it, now and in later games.
 *  - **Share with the table** — sends the choice to the proxy, which relays it to the
 *    other players at this game so they see my chosen art on my tokens.
 *  - **Override the opponent's choice** — a viewer-side toggle. Off (default): an
 *    opponent's token shows THEIR shared art, and my preference applies only where
 *    they have not chosen one. On: my art wins everywhere, including on boards whose
 *    controller picked something else.
 *
 * The picked printing is applied to MY preference AND shared in one step when sharing
 * is on, because "I chose this art" almost always means "show everyone this art".
 */

/**
 * Rough panel extent, for ContextMenuShell's viewport clamp (CSS px).
 *
 * The width is exact (`.token-art-menu` is `width: 300px`). The height is the
 * panel's own maximum, NOT an estimate: `.token-art-menu` caps at `max-height:
 * 60vh` (game-renderer.css) and `vh` inside the zoomed root resolves against the
 * REAL viewport height, so 60vh in root CSS px == 0.45 × cssViewportHeight().
 * Declaring a flat larger number over-clamps: a click in the lower half of the
 * screen had the panel floating well above the cursor (measured).
 */
const PANEL_WIDTH = 300
const PANEL_MAX_HEIGHT_VH = 0.6

export function TokenArtMenu() {
  const { isOpen, close, position } = useContextMenu('token-art')
  const openMenu = useGameStore((s) => s.openContextMenu)
  // The store union narrows by kind: this component only renders when the open
  // menu IS the token-art menu, so the payload is TokenArtMenuData.
  const payload = isOpen && openMenu?.kind === 'token-art' ? openMenu : null
  const tokenName = payload?.tokenName ?? ''
  const engineSet = payload?.engineSet

  const [versions, setVersions] = useState<TokenArtVersion[] | null>(
    () => getTokenVersions(tokenName)?.versions ?? null,
  )
  const [loading, setLoading] = useState(false)
  const [note, setNote] = useState<string | null>(null)
  const [share, setShare] = useState(true)
  const [override, setOverride] = useState(() => isOverrideOpponent())
  const [ownUrl, setOwnUrl] = useState(() => getOwnTokenArt(tokenName) ?? null)
  /**
   * A replay has no table to relay to: the proxy accepts a preference
   * viewer-locally (WebProxySession.handleTokenArtPreference) but nothing is
   * shared — and saying "Share with the table" would promise something that
   * cannot happen. The local half (my art for this token type) still applies,
   * so the picker stays useful in a replay.
   */
  const inReplay = useGameStore((s) => s.inReplay)

  /**
   * The art the proxy picked as this token's default — the printing from the token's
   * own set when that set printed it. This is what "default" in the grid means, and
   * it is NOT necessarily the first entry (the list opens on the newest printing).
   */
  const engineDefaultUrl = getTokenVersions(tokenName)?.defaultUrl

  // A different token right-clicked while this menu is up: the panel is one mount
  // point, so its state must follow the payload rather than survive it. React
  // would otherwise keep the previous token's versions/preference (the component
  // does not unmount between two token-art opens).
  useEffect(() => {
    setVersions(getTokenVersions(tokenName)?.versions ?? null)
    setOwnUrl(getOwnTokenArt(tokenName) ?? null)
    setNote(null)
  }, [tokenName])

  const load = useCallback(
    () => {
      setLoading(true)
      const ws = useGameStore.getState().ws as { send?: (m: Record<string, unknown>) => void } | null
      if (!ws || typeof ws.send !== 'function') {
        setLoading(false)
        setNote('Not connected')
        return
      }
      fetchTokenVersions(tokenName, engineSet, ws.send.bind(ws) as (m: Record<string, unknown>) => void)
        .then((r) => {
          setVersions(r.versions)
          setLoading(false)
          if (r.versions.length === 0) {
            setNote(r.fetchFailed ? 'Could not reach Scryfall' : 'No art found for this token')
          } else {
            setNote(null)
          }
        })
        .catch(() => {
          setLoading(false)
          setNote('Lookup failed')
        })
    },
    [tokenName, engineSet],
  )

  // Open with the token's real printings; they arrive from cache instantly when the
  // tile already fetched them. The fetched result is written back through
  // setTokenVersions so other tiles of the same name benefit too.
  useEffect(() => {
    if (!tokenName) return
    if (versions === null) load()
  }, [tokenName, versions, load])

  const pick = useCallback(
    (v: TokenArtVersion) => {
      setOwnTokenArt(tokenName, v.url)
      setOwnUrl(v.url)
      if (share) {
        const ws = useGameStore.getState().ws as { send?: (m: Record<string, unknown>) => void } | null
        if (ws && typeof ws.send === 'function') {
          const msg: Record<string, unknown> = { type: 'token_art_preference', tokenName, url: v.url }
          if (v.setCode) msg.setCode = v.setCode
          if (v.collectorNumber) msg.collectorNumber = v.collectorNumber
          ws.send(msg)
        }
      }
      close()
    },
    [tokenName, share, close],
  )

  const clear = useCallback(() => {
    setOwnTokenArt(tokenName, null)
    setOwnUrl(null)
    const ws = useGameStore.getState().ws as { send?: (m: Record<string, unknown>) => void } | null
    if (ws && typeof ws.send === 'function') {
      ws.send({ type: 'token_art_preference', tokenName, url: null })
    }
    close()
  }, [tokenName, close])

  const sharedByOthers = tokenName ? listSharedTokenArts(tokenName).length > 0 : false

  return (
    <ContextMenuShell
      position={position}
      onClose={close}
      width={PANEL_WIDTH}
      height={vhToCssPx(PANEL_MAX_HEIGHT_VH)}
      className="token-art-menu-panel"
      ariaLabel={`Token art — ${tokenName}`}
    >
      <div className="token-art-menu">
        <div className="token-art-menu-title">Token art — {tokenName}</div>

        {inReplay ? (
          /* No table to share with during a replay: the choice is local, and a
             checked-but-inert "Share" box would be a lie. */
          <div className="token-art-menu-note">
            Replay viewing — the art is applied to your view (and saved for later
            games as your personal preference); there is no table to share with.
          </div>
        ) : (
          <label className="token-art-menu-row">
            <input type="checkbox" checked={share} onChange={(e) => setShare(e.target.checked)} />
            <span>Share with the table</span>
          </label>
        )}
        <label className="token-art-menu-row">
          <input
            type="checkbox"
            checked={override}
            onChange={(e) => {
              setOverride(e.target.checked)
              setOverrideOpponent(e.target.checked)
            }}
          />
          <span>Always use my art (overrides opponent&rsquo;s choice)</span>
        </label>

        {loading && versions === null && <div className="token-art-menu-note">Loading printings…</div>}
        {note && <div className="token-art-menu-note">{note}</div>}

        {versions && versions.length > 0 && (
          <div className="token-art-menu-grid">
            {versions.map((v) => (
              <button
                key={v.url}
                type="button"
                className={`token-art-menu-thumb${ownUrl === v.url ? ' selected' : ''}`}
                data-explain="token-art-pick"
                title={
                  (v.setName ?? v.setCode ?? v.source ?? '') +
                  (v.releasedAt ? ` (${v.releasedAt})` : '') +
                  ' — click to use this art'
                }
                onClick={() => pick(v)}
              >
                <img src={v.url} alt="" loading="lazy" draggable={false} />
                {v.setName && <span className="token-art-menu-set">{v.setName}</span>}
                {v.url === engineDefaultUrl && (
                  <span className="token-art-menu-badge">default</span>
                )}
              </button>
            ))}
          </div>
        )}

        <div className="token-art-menu-actions">
          {ownUrl && (
            <button type="button" className="token-art-menu-action" data-explain="token-art-reset" onClick={clear}>
              Reset to default
            </button>
          )}
        </div>

        {sharedByOthers && !override && (
          <div className="token-art-menu-note">
            A player at this table chose art for this token; it is used unless you
            override above.
          </div>
        )}
      </div>
    </ContextMenuShell>
  )
}
