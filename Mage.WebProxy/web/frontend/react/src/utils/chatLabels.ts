/**
 * Chat-line labeling shared by BOTH web chat surfaces (the in-game rail's Chat
 * tab and the lobby's Talk tab). Pure, no React, no store — exercised directly
 * by scripts/verify-chat-labels.ts under Node.
 *
 * WHY (desktop label parity): the desktop client prefixes every
 * private message with its direction, and the web rendered only the bare name.
 * ChatPanelBasic.addMessage (Mage.Client/.../chat/ChatPanelBasic.java:245-259):
 *
 *     if (messageType == MessageType.WHISPER_FROM) username = "Whisper from " + username;
 *     if (messageType == MessageType.WHISPER_TO)   username = "Whisper to " + username;
 *
 * and the name it prefixes is the ChatMessage's `username` field, which the
 * server sets per direction in ChatSession.broadcastWhisperToUser
 * (Mage.Server/.../ChatSession.java:129-140):
 *
 *   WHISPER_FROM → new ChatMessage(fromUser.getName(), …)   — the SENDER
 *   WHISPER_TO   → new ChatMessage(toUser.getName(), …)     — the RECIPIENT
 *
 * The proxy forwards both the name (`from`) and the raw server enum name
 * (`chatType`, lower-cased: 'whisper_from' / 'whisper_to' — WebProxyClient
 * .handleChatMessage:1147-1152), so the web derives the same labels from the
 * same wire data — no wire or server change, and the wording is identical to
 * the desktop's.
 *
 * FAIL-NEUTRAL on any other/absent chatType: the plain sender name is returned,
 * with no direction prefix. A type this module does not recognize must never be
 * labeled a whisper (that would invent sender/recipient semantics it cannot
 * know); it renders exactly as it did before this helper existed.
 */

/**
 * The label a chat line renders before its text: the author's name, prefixed
 * with the whisper direction when (and only when) the line is a private-message
 * frame. The `: ` separator is the caller's (both surfaces and the desktop put
 * it outside this text — ChatPanelBasic's `userSeparator`).
 *
 * @param event anything carrying the wire `chatType` and the message `from`
 * @returns e.g. 'Whisper from Ket' | 'Whisper to Ket' | 'Ket'
 */
export function chatFromLabel(event: { chatType?: string | null; from?: string | null }): string {
  const from = event.from || '?'
  switch ((event.chatType ?? '').toLowerCase()) {
    case 'whisper_from':
      return 'Whisper from ' + from
    case 'whisper_to':
      return 'Whisper to ' + from
    default:
      return from
  }
}
