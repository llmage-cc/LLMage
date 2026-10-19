/**
 * Message-of-the-Day strip — stock parity with the desktop client's blue
 * bottom bar (TablesPanel jPanelBottom / labelMessageHeader
 * "Message of the Day:"). Renders nothing when the server has no MOTD
 * (lobbyMotd === null): the desktop client hides jPanelBottom in exactly that
 * case (TablesPanel.reloadServerMessages).
 *
 * The text is a PULL, delivered on the `users_list` response
 * (WebProxySession.handleGetRoomUsers → xmageSession.getServerMessages →
 * ServerMessagesUtil server.msg.txt lines) — the same strings the Swing client
 * shows. Renders stripped of server HTML.
 */
import { useGameStore } from '@/store/gameStore'
import { stripServerHtml } from '@/utils/textFormat'

export default function MotdStrip() {
  const motd = useGameStore((s) => s.lobbyMotd)
  if (!motd) return null
  return (
    <div className="lobby-motd" data-explain="lobby-motd">
      <span className="lobby-motd-label">Message of the Day</span>
      <span className="lobby-motd-text">{stripServerHtml(motd)}</span>
    </div>
  )
}