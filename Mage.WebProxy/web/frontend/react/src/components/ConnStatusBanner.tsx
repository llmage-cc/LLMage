import type { ConnectionStatus } from '@/types'

interface ConnStatusBannerProps {
  status: ConnectionStatus
}

/** Connection status banner — shown during reconnect/resync. */
export default function ConnStatusBanner({ status }: ConnStatusBannerProps) {
  const labels: Record<ConnectionStatus, string> = {
    '': '',
    reconnecting: 'Reconnecting...',
    resyncing: 'Resyncing game state...',
    connection_lost: 'Connection lost. Attempting to reconnect...',
  }

  const text = labels[status]
  if (!text) return null

  return (
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        background: 'rgba(255, 193, 7, 0.9)',
        color: '#333',
        padding: '8px 16px',
        textAlign: 'center',
        fontWeight: 600,
        zIndex: 1500,
      }}
    >
      {text}
    </div>
  )
}