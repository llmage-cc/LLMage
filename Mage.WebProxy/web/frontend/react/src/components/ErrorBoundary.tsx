import React from 'react'

/**
 * App-root error boundary. Before it, #root emptied with no
 * visible error when a render threw — the builtin-picker incident,
 * reproduced by scripts/verify-builtin-picker-tree.ts's root-cause
 * check.
 *
 * This surfaces failure, it does not swallow it:
 *  - the error is logged with console.error (visible in devtools and in any
 *    console-capturing harness), THEN the fallback card renders;
 *  - the boundary exists ONLY at the app root (mounted in main.tsx around
 *    <App />). No per-subtree catch-alls — a subtree bug must still be fixed,
 *    not hidden behind a nested boundary;
 *  - the card shows the real error message plus a reload affordance. There is
 *    deliberately no "try to recover in place" path: a thrown render means the
 *    component tree is in an unknown state, and a soft reset would mask the
 *    bug (Fail Fast).
 */

interface RootErrorBoundaryProps {
  children: React.ReactNode
}

interface RootErrorBoundaryState {
  error: Error | null
}

export class RootErrorBoundary extends React.Component<
  RootErrorBoundaryProps,
  RootErrorBoundaryState
> {
  state: RootErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: unknown): RootErrorBoundaryState {
    // React contract: only real Error instances flow here as errors; anything
    // else is coerced so the card can always render a message.
    const err = error instanceof Error ? error : new Error(String(error))
    return { error: err }
  }

  componentDidCatch(error: Error, info: React.ErrorInfo): void {
    // Surfaced, never swallowed: the full error + component stack go to the
    // console BEFORE the fallback card renders.
    console.error('Uncaught render error caught by the app-root error boundary:', error, info)
  }

  private reload = (): void => {
    window.location.reload()
  }

  render(): React.ReactNode {
    const { error } = this.state
    if (!error) return this.props.children
    return (
      <div
        role="alert"
        data-testid="root-error-boundary"
        style={{
          display: 'flex',
          flexDirection: 'column',
          gap: '0.75rem',
          alignItems: 'flex-start',
          justifyContent: 'center',
          height: '100%',
          padding: '1.5rem',
          boxSizing: 'border-box',
        }}
      >
        <h1 style={{ fontSize: '1.2rem', color: 'var(--color-danger)' }}>
          Something went wrong
        </h1>
        <p style={{ fontSize: '0.9rem', color: 'var(--text-secondary)', maxWidth: '60ch' }}>
          The web client hit an unexpected error while rendering and stopped. The error is
          logged in the browser console.
        </p>
        <pre
          style={{
            fontSize: '0.8rem',
            color: 'var(--text-primary)',
            background: 'var(--bg-elevated)',
            border: '1px solid var(--border-default)',
            borderRadius: '6px',
            padding: '0.75rem',
            maxWidth: '80ch',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            margin: 0,
          }}
        >
          {error.message}
        </pre>
        <button type="button" className="btn btn-secondary" onClick={this.reload} data-explain="error-boundary-reload">
          Reload
        </button>
      </div>
    )
  }
}

export default RootErrorBoundary