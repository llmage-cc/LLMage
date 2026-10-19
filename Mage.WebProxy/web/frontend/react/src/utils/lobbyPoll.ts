/**
 * Lobby table-list auto-refresh.
 *
 * The grid used to refresh ONLY on a manual click ("Refresh Tables"), on
 * connect, and after create/match flows — so another player's new table did
 * not appear until the user clicked. The poll below fixes that.
 *
 * The gate is a PURE predicate so a harness can pin the behavior without a
 * DOM, and the interval lives here so the source scan and the component
 * cannot drift apart.
 *
 * Timer philosophy (same as the seated 2 s backstop in LobbyView.tsx):
 * Chromium degrades background-tab timers to roughly one tick per minute, so
 * a poll is never load-bearing — the visibilitychange catch-up in LobbyView
 * is the other half.
 */

/** Grid auto-refresh cadence. */
export const GRID_POLL_MS = 15000

export interface GridPollState {
  connected: boolean
  joinWaiting: boolean
  showSetup: boolean
  showTournamentSetup: boolean
}

/**
 * Poll the table list while the lobby grid is actually the visible surface:
 * connected, not seated at a table (the grid is hidden and the 2 s backstop
 * covers that case), and no setup/tournament dialog covering it.
 */
export function shouldPollGrid(s: GridPollState): boolean {
  return s.connected && !s.joinWaiting && !s.showSetup && !s.showTournamentSetup
}
