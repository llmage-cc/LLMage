/**
 * Which open context menu belongs to which tile.
 *
 * The context-menu registry (store `openContextMenu`) is GLOBAL and holds one
 * entry at a time; its payloads scope ownership differently:
 *
 *  - 'token-art' (TokenArtMenuData) is NAME-scoped: the payload carries only the
 *    token's name, so any tile rendering that token name anchors the menu.
 *  - 'card' (CardMenuData) is INSTANCE-scoped: the payload carries the card, so
 *    match on id (name only as a fallback for cards without one).
 *
 * CardThumb's unmount cleanup uses this to close the menu ONLY when the
 * unmounting tile owns it. The unscoped version ("any tile unmounts while any
 * menu is open") let an unrelated tile's unmount clear a menu that was still
 * anchored — measured during replay playback, an unrelated Morska unmount
 * closed the open Clue Token art menu at a board push — a
 * token-selector flicker.
 */
export interface OpenMenuScope {
  kind?: string | undefined
  tokenName?: string | undefined
  card?: { id?: string | null | undefined; name?: string | undefined } | undefined
}

/**
 * Does the open menu belong to the tile identified by `name`/`id`?
 *
 * Only the owning tile may clear the registry on unmount; ownership is
 * name-scoped for token-art menus (a board push can re-key a token tile —
 * new id, same name — within one commit) and instance-scoped for card menus.
 */
export function menuOwnedByTile(
  open: OpenMenuScope | null | undefined,
  name: string,
  id: string | null,
): boolean {
  if (!open) return false
  if (open.kind === 'token-art') return open.tokenName === name
  if (open.kind === 'card') {
    if (!open.card) return false
    return id ? open.card.id === id : open.card.name === name
  }
  return false
}
