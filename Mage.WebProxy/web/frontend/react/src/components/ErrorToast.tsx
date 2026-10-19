import { useState } from 'react'
import { useGameStore } from '@/store/gameStore'

interface ErrorToastProps {
  message: string
  /**
   * The optional details/stack the proxy supplied. '' (or
   * absent) renders no collapsible section at all — the toast is exactly the
   * message-only toast in that case.
   */
  details?: string | undefined
}

/**
 * Error toast — shown briefly when an error occurs.
 *
 * The toast auto-hides at 5 s and previously showed the
 * message only. It now also:
 *   - offers COPY (copy the message + details to the clipboard — the desktop
 *     error dialogs' copy affordance for pasting a bug report),
 *   - renders a COLLAPSIBLE details/stack section when the proxy supplied one
 *     (the proxy's `error`/`server_error`/`callback_error`/`game_error` frames
 *     may carry `details` — a server stack / cause chain),
 *   - SUSPENDS the auto-hide while the cursor is over the toast or the details
 *     are expanded, so the Copy button and the stack stay reachable (the store's
 *     errorToastHeld; a plain 5 s timer would make both unusable).
 */
export default function ErrorToast({ message, details }: ErrorToastProps) {
  const setErrorToastHeld = useGameStore((s) => s.setErrorToastHeld)
  const showError = useGameStore((s) => s.showError)
  const [expanded, setExpanded] = useState(false)
  const [copied, setCopied] = useState(false)

  const hasDetails = !!details && details.trim().length > 0

  /** Hover/expand hold — released when the cursor leaves and nothing is expanded. */
  const applyHold = (hovering: boolean) => {
    setErrorToastHeld(hovering || expanded)
  }

  const handleCopy = () => {
    const text = hasDetails ? `${message}\n\n${details}` : message
    if (!navigator.clipboard?.writeText) {
      showError('Clipboard unavailable in this browser')
      return
    }
    navigator.clipboard.writeText(text).then(
      () => {
        setCopied(true)
        // Re-arm the hold so the "Copied" feedback stays visible a moment.
        setErrorToastHeld(true)
        window.setTimeout(() => {
          setCopied(false)
          setErrorToastHeld(false)
        }, 1200)
      },
      (err) => showError(`Copy failed: ${(err as Error).message}`),
    )
  }

  return (
    <div
      style={{
        position: 'fixed',
        bottom: '20px',
        left: '50%',
        transform: 'translateX(-50%)',
        background: 'var(--error-color)',
        color: 'white',
        padding: '12px 18px',
        borderRadius: '6px',
        zIndex: 2000,
        maxWidth: '80vw',
        textAlign: 'center',
      }}
      onMouseEnter={() => applyHold(true)}
      onMouseLeave={() => applyHold(false)}
    >
      <div>{message}</div>
      <div
        style={{
          marginTop: '0.5rem',
          display: 'flex',
          gap: '0.5rem',
          justifyContent: 'center',
          alignItems: 'center',
        }}
      >
        <button
          className="btn btn-secondary btn-sm"
          onClick={handleCopy}
          data-explain="error-toast-copy"
          style={{ fontSize: '0.72rem' }}
        >
          {copied ? 'Copied' : 'Copy'}
        </button>
        {hasDetails && (
          <button
            className="btn btn-secondary btn-sm"
            onClick={() => {
              const next = !expanded
              setExpanded(next)
              setErrorToastHeld(next)
            }}
            data-explain="error-toast-details"
            aria-expanded={expanded}
            style={{ fontSize: '0.72rem' }}
          >
            {expanded ? '▾ Details' : '▸ Details'}
          </button>
        )}
      </div>
      {expanded && hasDetails && (
        <pre
          data-testid="error-toast-stack"
          style={{
            marginTop: '0.5rem',
            maxHeight: '30vh',
            overflow: 'auto',
            textAlign: 'left',
            fontSize: '0.7rem',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            background: 'rgba(0, 0, 0, 0.35)',
            borderRadius: '4px',
            padding: '0.5rem',
          }}
        >
          {details}
        </pre>
      )}
    </div>
  )
}
