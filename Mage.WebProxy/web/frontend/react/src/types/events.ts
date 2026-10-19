import type { EventCategory } from './enums'

/**
 * Event types — from WebProxyClient.java sendEvent() / sendGameEvent().
 */

/** Game event — from state.put("event"...) and "event" message type. */
export interface GameEvent {
  seq: number
  type: 'action' | 'talk'
  message: string
  category?: EventCategory
  from?: string
  /**
   * Raw XMage chat message type (lower-cased ChatMessage.MessageType enum name),
   * forwarded by WebProxyClient.handleChatMessage since the lobby-chat work:
   * 'talk' | 'whisper_from' | 'whisper_to' | 'user_info' | 'status' | 'game'.
   * Absent on events sent before that change — routeChat(undefined) → 'system'.
   */
  chatType?: string
}