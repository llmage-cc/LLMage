/**
 * Button hover explanations — the SINGLE shared source of "what does this control do?".
 *
 * WHY: "Have a hover effect on every button that explains thoroughly what it does."
 * Every control in the web client resolves its explanation through this module, so the
 * client never grows 135 bespoke tooltips.
 *
 * ── THE MECHANISM ──
 *
 * `Explainer.tsx` holds ONE delegated listener pair (mouseover/mouseout + focusin/focusout)
 * and renders ONE styled tooltip. On hover/focus it asks `resolveExplanation(el)` here.
 *
 * Resolution order (first hit wins):
 *   1. `data-explain="<id>"` on the element (or an ancestor) → `EXPLAIN[id]`. The id form is
 *      for controls whose explanation is specific to that one button.
 *   2. `data-explain="@<literal text>"` → the literal text, for explanations that are computed
 *      by the component (e.g. a server-supplied label).
 *   3. `RULES` — family matchers keyed on structure (`.prompt-choices button`, `.phase-btn`,
 *      `.float-window-close`, …). This is how surfaces whose source files are owned by another
 *      change (DecisionPopup, PlayManaPrompt, CardSelectPopup, GameView's revealed buttons,
 *      the phase bar) get explanations with ZERO edits in those files.
 *   4. `title` upgrade — a control that already carries a native `title` is explained by
 *      upgrading it (`EXPLAIN_ATTR`-less buttons like "Replay" keep working). Explainer
 *      suppresses the native tooltip while its own is visible, so the two never stack.
 *
 * `{label}` in any explanation text is replaced with the control's visible label — that is how
 * dynamic labels (prompt choices, tournament buttons) get a full sentence without per-case code.
 *
 * Coverage is PROVABLE, not asserted by hand: `explainCoverageReport()` walks a live DOM and
 * returns total / explained / missing, and `HintsRoot` runs it as a dev assertion (see
 * `runExplainAudit`). The browser harness prints the same report.
 */

export const EXPLAIN_ATTR = 'data-explain'

/**
 * `data-explain` id → explanation. Ids are stable; the text is written in game terms (what
 * happens in the match), not UI terms (what the widget is called).
 */
export const EXPLAIN: Record<string, string> = {
  // ── ActionPromptBar: the priority rail ──
  'pass-priority':
    'Pass Priority — hand priority to the opponent. The game advances only after all players pass in a row; if you have nothing to cast or activate, this is how the turn moves on.',
  'undo-action':
    'Undo — roll the game back to your last stored bookmark (undo is only offered with an empty stack and a bookmark saved by the game settings). Both players see the rollback.',
  'skip-eot':
    'Skip to End of Turn (F5) — pass priority automatically for the rest of this turn; you are asked to act again in your own end step only if the game settings say so. Click again to cancel the skip.',
  'skip-main':
    'Skip to Next Main Phase (F7) — pass priority through the current phase and stop at the next main phase. Click again to cancel the skip.',
  'skip-turn':
    'Skip Turn (F4) — pass priority for the rest of the current turn; the game stops at the start of the next player\u2019s turn. Click again to cancel the skip.',
  'skip-my-turn':
    'Skip to My Next Turn (F9) — pass priority through every turn until your own next turn begins. Click again to cancel the skip.',
  'skip-stack':
    'Skip Stack (F10) — keep passing until the stack is empty and every spell and ability has resolved, then stop. Click again to cancel the skip.',
  'skip-end-step':
    'Skip to End Step Before My Turn (F11) — pass priority until the end step that comes right before your next turn, so you can act one last time before your turn starts. Click again to cancel the skip.',
  'replay-scrubber':
    'Replay position — drag to jump to any point in the recorded game, or click the track to go straight there. The counter reads the current frame out of the frames in the save.',
  'cancel-skips':
    'Cancel All Skips (F3) — clear every armed skip so the server asks for your priority again at each step.',
  'hold-priority':
    'Hold Priority — stop auto-passing after you cast a spell or activate an ability, so you keep priority and can respond to your own action (the Ctrl+click behaviour of the desktop client).',
  'rollback-turn':
    'Rollback Turn — ask the server to rewind to the start of the current turn. Only allowed when the game was created with rollbacks enabled and both players agree.',
  'concede':
    'Concede — give up the game. You are asked to choose between conceding this game and the whole match.',
  'concede-game':
    'Concede Game — lose the current game only. A best-of match continues with the next game (sideboarding happens first if the match uses sideboards).',
  'concede-match':
    'Concede Match — lose the entire match immediately, including any games not yet played.',
  'cancel-action': 'Cancel — close this without changing anything.',

  // ── Game header / right rail ──
  'settings-gear':
    'Settings — font size, card size, dark/light theme and the highlight colours that mark playable, targetable, mana-tappable and attacking/blocking cards.',
  'header-undo':
    'Undo — roll back to your last stored bookmark, available only while the stack is empty (offered for the same reason, from the top bar).',
  'stop-watching':
    'Stop Watching — leave spectating and return to the lobby. The game itself is not affected.',
  'header-concede': 'Concede — resign the game from the top bar (see the Concede panel in the right rail for game vs match).',
  'rail-tab-log':
    'Game Log — the running list of everything that happens in the game, in engine order: draws, casts, triggers, combat and life changes.',
  'rail-tab-chat':
    'Chat — talk to the other players at the table. Messages appear for everyone and are kept with the match.',
  'rail-tab-llm':
    'LLM — the reasoning trace of an AI (LLM) opponent: its game plan, every decision with the prompt it answered, and its post-game reflection.',
  'rail-send-chat':
    'Send — post the typed message to the table chat. Enter in the text field does the same.',

  // ── Lobby ──
  'lobby-new-game':
    'New Game — set up a match: pick the format, add human/AI/LLM opponents, paste a deck for each seat, then Start Game.',
  'lobby-new-tournament':
    'New Tournament — create a tournament (type, seats, decks) and run it from the tournament view; the lobby returns when it is finished.',
  'lobby-deck-editor':
    'Deck Editor — build and save decks, check their legality, and keep a personal collection that can be imported into any game.',
  'lobby-settings': 'Settings — display and highlight preferences for the whole client (they are remembered in this browser).',
  'lobby-logout':
    'Log out — end this session and return to the connect screen. Your saved username is cleared, so the next connect starts fresh.',
  'lobby-logout-confirm':
    'Log out — leave the table and end the session now. The seat is abandoned; the match keeps waiting on the server until it times out.',
  'lobby-start-match':
    'Start Match — begin the game now that every seat is filled. Only the player who created the table sees this, and the server refuses to start while a seat is empty.',
  'lobby-refresh-tables':
    'Refresh Tables — re-read the server\u2019s table list right now and show every open and running table. The list also refreshes itself about every 15 seconds; this button forces it immediately. In the waiting room you stay seated.',
  // Leave-table flow (desktop TableWaitingDialog's Cancel).
  'lobby-leave-table':
    'Leave — give up your seat at this table and return to the lobby. The table keeps waiting for players without you; if a match already started, the server refuses and the seat stays yours. Use this instead of logging out when you no longer want the seat.',
  'lobby-watch-table':
    'Watch — spectate this running game. You see the whole board as an observer; you cannot act, and leaving the view does not affect the match.',
  'lobby-join-table':
    'Join — sit down at this table: pick your deck in the dialog that opens and you are seated once it starts. Only tables with a free seat offer this.',
  'lobby-enter-table':
    'Enter — you are seated at this table. Re-opens your waiting room so you can follow seats filling up or start the match when you own the table.',
  'lobby-grid-start-match':
    'Start Match — begin the game now that every seat is filled. Shown on your own table in the list even when you hold no seat at it (the creator can always start); the server refuses to start while a seat is empty.',
  'tables-grid-kind':
    'Table filter — show every table, only running matches, or only tournaments. Sorting follows the column headers.',
  'games-list-search':
    'Search games — filter the list by player name, deck, game type or table name as you type.',
  'games-list-empty':
    'No live games or replays are listed right now — finished games appear here once a match ends.',
  'games-list-spectate':
    'Spectate — watch this running game live. You see the whole board as an observer; you cannot act.',
  'games-list-spectate-disabled':
    'Spectating is disabled on this table — its owner started the game without allowing spectators.',
  'games-list-watch-replay':
    'Watch Replay — step through this finished game at your own pace, replaying every move that was made.',
  'lobby-chat':
    'Lobby chat — talk with everyone in the room while you are not seated at a table.',
  'lobby-chat-tab-talk':
    'Talk tab — player conversation (whispers you receive also land here).',
  'lobby-chat-tab-system':
    'System tab — server status messages and game-system notices.',
  'lobby-chat-send':
    'Send — post your message to the lobby chat. While seated in a game your messages go to that game\u2019s chat instead.',
  'lobby-users':
    'Online users — everyone currently connected to the room. Your own name is highlighted.',
  'lobby-rail-resize':
    'Drag to widen or narrow the users/chat column: drag left to give chat and users more room, right to give the games list more. The width is remembered in this browser.',
  'lobby-users-chat-resize':
    'Drag to split the column between the users list and chat: drag down to give chat more room, up to give the users list more. The split is remembered in this browser.',
  'lobby-motd':
    'Message of the Day — an announcement from the server operator. Disappears when the server has none.',

  // ── Game end / setup dialogs ──
  'game-end-close': 'Close — dismiss the result window and stay at the final board.',
  // Save-log controls on the game-end dialog.
  'game-end-copy-log':
    'Copy Log — put the whole game log on the clipboard as plain text, so it can be pasted into a note, a chat or a bug report. The text is the same log the Game Log tab shows (the dead-seat noise after a forfeit is cleaned out). Disabled when this game recorded no log.',
  'game-end-save-log':
    'Save Log — download the game log to a .txt file (same text Copy Log produces). Useful to keep a game after the session ends. Disabled when this game recorded no log.',
  'setup-add-player':
    'Add Player — add another seat to the match (a human, an AI or an LLM opponent). Each seat gets its own deck.',
  'setup-start-or-join':
    'Start Game / Join — create the table and start the match with these seats and decks (or, when joining, sit down at the open table with this deck).',
  'setup-cancel': 'Cancel — close the setup dialog and return to the lobby without creating anything.',

  // ── Prompt surfaces ──
  'prompt-yes': 'Answer the question with Yes. The game resumes with your answer.',
  'prompt-no': 'Answer the question with No. The game resumes with your answer.',
  'prompt-submit-amount':
    'Submit — send the number in the field as your answer. The range under the field shows what the game will accept.',
  'prompt-cancel-amount': 'Cancel — withdraw the amount choice; the pending action is abandoned rather than answered.',
  'prompt-skip-target':
    'Skip (Don\u2019t select) — decline to choose a target for this optional prompt and pass priority instead. Required prompts do not offer this.',
  'prompt-choose-pile-1': 'Choose Pile 1 — take the first pile offered (Fact or Fiction and similar split effects). The other pile is discarded.',
  'prompt-choose-pile-2': 'Choose Pile 2 — take the second pile offered; the other pile is discarded.',
  'prompt-auto-tap':
    'Auto-Tap All Lands — tap every untapped land known to produce a colour this cost needs, in one go, and pay as much of the cost as those lands allow.',
  'prompt-auto-pay':
    'Auto-Pay Pool — let the server pay costs from your mana pool automatically instead of asking you to choose sources each time.',
  'prompt-first-mana':
    'First Mana Ability — when tapping a land with several mana abilities (a dual land), use its first ability. That is how the colour a dual produces is chosen.',
  'prompt-special-mana':
    'Special Mana — open the special mana actions the game offers for this payment (for example mana from a non-land source or an ability that produces several colours).',
  'prompt-cancel-spell': 'Cancel Spell — give up the pending cast or activation. The spell is not cast and nothing is paid.',

  // ── Replay viewer ──
  'replay-exit': 'Exit Replay (Esc) — close the replay viewer and return to the lobby.',
  'replay-prev': 'Previous (←) — step back one play in the recorded game.',
  'replay-play': 'Play / Pause (space) — replay the recorded game automatically, or stop the automatic playback where it is.',
  'replay-next': 'Next (→) — step forward one play in the recorded game.',
  'replay-skip': 'Skip — jump ahead several plays at once instead of stepping one at a time.',
  'replay-stop': 'Stop Replay — end the replay session; the recorded file itself is kept.',
  'replay-browse': 'Replays — open the list of finished matches and saved replay files that can be watched.',
  'replay-refresh': 'Refresh — re-read the finished-match and replay-file lists from the server.',
  'replay-load-file': 'Replay — load this saved game file and open it in the replay viewer.',
  'replay-load-game': 'Replay — load this game of the match (one button per game in a best-of) and open it in the replay viewer.',
  'replay-search': 'Search replays — filter finished games and saved files by player, deck, type or table name.',
  'replay-empty': 'No replays are listed yet — finished matches and saved replay files appear here.',
  'replay-load': 'Replay — load this game and open it in the replay viewer to step through it.',

  // ── Sideboard (between games) ──
  'sb-move': 'Move this card between the maindeck and the sideboard. The change is local until you submit.',
  'sb-submit':
    'Submit deck — send the sideboarded deck to the server. The next game of the match starts once every player has submitted; the server submits for you when the timer runs out.',
  'sb-move-all': 'Move all to sideboard — start from an empty maindeck by moving every card across, then bring back the cards you want to keep.',
  'sb-hide': 'Hide — close this view. The submission was already sent; the next game starts without it.',

  // ── Tournament ──
  'tour-refresh': 'Refresh — re-read the tournament standings and pairings from the server.',
  'tour-back': 'Back to Lobby — leave the tournament view; the tournament keeps running on the server.',
  'tour-watch': 'Watch — spectate this tournament match as an observer.',
  'tour-create': 'Create Tournament — create the tournament with these seats and decks; it starts immediately.',
  'tour-cancel': 'Cancel — close the tournament setup without creating anything.',

  // ── Deck editor ──
  'deck-new': 'New — start an empty deck in the editor. The deck currently open is not saved by this.',
  'deck-save': 'Save — write this deck back to its saved slot in this browser (the * in the title means there are unsaved changes).',
  'deck-save-as': 'Save as… — store the deck under a new name, leaving the original saved deck untouched.',
  'deck-open': 'Open… — list the decks saved in this browser so one can be loaded into the editor.',
  'deck-copy-dck': 'Copy .dck — copy the deck to the clipboard in the .dck format used by XMage and mtgtop8.',
  'deck-download-dck': 'Download .dck — save the deck as a .dck file that can be uploaded into a game seat.',
  'deck-import': 'Import… — open the import box to paste a decklist (or upload a .dck/.txt file) and load it into the editor.',
  'deck-import-submit': 'Import — parse the text or file in the box and replace the deck in the editor with the result.',
  'deck-import-cancel': 'Cancel — close the import box without loading anything.',
  'deck-save-as-submit': 'Save — store the deck under the name in the field.',
  'deck-save-as-cancel': 'Cancel — close the save box without storing anything.',
  'deck-saved-open': 'Open deck — load this saved deck into the editor, replacing what is currently open (save first if needed).',
  'deck-saved-delete': 'Delete — remove this saved deck from this browser. This cannot be undone.',
  'deck-editor-back': 'Back to Lobby — leave the deck editor and return to the lobby.',
  'deck-editor-build-tab': 'Build — search cards and put together the deck (search on the left, the deck and sideboard on the right).',
  'deck-editor-collection-tab':
    'Collection — the cards you own in this browser: add copies with +1, move them into the deck, or clear the list.',
  'deck-search-color': 'Colour filter — include cards of this colour in the search results. Toggle it off to exclude them.',
  'deck-search-advanced': 'Advanced filters — show or hide the extra search fields (type, set, mana value, rarity, rules text).',
  'deck-search-add-collection': 'Add 1 copy to your collection — collect this card so it can be added to a deck from the Collection tab.',
  'collection-clear': 'Clear all — empty your saved collection. Cards already in decks are not affected.',
  'collection-to-deck': '→ Deck — add one copy of this collected card to the deck in the editor.',
  'collection-plus': '+1 — add another copy of this card to your collection.',
  'collection-minus': '−1 — remove one copy of this card from your collection.',
  'legality-refresh': 'Re-check legality — run the deck against the format\u2019s rules again and list anything that is not allowed.',
  'deck-tile-context': 'Right-click for options — change the card\u2019s art, adjust how many copies are in the deck, or move it to the other deck section.',
  'deck-ctx-change-art': 'Change art… — pick which printing of this card is used; the deck keeps the same card either way.',
  'deck-ctx-copy': '+1 Copy — add another copy of this card to the deck.',
  'deck-ctx-minus': '−1 Copy — remove a copy of this card from the deck.',
  'deck-ctx-to-main': '→ Main deck — move this sideboard card into the maindeck.',
  'deck-ctx-to-sideboard': '→ Sideboard — move this card out of the maindeck and into the sideboard.',
  'deck-ctx-remove': 'Remove all copies — delete every copy of this card from the deck.',
  'art-picker-close': 'Close — leave the art list and keep the card\u2019s current printing.',
  'art-picker-card': 'Pick this printing — the deck uses this set and card number from now on. The card\u2019s rules do not change.',
  'deck-add-land':
    'Add Lands… — open the basic-land box: choose how many Plains, Islands, Swamps, Mountains, Forests and Wastes to put into the deck. The printing comes from the server\u2019s card database.',
  'addland-plus': '+1 — add one more of this basic land to the total that will be added.',
  'addland-minus': '−1 — take one back from this basic land\u2019s total.',
  'addland-count': 'How many of this basic land to add. You can also type the number directly.',
  'addland-submit':
    'Add lands — put the chosen numbers of basic lands into the deck\u2019s maindeck. The deck\u2019s legality is checked again afterwards.',
  'addland-cancel': 'Cancel — close this box without adding any lands.',
  'addland-close': 'Close — leave the basic-land box without adding any lands.',

  // ── Connection ──
  'connect-submit':
    'Connect — open the connection to the XMage server with these settings and load the lobby.',
  'connect-register':
    'Register… — create a new account on this XMage server. The server generates the initial password and emails it to the address you enter.',
  'register-username':
    'User name — the account name you will log in with. 3 to 14 characters, letters, digits and underscore only.',
  'register-password':
    'Password — type the password twice (here and in the confirmation field) to confirm it. The server replaces it with its own generated password and emails that to you.',
  'register-password-confirmation':
    'Password (confirmation) — must match the password field above; a mismatch is refused before anything is sent to the server.',
  'register-email':
    'Email — where the server sends your initial generated password, and where a password reset would go. Each email address can belong to only one account.',
  'register-submit':
    'Register — create the account. On success the server sends the initial password to your email address.',
  'register-cancel': 'Cancel — close this without creating an account.',
  'llm-refresh-models': 'Refresh — ask the LLM endpoint for its model list again (use it after changing the endpoint or the API key).',
  'llm-config-tab':
    'LLM config section ({label}) — show these settings for the AI player in this seat. One section is shown at a time; arrow keys move between them, like the desktop client.',
  'player-slot-remove': 'Remove — delete this seat from the game setup. At least two seats are kept.',
  'seat-name':
    'Seat name — the name this seat plays under: it labels the seat in the lobby, the board header and the game log. Leave it empty to use the default (your username for your own seat, AI-N for an AI seat). A name for a human opponent seat is not set here — it arrives when that player joins with their own username.',
  'user-request-response': 'Answer the other player\u2019s request: {label}. The whole table waits until you respond.',

  // ── Settings dialog ──
  'settings-tab':
    'Settings section ({label}) — show these settings. One section is shown at a time; arrow keys move between sections, like the desktop client.',
  'settings-close': 'Close — leave the settings without changing anything else. Changes already applied stay applied.',
  'settings-done': 'Close — leave the settings. Font size, card size, theme and highlight colours take effect immediately and are remembered in this browser.',
  'settings-reset':
    'Reset to Default — restore every setting in this dialog (font, UI and card scale, hover preview size, theme, highlight colours, panel widths and the battlefield divider) to its default. Applied immediately, no restart needed.',
  'settings-theme-dark': 'Dark theme — dark backgrounds for the whole client, the default for playing.',
  'settings-theme-light': 'Light theme — light backgrounds for the whole client, easier to read in a bright room.',
  'settings-autofit-on':
    'Auto-fit battlefield ON — cards shrink automatically so both battlefields and both hands always fit the screen without scrolling. Card Display Size only caps how large the cards may get.',
  'settings-autofit-off':
    'Auto-fit battlefield OFF — cards use the fixed viewport-based size from Card Display Size; a full board may need scrolling to see everything.',
  'settings-ding-enabled':
    'Priority ding — play a short sound each time a NEW question waits for you (a spell to respond to, a target to choose, your draw step). The mana-payment follow-ups of a cost you are already paying stay silent. Off by default.',
  'settings-ding-variant':
    'Choose the ding sound: Ding (a short bell), Chime (three rising notes) or Soft click (a quiet tick). Picking one plays it immediately as a preview.',
  'settings-ding-volume':
    'Ding volume — how loud the priority ding plays, from silent to full. Drag it and the next ding uses the new level; the sound select above previews at this volume.',
  'settings-ding-focus':
    'When the ding plays: ONLY when the game window is out of focus (you are in another window/tab and might miss your turn — the most common choice), ALWAYS (even while you are looking at the game), or NEVER when it is out of focus (in-focus only).',
  'settings-flash-enabled':
    'Screen flash — alongside the sound, the edge of the whole screen glows once in the accent colour when a new question waits for you. Visible even when the window is not focused; suppressed while the page is hidden.',
  'settings-flash-intensity':
    'Flash intensity — how strong the edge glow is, from a whisper to unmissable. Dragging the slider fires a test flash at the new level.',
  'settings-flash-sustain':
    'Sustained flash — the screen edge stays lit at the chosen intensity for as long as a question is waiting on you, instead of pulsing once. It goes out the moment you answer or pass, when the game ends, and whenever the page is hidden.',
  'settings-remember-prompt-position':
    'Remember prompt position — the centered prompt window reopens wherever you last dragged it, instead of the screen centre each time. The mana-payment window still anchors at your mana pool.',
  'settings-preset-name':
    'Preset name — type a name for the current settings, then press Save to store them. Loading that preset later restores exactly these values.',
  'settings-preset-save':
    'Save — store every setting in this dialog (font, UI and card scale, preview size, theme, highlights, sound and token art, panel widths and the battlefield divider) under the name on the left. Saving over an existing name replaces that preset.',
  'settings-card-preview-scale':
    'Hover Preview Size — how large the card panel that follows the mouse is drawn, from 50% to 400%. Image and rules text scale together, separately from the Card Display Size used on the board.',
  // ── Multiplayer layout ──
  'board-mode-maximize':
    'Maximize my board — in a 3+ player game the opponents collapse into a compact chip strip (avatar, name, life) along the top and your board takes the rest of the height. Turn it off to see every board on the grid again.',
  'board-mode-maximize-row':
    'Maximize my board — the row holding the toggle (explains the toggle it labels).',
  'board-mode-focus':
    'Focus on one opponent — play 1-on-1 against the focused opponent while the others show as chips above (click to focus, \u25c0/\u25b6 cycle, \u2715 back to all players). While one is focused this outranks "Maximize my board".',
  'board-mode-focus-row':
    'Focus on one opponent — the row holding the toggle (explains the toggle it labels).',
  'board-mode-follow-active':
    'Follow the active player — the focus follows whoever\u2019s turn it is among your opponents (yours last). Off, the focused opponent stays as clicked/cycled until you change it. Only available while Focus is on.',
  'board-mode-follow-active-row':
    'Follow the active player — the row holding the toggle (explains the toggle it labels).',
  'battlefield-split-handle':
    'Drag to resize the two battlefields: drag up to give your board more room, down to give the opponent\u2019s more. The split is remembered in this browser (Reset to Default in Settings restores it).',

  // ── Phase stop settings dialog ──
  'phase-stop-apply':
    'Apply — send these phase stops to the server now and close the dialog. Unchecked phases stop asking for your priority.',
  'phase-stop-reset':
    'Reset All — switch every phase stop back on (the default): the server will ask you for priority at every step again.',
  'phase-stop-cancel': 'Cancel — close this dialog. Stops you already applied earlier stay as they were.',

  // ── Onboarding tip controls (HintLayer) ──
  'hint-dismiss': "Don't show again — dismiss THIS tip for good in this browser; the other tips still appear.",
  'hint-turn-off-all':
    'Turn off all hints — switch the whole onboarding system off in this browser, for good. No tip shows again until localStorage is cleared.',

  // ── Replay LLM chat history ──
  'replay-llm-history-toggle':
    'Show entire history — render the LLM conversation accumulated UP TO this frame instead of only the exchanges recorded at this exact frame. Off, the panel returns to single-frame behavior. Remembered in this browser (also editable in Settings).',
  // ── Builtin deck library ──
  'builtin-decks-retry':
    'Retry — clear the fetch error and request the built-in deck library again. The library loads once per session; this forces a fresh attempt.',
  // ── Random deck generator ──
  'random-deck-generate':
    'Random — build a 60-card deck from the cards you own in this browser (max 4 copies of any card, basic lands to match the colors drawn) and put it in the deck box below. The seed is written into the deck name so the same deck can be regenerated.',

  // ── App-root error boundary ──
  'error-boundary-reload':
    'Reload — restart the web client with a fresh page load. A render error stopped the client and its state cannot be trusted; reloading rebuilds everything from the server.',
  // ── Error toast controls ──
  'error-toast-copy':
    'Copy — put this error message (and its details, when present) on the clipboard, ready to paste into a bug report or a note. The toast stays visible while the cursor is over it.',
  'error-toast-details':
    'Details — expand the technical details the server sent with this error (a cause chain or stack trace) to help diagnose it. The toast is held open while the details are shown.',

  // ── Dead-socket lobby surfacing ──
  'lobby-socket-down':
    'Connection status — the client lost its connection to the server: your last lobby clicks were NOT sent, the lobby actions are disabled, and the client is reconnecting automatically. Reload the page if it does not recover.',

  // ── Context menus + token art ──
  // ADDITIVE BLOCK — new ids only; no existing entry above was moved or reworded.
  'phase-ctx-toggle-mine':
    '{label} — switch this phase\u2019s priority stop on or off for YOUR turns. On (✓), the server asks for your priority every time your turn reaches this step; off, the step auto-skips. This writes the same setting the Phase Stop dialog edits.',
  'phase-ctx-toggle-opp':
    '{label} — switch this phase\u2019s priority stop on or off for the OPPONENT\u2019s turns. On (✓), the game stops at this step during their turn too, so you get priority there (their upkeep, their draw step); off, it auto-skips.',
  'player-ctx-llm':
    'View LLM reasoning — open this seat\u2019s reasoning trace in the right rail\u2019s LLM tab: its game plan, every decision with the prompt it answered, and its post-game reflection. Offered only for seats run by an AI (LLM) opponent.',
  'player-ctx-graveyard':
    'View graveyard — open this player\u2019s graveyard and see every card in it. Graveyards are public: discarded, destroyed and countered cards go there, and some effects still play cards from them.',
  'player-ctx-exile':
    'View exile — open this player\u2019s exile pile and see every card in it. Exile is public, and some effects still return or cast cards from there.',
  'player-ctx-copy-name':
    'Copy player name — put this player\u2019s name on the clipboard, for pasting into chat or a note. Nothing in the game changes.',
  // ── View deck (limited pool) ──
  'player-ctx-view-deck':
    'View deck — open this seat\u2019s deck (maindeck + sideboard) in a read-only window. Offered for your own seat and for computer opponents; the engine blocks viewing another human player\u2019s deck, so the item is disabled for those seats and marked restricted. Nothing in the game changes.',
  'view-deck-close':
    'Close — dismiss this deck window. The deck was only shown to you; the game state is untouched.',
  'settings-token-art-override':
    'Always use my token art — the global token-art override. Off (default), a token shows the art its owner shared and your own choice applies only where the owner has picked none. On, YOUR art for a token is used everywhere, including on an opponent\u2019s tokens. Right-click a token to choose its art.',
  'card-ctx-pin':
    'Pin preview — keep this card\u2019s preview panel open above the board (the same panel hover shows) so it can be read at leisure; the next hover cycle replaces it. Nothing in the game changes.',
  'card-ctx-play':
    'Play / Cast — act on this card the way clicking its tile does: a land goes onto the battlefield, a spell is put on the stack for the table to answer. Offered only for cards the engine marked playable right now.',
  'card-ctx-copy-name':
    'Copy name — put this card\u2019s name on the clipboard, for pasting into chat or a decklist. Nothing in the game changes.',
  'card-ctx-copy-rules':
    'Copy rules text — put this card\u2019s rules text on the clipboard, for pasting elsewhere. Disabled when this card view carries no rules text.',
  'token-art-pick':
    'Use this art — render this token with the chosen printing: it is saved as your preference for this token name and, while Share with the table is on, sent to the others so they see it too. The badge marks the printing the engine picked as default.',
  'token-art-reset':
    'Reset to default — drop your saved art for this token name and use the printing the engine picks. The reset is told to the table too; in a replay it only changes your own view.',

  // ── Event-sound settings — ADDITIVE BLOCK, new ids only ──
  'settings-audio-enabled':
    'Game event sounds — play a sound for game events: the start of your game, a new object on the stack, winning or losing, a player joining or leaving your table, an incoming whisper, and arming or cancelling a skip. Separate from the priority ding above: muting one keeps the other.',
  'settings-audio-volume':
    'Event-sound volume — how loud the game event sounds play, from silent to full. It applies the moment it changes; the priority ding keeps its own volume above.',
  // ── Rebindable keyboard shortcuts ──
  // ADDITIVE BLOCK — new ids only; no existing entry above was moved or reworded.
  'settings-hotkeys-rebind':
    '{label} — arm this shortcut, then press the key you want it bound to. Escape cancels and keeps the current key. While a rebind is armed the game does not react to keys, so pressing F5 here rebinds it instead of skipping a turn. A key that another action already uses is refused with a message naming that action.',
  'settings-hotkeys-reset':
    'Reset — put this shortcut back on its shipped default key. Offered only while the row differs from the default; the footer\u2019s Reset to Default restores every shortcut at once.',
  'settings-hotkeys-reset-all':
    'Reset All Shortcuts — put EVERY game shortcut back on its shipped default key (F2 confirm, F3 cancel skips, F4/F5/F6/F7/F9/F10/F11 skips). The replay viewer\u2019s keys are fixed and not affected.',
  // ── Remembered yes/no answers + reset surfaces ──
  // ADDITIVE BLOCK — new ids only; no existing entry above was moved or reworded.
  'prompt-always-yes':
    'Remember YES — open the list of ways to answer this YES automatically when the same question comes up again. Picking an entry stores it AND answers this prompt YES now (that is the desktop client\u2019s behaviour).',
  'prompt-always-no':
    'Remember NO — open the list of ways to answer this NO automatically when the same question comes up again. Picking an entry stores it AND answers this prompt NO now (that is the desktop client\u2019s behaviour).',
  'prompt-always-id':
    '{label} — store this answer for THIS exact question coming from THIS exact source ability: when that ability asks it again, the game answers without showing you the prompt. It also answers the prompt on screen now. Reset it via a player header\u2019s right-click menu.',
  'prompt-always-text':
    '{label} — store this answer for this exact question text, no matter which card or ability asks it: the game answers it automatically the next time it comes up. It also answers the prompt on screen now. Reset it via a player header\u2019s right-click menu.',
  'player-ctx-reset-yesno':
    'Reset all YES/NO auto-answers — forget every remembered automatic YES/NO answer for this game, both the ones stored per question text and the ones stored per source ability. The next matching question is asked normally again.',
  'player-ctx-reset-trigger-order':
    'Reset trigger-order auto-answers — forget every triggered-ability ordering preference you set (always-first / always-last, by ability or by rules text). The engine asks you to order those triggers again.',
  'player-ctx-reset-replacement':
    'Reset replacement-effect auto-answers — forget the replacement effects you told the game to apply automatically this game (the "apply this every time?" answers). They are asked again.',




  // ── Card hints helper ──
  // ADDITIVE BLOCK — new ids only; no existing entry above was moved or reworded.
  'header-card-hints':
    'Card hints helper — open a window listing the engine\u2019s own card hints for every card you can currently see: what a card is counting, whether a condition is met, whether something may attack or block. Group them by hint or by card, filter by player and search the text. Information only — nothing in the game changes.',
  'card-hints-filter':
    'Filter by player — show hints for one seat only, or for the whole board (All players). The list is rebuilt from the cards that seat currently owns on your screen.',
  'card-hints-group':
    'Group hints by — Hints (default) lists each unique hint line once, with the cards carrying it; Cards lists every hint under its card, grouped by player and zone so you can read one permanent\u2019s full hint set.',
  'card-hints-search':
    'Search hints — type any words to filter the list. A card is kept when any word appears in the player name, the zone, the card name, the card id prefix or the hint text itself (matching the desktop client, which searches the same fields).',
  'card-hints-close':
    'Close — dismiss the card hints window. It only shows information; the game state is untouched.',
// ── Hand request / permission management ──
  // ADDITIVE BLOCK — new ids only; no existing entry above was moved or reworded.
  // Wording follows the desktop client's own menu items and tooltips
  // PlayAreaPanel.java:403-434:479-481).
  'player-ctx-request-hand':
    'Request permission to see the hand cards — ask this player to let you look at their hand for the rest of the match. They get a prompt to Accept or Reject; while watching a game you can ask any seat. Each user may ask once per game, and a player who has requests turned off refuses automatically. If they accept, their hand opens in a window you can keep on screen.',
  'player-ctx-view-granted-hand':
    '{label} — show the hand you were granted permission to see in a read-only window: the cards are only shown to you, one glance does not let you play or target from them. They appear the same way a hand you control does, and the player can revoke the permission at any time, which closes the window.',
  'player-ctx-allow-hand':
    '{label} — while ticked, watchers and other players may ask to see your hand cards, each user once per game; while unticked, requests are refused automatically. Re-ticking it is what allows requests again, so use it when you want to re-open requests after someone has already asked.',
  'player-ctx-revoke-hand':
    'Revoke all permission(s) to see your hand cards — take back every permission you have granted this match: anyone currently watching your hand stops seeing it on their next update, and they cannot ask again once requests are turned off. Nothing in the game changes.',
  'hand-watch-switch':
    '{label} — show that player\'s hand, which you were granted permission to see, in the hand window (click the same button again to close it). Offered only while you hold permission for more than one hand, so you can switch between them like the desktop client\'s Switch Hand button.',
  'hand-watch-close':
    'Close — dismiss this hand window. The cards were only shown to you; the game state is untouched and the permission stays granted until the player revokes it.',
  // ── Player avatars ──
  // ADDITIVE BLOCK — new ids only; no existing entry above was moved or reworded.
  'settings-avatar-pick':
    'Choose your avatar — the picture shown next to your name on the game board, for you and for everyone at the table. It is saved and sent to the running game immediately. The avatar marked with the highlighted border is the one you have now.',
  // ── Switch hands ──
  // ADDITIVE BLOCK — new ids only; no existing entry above was moved or reworded.
  'hand-switch':
    'Switch Hand — choose whose hand the hand area shows: yours, or a hand the game lets you see. Offered only while MORE THAN ONE hand is available to you, which happens when you control another player\u2019s turn (mind-control effects) or when a player granted you permission to look at their hand. The button\u2019s own label names the hand currently displayed.',

}

/** Collapse whitespace and shorten very long labels for tooltip use. */
function collapse(s: string): string {
  return s.replace(/\s+/g, ' ').trim()
}

function labelOf(el: HTMLElement): string {
  const fromText = collapse(el.textContent ?? '')
  const raw = fromText || collapse(el.getAttribute('aria-label') ?? '')
  return raw.length > 60 ? `${raw.slice(0, 57)}…` : raw
}

/** Replace `{label}` with the control's own label. */
function fill(text: string, label: string): string {
  return text.includes('{label}') ? text.replaceAll('{label}', label || 'this answer') : text
}

/**
 * A native `title` that is really a shortcut hint is upgraded to say so; anything else is
 * appended to the label so the explanation keeps the button's own wording and adds the why.
 */
function upgradeTitle(label: string, title: string): string {
  if (/^(F\d{1,2}|Escape|Esc|space|←|→)$/i.test(title)) return `${label} — keyboard shortcut: ${title}.`
  if (title.toLowerCase() === label.toLowerCase()) return `${label} — see the button label; it explains itself in game terms.`
  return `${label} — ${title}`
}

/** How a card-thumb's explanation depends on the prompt that is currently asking for input. */
function cardThumbExplanation(el: HTMLElement, label: string): string | null {
  const name = el.getAttribute('data-card-name') ?? label
  if (el.classList.contains('card-target')) {
    return `${name} — a legal target for the spell or ability that is waiting for your answer. Click to choose it.`
  }
  if (el.classList.contains('card-playable')) {
    return `${name} — playable right now: click it to cast it (a permanent goes onto the battlefield, a spell onto the stack).`
  }
  if (el.classList.contains('combat-declared')) {
    return `${name} — already declared in combat this turn. Click it AGAIN to take the declaration back (undeclare attacker/blocker) before you finish combat.`
  }
  if (el.classList.contains('combat-selectable')) {
    return `${name} — selectable in combat: click to declare it as an attacker or blocker, as the prompt asks. (Already declared? Click the creature again to undeclare it.)`
  }
  if (el.classList.contains('card-mana-tappable')) {
    return `${name} — can be tapped for mana to pay the cost the game is asking for. Click to tap it.`
  }
  if (el.closest('.hand-section')) {
    return `${name} — in your hand. Click it when it is your priority to play it; a land goes straight down, a spell waits on the stack.`
  }
  if (el.closest('.land-row')) {
    return `${name} — a land you control. During a mana payment, click it to tap for one mana; it untaps at the start of your turn.`
  }
  if (el.closest('.stack-section')) {
    return `${name} — on the stack, waiting to resolve. The top item resolves first; both players may respond before it does.`
  }
  return null
}

type Rule = (el: HTMLElement, label: string) => string | null

/**
 * Family matchers, in order. Each returns an explanation or null. These exist so surfaces whose
 * source files are owned by a parallel change still get explanations without being edited.
 */
const RULES: readonly Rule[] = [
  // A playable command-zone badge (emblem / plane) — PlayerBoard adds
  // `player-badge-playable` when the current select prompt has a choice whose id
  // is the badge's own id, and the badge then carries role="button". The
  // explanation names the ability from the badge's own title (rules text).
  (el, label) => {
    if (!el.classList.contains('player-badge-playable')) return null
    const rules = collapse(el.getAttribute('title') ?? '')
    const who = label || 'This emblem'
    return rules
      ? `${who} — activatable right now: ${rules} Click it to choose this ability; the game then asks for its cost (X, discards, targets).`
      : `${who} — activatable right now. Click it to choose this ability; the game then asks for its cost.`
  },
  // Prompt choice lists (SelectPrompt / ChooseChoicePrompt / ChooseAbilityPrompt / DecisionPopup
  // body / TargetPrompt ability list). The label is the engine's own wording for the choice.
  (el, label) => {
    if (!el.classList.contains('btn-prompt')) return null
    if (el.closest('.prompt-targets')) {
      return `${label} — put this triggered ability / target on the stack. The game waits for exactly this answer.`
    }
    return `Answer the pending prompt with this choice: ${label}. The game resumes once the server has your answer.`
  },
  // Fact-or-Fiction-style pile buttons (CardSelectPopup — file owned by another change).
  (el, label) => (el.classList.contains('btn') && /^Choose (Pile|Option)/i.test(label) ? EXPLAIN['prompt-choose-pile-1']! : null),
  // The "Skip (Don't select)" / "Done" button of an optional prompt.
  (_el, label) => (/^Skip \(Don/i.test(label) || /^Done$/i.test(label) ? EXPLAIN['prompt-skip-target']! : null),
  // Close buttons of the floating zone/revealed windows.
  (el) =>
    el.classList.contains('float-window-close')
      ? 'Close — hide this window. It only shows information; nothing in the game changes.'
      : null,
  // Revealed/looked-at group buttons in the left rail (GameView — structure owned by another change).
  (el, label) =>
    el.classList.contains('revealed-reopen-btn')
      ? `Reopen “${label}” — show these revealed or looked-at cards again; they are information only.`
      : null,
  // Phase chips (PhaseBar — element owned by another change).
  (el, label) =>
    el.classList.contains('phase-btn')
      ? `${label} — the step of the turn. Click to choose whether the game stops here to give you priority (phase stops).`
      : null,
  // Zone titles (graveyard / exile / sideboard) in the player boards.
  (el, label) =>
    el.classList.contains('clickable-zone-title')
      ? `${label} — click to open this zone and see every card in it. Cards in public zones can still be played by effects.`
      : null,
  // Mana pool pips.
  (el, label) =>
    el.classList.contains('player-mana')
      ? `Spend this mana from your pool (${label} available). Mana left in the pool is lost when a step ends.`
      : null,
  // Panel resize grips.
  (el) =>
    el.classList.contains('resize-handle')
      ? 'Drag to resize this panel. The width is remembered in this browser.'
      : null,
  // Right-rail tabs and the LLM sub-tabs.
  (el, label) => (el.classList.contains('right-tab') ? `Switch the right rail to the ${label} tab.` : null),
  (el, label) => (el.classList.contains('llm-sub-tab') ? `Show the conversation and reasoning of ${label}.` : null),
  // Cards in the card-select / revealed / zone popups.
  (el, label) => {
    if (!el.classList.contains('popup-card')) return null
    if (el.classList.contains('valid-target')) return `${label} — a legal choice here. Click to select it.`
    if (el.classList.contains('popup-card-playable')) {
      // Off-hand play affordance (zone window): the current select prompt offers a
      // play on this card (flashback / play-from-exile) — click it right here.
      return `${label} — playable right now from this zone: click it to play it (the same click the board tile would send).`
    }
    return null // pile/exile cards with no action: covered as a written exception in the audit
  },
  // Look-at-cards thumbs inside the target prompt (Surveil/scry/fetch — TargetPrompt's
  // popup-cards-grid). Same semantics as the popup cards: selectable only when valid.
  (el, label) => {
    if (!el.classList.contains('popup-card-thumb')) return null
    if (el.classList.contains('valid-target')) return `${label} — a legal target. Click to choose it.`
    return `Look at ${label} — this card is only shown to you here; it cannot be chosen.`
  },
  // A card candidate the current prompt does not allow (popup-card-disabled): clicking it
  // is a no-op by design (clickCandidate refuses it), so the tooltip explains exactly that.
  (el, label) =>
    el.classList.contains('popup-card-disabled')
      ? `${label} — not a legal choice in this prompt. Clicking it does nothing; the cards marked ✓ Valid are the ones the game accepts.`
      : null,
  // Flip button on a card thumb (two-faced cards): opens the OTHER face in the
  // hover preview. The button's title already names the hidden face; the rule
  // covers thumbs whose title was not read by the resolver's title fallback.
  (el, label) => {
    if (!el.classList.contains('card-flip-btn')) return null
    return `Show the other side of ${label}. Two-faced card — the other face's name and art open in the preview panel.`
  },
  // Buttons of the mana-payment popup body (DecisionPopup's prompt-mana-buttons — the
  // "Special Mana" button carries no title and the file is owned by another change).
  (el, label) => {
    if (!el.closest('.prompt-mana-buttons')) return null
    if (/Special Mana/i.test(label))
      return 'Special Mana — open the special mana actions the game offers for this payment (for example mana from a non-land source or an ability that produces several colours).'
    return null
  },
  // Lobby table rows.
  (el) =>
    el.closest('.table-card') && el.tagName !== 'BUTTON'
      ? 'Click to join this table with your deck. Use Watch to spectate instead.'
      : null,
  // Deck-editor search results.
  (el) =>
    el.closest('.deck-search-card') && el.tagName !== 'BUTTON'
      ? 'Click to add a copy to the deck in the editor · right-click to add it to the sideboard · +Col adds it to your collection.'
      : null,
  (el) => (el.classList.contains('deck-search-card') ? 'Click to add a copy to the deck · right-click for the sideboard · +Col for your collection.' : null),
  // Deck-editor card tiles.
  (el) =>
    el.classList.contains('deck-card-tile')
      ? EXPLAIN['deck-tile-context'] ?? null
      : null,
  // Trigger-order context menu entries (right-click on a stack ability).
  (el, label) =>
    el.classList.contains('trigger-order-item')
      ? `${label} — change the automatic ordering the engine uses for triggered abilities like this one.`
      : null,
  // Generic dialog buttons that carry no id: Cancel/Close in modals.
  (el, label) =>
    el.tagName === 'BUTTON' && /^(Cancel|Close|✕|×)$/i.test(label) && el.closest('.modal-overlay')
      ? 'Cancel — close this dialog without changing anything.'
      : null,
]

/**
 * The explanation for `el`, or null when the control has none (the audit reports those).
 * `el` must be the interactive element itself (the tooltip's delegated listener resolves the
 * nearest `EXPLAIN_TARGET_SELECTOR` ancestor before calling this).
 */
export function resolveExplanation(el: HTMLElement): string | null {
  const explicitHolder = el.closest(`[${EXPLAIN_ATTR}]`)
  if (explicitHolder instanceof HTMLElement) {
    const raw = explicitHolder.getAttribute(EXPLAIN_ATTR) ?? ''
    if (raw.startsWith('@')) return fill(collapse(raw.slice(1)), labelOf(explicitHolder))
    const known = EXPLAIN[raw]
    // Fail Fast: a typo in an id must break the audit loudly instead of silently showing
    // "undefined" or nothing at all.
    if (!known) throw new Error(`explain: unknown data-explain id "${raw}"`)
    return fill(known, labelOf(explicitHolder))
  }

  const label = labelOf(el)
  for (const rule of RULES) {
    const text = rule(el, label)
    if (text) return text
  }
  const fromCard = cardThumbExplanation(el, label)
  if (fromCard) return fromCard

  const title = collapse(el.getAttribute('title') ?? '')
  if (title) return upgradeTitle(label, title)
  return null
}

/** Every element that must have an explanation. Used by the tooltip listener and by the audit. */
export const EXPLAIN_TARGET_SELECTOR = [
  'button',
  'input[type="submit"]',
  'input[type="button"]',
  '[role="button"]',
  '[data-explain]',
  '.phase-btn',
  '.clickable-zone-title',
  '.player-mana',
  '.resize-handle',
  '.trigger-order-item',
  '.popup-card',
  '.popup-card-thumb',
  '.card-thumb',
  '.deck-card-tile',
].join(', ')

/** The nearest element the tooltip should describe, starting at (and including) `node`. */
export function findExplainTarget(node: EventTarget | null): HTMLElement | null {
  if (!(node instanceof HTMLElement)) return null
  return node.closest(EXPLAIN_TARGET_SELECTOR)
}

/** True when the element is really on screen (the audit must not demand text for hidden controls). */
function isRendered(el: HTMLElement): boolean {
  const rect = el.getBoundingClientRect()
  if (rect.width === 0 && rect.height === 0) return false
  const style = getComputedStyle(el)
  return style.visibility !== 'hidden' && style.display !== 'none'
}

export interface CoverageEntry {
  /** Visible label (or card name) — what a reader needs to find the control. */
  label: string
  /** CSS path-ish descriptor for locating it in the DOM. */
  selector: string
  /** Why it is exempt from needing an explanation. */
  reason: string
}

export interface CoverageReport {
  total: number
  explained: number
  missing: CoverageEntry[]
  exceptions: CoverageEntry[]
}

function describe(el: HTMLElement): string {
  const cls = typeof el.className === 'string' && el.className ? `.${el.className.split(/\s+/).join('.')}` : ''
  const id = el.id ? `#${el.id}` : ''
  return `${el.tagName.toLowerCase()}${id}${cls}`
}

/**
 * Walk `root` and report every interactive control with and without an explanation.
 *
 * Exceptions are CONTROLS THAT CANNOT BE USED in the current state, and each one is listed with
 * its reason rather than silently skipped — the point of the audit is that its totals can be
 * checked against the DOM, not that it prints 100%.
 */
export function explainCoverageReport(root: ParentNode): CoverageReport {
  const missing: CoverageEntry[] = []
  const exceptions: CoverageEntry[] = []
  let total = 0
  let explained = 0

  for (const el of Array.from(root.querySelectorAll<HTMLElement>(EXPLAIN_TARGET_SELECTOR))) {
    if (!isRendered(el)) continue
    const label = labelOf(el) || el.getAttribute('data-card-name') || '(unlabelled)'

    // Card thumbs that nothing can be done with in this state — the card-preview panel is the
    // hover surface for those, and a tooltip would fight it for the same hover.
    if (el.classList.contains('card-thumb')) {
      const actionable =
        el.classList.contains('card-playable') ||
        el.classList.contains('card-target') ||
        el.classList.contains('combat-selectable') ||
        el.classList.contains('card-mana-tappable') ||
        el.closest('.hand-section') != null ||
        el.closest('.land-row') != null ||
        el.closest('.stack-section') != null ||
        el.classList.contains('ability-thumb')
      if (!actionable) {
        exceptions.push({ label, selector: describe(el), reason: 'permanent with no action available in this state — the card preview panel is its hover surface' })
        continue
      }
    }
    // Disabled mana pips: a zero-count colour in the pool cannot be clicked.
    if (el.classList.contains('player-mana') && el.classList.contains('zero')) {
      exceptions.push({ label, selector: describe(el), reason: 'empty mana pool colour — not clickable' })
      continue
    }
    // Pile / exile cards inside a popup that is only being displayed (no selection asked for).
    // EXCEPTION to the exception: a zone-window tile carrying the off-hand playable
    // affordance (popup-card-playable — flashback / play-from-exile) IS actionable.
    if (el.classList.contains('popup-card') && !el.classList.contains('valid-target') && !el.classList.contains('popup-card-disabled') && !el.classList.contains('popup-card-playable')) {
      exceptions.push({ label, selector: describe(el), reason: 'display-only card in a popup — selection is made with the buttons beneath it' })
      continue
    }

    total++
    if (resolveExplanation(el) != null) explained++
    else missing.push({ label, selector: describe(el), reason: 'no data-explain id, no matching rule, no title' })
  }

  return { total, explained, missing, exceptions }
}
