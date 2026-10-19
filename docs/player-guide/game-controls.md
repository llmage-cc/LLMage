# Game controls

A game in LLMage is played through a small set of surfaces: the battlefield, your hand, the stack, the player panels and the feedback panel that asks every question. This page explains what each surface offers and how to answer the prompts for priority, mana, targets, combat, mulligans and sideboarding.

> **Who this is for:** players who are in a game and need to know which control answers which prompt.

## The game window

| Area | What it shows | What you do there |
|---|---|---|
| Phase strip | The steps of a turn — Untap, Upkeep, Draw, Main 1, Combat Start, Combat Attack, Combat Block, Combat Damage, Combat End, Main 2, Cleanup, Next Turn — with the current step highlighted, plus the active player, the priority player and the turn number. | Read the current step before acting. |
| Battlefield | One play area per player, with permanents, counters and arrows for attacks, blocks and targets. | Click permanents to select them or to choose them as targets. Right-click empty space for the game menu. |
| Hand | Your cards along the bottom of the window. Playable cards are highlighted. | Click a card to select or play it. |
| Stack | Spells and abilities waiting to resolve, in order. | Read what is about to resolve; some effects let you respond. |
| Player panels | Each player's name, life, hand, library, graveyard, exile and command zone, plus that player's clock. | Click the graveyard or exile icon to open the zone. Right-click the panel for hand, deck and sideboard options. |
| Mana pool | Six counters: white, blue, black, red, green and colourless. | Read what is floating; spend it through the mana symbols on your player panel. |
| Feedback panel | The current question and its buttons. | Answer the prompt. |
| Chat and log | Two panes on the right of the game area. | Talk to the table or read the game log. |
| Big card | A large preview of the card under the pointer. | Press **Alt+E** to enlarge it; **Alt+S** shows the other face. |

## The feedback panel

Every decision the game asks of you appears in the feedback panel at the bottom of the game area. The buttons change with the kind of question:

| Prompt | Buttons | What the buttons do |
|---|---|---|
| Information | None | The panel reports an event; play continues. |
| Yes or no | **Yes** / **No** | Answers the question. Right-click either button for auto-answer options. |
| Confirmation | **OK** / **Cancel** | Confirms or cancels the action being set up. |
| Cancel only | **Cancel** | Cancels the pending action. |
| Selection | **Done** | Confirms the cards or targets you have selected on the board. |
| End of game | **Close game** | Closes the finished game window. |

A **Special** button appears when the game offers a shortcut for the current step, and **Undo** appears when the game has a saved state you can return to. Undo is limited to your own last action, with an empty stack.

## Priority and passing

When you have priority and no action to take, press **Done**. When you cast a spell or activate an ability, the client normally passes priority for you afterwards. Hold **Ctrl** while clicking the card or ability (on macOS, **Cmd**) to keep priority instead, so you can respond to your own spell.

The skip buttons next to the phase strip are the auto-pass controls: arming one passes priority automatically until its stop condition is met.

| Button | Passes until | Stops early when |
|---|---|---|
| Skip to next turn | The next turn begins | Something goes on the stack, or attackers or blockers must be declared |
| Skip to end of turn | The end-of-turn step | Something goes on the stack, or attackers or blockers must be declared |
| Skip to main step | The next main phase | Something goes on the stack, or attackers or blockers must be declared |
| Skip to your turn | Your next turn begins | Attackers or blockers must be declared |
| Skip until the stack resolves | The stack is empty | A new object is added, when that option is enabled |
| Skip to end step before your turn | The end step of the turn before yours | Attackers or blockers must be declared |
| Cancel all skips | — | Clears every armed skip |

Which phases stop a skip is a preference, not a rule. **Preferences → Game - Phases & Priority** has one switch per phase and the skip settings:

- Stop skips on declare attackers when attackers are available (on by default).
- Stop skips when attacked and on declare blockers when any blockers are available (on by default).
- Stop skips when attacked when zero blockers are available (off by default).
- Stop a stack skip on new objects, or only when the stack is empty.
- Stop a main-step skip on any main step, or only on your main step.
- Stop an end-step skip on any end step, or only on the opponent's end step.

Each skip button shows its current key in its tooltip. The complete key list, including how to rebind it, is in [Keyboard shortcuts](../reference/keyboard-shortcuts.md).

## Mana payment

By default the client pays costs automatically from your lands and mana pool. Right-click the battlefield and open **Mana payment** to change that:

| Item | Effect |
|---|---|
| Automatically | The client chooses which mana to tap and spend. |
| No automatic usage for mana already in the pool | Mana already floating is spent only when you click it, so you can hold it for a later spell. |
| Use first mana ability when tapping lands | Tapping a land uses its first mana ability instead of asking which one. |

Manual payment works through the mana symbols on your player panel. While a cost is being paid, the sources that can pay it are highlighted; click one to add its mana, and click a mana symbol to spend that colour from your pool. The mana pool panel is the running total of what is floating.

Hold **Alt+1** while tapping lands to use the first mana ability for that tap, even when the preference is off. Spells with an X cost ask for the value in a number dialog before payment.

## Targets, choices, and card selection

A prompt names what it wants — a target, a card, a player, a sacrifice. Legal choices are highlighted on the board. Click a choice to select it, then press **Done**. To choose a player, click that player's avatar or name. Clicking a selected permanent again removes it where the prompt allows that, as it does for attackers and blockers.

Other prompts use a dialog:

| Dialog | Used for |
|---|---|
| Ability list | An object with more than one usable ability. The list opens at the pointer; choose the ability to activate. |
| Choice list | A named choice, such as a colour or a card name. Long lists have a search field. |
| Number | A single value with a minimum and maximum, such as X or a life payment. |
| Multiple numbers | A distribution across several items, with a running total such as `3 out of 5` and a **Choose** button. |
| Piles | Splitting cards into two piles, for example for a Fact or Fiction effect. |
| Card selection | Choosing cards from a revealed set, a graveyard, a hand or a library. |
| Check boxes | Choosing any number of items from a list. |

When several triggered abilities you control would go on the stack at the same time, the game asks for their order. Right-click a card in that dialog to make the choice automatic in future: first or last for that ability, or first or last for any ability with the same text. The battlefield menu under **Auto-answers** resets those rules.

### Auto-answers

Right-click **Yes** or **No** on a yes/no prompt to answer the same question automatically from then on:

- For the same text and the same ability.
- For the same text, regardless of the source.

The same menu has **Reset all YES/NO auto-answers**. The battlefield right-click menu also resets auto-selected replacement effects and automatic trigger ordering.

## Combat

**Declare attackers.** Creatures you may attack with are highlighted. Click a creature to attack with it; with one possible defender it attacks that player or planeswalker immediately. When several defenders are possible, the game asks which one each attacker attacks. Click an attacking creature again to remove it from combat. If every available creature should attack the same defender, the **Special** button offers **All attack**.

**Declare blockers.** Click a blocker to block with it; when only one attacker can be blocked, the block is assigned immediately, and otherwise the game asks which attacker it blocks. Click the blocker again to undo the block. A creature that can block several attackers is assigned one attacker at a time.

**Combat damage.** When an attacker is blocked by several creatures, the player who assigns its damage gets a multi-number dialog titled **Assign combat damage**. Each blocker is listed in the order it was declared, with its power and toughness; the default split assigns lethal damage in that order, and you can adjust the amounts. With trample, damage left over after the blockers goes to the defending player. Some cards ask for confirmation before damage is assigned as though the creature were unblocked.

Arrows on the battlefield show the declared attacks and blocks, and a blocked attacker is drawn in a different colour from an unblocked one.

## Mulligan

The mulligan prompt appears before the first turn. It reads **Mulligan down to N cards?** or, when the mulligan is free, **Mulligan for free, draw another N cards?**. The two buttons are **Mulligan** and **Keep**. Keeping ends your mulligan decisions for the game.

The table's mulligan rule is chosen in **Custom Options...** when the table is created:

| Option | Rule |
|---|---|
| Game Default | The mulligan the game type uses unless the table overrides it. |
| Vancouver | Scry after keeping, with the hand size reduced per mulligan. |
| Paris | No scry; each mulligan reduces the hand size. |
| London | Draw a new hand, then put cards on the bottom. |
| Smoothed London | London with the hand smoothed toward the deck's land ratio. |
| Canadian Highlander | The Canadian Highlander mulligan. |

The same dialog sets the number of free mulligans.

## Sideboard between games

In a match, the server opens a deck editor in sideboarding mode between games. The window shows your maindeck and your sideboard; move cards between them, then press **SUBMIT**. A countdown runs on the submit button, and the next game starts when the table's sideboard step ends.

During a game, right-click a player panel and choose **View sideboard**:

- Your own sideboard is always available.
- A computer opponent's sideboard is available.
- A human opponent's sideboard is restricted.

The same menu offers **View deck** with the same restrictions, and **Cards on hand** to request permission to see another player's hand. A hand request can be made once per game; the owner can allow or revoke requests from their own menu.

## Zones and card information

- Click the graveyard icon in a player panel to open that graveyard in a card window. The window updates as cards move.
- Click the exile icon to open the exile zone.
- Hover a card to preview it, and press **Alt+E** to enlarge the preview. **Alt+S** shows the other face of a double-faced, modal or split card.
- The stack pane shows the order of spells and abilities; the game log records what resolved.

## Desktop and web differences

The rules and the set of prompts are the same in both clients; the layout is not. The web client draws its own prompt window, sideboard window and chat rail, and its replay viewer has a scrubber instead of the desktop replay buttons. Use [The web client](web-client.md) for its controls and [Web replay](web-replay.md) for its replay viewer. This page describes the desktop client.

Base XMage behaviour is documented in the [upstream XMage wiki](https://github.com/magefree/mage/wiki).

## Related

- [The desktop client](desktop-client.md)
- [Game modes](game-modes.md)
- [The web client](web-client.md)
- [Keyboard shortcuts](../reference/keyboard-shortcuts.md)
- [Troubleshooting](../reference/troubleshooting.md)
