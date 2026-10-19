import { useGameStore } from '@/store/gameStore'

/**
 * User request dialog — mirrors the Java client's UserRequestDialog.
 * Shown for USER_REQUEST_DIALOG callbacks (e.g. rollback-permission requests).
 * Renders the server-provided buttons; clicking one sends the corresponding
 * PlayerAction back via user_request_response.
 */
export function UserRequestDialog() {
  const userRequest = useGameStore((s) => s.userRequest)
  const respondToUserRequest = useGameStore((s) => s.respondToUserRequest)

  if (!userRequest) return null

  const buttons: Array<{ text: string; action: string }> = []
  if (userRequest.button1Text && userRequest.button1Action) {
    buttons.push({ text: userRequest.button1Text, action: userRequest.button1Action })
  }
  if (userRequest.button2Text && userRequest.button2Action) {
    buttons.push({ text: userRequest.button2Text, action: userRequest.button2Action })
  }

  return (
    <div className="modal-overlay" style={{ display: 'flex' }}>
      <div className="modal-content" style={{ maxWidth: '500px' }}>
        <div className="modal-title" style={{ fontSize: '1.2rem' }}>
          {userRequest.title || 'Request'}
        </div>

        {userRequest.message && (
          <div style={{ margin: '0.5rem 0', fontSize: '0.95rem', color: 'var(--text-primary)' }}>
            {userRequest.message}
          </div>
        )}

        {userRequest.relatedUserName && (
          <div style={{ margin: '0.25rem 0', fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
            From: {userRequest.relatedUserName}
          </div>
        )}

        <div style={{ marginTop: '1rem', display: 'flex', gap: '0.5rem' }}>
          {buttons.map((b) => (
            <button
              key={b.action}
              className="btn btn-primary"
              onClick={() => respondToUserRequest(b.action)}
              data-explain="user-request-response"
            >
              {b.text}
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}
