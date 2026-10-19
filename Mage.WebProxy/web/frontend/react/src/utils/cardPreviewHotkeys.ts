/**
 * Card-preview hotkeys + the alternate-source model.
 *
 * WHY THIS MODULE EXISTS
 * ----------------------
 * The desktop client's enlarged-card view has three modes
 * (`mage.constants.EnlargeMode`: NORMAL / ALTERNATE / COPY) and swaps between
 * them on the mouse wheel and modifier keys (MageActionCallback.enlargeCard,
 * `handleMouseMoveOverCard` — wheel-up shows NORMAL, wheel-down shows ALTERNATE;
 * DragCardGrid tracks the ALT key globally). The web preview had NO modifier-key
 * handling at all (`grep -rn "altKey" src/` → 0 hits), so a copied permanent or a
 * transform card could only ever be read as what it currently is.
 *
 * The two hotkeys:
 *   - Alt+E — ENLARGE: a bigger, image-first preview panel (the desktop's
 *     big-card view).
 *   - Alt+S — ALTERNATE SOURCE: show the card's OTHER source instead of the
 *     current face. That is exactly what the desktop's ALTERNATE mode shows
 *     ("shows the alternative card … (copy source, other flip side, other
 *     transformed side)", MageActionCallback:91-92).
 *
 * Everything here is PURE (no DOM, no store, no React) so the harness exercises
 * the exact decisions the component makes — same convention as
 * previewArtCandidates / endDialogKind.
 */

import type { Card, Permanent, Commander } from '@/types'

/** Which alternate source a card has, if any. */
export type AlternateKind = 'face' | 'copy'

export interface AlternateSource {
  /** The name to display (and to resolve art from) for the alternate source. */
  name: string
  kind: AlternateKind
}

/** The two independent preview toggles. */
export interface PreviewMode {
  /** Alt+E — enlarged (bigger, image-first panel). */
  enlarged: boolean
  /** Alt+S — alternate source when the card has one. */
  alternate: boolean
}

export const DEFAULT_PREVIEW_MODE: Readonly<PreviewMode> = Object.freeze({
  enlarged: false,
  alternate: false,
})

export type PreviewHotkey = 'enlarge' | 'alternate'

/**
 * Map a keydown (key + altKey) to a preview action, or null. Only the ALT
 * chord triggers a preview action — a bare 'e'/'s' is ordinary typing and must
 * never be hijacked. Matches on the un-shifted letter so Alt+Shift+E etc. also
 * work; `key` is case-insensitive ('E' when shift is held).
 */
export function previewHotkey(key: string, altKey: boolean): PreviewHotkey | null {
  if (!altKey) return null
  const k = (key || '').toLowerCase()
  if (k === 'e') return 'enlarge'
  if (k === 's') return 'alternate'
  return null
}

/** Toggle one mode flag, returning a fresh object (never mutates). */
export function applyPreviewHotkey(mode: PreviewMode, hotkey: PreviewHotkey): PreviewMode {
  return hotkey === 'enlarge'
    ? { ...mode, enlarged: !mode.enlarged }
    : { ...mode, alternate: !mode.alternate }
}

/**
 * The card's alternate source, in the desktop's own preference order
 * (MageActionCallback's ALTERNATE branch): a real OTHER FACE (transform/MDFC/
 * flip/ meld — `secondFace`) first, else the COPY SOURCE (`originalCard`, which
 * the serializer sets from `perm.getOriginal()` for a modified permanent or
 * `card.getAlternateName()` for an alternate-face name). Null when the card has
 * neither — then Alt+S has nothing to show and is a no-op, exactly as the
 * desktop's ALTERNATE mode shows nothing for a plain card.
 */
export function alternateSourceOf(
  card: Card | Permanent | Commander | null,
): AlternateSource | null {
  if (!card) return null
  const secondFace = (card as { secondFace?: { name?: string } }).secondFace
  if (secondFace && typeof secondFace.name === 'string' && secondFace.name.trim() !== '') {
    return { name: secondFace.name, kind: 'face' }
  }
  const original = (card as { originalCard?: unknown }).originalCard
  if (typeof original === 'string' && original.trim() !== '') {
    return { name: original.trim(), kind: 'copy' }
  }
  return null
}

/** True when Alt+S would do something for this card. */
export function hasAlternateSource(card: Card | Permanent | Commander | null): boolean {
  return alternateSourceOf(card) != null
}

/**
 * The name the panel should render for the current mode. When alternate is on
 * AND the card has a source, that source's name; otherwise the card's own name.
 * (A toggled-on mode with nothing to show falls back to the card's name — the
 * panel never renders an empty title.)
 */
export function previewDisplayName(
  card: Card | Permanent | Commander | null,
  mode: PreviewMode,
): string {
  if (!card) return 'Unknown'
  if (mode.alternate) {
    const src = alternateSourceOf(card)
    if (src) return src.name
  }
  return card.name || 'Unknown'
}

/**
 * The preview panel geometry for a given setting + mode. Enlarged scales the
 * panel and the image column by `ENLARGE_FACTOR` on top of the user's hover-size
 * setting, so Alt+E reads as "big card" without a second setting to learn.
 * Kept here (pure) so the harness asserts the exact numbers the inline styles
 * use.
 */
export const ENLARGE_FACTOR = 1.6
export const BASE_PANEL_WIDTH_PX = 540
export const BASE_IMAGE_COL_PX = 220

export function previewPanelGeometry(
  cardPreviewScale: number,
  mode: PreviewMode,
): { panelWidth: string; imageCol: number } {
  const factor = cardPreviewScale * (mode.enlarged ? ENLARGE_FACTOR : 1)
  return {
    panelWidth: `min(${Math.round(BASE_PANEL_WIDTH_PX * factor)}px, calc(var(--vw) - 24px))`,
    imageCol: Math.round(BASE_IMAGE_COL_PX * factor),
  }
}
