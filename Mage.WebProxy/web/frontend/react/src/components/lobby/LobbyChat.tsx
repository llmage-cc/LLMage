import { useState, useRef, useEffect } from 'react'
import { useGameStore } from '@/store/gameStore'
import { isSocketDown } from '@/utils/socketDown'
import { chatFromLabel } from '@/utils/chatLabels'
import type { GameEvent } from '@/types'

/**
 * Lobby chat panel — stock parity with the Swing client's room chat
 * (ChatPanelBasic): "Talk | System" tabs above a scrolling message list and an
 * input row. Lives in the lobby's right-hand column under the users panel.
 *
 * Sending uses the store's EXISTING `sendChat` action (RightRail's exact
 * contract) — the proxy's handleSendChat routes by in-game state, so while the
 * user is NOT in a game the line goes to the ROOM chat, which this panel also
 * renders (the proxy joins the room chat on connect since the lobby-chat work;
 * see WebProxySession.handleConnect).
 *
 * Incoming lines are routed into the two lists by the raw server messageType
 * (chatType) in lobbySlice.appendLobbyRoutedEvent — see utils/lobbyChat.routeChat.
 */
export default function LobbyChat() {
  const activeTab = useGameStore((s) => s.lobbyChatTab)
  const setActiveTab = useGameStore((s) => s.setLobbyChatTab)
  const talkMessages = useGameStore((s) => s.lobbyTalkMessages)
  const systemMessages = useGameStore((s) => s.lobbySystemMessages)
  const sendChat = useGameStore((s) => s.sendChat)
  const connected = useGameStore((s) => s.connected)
  const ws = useGameStore((s) => s.ws)

  const [input, setInput] = useState('')
  const listEndRef = useRef<HTMLDivElement>(null)
  const messages = activeTab === 'talk' ? talkMessages : systemMessages

  // Auto-scroll to the newest line (same behavior as the RightRail chat tab).
  useEffect(() => {
    listEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages.length, activeTab])

  const submit = () => {
    const text = input.trim()
    if (!text) return
    // The old guard (`!ws || !connected`) missed the exact case the
    // finding documented — the session still says connected but the SOCKET is
    // dead (reconnecting), so the line went into GameWebSocket.send and was
    // dropped with a console.warn only. Test the same predicate the lobby
    // banner uses, not just the connected flag.
    if (!ws || isSocketDown(useGameStore.getState())) {
      useGameStore.getState().showError('Not connected — chat unavailable')
      return
    }
    sendChat(text)
    setInput('')
  }

  return (
    <div className="lobby-chat" data-explain="lobby-chat">
      <div className="lobby-chat-tabs">
        <button
          className={`lobby-chat-tab${activeTab === 'talk' ? ' active' : ''}`}
          onClick={() => setActiveTab('talk')}
          data-explain="lobby-chat-tab-talk"
        >
          Talk
        </button>
        <button
          className={`lobby-chat-tab${activeTab === 'system' ? ' active' : ''}`}
          onClick={() => setActiveTab('system')}
          data-explain="lobby-chat-tab-system"
        >
          System
        </button>
      </div>
      <div className="lobby-chat-list">
        {messages.length === 0 && (
          <div className="lobby-chat-empty">
            {activeTab === 'talk' ? 'No talk messages yet.' : 'No system messages.'}
          </div>
        )}
        {messages.map((event, i) => (
          <LobbyChatLine key={event.seq ?? i} event={event} />
        ))}
        <div ref={listEndRef} />
      </div>
      <div className="lobby-chat-input-row">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') submit()
          }}
          placeholder={connected ? 'Send a message...' : 'Connect to chat...'}
          aria-label="Lobby chat message"
        />
        <button className="btn btn-primary btn-sm" onClick={submit} data-explain="lobby-chat-send">
          Send
        </button>
      </div>
    </div>
  )
}

/**
 * One chat line — "from: text", the same shape the RightRail chat tab renders.
 * The name carries the desktop's whisper direction prefix when the line is a
 * private message (`chatFromLabel`, utils/chatLabels): the desktop
 * renders "Whisper from <sender>" / "Whisper to <recipient>"
 * (ChatPanelBasic.java:245-259) in this same single chat stream.
 */
function LobbyChatLine({ event }: { event: GameEvent }) {
  return (
    <div className={`lobby-chat-entry lobby-chat-${event.chatType ?? event.type}`}>
      <span className="lobby-chat-from">{chatFromLabel(event)}:</span> {event.message}
    </div>
  )
}