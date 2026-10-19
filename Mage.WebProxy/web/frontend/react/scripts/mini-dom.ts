/**
 * Mini-DOM shim for the explain-coverage harness (scripts/verify-explain-coverage.*).
 *
 * `src/hints/explain.ts` walks a live DOM, and the project has no jsdom/happy-dom dependency
 * (node_modules holds only vite/react/zustand/rolldown). Rather than add one, this file
 * implements exactly the DOM surface explain.ts uses — tagName, classList, attributes,
 * textContent, closest(), matches() and a bounding rect — over element descriptors that are
 * written to mirror the REAL rendered markup.
 *
 * It is deliberately NOT a general-purpose DOM: an unimplemented selector simply does not match,
 * so a scene that out-grows this shim fails loudly (0 controls found) instead of silently
 * passing. The live-browser walk (`window.__xmageHints.report()`) is the other half of the
 * evidence and needs no shim at all.
 */

export interface ElSpec {
  tag: string
  cls?: string
  text?: string
  attrs?: Record<string, string>
  /** True when the element renders with a non-zero box (the audit skips zero-size nodes). */
  rendered?: boolean
}

/** One element of the shim tree. */
export class MiniEl {
  readonly tagName: string
  readonly classList: { contains: (c: string) => boolean }
  readonly className: string
  readonly textContent: string
  private readonly attrs: Record<string, string>
  private readonly rendered: boolean
  readonly parent: MiniEl | null

  constructor(spec: ElSpec, parent: MiniEl | null = null) {
    this.tagName = spec.tag.toUpperCase()
    this.className = spec.cls ?? ''
    this.textContent = spec.text ?? ''
    this.attrs = spec.attrs ?? {}
    this.rendered = spec.rendered !== false
    this.parent = parent
    const classes = this.className.split(/\s+/).filter(Boolean)
    this.classList = { contains: (c: string) => classes.includes(c) }
  }

  get id(): string {
    return this.attrs.id ?? ''
  }

  getAttribute(name: string): string | null {
    return this.attrs[name] ?? null
  }

  getBoundingClientRect(): { x: number; y: number; width: number; height: number; top: number; right: number; bottom: number; left: number } {
    const w = this.rendered ? 80 : 0
    const h = this.rendered ? 24 : 0
    return { x: 0, y: 0, width: w, height: h, top: 0, right: w, bottom: h, left: 0 }
  }

  /** Compound-selector match: `tag`, `.class`, `[attr]`, `[attr="v"]` and any combination. */
  matches(selector: string): boolean {
    const parts = selector.trim().split(/(?=[.[])/)
    for (const part of parts) {
      const p = part.trim()
      if (!p) continue
      if (p.startsWith('.')) {
        if (!this.classList.contains(p.slice(1))) return false
      } else if (p.startsWith('[')) {
        const m = /^\[([\w-]+)(?:="([^"]*)")?\]$/.exec(p)
        if (!m) return false
        const [, name, value] = m
        const actual = this.getAttribute(name!)
        if (actual == null) return false
        if (value !== undefined && actual !== value) return false
      } else if (!this.tagName.startsWith(p.toUpperCase())) {
        return false
      }
    }
    return true
  }

  closest(selectorList: string): MiniEl | null {
    const selectors = selectorList.split(',').map((s) => s.trim())
    let node: MiniEl | null = this
    while (node) {
      if (selectors.some((s) => node!.matches(s))) return node
      node = node.parent
    }
    return null
  }

  querySelectorAll(selectorList: string): MiniEl[] {
    const selectors = selectorList.split(',').map((s) => s.trim())
    const out: MiniEl[] = []
    const walk = (n: MiniEl) => {
      for (const child of n.children) {
        if (selectors.some((s) => child.matches(s))) out.push(child)
        walk(child)
      }
    }
    walk(this)
    return out
  }

  readonly children: MiniEl[] = []
}

/** Build a shim tree from a nested descriptor. */
export function el(spec: ElSpec, children: Array<ElSpec | MiniEl> = []): MiniEl {
  const node = new MiniEl(spec)
  for (const child of children) {
    const c = child instanceof MiniEl ? child : el(child)
    node.children.push(c)
    ;(c as unknown as { parent: MiniEl | null }).parent = node
  }
  return node
}

/** Root wrapper — `querySelectorAll` sees the whole tree. */
export function root(children: Array<ElSpec | MiniEl>): MiniEl {
  const node = new MiniEl({ tag: 'body', rendered: false })
  for (const child of children) {
    const c = child instanceof MiniEl ? child : el(child)
    node.children.push(c)
    ;(c as unknown as { parent: MiniEl | null }).parent = node
  }
  return node
}
