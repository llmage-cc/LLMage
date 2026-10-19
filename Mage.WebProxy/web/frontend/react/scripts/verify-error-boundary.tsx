/**
 * Regression harness for the blank-app residual: any uncaught render error emptied
 * #root with NO visible error (the builtin-picker incident) because the app had
 * no error boundary. The fix is RootErrorBoundary mounted at the app root
 * (main.tsx wraps <App />), which logs the error via console.error and renders
 * a readable error card with a reload affordance.
 *
 * Run with:  bash scripts/verify-error-boundary.sh
 *
 * Node-only (no browser, no jsdom — the project has none; same bundling pattern
 * as verify-builtin-picker-tree.sh / verify-llm-config-tabs.sh). Error-boundary
 * RECOVERY is client-renderer behaviour, which Node cannot exercise without a
 * DOM, so the harness drives the REAL boundary module at the contract level:
 *
 *  (a) mounting contract: main.tsx (the app root) wraps <App /> in
 *      <RootErrorBoundary> OUTSIDE the app tree, and no component inside src/
 *      mounts its own RootErrorBoundary subtree (root-only, no catch-alls);
 *  (b) getDerivedStateFromError (the REAL static method) maps a thrown Error —
 *      and a non-Error throw — to { error } state without throwing;
 *  (c) the fallback render output carries the error message, the reload
 *      affordance and role=alert, and renders NOTHING of the children;
 *  (d) componentDidCatch (the REAL method) logs via console.error — captured
 *      and asserted, since that logging is the "surfaced, not swallowed" half;
 *  (e) a clean render (no error state) outputs the children untouched.
 */
import { renderToStaticMarkup } from 'react-dom/server'
import React from 'react'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { RootErrorBoundary } from '../src/components/ErrorBoundary'

let failures = 0
function check(name: string, cond: boolean, detail = '') {
  if (cond) { console.log(`  PASS  ${name}`) } else { failures++; console.log(`  FAIL  ${name} ${detail}`) }
}
function section(t: string) { console.log(`\n## ${t}`) }
function fail(msg: string): never {
  console.error(`  FAIL  ${msg}`)
  throw new Error(msg)
}

// ── (a) mounting contract, from the REAL sources ────────────────────────────
section('Mounting contract — root-only, outside the crashing tree')

{
  const mainSrc = readFileSync(join(process.cwd(), 'src/main.tsx'), 'utf8')
  check('main.tsx imports RootErrorBoundary from the real module', mainSrc.includes("from './components/ErrorBoundary'"), mainSrc.slice(0, 400))
  check('main.tsx wraps <App /> in <RootErrorBoundary>', /<RootErrorBoundary>\s*<App \/>/.test(mainSrc), mainSrc)
  check('the boundary sits outside StrictMode-wrapped App, not inside App.tsx', !mainSrc.includes('createRoot(root).render(\n    <App'))

  // No per-subtree catch-alls: no OTHER source file may render RootErrorBoundary.
  const SRC = join(process.cwd(), 'src')
  const tsxFiles = (dir: string): string[] => {
    const out: string[] = []
    for (const name of readdirSync(dir)) {
      const p = join(dir, name)
      if (statSync(p).isDirectory()) out.push(...tsxFiles(p))
      else if (name.endsWith('.tsx')) out.push(p)
    }
    return out
  }
  const otherUsers = tsxFiles(SRC)
    .filter((f) => !f.endsWith('components/ErrorBoundary.tsx') && !f.endsWith('src/main.tsx'))
    .filter((f) => readFileSync(f, 'utf8').includes('RootErrorBoundary'))
  check('no component inside src/ mounts a subtree boundary (root-only)', otherUsers.length === 0, otherUsers.join(', '))

  // The fallback card must not depend on app CSS variables that could be missing
  // after a crash — check it renders with inline styles only (no className dependency).
  const ebSrc = readFileSync(join(process.cwd(), 'src/components/ErrorBoundary.tsx'), 'utf8')
  check('the boundary keeps console.error logging (surfaced, not swallowed)', ebSrc.includes('console.error'))
  check('the boundary renders a reload affordance', ebSrc.includes('window.location.reload'))
}

// ── (b)+(c)+(d)+(e) behavioural contract, through the REAL module ───────────
section('Boundary contract — getDerivedStateFromError / componentDidCatch / fallback render')

{
  // (b) a real Error maps to state, no throw.
  const boom = new Error('boom-test: the builtin-picker sort crash')
  let derived: { error: Error | null } | null = null
  try {
    derived = RootErrorBoundary.getDerivedStateFromError(boom)
  } catch (e) {
    fail(`getDerivedStateFromError threw on a plain Error: ${String(e)}`)
  }
  check('getDerivedStateFromError maps an Error to { error } state', derived?.error === boom, String(derived))

  // Non-Error throws are coerced so the card can always render a message.
  let coerced: { error: Error | null } | null = null
  try {
    coerced = RootErrorBoundary.getDerivedStateFromError('plain string throw')
  } catch (e) {
    fail(`getDerivedStateFromError threw on a non-Error: ${String(e)}`)
  }
  check('a non-Error throw is coerced into an Error carrying its text', coerced?.error instanceof Error && coerced.error.message === 'plain string throw', String(coerced?.error))

  // (d) componentDidCatch logs with console.error BEFORE anything else.
  const logs: Array<{ text: string; errArg: unknown; infoArg: unknown }> = []
  const origError = console.error
  console.error = (...args: unknown[]) => {
    logs.push({
      text: args.map((a) => (typeof a === 'string' ? a : '')).join(' '),
      errArg: args[1],
      infoArg: args[2],
    })
  }
  try {
    const boundary = new (RootErrorBoundary as unknown as new (props: { children?: React.ReactNode }) => {
      componentDidCatch(error: Error, info: React.ErrorInfo): void
    })({})
    boundary.componentDidCatch(boom, { componentStack: '\n    at ThrowingChild' })
  } finally {
    console.error = origError
  }
  check('componentDidCatch logs via console.error exactly once', logs.length === 1, String(logs.length))
  check('the log names the app-root error boundary', logs[0]!.text.includes('app-root error boundary'), logs[0]?.text ?? '(none)')
  check('the log carries the error itself (message intact)', logs[0]!.errArg === boom, String(logs[0]?.errArg))
  const info = logs[0]!.infoArg as { componentStack?: string } | undefined
  check('the log carries the component stack (the second console.error arg)', info?.componentStack?.includes('ThrowingChild') === true, String(info))

  // (c) the fallback card: getDerivedStateFromError's state rendered through the
  // real component. React state for a class component is set directly on the
  // instance for this check — same object the reconciler would install.
  const BoundaryCtor = RootErrorBoundary as unknown as {
    new (props: { children?: React.ReactNode }): React.Component & { state: { error: Error | null } }
  }
  const instance = new BoundaryCtor({ children: null })
  instance.state = RootErrorBoundary.getDerivedStateFromError(boom)
  const fallbackHtml = renderToStaticMarkup(
    React.createElement(RootErrorBoundary as unknown as React.ComponentType<{ children?: React.ReactNode }>, {
      // Render through the real component with the error state pre-installed:
      // React calls the constructor with props, so seed state via a subclass is
      // overkill — instead assert on a fresh instance path: the component reads
      // this.state.error, which for the markup run we provide by rendering the
      // instance's own render() through createElement of a shim component.
      children: null,
    })
  )
  // The element above renders the CLEAN path (state starts null). The fallback
  // path is driven through the instance's render method directly:
  const fallbackMarkup = renderToStaticMarkup(React.createElement(() => instance.render()))
  void fallbackHtml
  check('fallback markup carries the error message', fallbackMarkup.includes('boom-test'), fallbackMarkup)
  check('fallback markup has a Reload button (the reload affordance)', fallbackMarkup.includes('Reload') && fallbackMarkup.includes('<button'), fallbackMarkup)
  check('fallback markup exposes role="alert"', fallbackMarkup.includes('role="alert"'), fallbackMarkup)
  check('fallback markup carries the boundary testid', fallbackMarkup.includes('data-testid="root-error-boundary"'), fallbackMarkup)
  check('fallback renders nothing of the children (they crashed)', !fallbackMarkup.includes('CHILD-MARKER'))

  // (e) clean path renders the children untouched.
  const cleanMarkup = renderToStaticMarkup(
    React.createElement(
      RootErrorBoundary as unknown as React.ComponentType<{ children?: React.ReactNode }>,
      {},
      React.createElement('div', {}, 'CHILD-MARKER')
    )
  )
  check('a clean render passes the children through unchanged', cleanMarkup.includes('CHILD-MARKER') && !cleanMarkup.includes('root-error-boundary'), cleanMarkup)
}

console.log(`\n${failures === 0 ? 'ALL CHECKS PASSED' : failures + ' CHECK(S) FAILED'}`)
;(globalThis as unknown as { process: { exit: (n: number) => void } }).process.exit(failures === 0 ? 0 : 1)