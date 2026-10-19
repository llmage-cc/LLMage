# Keyboard shortcuts

The canonical list of keyboard bindings for both clients. The desktop client and the web client share most of their defaults, but not all of them, and a few keys behave differently. Each table below gives the desktop key and the web key side by side so you can see the difference at a glance.

> **Who this is for:** players using the desktop client or the browser client.

## How to read this page

- **Desktop** means the Swing client started by `start-client.sh`.
- **Web** means the React client served by the web proxy.
- `—` means the action has no binding in that client.
- All game bindings are defaults. The desktop client lets you rebind them in **Preferences → Game - Hotkeys**; the web client lets you rebind the game bindings in **Settings → Hotkeys**. The replay keys are fixed on the web (see [Rebinding](#rebinding)).
- No binding is application-wide. The desktop game keys act while a game window has focus; the web game keys act while a game view is mounted and no surface listed under [When a key does not fire](#when-a-key-does-not-fire) owns the keyboard.

## Game actions

These work while a game is on screen and no dialog is waiting for input.

| Action | Desktop | Web | Notes |
|---|---|---|---|
| Confirm, or take the first choice | `F2` | `F2` | Presses the open prompt's default: OK, Yes, or the first option of a choice list. |
| Cancel all skips | `F3` | `F3` | Clears every armed auto-pass. It does not pass priority. |
| Skip to next turn | `F4` | `F4` | Passes until the next turn begins. In the web client, pressing the same key again cancels an armed skip; the desktop client re-sends the skip and uses `F3` to cancel. |
| Skip to end of turn | `F5` | `F5` | Passes until the end-of-turn step. |
| Skip to next turn and resolve the stack | `F6` | `F6` | Desktop: the key is bound, but this build sends nothing when you press it. Web: works, and because it has no matching rail button it only arms the skip. |
| Skip to next main phase | `F7` | `F7` | Passes until the next main phase. |
| Toggle macro recording | `F8` | `—` | Desktop only. Sends the record-macro toggle; the client labels the feature unsupported. The web client has no macro recorder, so the key is unbound. |
| Skip to my next turn | `F9` | `F9` | Passes through the opponent's turns until your next turn. |
| Skip until the stack resolves | `F10` | `F10` | Passes until the stack is empty. A new object on the stack cancels the skip. |
| Skip to end step before my turn | `F11` | `F11` | Passes until the end step of the turn before yours. |
| Move focus into or out of chat | `F12` | `—` | Desktop only. The web client has a single chat panel and no focus toggle. |

The web client leaves `F8` and `F12` unbound on purpose: it has no action to attach them to. You can still rebind an existing web action onto either key if you prefer.

## Card preview

Both clients show a larger view of a card on hover and both offer the same two preview modifiers. Hold `Alt` while the preview is open.

| Action | Desktop | Web | Notes |
|---|---|---|---|
| Enlarge the previewed card | `Alt+E` | `Alt+E` | Desktop switches the enlarged-card view to its normal mode. Web scales the preview panel up. |
| Show the card's other source | `Alt+S` | `Alt+S` | Shows the other face of a transform, modal double-faced, or split card, or the copy source of a modified permanent. In the web client the key does nothing when the card has neither. |
| Use the first available mana ability while held | `Alt+1` | `—` | Desktop only. Sends the "use first mana ability" action while the keys are held, and turns it off when released. |

## Choices, amounts, and targeting

| Action | Desktop | Web | Notes |
|---|---|---|---|
| Pick an option by number | `1`–`9` | `—` | Desktop choice dialogs number their options and accept the digit. |
| Confirm the current choice | `Space` or `Enter` | `Enter` | In the web client, `Enter` submits a typed amount and activates a focused playable card. `Space` does the same for a focused card. |
| Cancel a choice dialog | `Backspace` | `—` | Desktop: cancels a card-selection dialog such as an optional "may" ability. `Backspace` is bound instead of `Escape` because a popup can steal focus from `Escape`. |

## Draft

| Action | Desktop | Web | Notes |
|---|---|---|---|
| Show all hidden cards again | `F9` | `—` | Desktop draft panel. Useful after picking a card that hid the rest of the pack. |

## Replay

| Action | Desktop | Web | Notes |
|---|---|---|---|
| Play or pause | `—` | `Space` | Web replay viewer only. |
| Next play | `—` | `→` | |
| Previous play | `—` | `←` | |
| Exit the replay | `—` | `Esc` | Also closes the settings dialog when it is open over the replay. |

The desktop replay panel is button-driven; the client defines no keyboard bindings for it, so none are listed here.

## Deck editor

Neither client defines deck-editor shortcuts for adding, removing, or searching cards; those actions use buttons, drag and drop, and text fields. The only bindings are dismissal keys.

| Action | Desktop | Web | Notes |
|---|---|---|---|
| Close a card's context menu | `—` | `Esc` | Web deck editor card tile. |
| Cancel an import or export dialog | `Esc` | `—` | Desktop clipboard deck dialogs. |

## Chat and dialogs

| Action | Desktop | Web | Notes |
|---|---|---|---|
| Send the typed message | `Enter` | `Enter` | |
| Close a dialog | `Esc` | `Esc` | Desktop dialogs register this individually: the connect, deck import/export, and card-selection dialogs all honour `Esc`. |
| Move between settings tabs | `—` | `←` `→` `↑` `↓`, `Home`, `End` | Web settings dialog, when a tab is focused. |
| Close a context menu | `—` | `Esc` | |

## When a key does not fire

Both clients disable game hotkeys while you are typing, and each has a few more conditions:

| Client | Hotkeys stand down when |
|---|---|
| Desktop | A modal dialog is open, or the chat input has focus. Game keys resume when the dialog closes or focus leaves the chat field. |
| Web | The settings dialog is open, the replay viewer is up, a context menu is open, the keypress is going into a text field, or a rebind capture is armed. |

Two further differences matter when a key seems unresponsive:

- The web client matches on the key name alone and ignores modifiers. `Ctrl+F5` therefore does the same thing as `F5`. The desktop client binds an exact key-plus-modifier combination, so `Ctrl+F5` does not trigger the `F5` action there.
- Window managers and laptop firmware sometimes claim function keys. If `F2`–`F11` do nothing in either client, check the desktop environment's own shortcuts before rebinding anything.

## Rebinding

### Desktop

Open **Preferences → Game - Hotkeys**. Each action has a button showing its current key.

1. Click the button for the action you want to change.
2. Press a key, or a combination with `Ctrl`, `Alt`, or `Shift`.
3. Press `Space` to clear a binding, or `Esc` to cancel the change.

New bindings take effect when the next game starts, not in the game already on screen. **Reset** restores the defaults. The macro and chat bindings are in the same tab.

### Web

Open **Settings → Hotkeys**. Every game action and every fixed replay key is listed.

1. Choose **Rebind** on a row.
2. Press the key you want. `Esc` cancels the capture.
3. The new key applies immediately and is stored in this browser.

Rules the web client enforces:

- **One key, one meaning.** A key already used by another action is refused, and the refusal names the action that owns it. The rejected change leaves your map untouched.
- **Fixed replay keys.** `Space`, the arrow keys, and `Esc` cannot be rebound, because the replay viewer hands them back to text fields, open menus, and the settings dialog.
- **No invented actions.** `F8` and `F12` are not rows in the table because the web client has no action for them. You may bind an existing action to those keys.
- **Unusable keys.** `Esc`, `Enter`, `Tab`, `Backspace`, and function keys above `F12` cannot be bound; they belong to dialog and focus behaviour.
- **Reset to Default** clears every override at once. A corrupt saved map is reported in the Hotkeys tab and falls back to the defaults rather than blocking the client.

## Related

- [Game controls](../player-guide/game-controls.md)
- [Desktop client](../player-guide/desktop-client.md)
- [Web client](../player-guide/web-client.md)
- [Web replay](../player-guide/web-replay.md)
- [Troubleshooting](troubleshooting.md)
