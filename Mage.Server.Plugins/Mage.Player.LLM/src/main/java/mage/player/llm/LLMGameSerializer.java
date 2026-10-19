package mage.player.llm;

import mage.abilities.Ability;
import mage.abilities.ActivatedAbilityImpl;
import mage.abilities.SpellAbility;
import mage.abilities.costs.AlternativeCost;
import mage.abilities.costs.AlternativeCostSourceAbility;
import mage.abilities.costs.AlternativeSourceCosts;
import mage.abilities.costs.Cost;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.costs.mana.ManaCosts;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.keyword.*;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.abilities.mana.ManaOptions;
import mage.Mana;
import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.constants.TurnPhase;
import mage.constants.Zone;
import mage.filter.StaticFilters;
import mage.game.Game;
import mage.game.GameState;
import mage.game.permanent.Permanent;
import mage.game.stack.Spell;
import mage.game.stack.StackObject;
import mage.players.PlayableObjectsList;
import mage.players.Player;
import mage.view.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Serializes an XMage game state into a text representation suitable for
 * consumption by an LLM. Includes full game history, player action history,
 * and current board state with available actions.
 */
public class LLMGameSerializer {

    /**
     * Build a complete prompt for the LLM, with optional board cursor optimization.
     * When boardCursor is non-zero and matches the current board hash, the full board
     * state section is replaced with a compact "(Board unchanged)" marker to save tokens.
     * landIdMap is populated with short ID -> permanent UUID mappings for mana planning.
     */
    public static String buildPrompt(Game game, UUID playerId, LLMConfig config, List<String> selfLog, int lastShownLogSize, List<String> actionDescriptions, long boardCursor, Map<String, UUID> landIdMap) {
        Player player = game.getPlayer(playerId);
        if (player == null) throw new IllegalStateException("Player not found: " + playerId);

        GameState state = game.getState();
        StringBuilder sb = new StringBuilder();

        // ================================================================
        // SECTION 1: Game events since last priority call (incremental only)
        // ================================================================
        // Snapshot before iterating: gameLog is appended by LLMGameLogCollector
        // from game-event threads while this prompt is built on the LLM decision
        // thread — iterating the live list throws ConcurrentModificationException.
        // Copy under the list's own monitor (see LLMPlayer.snapshotGameLog) so the
        // copy is atomic against appenders that hold it.
        List<String> gameLog;
        synchronized (state.getGameLog()) {
            gameLog = new ArrayList<>(state.getGameLog());
        }
        int totalEvents = gameLog.size();
        int startIdx = Math.max(0, Math.min(lastShownLogSize, totalEvents));
        int newCount = totalEvents - startIdx;

        // Minor consistency: FIXED header text (the header previously embedded
        // the count — "=== NEW GAME EVENTS (last N events) ===" — which weakened
        // the exact-match header contract the system prompt documents). The
        // count now rides on its own labeled line below the header.
        sb.append("=== NEW GAME EVENTS ===\n");
        if (newCount > 0) {
            sb.append("(New events since your last action: ").append(newCount).append(")\n");
            for (int i = startIdx; i < totalEvents; i++) {
                sb.append(cleanHtml(gameLog.get(i))).append("\n");
            }
        } else {
            sb.append("(No new events since your last action)\n");
        }

        // Always show total event count so the LLM knows game length
        sb.append("(Total game events: ").append(totalEvents).append(")\n");

        // Prior actions carried in conversation history (compact summaries + answers + thinking)

        // ================================================================
        // SECTION 3: Current board state (skipped if unchanged via cursor)
        // ================================================================
        sb.append("\n=== CURRENT BOARD STATE ===\n");
        // Phase suppressed when identical to Step (see formatTurnPhaseStep).
        sb.append(LLMGameSerializer.formatTurnPhaseStep(game)).append("\n");
        sb.append("Active player: ").append(game.getPlayer(game.getActivePlayerId()).getName()).append("\n");

        long currentBoardHash = computeBoardHash(game, playerId);
        if (boardCursor != 0 && boardCursor == currentBoardHash) {
            // Board unchanged — skip full render to save tokens.
            // BUT always render the stack — it's the most critical info for
            // counterspell decisions and changes frequently between priority
            // windows. Omitting it causes the LLM to use stale stack state
            // from conversation history, leading to failed counterspells.
            // The stack render goes through the full-text-once funnel too
            // (seen-set lookup + ThreadLocal handoff), so a FIRST sighting of
            // an opponent's spell on an unchanged board still carries its
            // full text, and repeats render shorthand.
            sb.append("(Board unchanged since your last priority — see previous message for full state.)\n");
            sb.append("\n--- Stack ---\n");
            if (game.getStack().isEmpty()) {
                sb.append("  (empty)\n");
            } else {
                // Numbered identically to the full board render (minor
                // consistency: this branch was unnumbered).
                withSeenNames(game.getId(), playerId, () -> {
                    int pos = 1;
                    for (StackObject so : game.getStack()) {
                        appendStackObjectHeader(sb, pos++, so, game);
                        appendStackObjectRule(sb, so, game);
                    }
                    return null;
                });
            }
        } else {
            sb.append("\n");
            // Full board render through the encounter funnel: the (game, seat)
            // seen-set is looked up once, exposed to the deep formatters via
            // ThreadLocal, and cleared in a finally block (the proven
            // ACTIVE_DECKLIST handoff pattern, now carrying the live set so
            // first-emission marks persist into the registry).
            withSeenNames(game.getId(), playerId, () -> {
                appendBoardState(sb, game, player, playerId, config);
                return null;
            });
        }

        // ================================================================
        // SECTION 4: Available actions
        // ================================================================
        sb.append("\n=== AVAILABLE ACTIONS ===\n");
        List<String> actions = actionDescriptions;
        sb.append("0. Pass priority (skip this priority window only)\n");
        if (actions.isEmpty()) {
            sb.append("(No other actions available)\n");
        }
        for (int i = 0; i < actions.size(); i++) {
            sb.append(i + 1).append(". ").append(actions.get(i)).append("\n");
        }

        // Always offer skip options as the last entries
        int skipTurnIdx = actions.size() + 1;
        int skipEndStepIdx = actions.size() + 2;
        int skipStackIdx = actions.size() + 3;
        int cancelSkipIdx = actions.size() + 4;

        sb.append(skipTurnIdx).append(". SKIP TO YOUR NEXT TURN\n");
        sb.append(skipEndStepIdx).append(". SKIP TO END STEP BEFORE YOUR TURN\n");
        sb.append(skipStackIdx).append(". SKIP UNTIL STACK RESOLVES\n");
        sb.append(cancelSkipIdx).append(". CANCEL ALL SKIPS\n");
        if (config.isConcedeEnabled()) {
            int concedeIdx = actions.size() + 5;
            sb.append(concedeIdx).append(". CONCEDE\n");
        }

        // Single reply-format footer (stated ONCE, at the end of the
        // prompt). Previously the action-number instruction appeared here AND
        // again after the mana list with slightly different wording; identity
        // only is kept at this position (mock harnesses scan the action list
        // up to this line).
        sb.append("\nYou are player: ").append(player.getName()).append(".");

        // Mana sources with short IDs for mana planning.
        // Lands AND non-land mana sources (Mox Diamond, Sol Ring, mana dorks,
        // ...) — the section used to filter isLand, so artifact/creature mana
        // could never appear in a plan (available but unmentionable). Non-land
        // sources are appended after the
        // lands; each entry keeps the mana-vs-non-mana ability split added
        // 2026-08-15 (tap-for-mana options vs the rules of its other activated
        // abilities — e.g. Urza's Saga's {T} conflict).
        List<Permanent> manaSources = game.getBattlefield().getAllPermanents().stream()
                .filter(p -> p.isControlledBy(playerId) && !p.isTapped()
                        && (p.isLand(game) || isManaSource(p, game)))
                .collect(Collectors.toList());
        if (!manaSources.isEmpty()) {
            sb.append("\n\n--- Untapped Mana Sources (for mana planning) ---\n");
            int shown = 0;
            for (int i = 0; i < manaSources.size(); i++) {
                Permanent source = manaSources.get(i);
                // Show what mana it can produce by scanning its abilities
                List<String> manaOptions = new ArrayList<>();
                List<String> nonManaOptions = new ArrayList<>();
                for (Object ab : source.getAbilities()) {
                    if (ab instanceof ActivatedManaAbilityImpl) {
                        ActivatedManaAbilityImpl ma = (ActivatedManaAbilityImpl) ab;
                        for (Mana m : ma.getNetMana(game)) {
                            String s = m.toString();
                            if (!s.isEmpty()) manaOptions.add(s);
                        }
                    } else if (ab instanceof ActivatedAbilityImpl) {
                        ActivatedAbilityImpl aa = (ActivatedAbilityImpl) ab;
                        String rule = aa.getRule();
                        if (rule != null && !rule.isEmpty()) {
                            // A land's PlayLandAbility renders its rule as
                            // "Play <name>" — that is the special land-drop action,
                            // not an activated ability of the permanent, so it must
                            // not appear as a "(non-mana: Play Forest)" suffix.
                            if (rule.equals("Play " + source.getName())) {
                                continue;
                            }
                            nonManaOptions.add(rule);
                        }
                    }
                }
                if (manaOptions.isEmpty() && nonManaOptions.isEmpty()) {
                    // Nothing usable to report — listing it would only pad the prompt.
                    continue;
                }
                if (shown >= MANA_SOURCE_MAX_ENTRIES) {
                    sb.append("  ... (").append(manaSources.size() - i)
                            .append(" more untapped mana sources not listed for prompt size; they are still")
                            .append(" auto-tapped when a mana plan omits them)\n");
                    break;
                }

                String shortId = "L" + (shown + 1);
                landIdMap.put(shortId, source.getId());
                sb.append("  ").append(shortId).append(": ").append(source.getName());
                if (!source.isLand(game)) {
                    // Marked so the LLM knows this id is not a land (no land drop used).
                    sb.append(" [").append(source.getCardType().stream()
                                    .map(t -> t.toString().toLowerCase(Locale.ROOT))
                                    .collect(Collectors.joining("/")))
                            .append(", mana source]");
                }
                if (!manaOptions.isEmpty()) {
                    sb.append(" -> ").append(String.join("/", manaOptions));
                }
                if (!nonManaOptions.isEmpty()) {
                    sb.append(" (non-mana: ").append(String.join("; ", nonManaOptions)).append(")");
                }
                sb.append("\n");
                shown++;
            }
        }

        // The ONE reply-format footer. Previously the action-number
        // instruction appeared twice (after the action list and again here)
        // with slightly different wording; the action list now carries identity
        // only (`You are player: NAME.` — also the terminating line harness
        // scanners rely on), and every reply-format statement lives here,
        // emitted once, whether or not mana sources are listed.
        sb.append("\nTo choose an action, respond with ONLY the action number (e.g., '3').\n");
        sb.append("If mana of a specific color matters, append your mana plan: action:3 mana:L1,L3\n");
        sb.append("If mana is omitted, sources are auto-tapped (a dual land may be tapped for the wrong color).");

        return sb.toString();
    }

    /**
     * Cap on how many mana sources the prompt lists. A wide board (tokens +
     * rocks + many lands) otherwise floods the section; entries past the cap are
     * dropped with an explicit "N more omitted" line so the LLM knows a plan may
     * legitimately omit them (auto-tap still covers the rest).
     */
    private static final int MANA_SOURCE_MAX_ENTRIES = 24;

    /**
     * Whether a permanent can produce mana right now — i.e. it has at least one
     * activated mana ability the player may activate. Uses the engine's own
     * availability check, so summoning-sick dorks / already-used rocks are not
     * advertised.
     */
    private static boolean isManaSource(Permanent perm, Game game) {
        // getAvailableActivatedManaAbilities returns abilities only where
        // canActivate() passes (see AbilitiesImpl:150-158).
        return !perm.getAbilities(game)
                .getAvailableActivatedManaAbilities(Zone.BATTLEFIELD, perm.getControllerId(), game)
                .isEmpty();
    }

    /**
     * Append the full board state section to the prompt builder.
     * Extracted from buildPrompt to allow conditional skipping via board cursor.
     */
    private static void appendBoardState(StringBuilder sb, Game game, Player player, UUID playerId, LLMConfig config) {
        // Players summary
        sb.append("--- Players ---\n");
        for (Player p : game.getPlayers().values()) {
            sb.append(formatPlayer(p, game, playerId));
        }

        // Floating mana — show prominently so LLM knows what's available to spend
        sb.append("\n--- Floating Mana ---\n");
        Player self = player;
        if (self.getManaPool().isEmpty()) {
            sb.append("  None floating\n");
        } else {
            sb.append("  ").append(self.getManaPool().getMana().toString()).append("\n");
        }

        // Stack
        sb.append("\n--- Stack ---\n");
        if (game.getStack().isEmpty()) {
            sb.append("(empty)\n");
        } else {
            int pos = 1;
            for (StackObject so : game.getStack()) {
                appendStackObjectHeader(sb, pos++, so, game);
                appendStackObjectRule(sb, so, game);
            }
        }

        // Your hand
        sb.append("\n--- Your Hand ---\n");
        for (Card card : player.getHand().getCards(game)) {
            sb.append(formatCardBrief(card)).append("\n");
        }
        if (player.getHand().isEmpty()) sb.append("  (empty)\n");

        // Your battlefield
        sb.append("\n--- Your Battlefield ---\n");
        List<Permanent> yourPerms = game.getBattlefield().getAllPermanents().stream()
                .filter(p -> p.isControlledBy(playerId))
                .collect(Collectors.toList());
        for (Permanent perm : yourPerms) {
            sb.append("  ").append(formatPermanent(perm, game)).append("\n");
        }
        if (yourPerms.isEmpty()) sb.append("  (empty)\n");

        // Opponent battlefield
        for (Player opp : game.getPlayers().values()) {
            if (opp.getId().equals(playerId)) continue;
            sb.append("\n--- ").append(opp.getName()).append("'s Battlefield ---\n");
            List<Permanent> oppPerms = game.getBattlefield().getAllPermanents().stream()
                    .filter(p -> p.isControlledBy(opp.getId()))
                    .collect(Collectors.toList());
            for (Permanent perm : oppPerms) {
                sb.append("  ").append(formatPermanent(perm, game)).append("\n");
            }
            if (oppPerms.isEmpty()) sb.append("  (empty)\n");
        }

        // Exile (owner-labeled, name+cost only, capped at 5 per owner
        // like the graveyard — previously the full text of every exiled card
        // was dumped for both players)
        sb.append("\n--- Exile ---\n");
        List<Card> exiledCards = game.getExile().getAllCards(game);
        if (exiledCards.isEmpty()) {
            sb.append("  (empty)\n");
        } else {
            boolean ownerShown = false;
            for (Player p : game.getPlayers().values()) {
                List<Card> mine = exiledCards.stream()
                        .filter(c -> c != null && p.getId().equals(c.getOwnerId()))
                        .collect(Collectors.toList());
                if (mine.isEmpty()) continue;
                ownerShown = true;
                sb.append("  ").append(p.getName()).append(": ").append(mine.size()).append(" card")
                  .append(mine.size() == 1 ? "" : "s").append(" (")
                  .append(mine.stream().limit(5).map(c -> {
                      String cost = c.getManaCost().getText();
                      return cost == null || cost.isEmpty() ? c.getName() : c.getName() + " [" + cost + "]";
                  }).collect(Collectors.joining(", ")));
                if (mine.size() > 5) sb.append(", ...");
                sb.append(")\n");
            }
            if (!ownerShown) {
                // Fallback: exile can hold cards whose owner is not a player in
                // this game (e.g. a card owned by a player who left, or a token
                // source) — listing nothing would silently hide them, so fall
                // back to the same capped owner-less rendering.
                sb.append("  ").append(exiledCards.size()).append(" cards (")
                  .append(exiledCards.stream().limit(5).map(c -> {
                      String cost = c.getManaCost().getText();
                      return cost == null || cost.isEmpty() ? c.getName() : c.getName() + " [" + cost + "]";
                  }).collect(Collectors.joining(", ")));
                if (exiledCards.size() > 5) sb.append(", ...");
                sb.append(")\n");
            }
        }

        // --- Active skip status ---
        sb.append("\n--- ACTIVE SKIP STATUS ---\n");
        boolean anyActive = appendSkipStatus(sb, player, "Skip to your next turn", player.getPassedAllTurns());
        anyActive |= appendSkipStatus(sb, player, "Skip to end step before your turn", player.getPassedUntilEndStepBeforeMyTurn());
        anyActive |= appendSkipStatus(sb, player, "Skip until stack resolves", player.getPassedUntilStackResolved());
        anyActive |= appendSkipStatus(sb, player, "Skip until end of turn", player.getPassedUntilEndOfTurn());
        anyActive |= appendSkipStatus(sb, player, "Skip turn", player.getPassedTurn());
        if (!anyActive) {
            sb.append("  None active — you are receiving priority normally.\n");
        } else {
            sb.append("  These skips will auto-pass unless you cancel them or take an action.\n");
        }
    }

    /**
     * Render a decision-facing card line (formatCardForChoice) inside the
     * (game, seat) encounter funnel. Used by LLMPlayer's choice prompts
     * (mulligan hand, target selection, pile choice, …) so the FIRST sighting
     * of a name carries the card's full rules text and later prompts render
     * shorthand — same mechanic as the priority prompt's board render.
     *
     * <p>Package-private because it must be called only for real decision
     * prompts; LLMPlayer passes its own playerId as the seat.
     */
    static String formatCardForChoiceOnce(Game game, UUID playerId, Card card) {
        return withSeenNames(game.getId(), playerId, () -> formatCardForChoice(card, game));
    }

    /**
     * Render a decision-facing card line with the optional-game overload,
     * inside the (game, seat) encounter funnel (see
     * {@link #formatCardForChoiceOnce(Game, UUID, Card)}).
     */
    static String formatCardForChoiceOnce(UUID gameId, UUID playerId, Card card) {
        return withSeenNames(gameId, playerId, () -> formatCardForChoice(card));
    }

    /**
     * Placeholder name for a FACE-DOWN spell (morph/disguise cast) on the stack.
     * The real card name is hidden information: a human opponent sees only
     * "a face-down creature spell", never the card. Emitting
     * {@code spell.getName()} here would hand the LLM seat knowledge no human
     * has (and contaminate the postgame reflection).
     */
    static final String FACE_DOWN_SPELL_NAME = "face-down spell";

    /**
     * Placeholder rule line for a FACE-DOWN spell — replaces the (leaking) real
     * rules text. See {@link #FACE_DOWN_SPELL_NAME}.
     */
    static final String FACE_DOWN_SPELL_RULE = "(face-down spell — identity hidden)";

    /**
     * Whether a stack object is a face-down spell whose real identity is hidden
     * information. Only {@link Spell} can be face-down ({@code Spell.faceDown}
     * is set at construction from {@code SpellAbilityCastMode.isFaceDown()},
     * i.e. morph/megamorph/disguise); a non-spell stack object (an activated
     * ability) is never face-down.
     * <p>
     * This is the stack-path mirror of {@code formatPermanent}'s correct
     * face-down handling: the permanent path renders the permanent's LIVE
     * filtered rules (only {@code worksFaceDown} abilities survive). The stack
     * path has no equivalent live-filtered rules — {@code Spell.getRules(game)}
     * delegates straight to the backing card — so the only safe render is a
     * placeholder that reveals nothing.
     */
    private static boolean isFaceDownSpell(StackObject so, Game game) {
        return so instanceof Spell && ((Spell) so).isFaceDown(game);
    }

    /**
     * The display name of a stack object for any prompt/history surface: the
     * real name, or {@link #FACE_DOWN_SPELL_NAME} when the object is a
     * face-down spell. Package-private so every stack-name render site (this
     * class's board render, the compact situation summary, and LLMPlayer's
     * action-failure hint) funnels through ONE hidden-information rule.
     */
    static String stackObjectDisplayName(StackObject so, Game game) {
        return isFaceDownSpell(so, game) ? FACE_DOWN_SPELL_NAME : so.getName();
    }

    /**
     * Append the numbered stack header line, hiding a face-down spell's name.
     * The controller name is public information and is always kept. Extracted
     * so the two stack-render call sites (unchanged-board branch and full board
     * render) cannot drift apart on the hidden-information rule.
     */
    private static void appendStackObjectHeader(StringBuilder sb, int pos, StackObject so, Game game) {
        sb.append("  ").append(pos).append(". ").append(stackObjectDisplayName(so, game)).append(" (")
          .append(game.getPlayer(so.getControllerId()).getName()).append(")\n");
    }

    /**
     * Append the rule text for a stack object through the full-text-once funnel.
     * Spells: first sighting of the spell's NAME for this (game, seat) emits the
     * full spell text (Spell.getRules(game), ALL rules strings, cleanHtml'd)
     * with the explicit FULL TEXT marker; repeat appearances render the existing
     * compact rule join. Abilities (non-spell stack objects): the ability's own
     * rule is short and per-instance (each activation is its own object with
     * its own rule text) — they keep the existing direct render and do NOT
     * consume a name sighting.
     * <p>
     * FACE-DOWN spells (morph/disguise) are a separate branch that renders the
     * {@link #FACE_DOWN_SPELL_RULE} placeholder and returns BEFORE
     * {@link #fullTextOnce} — deliberately:
     * <ul>
     *   <li>the real name and rules text are hidden information and must never
     *   reach the prompt;</li>
     *   <li>and the face-down spell must NOT register (nor consult) a name
     *   sighting: entering the real name into the (game, seat) seen-set would
     *   make a LATER face-UP render of the same card shorthand with no full
     *   text, and that differential is itself an inference channel back to the
     *   morph identity. A face-down sighting must not consume the one
     *   full-text slot that the face-up card is entitled to.</li>
     * </ul>
     */
    private static void appendStackObjectRule(StringBuilder sb, StackObject so, Game game) {
        if (so instanceof Spell) {
            if (isFaceDownSpell(so, game)) {
                sb.append("     ").append(FACE_DOWN_SPELL_RULE).append("\n");
                return;
            }
            Spell spell = (Spell) so;
            String fullText = fullTextOnce(spell.getName(), () -> spell.getRules(game));
            if (!fullText.isEmpty()) {
                sb.append("     ").append(fullText).append("\n");
            } else {
                sb.append("     ").append(cleanHtml(String.join(" | ", spell.getRules(game)))).append("\n");
            }
        } else {
            sb.append("     ").append(so.getStackAbility().getRule()).append("\n");
        }
    }

    /**
     * Compute a hash of the board state visible to the player.
     * Used to detect unchanged board between priority calls and skip re-rendering.
     * Covers: life totals, hand sizes, battlefield permanents (name, tapped, power/toughness),
     * stack objects, exile, and floating mana. Does NOT include game log (always changes).
     */
    public static long computeBoardHash(Game game, UUID playerId) {
        long hash = 1L;
        for (Player p : game.getPlayers().values()) {
            hash = hash * 31 + p.getLife();
            hash = hash * 31 + p.getHand().size();
            hash = hash * 31 + p.getLibrary().size();
            if (!p.getManaPool().isEmpty()) {
                hash = hash * 31 + p.getManaPool().getMana().count();
            }
        }
        // Battlefield permanents — name, tapped state, P/T, ALL counters (loyalty
        // changes alone previously left the hash unchanged, breaking the cursor on
        // planeswalker activations), attachments (aura/equipment on/off), controller.
        for (Permanent perm : game.getBattlefield().getAllPermanents()) {
            hash = hash * 31 + perm.getName().hashCode();
            hash = hash * 31 + (perm.isTapped() ? 1 : 0);
            if (perm.isCreature(game)) {
                hash = hash * 31 + perm.getPower().getValue();
                hash = hash * 31 + perm.getToughness().getValue();
            }
            // All counters, not just +1/+1: loyalty, charge, lore, etc.
            for (Map.Entry<String, mage.counters.Counter> e : perm.getCounters(game).entrySet()) {
                hash = hash * 31 + e.getKey().hashCode();
                hash = hash * 31 + e.getValue().getCount();
            }
            // Attachments — a Sword landing on a creature must invalidate the cursor
            for (java.util.UUID attId : perm.getAttachments()) {
                hash = hash * 31 + attId.hashCode();
            }
            // Controller changes (e.g. Threaten effects)
            if (perm.getControllerId() != null) {
                hash = hash * 31 + perm.getControllerId().hashCode();
            }
        }
        // Stack
        for (StackObject so : game.getStack()) {
            hash = hash * 31 + so.getName().hashCode();
        }
        // Exile count
        hash = hash * 31 + game.getExile().getAllCards(game).size();
        // Graveyard sizes
        for (Player p : game.getPlayers().values()) {
            hash = hash * 31 + p.getGraveyard().size();
        }
        return hash;
    }

    /**
     * Get human-readable descriptions of alternative costs available for a card.
     * Handles Evoke (Solitude/Fury), Pitch (Force of Will/Force of Negation), Morph, etc.
     */
    private static final java.util.logging.Logger ALT_COST_LOGGER =
            java.util.logging.Logger.getLogger(LLMGameSerializer.class.getName());

    static List<String> getAlternativeCostTexts(Card card, Game game, UUID playerId) {
        return getAlternativeCostTexts(card, game, playerId, null);
    }

    /**
     * As {@link #getAlternativeCostTexts(Card, Game, UUID)}, but reuses a
     * caller-computed {@link ManaOptions} so a whole action list pays for the
     * (non-trivial) available-mana walk once. Null = compute it here.
     * <p>
     * Payability gate (an alternative cost must not be offered
     * despite insufficient mana): the engine's cost API cannot be trusted for
     * alternative MANA costs — {@code ManaCostImpl.canPay} returns true
     * unconditionally (Mage/src/.../mana/ManaCostImpl.java:247-249), so
     * {@code canActivateAlternativeCostsNow} reports a {1}{R} dash as activatable
     * with zero red sources, and the entry lands in the action list. The rule
     * itself is a core bug; the plugin-side GATE below is correct on
     * its own and is what we ship: the alternative cost's mana components are
     * priced against the mana the ENGINE says is actually available
     * ({@code Player.getManaAvailable}), and the entry is only offered when one
     * full payment combination is affordable. Non-mana components keep the
     * engine's verdict — those check implementations are real (PayLifeCost
     * checks life, target costs check legality), unlike the mana stub.
     */
    static List<String> getAlternativeCostTexts(Card card, Game game, UUID playerId,
                                                ManaOptions callerAvailableMana) {
        List<String> altCosts = new ArrayList<>();
        try {
            SpellAbility sa = card.getSpellAbility();
            if (sa == null) {
                ALT_COST_LOGGER.fine("  [alt-cost] " + card.getName() + ": spellAbility is null");
                return altCosts;
            }
            for (Ability ability : card.getAbilities(game)) {
                if (ability instanceof AlternativeSourceCosts) {
                    AlternativeSourceCosts asc = (AlternativeSourceCosts) ability;
                    // Must check both isAvailable (condition check, e.g. NotMyTurnCondition)
                    // and canActivateAlternativeCostsNow (cost payability check)
                    boolean isAvailable = asc.isAvailable(sa, game);
                    boolean canActivate = isAvailable && asc.canActivateAlternativeCostsNow(sa, game);
                    boolean manaPayable = canActivate
                            && alternativeCostManaIsPayable(asc, game, playerId, callerAvailableMana);
                    ALT_COST_LOGGER.info("  [alt-cost] " + card.getName()
                            + " found alt cost: " + ability.getClass().getSimpleName()
                            + " rule=" + asc.getRule()
                            + " isAvailable=" + isAvailable
                            + " canActivateNow=" + canActivate
                            + " manaPayable=" + manaPayable);
                    if (canActivate && manaPayable) {
                        String text = asc.getRule();
                        if (text != null && !text.isEmpty()) {
                            altCosts.add(text);
                        }
                    }
                }
            }
            // Controller-scoped alternative costs (Omniscience, Aluren, Tamiyo emblem):
            // these live on the PLAYER, not on the card, so the card-scoped loop above
            // never sees them. Without this scan a free cast via Omniscience produces
            // zero alt-cost action entries — the LLM can only pick the normal cast,
            // which then fails against an unpayable mana cost

            Player controller = game.getPlayer(playerId);
            if (controller != null) {
                for (AlternativeSourceCosts asc : controller.getAlternativeSourceCosts()) {
                    // isAvailable/canActivateAlternativeCostsNow must be evaluated
                    // against the SPELL ability (like the card-scoped loop above):
                    // AlternativeCostSourceAbility.isAvailable resolves its filter
                    // via game.getCard(source.getSourceId()), i.e. the card being cast.
                    boolean isAvailable = asc.isAvailable(sa, game);
                    boolean canActivate = isAvailable && asc.canActivateAlternativeCostsNow(sa, game);
                    boolean manaPayable = canActivate
                            && alternativeCostManaIsPayable(asc, game, playerId, callerAvailableMana);
                    ALT_COST_LOGGER.info("  [alt-cost] " + card.getName()
                            + " found controller alt cost: " + asc.getClass().getSimpleName()
                            + " rule=" + asc.getRule()
                            + " isAvailable=" + isAvailable
                            + " canActivateNow=" + canActivate
                            + " manaPayable=" + manaPayable);
                    if (canActivate && manaPayable) {
                        String text = asc instanceof AlternativeCostSourceAbility
                                ? ((AlternativeCostSourceAbility) asc).getAlternativeCostText(sa, game)
                                : asc.getRule();
                        if (text != null && !text.isEmpty()) {
                            altCosts.add(text);
                        }
                    }
                }
            }
            if (altCosts.isEmpty() && !card.isLand(game)) {
                ALT_COST_LOGGER.fine("  [alt-cost] " + card.getName() + ": no alt costs found (checked "
                        + card.getAbilities(game).size() + " abilities)");
            }
        } catch (Exception e) {
            ALT_COST_LOGGER.warning("  [alt-cost] " + card.getName() + ": EXCEPTION: " + e.getMessage());
        }
        return altCosts;
    }

    /**
     * Whether the alternative cost's mana components can actually be paid with
     * the mana available to the player, per the engine's own mana accounting.
     * <p>
     * Returns true when the alternative cost carries NO mana component (nothing
     * to check here — the engine's non-mana cost checks are real) and when the
     * player cannot be resolved (no player, no mana model to compare against;
     * the caller-side engine verdict stays authoritative).
     */
    static boolean alternativeCostManaIsPayable(AlternativeSourceCosts asc, Game game, UUID playerId,
                                               ManaOptions callerAvailableMana) {
        Player player = game.getPlayer(playerId);
        if (player == null) {
            return true;
        }
        if (asc.getCosts() == null) {
            return true;
        }
        // Collect the mana components. Both alternative-cost families wrap them:
        // AlternativeSourceCostsImpl holds the raw cost, AlternativeCostSourceAbility
        // holds AlternativeCost wrappers around it (see their getCosts() impls).
        ManaCosts<ManaCost> manaCosts = new ManaCostsImpl<>();
        boolean sawManaComponent = false;
        for (Cost cost : asc.getCosts()) {
            Cost inner = cost;
            if (cost instanceof AlternativeCost) {
                inner = ((AlternativeCost) cost).getCost();
            }
            if (inner instanceof ManaCost) {
                manaCosts.add((ManaCost) inner);
                sawManaComponent = true;
            }
        }
        if (!sawManaComponent) {
            return true;
        }

        ManaOptions available = callerAvailableMana != null
                ? callerAvailableMana
                : player.getManaAvailable(game);
        Ability sourceAbility = asc instanceof Ability ? (Ability) asc : null;
        boolean canPayLife = sourceAbility != null && player.canPayLifeCost(sourceAbility);
        // One affordable full combination is enough (Phyrexian/hybrid costs have
        // several — e.g. {3}{U/P} is payable as {3}{U} or {3} + 2 life).
        for (Mana option : manaCosts.getOptions(canPayLife)) {
            if (available.enough(option)) {
                return true;
            }
        }
        ALT_COST_LOGGER.info("  [alt-cost] " + asc.getRule() + " NOT payable: needs "
                + manaCosts.getText() + ", available=" + available);
        return false;
    }

    // --- Formatting helpers ---

    /**
     * Encounter registry for the full-text-once-then-shorthand card render
     * for each (game, seat) it records
     * which card NAMES that seat has already been shown IN FULL during this
     * game. The first sighting of a name emits the card's complete rules text
     * (marked `— FULL TEXT: ...`); every later sighting renders shorthand
     * (name + cost + types + P/T). This replaces the decklist-name skip
     * heuristic (broken: a Bant Spirits seat had Spell Queller's full text in
     * its system-prompt decklist on every call and still concluded "no flash"),
     * and fixes its latent seat collision: the old
     * {@code Map<UUID gameId, Set<String>>} was keyed by game ONLY, so in an
     * LLM-vs-LLM game the second seat's useDeck() overwrote the first seat's
     * registration.
     *
     * <p>Keyed by game ID (not ThreadLocal): useDeck()/game-end cleanup run on
     * the game-start/game thread while prompts are built on the LLM decision
     * thread — a ThreadLocal was confirmed broken in a bot-game
     * run. Per-seat sets are ConcurrentHashMap.newKeySet() so the whole
     * registry is safe even under concurrent mutation (each seat renders
     * serially on its own decision thread, but two seats share the game map).
     *
     * <p>Lifecycle: the set starts EMPTY each game — useDeck() does NOT
     * pre-register decklist names (the whole point: decklist cards still get
     * their full text on first in-game sighting). It grows on first emission.
     * {@link #unregisterSeenCardNames(UUID)} drops the whole game entry (all
     * seats) at game end from LLMPlayer.releaseGameScopedRegistries.
     */
    private static final Map<UUID, Map<UUID, Set<String>>> SEEN_CARD_NAMES_BY_GAME =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Ensure a seen-name registry exists for (gameId, playerId) and return the
     * live per-seat set. The returned set is the one the render funnel
     * mutates — holding it (not a copy) means mutations persist into the
     * registry.
     */
    public static Set<String> seenCardNames(UUID gameId, UUID playerId) {
        return SEEN_CARD_NAMES_BY_GAME
                .computeIfAbsent(gameId, g -> new java.util.concurrent.ConcurrentHashMap<>())
                .computeIfAbsent(playerId, p -> java.util.concurrent.ConcurrentHashMap.newKeySet());
    }

    /** Drop the seen-name registry for a finished game (all seats). Idempotent. */
    public static void unregisterSeenCardNames(UUID gameId) {
        SEEN_CARD_NAMES_BY_GAME.remove(gameId);
    }

    /**
     * The seen-name set for the (game, seat) whose prompt is currently being
     * rendered on THIS thread. Set/cleared per prompt-builder call in a
     * finally block (the proven ACTIVE_DECKLIST handoff pattern) so deep
     * formatters can check-and-emit without a (game, playerId) parameter
     * threaded through every call.
     *
     * <p>Null means "no funnel active": a formatter invoked outside a prompt
     * render (e.g. a unit test calling formatCardForChoice directly) renders
     * full text without marking seen — a one-off render, no encounter state.
     */
    private static final ThreadLocal<Set<String>> ACTIVE_SEEN_NAMES = new ThreadLocal<>();

    /**
     * Run the render with the (game, seat) seen-set exposed to deep formatters.
     * Mutations through the ThreadLocal-held reference persist into the
     * registry (the live set is held, never a copy). The finally block clears
     * the ThreadLocal so no other render can inherit this seat's set.
     */
    private static <T> T withSeenNames(UUID gameId, UUID playerId, java.util.function.Supplier<T> render) {
        Set<String> seen = seenCardNames(gameId, playerId);
        ACTIVE_SEEN_NAMES.set(seen);
        try {
            return render.get();
        } finally {
            ACTIVE_SEEN_NAMES.remove();
        }
    }

    /** Marker prefixed to a card's full rules text on its FIRST sighting for this (game, seat). */
    static final String FULL_TEXT_MARKER = "— FULL TEXT: ";

    /**
     * The one shared check-and-emit at the heart of the design. First sighting
     * of the card NAME in this (game, seat) render: emits the card's FULL
     * rules text (all rules strings, cleanHtml'd, no cap) after the explicit
     * {@link #FULL_TEXT_MARKER}, and marks the name seen at the moment of
     * emission. Subsequent sightings (including later occurrences in the
     * SAME prompt) emit nothing here — the caller's shorthand render stands.
     *
     * @return the "— FULL TEXT: <rules>" segment to append, or "" when this
     *         is not the first sighting / no rules text / no funnel active.
     */
    static String fullTextOnce(String cardName, java.util.function.Supplier<List<String>> rules) {
        Set<String> seen = ACTIVE_SEEN_NAMES.get();
        if (seen == null) {
            return "";  // outside a prompt render — one-off, no encounter state
        }
        if (!seen.add(cardName)) {
            return "";  // already sighted this game: shorthand render stands
        }
        String text = cleanHtml(rules.get().stream().collect(Collectors.joining(" | ")));
        if (text.isEmpty()) {
            return "";  // vanilla card: nothing to mark (name is still seen)
        }
        return FULL_TEXT_MARKER + text;
    }

    private static String formatPlayer(Player p, Game game, UUID myId) {
        StringBuilder sb = new StringBuilder();
        String marker = p.getId().equals(myId) ? " (YOU)" : "";
        sb.append("  ").append(p.getName()).append(marker)
          .append(" | Life: ").append(p.getLife())
          .append(" | Hand: ").append(p.getHand().size())
          .append(" | Library: ").append(p.getLibrary().size());
        if (!p.getManaPool().isEmpty()) {
            sb.append(" | Mana: ").append(p.getManaPool().getMana().toString());
        }
        sb.append("\n");

        // Graveyard
        sb.append("    Graveyard: ");
        List<UUID> gy = p.getGraveyard().stream().collect(Collectors.toList());
        if (gy.isEmpty()) {
            sb.append("(empty)");
        } else {
            sb.append(gy.size()).append(" cards (")
              .append(gy.stream().limit(5).map(id -> {
                  Card c = game.getCard(id);
                  return c != null ? c.getName() : "?";
              }).collect(Collectors.joining(", ")));
            if (gy.size() > 5) sb.append(", ...");
            sb.append(")");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Shorthand render for a hand/known card: name + cost + types (+ P/T for
     * creatures — added because the shorthand previously omitted it, so a
     * shorthand creature line carried no combat stats). Through the encounter
     * funnel: the FIRST sighting of the name this game appends the card's
     * full rules text after the explicit FULL TEXT marker; later sightings
     * render this shorthand only. No per-line "see earlier" note — the
     * system-prompt instruction covers the mechanic.
     */
    private static String formatCardBrief(Card card) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(card.getName());
        if (!card.getManaCost().getText().isEmpty()) {
            sb.append(" [").append(card.getManaCost().getText()).append("]");
        }
        if (!card.getCardType().isEmpty()) {
            sb.append(" ").append(card.getCardType().stream()
                    .map(Object::toString).collect(Collectors.joining(" ")));
        }
        // P/T on the shorthand line: combat stats without the full text block.
        List<CardType> types = card.getCardType();
        if (types.contains(CardType.CREATURE) || types.contains(CardType.KINDRED)) {
            sb.append(" ").append(card.getPower().getValue())
              .append("/").append(card.getToughness().getValue());
        }
        // Full-text-once: first sighting carries the complete rules text.
        String fullText = fullTextOnce(card.getName(), card::getRules);
        if (!fullText.isEmpty()) {
            sb.append(" ").append(fullText);
        }
        return sb.toString();
    }

    /** Cap on abilities listed in a permanent's shorthand render. */
    private static final int PERMANENT_ABILITY_CAP = 4;

    /** Cap on a single ability rule's length in the shorthand render. */
    private static final int PERMANENT_ABILITY_RULE_CAP = 60;

    /**
     * Render a battlefield permanent. Through the encounter funnel:
     * <ul>
     *   <li>First sighting of the name this game: the stat/state line as
     *   before, then the CARD's FULL rules text with the explicit
     *   {@link #FULL_TEXT_MARKER} — the LLM gets the complete text once.</li>
     *   <li>Subsequent sightings: the existing abilities summary (up to
     *   {@value #PERMANENT_ABILITY_CAP} ability rules from the LIVE
     *   permanent, each capped at {@value #PERMANENT_ABILITY_RULE_CAP} chars)
     *   — current board-relevant state, not the static card text.</li>
     * </ul>
     * Audit item #23 (no silent truncation): when the summary caps fire —
     * rules cut mid-line or abilities dropped past the count cap — the cut
     * is VISIBLE: a truncated rule ends with "..." plus the truncation
     * marker, and dropped abilities append an explicit "+N more abilities"
     * line. The full text was given on first sighting, so the marker also
     * tells the LLM where to find it.
     */
    private static String formatPermanent(Permanent perm, Game game) {
        StringBuilder sb = new StringBuilder();
        sb.append(perm.getName());
        if (perm.isTapped()) sb.append(" (TAPPED)");
        if (perm.hasSummoningSickness() && perm.isCreature(game)) sb.append(" (SICK)");

        // Type & stats
        if (perm.isCreature(game)) {
            sb.append(" [").append(perm.getPower().getValue())
              .append("/").append(perm.getToughness().getValue()).append("]");
        }
        if (perm.isPlaneswalker(game)) {
            sb.append(" [Loyalty: ").append(perm.getCounters(game).getCount("loyalty")).append("]");
        }

        // Counters
        perm.getCounters(game).values().stream()
                .filter(c -> !c.getName().equals("loyalty") && !c.getName().equals("+1/+1"))
                .forEach(c -> sb.append(" [").append(c.getCount()).append("x ").append(c.getName()).append("]"));

        // Attachments: auras/equipment change combat math and bounce
        // decisions — the fact was already rendered by formatCardForChoice but
        // was missing from the board render.
        if (!perm.getAttachments().isEmpty()) {
            List<String> attachedNames = new ArrayList<>();
            for (UUID attId : perm.getAttachments()) {
                Permanent att = game.getPermanent(attId);
                if (att != null) {
                    attachedNames.add(att.getName());
                }
            }
            if (!attachedNames.isEmpty()) {
                sb.append(" (attached: ").append(String.join(", ", attachedNames)).append(")");
            }
        }

        // Marked damage: remaining damage matters for blocks, bounce,
        // burn and pump decisions.
        if (perm.getDamage() > 0) {
            sb.append(" (damage: ").append(perm.getDamage()).append(")");
        }

        // Full-text-once vs current-abilities summary. The seen-set decides:
        // first sighting → the card's complete rules text; repeats → the
        // summary of the permanent's LIVE abilities.
        //
        // Source of the full text: the PERMANENT's own getRules(game), not
        // game.getCard(id) — the permanent's rules derive from its live
        // ability list, which the engine already filters for hidden
        // information (a face-down morph/disguise permanent keeps only
        // worksFaceDown abilities) and which carries the correct face for
        // transformed DFCs. Resolving the backing Card instead would leak the
        // real card's rules text through a face-down permanent.
        String fullText = fullTextOnce(perm.getName(), () -> perm.getRules(game));
        if (!fullText.isEmpty()) {
            sb.append(" ").append(fullText);
            return sb.toString();
        }

        // Abilities summary (subsequent sightings) — audit #23: every cut is
        // marked so the model knows text exists it cannot see.
        List<String> allRules = perm.getAbilities(game).stream()
                .filter(a -> a.getRule() != null && !a.getRule().isEmpty())
                .map(a -> a.getRule())
                .collect(Collectors.toList());
        int droppedAbilities = Math.max(0, allRules.size() - PERMANENT_ABILITY_CAP);
        List<String> shownRules = allRules.stream()
                .limit(PERMANENT_ABILITY_CAP)
                .collect(Collectors.toList());
        if (!shownRules.isEmpty()) {
            sb.append(" | ");
            List<String> rendered = new ArrayList<>();
            for (String rule : shownRules) {
                if (rule.length() > PERMANENT_ABILITY_RULE_CAP) {
                    rendered.add(rule.substring(0, PERMANENT_ABILITY_RULE_CAP - 3) + "..." + TRUNCATED_RULE_MARKER);
                } else {
                    rendered.add(rule);
                }
            }
            sb.append(String.join(" | ", rendered));
        }
        if (droppedAbilities > 0) {
            sb.append(" (+").append(droppedAbilities).append(" more abilities, truncated — full text was given on first sighting)");
        }

        return sb.toString();
    }

    /** Audit #23 marker appended after a rule cut mid-line in the abilities summary. */
    private static final String TRUNCATED_RULE_MARKER = " (truncated)";

    /**
     * Render "Turn: N | Phase: P | Step: S", suppressing the Phase field when
     * it is identical to the Step field. PhaseStep's display names
     * coincide with TurnPhase's for the two main phases, so the common case
     * printed "Phase: Precombat Main | Step: Precombat Main"; the step alone
     * carries the information then.
     */
    static String formatTurnPhaseStep(Game game) {
        String phase = String.valueOf(game.getTurnPhaseType());
        String step = String.valueOf(game.getTurnStepType());
        StringBuilder sb = new StringBuilder();
        sb.append("Turn: ").append(game.getTurnNum());
        if (!phase.equals(step)) {
            sb.append(" | Phase: ").append(phase);
        }
        sb.append(" | Step: ").append(step);
        return sb.toString();
    }

    // --- HTML cleanup for game log messages ---

    /**
     * Strip HTML tags, attributes, and entities from XMage game log messages
     * and card rules text.
     * Converts &lt;br&gt; to newline, strips &lt;font ...&gt; tags, etc.
     * Also removes XMage's UUID shard markers like [3d2] that are meaningless to an LLM.
     * <p>
     * Named typographic entities seen verbatim in card rules
     * (&amp;mdash; 446 occurrences in the card corpus, &amp;bull;, &amp;ndash;, …)
     * are decoded here too — before this, only the XML-escaping five were
     * handled, so decklist rules text shipped raw entities to the model.
     */
    static String cleanHtml(String html) {
        if (html == null || html.isEmpty()) return html;

        String text = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&bull;", "•")
                .replace("&middot;", "·")
                .replace("&hellip;", "…")
                .replace("&lsquo;", "‘")
                .replace("&rsquo;", "’")
                .replace("&ldquo;", "“")
                .replace("&rdquo;", "”")
                .replace("&ouml;", "ö")
                .replace("&auml;", "ä")
                .replace("&uuml;", "ü")
                .replace("&eacute;", "é")
                .replace("&acirc;", "â")
                .replace("&Aelig;", "Æ")
                .replaceAll(" \\[[0-9a-fA-F]{3}\\]", "")
                .replaceAll("\\t+", "  ")
                .replaceAll("  +", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        return decodeNumericEntities(text);
    }

    /** Matches decimal (&#8212;) and hex (&#x2014;) numeric character references. */
    private static final java.util.regex.Pattern NUMERIC_ENTITY =
            java.util.regex.Pattern.compile("&#(x?[0-9a-fA-F]+);");

    /**
     * Decode numeric HTML character references left after the named-entity
     * pass (e.g. &#60; = '<', seen in card rules). Malformed or out-of-range
     * references are left verbatim rather than failing the whole render.
     */
    private static String decodeNumericEntities(String text) {
        java.util.regex.Matcher m = NUMERIC_ENTITY.matcher(text);
        if (!m.find()) return text;
        StringBuffer out = new StringBuffer();
        do {
            String digits = m.group(1);
            int radix = (digits.charAt(0) == 'x' || digits.charAt(0) == 'X') ? 16 : 10;
            String value = (radix == 16) ? digits.substring(1) : digits;
            String replacement = m.group(0);
            try {
                int cp = Integer.parseInt(value, radix);
                if (Character.isValidCodePoint(cp)) {
                    replacement = new String(Character.toChars(cp));
                }
            } catch (NumberFormatException e) {
                // keep the literal reference
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    private static boolean appendSkipStatus(StringBuilder sb, Player player, String description, boolean active) {
        if (active) {
            sb.append("  ⚠ ACTIVE: ").append(description).append("\n");
        }
        return active;
    }

    /**
     * Format a single card for the card selection prompt (library search, etc).
     * Package-visible so LLMPlayer can use it directly.
     */
    static String formatCardForChoice(Card card) {
        return formatCardForChoice(card, null);
    }

    /**
     * Format a card for a choice prompt with controller and short-ID disambiguation.
     * Through the encounter funnel: first sighting of the name this game emits
     * the card's FULL rules text (ALL rules strings — the silent limit(3) is
     * gone, audit: no unmarked caps) with the explicit FULL TEXT marker;
     * subsequent sightings render name+cost+types+P/T shorthand. Controller
     * and battlefield-state suffixes are appended in both branches — they are
     * per-instance, not card text.
     *
     * @param card the card to format
     * @param game optional — if non-null, looks up controller name for battlefield permanents
     */
    static String formatCardForChoice(Card card, Game game) {
        StringBuilder sb = new StringBuilder();
        sb.append(card.getName());
        if (!card.getManaCost().getText().isEmpty()) {
            sb.append(" [").append(card.getManaCost().getText()).append("]");
        }
        List<CardType> types = card.getCardType();
        sb.append(" ").append(types.stream().map(Object::toString).collect(Collectors.joining(" ")));
        if (types.contains(CardType.CREATURE) || types.contains(CardType.KINDRED)) {
            sb.append(" ").append(card.getPower().getValue()).append("/").append(card.getToughness().getValue());
        }
        // Full-text-once: first sighting carries the complete rules text (no
        // silent limit(3) — every rule the card has).
        String fullText = fullTextOnce(card.getName(), () -> card.getRules(game != null ? game : null));
        if (!fullText.isEmpty()) {
            sb.append(" ").append(fullText);
        }
        // Controller disambiguation: show owner name for battlefield permanents
        if (game != null && card.getId() != null) {
            Permanent perm = game.getBattlefield().getPermanent(card.getId());
            if (perm != null) {
                Player owner = game.getPlayer(perm.getControllerId());
                if (owner != null) {
                    sb.append(" [").append(owner.getName()).append("'s]");
                }
                // Battlefield state disambiguation — critical for legend rule choices
                // where two copies of the same card have identical names/types/P-T.
                // Without this, the LLM sees two indistinguishable entries and can't
                // make an informed decision about which to keep.
                if (perm.isTapped()) sb.append(" (TAPPED)");
                if (perm.hasSummoningSickness() && perm.isCreature(game)) sb.append(" (SICK)");
                // Counters (P1P1, lore, etc.) — distinguish copies with different counters
                perm.getCounters(game).values().stream()
                        .forEach(c -> sb.append(" [").append(c.getCount()).append("x ")
                                .append(c.getName()).append("]"));
                // Attachments (Auras, Equipment) — a legend with a Sword is worth keeping
                if (!perm.getAttachments().isEmpty()) {
                    List<String> attachedNames = new ArrayList<>();
                    for (UUID attId : perm.getAttachments()) {
                        Permanent att = game.getPermanent(attId);
                        if (att != null) {
                            attachedNames.add(att.getName());
                        }
                    }
                    if (!attachedNames.isEmpty()) {
                        sb.append(" (attached: ").append(String.join(", ", attachedNames)).append(")");
                    }
                }
            }
        }
        // Short UUID suffix so duplicate cards (two Underground Seas) are distinguishable
        if (card.getId() != null) {
            String idStr = card.getId().toString().replaceAll("-", "");
            sb.append(" (").append(idStr.substring(idStr.length() - 4)).append(")");
        }
        return sb.toString();
    }

    /**
     * Build a concise description of targeting requirements for card selection prompts.
     * Helps the LLM understand whether to pick targets or cancel.
     */
    static StringBuilder buildTargetsCountDescription(int min, int max, List<Card> cards) {
        StringBuilder sb = new StringBuilder();
        if (min == 0 && max == 1) {
            sb.append("Up to ").append(max).append(" target");
        } else if (min == 0) {
            sb.append("Up to ").append(max).append(" target(s) — choosing 0 is allowed");
        } else if (min == max) {
            sb.append("Must choose exactly ").append(min).append(" target(s)");
        } else {
            sb.append("Choose ").append(min).append(" to ").append(max).append(" target(s)");
        }
        sb.append(" from ").append(cards.size()).append(" valid option(s)");
        if (cards.isEmpty()) {
            sb.append(" — NO VALID TARGETS AVAILABLE");
        }
        return sb;
    }

    /**
     * Format a card name-only with cost and UUID suffix — for hand/graveyard/library
     * selections where the LLM already knows full text from the system prompt.
     */
    static String formatCardNameOnly(Card card) {
        StringBuilder sb = new StringBuilder();
        sb.append(card.getName());
        if (!card.getManaCost().getText().isEmpty()) {
            sb.append(" [").append(card.getManaCost().getText()).append("]");
        }
        // Short UUID suffix so duplicate cards are distinguishable
        if (card.getId() != null) {
            String idStr = card.getId().toString().replaceAll("-", "");
            sb.append(" (").append(idStr.substring(idStr.length() - 4)).append(")");
        }
        return sb.toString();
    }

    static String formatCardFull(Card card) {
        StringBuilder sb = new StringBuilder();
        sb.append(card.getName());
        if (!card.getManaCost().getText().isEmpty()) {
            sb.append(" ").append(card.getManaCost().getText());
        }
        sb.append("\n");
        sb.append("  Type: ").append(card.getCardType().stream().map(Object::toString).collect(Collectors.joining(" ")));
        sb.append("\n");

        // Rules text carries raw HTML (<br>, <i>, &mdash;…) — run it
        // through cleanHtml so decklists don't ship markup to the model.
        String rules = cleanHtml(card.getRules().stream().collect(Collectors.joining("\n")));
        if (!rules.isEmpty()) {
            sb.append("  Text: ").append(rules).append("\n");
        }

        List<CardType> fullTypes = card.getCardType();
        if ((fullTypes.contains(CardType.CREATURE) || fullTypes.contains(CardType.KINDRED))
                && card.getPower() != null && card.getToughness() != null) {
            sb.append("  P/T: ").append(card.getPower().getValue())
              .append("/").append(card.getToughness().getValue()).append("\n");
        }
        if (fullTypes.contains(CardType.PLANESWALKER) && card.getStartingLoyalty() >= 0) {
            sb.append("  Starting Loyalty: ").append(card.getStartingLoyalty()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Render a decklist (or sideboard) with duplicates grouped by name
     * — a deck with 4× Island previously emitted the full
     * name/Type/Text block once per copy — pure noise, the model already
     * knows what an Island does. Duplicates collapse to `4x Island` followed
     * by ONE rules block; singletons keep the existing single-block render.
     * Ordering follows the caller's (name-sorted) collection.
     */
    static String formatDecklistGrouped(Collection<Card> cards) {
        StringBuilder sb = new StringBuilder();
        Map<String, List<Card>> byName = new LinkedHashMap<>();
        for (Card card : cards) {
            byName.computeIfAbsent(card.getName(), k -> new ArrayList<>()).add(card);
        }
        for (List<Card> group : byName.values()) {
            if (group.size() > 1) {
                sb.append(group.size()).append("x ").append(formatCardFull(group.get(0)));
            } else {
                sb.append(formatCardFull(group.get(0)));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Build a one-shot prompt for pre-game deck assessment.
     * The LLM receives the full decklist + sideboard with complete card text
     * and is asked to formulate a concise gameplan.
     * <p>
     * The thinking from this call is discarded — only the final gameplan
     * answer is preserved and baked into the system prompt.
     */
    public static String buildDeckAssessmentPrompt(Game game, UUID playerId, Deck deck, String playerName) {
        // Note: this may be called before the player is registered in game.getPlayers(),
        // so we receive the player name directly rather than looking it up.

        StringBuilder sb = new StringBuilder();
        sb.append("=== PRE-GAME DECK ASSESSMENT ===\n\n");
        sb.append("You are playing as: ").append(playerName).append("\n");
        sb.append("You are about to play a game of Magic: The Gathering.\n");
        sb.append("Take a moment to study your deck and formulate a winning gameplan.\n\n");

        // Opponent context (may be empty during early setup)
        boolean hasOpponents = false;
        sb.append("=== OPPONENTS ===\n");
        for (Player p : game.getPlayers().values()) {
            if (p.getId().equals(playerId)) continue;
            sb.append("  ").append(p.getName()).append("\n");
            hasOpponents = true;
        }
        if (!hasOpponents) {
            sb.append("  (opponent not yet known — will be determined at match start)\n");
        }
        sb.append("\n");

        // Maindeck — full text (grouped: 4x Island + one rules block)
        sb.append("=== YOUR DECK (").append(deck.getMaindeckCards().size()).append(" cards) ===\n");
        List<Card> mainCards = deck.getMaindeckCards().stream()
                .sorted(Comparator.comparing(Card::getName))
                .collect(Collectors.toList());
        sb.append(formatDecklistGrouped(mainCards));

        // Sideboard — full text if non-empty
        Set<Card> sbCards = deck.getSideboard();
        if (!sbCards.isEmpty()) {
            sb.append("=== YOUR SIDEBOARD (").append(sbCards.size()).append(" cards) ===\n");
            List<Card> sbList = sbCards.stream()
                    .sorted(Comparator.comparing(Card::getName))
                    .collect(Collectors.toList());
            sb.append(formatDecklistGrouped(sbList));
        }

        sb.append("=== YOUR TASK ===\n");
        sb.append("After studying the decklist above, write a concise gameplan (150–400 words) covering:\n");
        sb.append("1. What is this deck's primary strategy / win condition?\n");
        sb.append("2. What is the ideal opening hand? What do you mulligan for?\n");
        sb.append("3. Key synergies and combos to look for.\n");
        sb.append("4. How should you sequence your early turns?\n");
        sb.append("5. What cards in your sideboard are flexible answers that could come in against various matchups?\n\n");
        sb.append("Write ONLY the gameplan text — no preamble, no markdown, just the plan.");

        return sb.toString();
    }

    // ==================================================================
    //  COMBAT PROMPTS
    // ==================================================================

    /**
     * Build a prompt for the declare attackers step.
     * The LLM receives the full board state and its available attackers,
     * then returns which creatures to attack with and at whom.
     */
    public static String buildAttackersPrompt(Game game, UUID playerId, List<Permanent> availableAttackers) {
        Player player = game.getPlayer(playerId);
        if (player == null) throw new IllegalStateException("Player not found: " + playerId);

        // Encounter funnel for this seat's render (hand section below uses
        // formatCardBrief): first sighting of a name emits full text, repeats
        // shorthand. Mutations persist into the (game, seat) registry.
        return withSeenNames(game.getId(), playerId, () -> {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DECLARE ATTACKERS ===\n");
        sb.append("Turn ").append(game.getTurnNum())
          .append(" — ").append(game.getTurnStepType()).append("\n\n");

        // Compact players summary
        sb.append("--- Players ---\n");
        for (Player p : game.getPlayers().values()) {
            sb.append("  ").append(p.getName());
            if (p.getId().equals(playerId)) sb.append(" (YOU)");
            sb.append(" | Life: ").append(p.getLife())
              .append(" | Hand: ").append(p.getHand().size())
              .append(" | Library: ").append(p.getLibrary().size()).append("\n");
        }

        // Your creatures (available attackers) — use the SAME list that the parser
        // will index into, so the LLM's numbered choices match the parser's indices.
        sb.append("\n--- Your Creatures (available attackers) ---\n");
        if (availableAttackers.isEmpty()) {
            sb.append("  (no available attackers)\n");
        } else {
            for (int i = 0; i < availableAttackers.size(); i++) {
                sb.append(i + 1).append(". ").append(formatCombatCreature(availableAttackers.get(i), game)).append("\n");
            }
        }

        // Your other non-creature permanents (planeswalkers, enchantments, etc.) — for context
        List<Permanent> myNonCreatures = game.getBattlefield().getAllPermanents().stream()
                .filter(p -> p.isControlledBy(playerId) && !p.isCreature(game) && !p.isLand(game))
                .collect(Collectors.toList());
        if (!myNonCreatures.isEmpty()) {
            sb.append("\n--- Your Other Permanents ---\n");
            for (Permanent p : myNonCreatures) {
                sb.append("  ").append(p.getName());
                if (p.isPlaneswalker(game)) {
                    sb.append(" [Loyalty: ").append(p.getCounters(game).getCount("loyalty")).append("]");
                }
                sb.append("\n");
            }
        }

        // Opponent's creatures (potential blockers)
        for (UUID oppId : game.getOpponents(playerId)) {
            Player opp = game.getPlayer(oppId);
            if (opp == null || !opp.isInGame()) continue;
            sb.append("\n--- ").append(opp.getName()).append("'s Creatures (potential blockers) ---\n");
            List<Permanent> oppCreatures = game.getBattlefield().getAllPermanents().stream()
                    .filter(p -> p.isControlledBy(oppId) && p.isCreature(game))
                    .collect(Collectors.toList());
            if (oppCreatures.isEmpty()) {
                sb.append("  (no creatures)\n");
            } else {
                for (Permanent c : oppCreatures) {
                    sb.append("  ").append(formatCombatCreature(c, game)).append("\n");
                }
            }

            // Planeswalkers and battles to attack
            List<Permanent> attackTargets = new ArrayList<>();
            game.getBattlefield().getActivePermanents(
                    StaticFilters.FILTER_PERMANENT_PLANESWALKER, playerId, game)
                    .stream()
                    .filter(p -> p.canBeAttacked(null, oppId, game))
                    .forEach(attackTargets::add);
            game.getBattlefield().getActivePermanents(
                    StaticFilters.FILTER_PERMANENT_BATTLE, playerId, game)
                    .stream()
                    .filter(p -> p.canBeAttacked(null, oppId, game))
                    .forEach(attackTargets::add);
            if (!attackTargets.isEmpty()) {
                // Minor consistency: sub-header aligned with every other list —
                // no stray 2-space indent, no `* ` bullets (`* ` collided with
                // the strict `N.` / `  Name` numbering contract).
                sb.append("\n--- ").append(opp.getName()).append("'s Planeswalkers/Battles (attackable) ---\n");
                for (Permanent pw : attackTargets) {
                    sb.append("  ").append(pw.getName());
                    if (pw.isPlaneswalker(game)) {
                        sb.append(" [Loyalty: ").append(pw.getCounters(game).getCount("loyalty")).append("]");
                    } else if (pw.isBattle(game)) {
                        sb.append(" [Defense: ").append(pw.getCounters(game).getCount("defense")).append("]");
                    }
                    sb.append("\n");
                }
            }
        }

        // Your hand for context
        sb.append("\n--- Your Hand ---\n");
        for (Card card : player.getHand().getCards(game)) {
            sb.append("  ").append(formatCardBrief(card)).append("\n");
        }
        if (player.getHand().isEmpty()) sb.append("  (empty)\n");

        // Response format — spell out the literal grammar the parser accepts
        // (pw: also covers battles; one option per line, no
        // separators outside the grammar).
        sb.append("\n=== ATTACK DECLARATION ===\n");
        sb.append("Reply with ONLY the declaration on ONE line. Each entry is attacker_num->target:\n");
        sb.append("attacker_num->player — attack the defending player with that attacker\n");
        sb.append("attacker_num->pw:Name — attack the planeswalker or battle named 'Name', exactly as written in the Planeswalkers/Battles list above\n");
        sb.append("all — attack with every available creature\n");
        sb.append("none — attack with nothing\n");
        sb.append("Separate multiple attacker_num->target entries with commas (e.g., '1->player, 2->player').");

        return sb.toString();
        });
    }

    /**
     * Build a prompt for the declare blockers step.
     * The LLM receives the attacking creatures and its available blockers,
     * then returns blocking assignments.
     */
    public static String buildBlockersPrompt(Game game, UUID playerId) {
        Player player = game.getPlayer(playerId);
        if (player == null) throw new IllegalStateException("Player not found: " + playerId);

        // Encounter funnel (see buildAttackersPrompt).
        return withSeenNames(game.getId(), playerId, () -> {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DECLARE BLOCKERS ===\n");
        sb.append("Turn ").append(game.getTurnNum())
          .append(" — ").append(game.getTurnStepType()).append("\n\n");

        // Compact players summary
        sb.append("--- Players ---\n");
        for (Player p : game.getPlayers().values()) {
            sb.append("  ").append(p.getName());
            if (p.getId().equals(playerId)) sb.append(" (YOU)");
            sb.append(" | Life: ").append(p.getLife())
              .append(" | Hand: ").append(p.getHand().size()).append("\n");
        }

        // Attacking creatures
        sb.append("\n--- Creatures Attacking You ---\n");
        Set<UUID> attackerIds = game.getCombat().getAttackers();
        if (attackerIds.isEmpty()) {
            sb.append("  (no attackers — nothing to block)\n");
        } else {
            List<Permanent> attackers = new ArrayList<>();
            for (UUID id : attackerIds) {
                Permanent p = game.getPermanent(id);
                if (p != null) attackers.add(p);
            }
            for (int i = 0; i < attackers.size(); i++) {
                Permanent att = attackers.get(i);
                UUID controllerId = att.getControllerId();
                Player controller = game.getPlayer(controllerId);
                String controllerName = controller != null ? controller.getName() : "?";
                sb.append(i + 1).append(". ").append(formatCombatCreature(att, game))
                  .append(" (from: ").append(controllerName).append(")");
                // Show what this attacker is attacking
                UUID defenderId = game.getCombat().getDefenderId(att.getId());
                if (defenderId != null) {
                    Player def = game.getPlayer(defenderId);
                    if (def != null) {
                        sb.append(" → attacking ").append(def.getName());
                    } else {
                        Permanent permDef = game.getPermanent(defenderId);
                        if (permDef != null) sb.append(" → attacking ").append(permDef.getName());
                    }
                }
                sb.append("\n");
            }
        }

        // Your available blockers
        sb.append("\n--- Your Available Blockers ---\n");
        List<Permanent> blockers = player.getAvailableBlockers(game);
        if (blockers.isEmpty()) {
            sb.append("  (no creatures available to block)\n");
        } else {
            for (int i = 0; i < blockers.size(); i++) {
                sb.append(i + 1).append(". ").append(formatCombatCreature(blockers.get(i), game)).append("\n");
            }
        }

        // Your hand for context
        sb.append("\n--- Your Hand ---\n");
        for (Card card : player.getHand().getCards(game)) {
            sb.append("  ").append(formatCardBrief(card)).append("\n");
        }
        if (player.getHand().isEmpty()) sb.append("  (empty)\n");

        // Response format — spell out the grammar and name the source lists
        // (both lists are numbered from 1; strict readers
        // otherwise assume shared indices).
        sb.append("\n=== BLOCK DECLARATION ===\n");
        sb.append("Reply with ONLY the declaration on ONE line: blocker_num->attacker_num, comma-separated for multiple pairs (e.g., '1->1' or '1->1, 3->2').\n");
        sb.append("blocker_num = a numbered creature from 'Your Available Blockers' above.\n");
        sb.append("attacker_num = a numbered creature from 'Creatures Attacking You' above. Each list is numbered from 1.\n");
        sb.append("none — block nothing.");

        return sb.toString();
        });
    }

    /**
     * Check whether a permanent has any ability of the given class.
     * For keyword abilities that aren't singletons (e.g., MenaceAbility, ProtectionAbility).
     */
    private static boolean hasAnyAbility(Permanent perm, Class<?> abilityClass) {
        return perm.getAbilities().stream().anyMatch(a -> abilityClass.isInstance(a));
    }

    /**
     * Format a creature for combat decisions — P/T, key abilities, tapped/sick status.
     */
    private static String formatCombatCreature(Permanent perm, Game game) {
        StringBuilder sb = new StringBuilder();
        sb.append(perm.getName())
          .append(" [").append(perm.getPower().getValue())
          .append("/").append(perm.getToughness().getValue()).append("]");

        if (perm.isTapped()) sb.append(" (T)");
        if (perm.hasSummoningSickness() && perm.isCreature(game)) sb.append(" (sick)");

        // Key combat abilities — short abbreviations to save tokens
        List<String> keywords = new ArrayList<>();
        if (perm.getAbilities().containsKey(FlyingAbility.getInstance().getId())) keywords.add("fly");
        if (perm.getAbilities().containsKey(FirstStrikeAbility.getInstance().getId())) keywords.add("FS");
        if (perm.getAbilities().containsKey(DoubleStrikeAbility.getInstance().getId())) keywords.add("DS");
        if (perm.getAbilities().containsKey(DeathtouchAbility.getInstance().getId())) keywords.add("dt");
        if (perm.getAbilities().containsKey(TrampleAbility.getInstance().getId())) keywords.add("tramp");
        if (perm.getAbilities().containsKey(VigilanceAbility.getInstance().getId())) keywords.add("vig");
        if (perm.getAbilities().containsKey(HasteAbility.getInstance().getId())) keywords.add("haste");
        if (perm.getAbilities().containsKey(LifelinkAbility.getInstance().getId())) keywords.add("life");
        if (perm.getAbilities().containsKey(ReachAbility.getInstance().getId())) keywords.add("reach");
        if (hasAnyAbility(perm, MenaceAbility.class)) keywords.add("menace");
        if (perm.getAbilities().containsKey(IndestructibleAbility.getInstance().getId())) keywords.add("indestruct");
        if (perm.getAbilities().containsKey(HexproofAbility.getInstance().getId())) keywords.add("hex");
        if (hasAnyAbility(perm, ProtectionAbility.class)) keywords.add("prot");
        if (perm.getAbilities().containsKey(WitherAbility.getInstance().getId())) keywords.add("wither");
        if (perm.getAbilities().containsKey(InfectAbility.getInstance().getId())) keywords.add("infect");

        if (!keywords.isEmpty()) {
            sb.append(" {").append(String.join(",", keywords)).append("}");
        } else {
            sb.append(" {}");
        }

        // +1/+1 counters
        int p1Counters = perm.getCounters(game).getCount("+1/+1");
        if (p1Counters > 0) sb.append(" [+").append(p1Counters).append("/+").append(p1Counters).append("]");

        return sb.toString();
    }

    /**
     * Build a sideboarding prompt for the LLM.
     * The LLM receives a summary of the just-completed game, the full
     * maindeck and sideboard, and is asked to propose swaps.
     */
    public static String buildSideboardPrompt(Game game, UUID playerId, Deck deck,
                                                mage.game.match.Match match, String playerName,
                                                String matchReview) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SIDEBOARDING ===\n\n");

        // Game result summary
        sb.append("=== PREVIOUS GAME RESULT ===\n");
        sb.append("Winner: ").append(getWinnerDescription(game)).append("\n");
        sb.append("Turns played: ").append(game.getTurnNum()).append("\n");

        Player self = game.getPlayer(playerId);
        if (self != null) {
            sb.append("Your final life: ").append(self.getLife()).append("\n");
        }
        sb.append("\n");

        // Match score
        sb.append("=== MATCH SCORE ===\n");
        for (mage.game.match.MatchPlayer mp : match.getPlayers()) {
            sb.append("  ").append(mp.getName())
              .append(": ").append(mp.getWins()).append(" win(s)");
            if (mp.hasQuit()) sb.append(" (QUIT)");
            sb.append("\n");
        }
        sb.append("Wins needed: ").append(match.getWinsNeeded()).append("\n");
        sb.append("Games played so far: ").append(match.getNumGames()).append("\n\n");

        // Game review — your own assessment of the previous game, written for sideboarding
        if (matchReview != null && !matchReview.isEmpty()) {
            sb.append("=== YOUR POST-GAME REVIEW ===\n");
            sb.append("You wrote this review after the last game to guide your sideboarding:\n\n");
            sb.append(matchReview).append("\n\n");
        }

        // Opponent's deck — what we saw them play
        sb.append("=== OPPONENT'S CARDS SEEN ===\n");
        boolean foundOppCards = false;
        for (Player p : game.getPlayers().values()) {
            if (p.getId().equals(playerId)) continue;
            // Show opponent's graveyard (cards they actually played or milled)
            Set<Card> oppSeen = new LinkedHashSet<>();
            for (UUID cardId : p.getGraveyard()) {
                Card c = game.getCard(cardId);
                if (c != null) oppSeen.add(c);
            }
            // Also show what's exiled
            for (Card c : game.getExile().getAllCards(game)) {
                if (c != null && c.getOwnerId() != null && c.getOwnerId().equals(p.getId())) {
                    oppSeen.add(c);
                }
            }
            if (!oppSeen.isEmpty()) {
                foundOppCards = true;
                sb.append("  Cards from ").append(p.getName()).append("'s deck seen this game:\n");
                for (Card c : oppSeen) {
                    sb.append("    ").append(c.getName());
                    if (!c.getManaCost().getText().isEmpty())
                        sb.append(" [").append(c.getManaCost().getText()).append("]");
                    sb.append("\n");
                }
            }
        }
        if (!foundOppCards) {
            sb.append("  (No opponent cards seen — game was very short)\n");
        }
        sb.append("\n");

        // Maindeck — full text so the LLM knows exactly what each card does when deciding swaps
        // (grouped: 4x Island + one rules block)
        sb.append("=== YOUR CURRENT MAINBOARD (").append(deck.getMaindeckCards().size()).append(" cards) ===\n");
        List<Card> mainCards = deck.getMaindeckCards().stream()
                .sorted(Comparator.comparing(Card::getName))
                .collect(Collectors.toList());
        sb.append(formatDecklistGrouped(mainCards));
        sb.append("\n");

        // Sideboard — full text so the LLM knows what each card does
        Set<Card> sbCards = deck.getSideboard();
        sb.append("=== YOUR SIDEBOARD (").append(sbCards.size()).append(" cards) ===\n");
        if (sbCards.isEmpty()) {
            sb.append("  (No sideboard — nothing to swap)\n");
        } else {
            List<Card> sbList = sbCards.stream()
                    .sorted(Comparator.comparing(Card::getName))
                    .collect(Collectors.toList());
            sb.append(formatDecklistGrouped(sbList));
        }

        sb.append("=== YOUR TASK ===\n");
        sb.append("Based on the game you just played and what you saw from the opponent,\n");
        sb.append("decide which cards to swap between your mainboard and sideboard.\n");
        sb.append("\n");
        sb.append("Rules:\n");
        sb.append("- You MUST maintain the SAME number of mainboard cards.\n");
        sb.append("- Each card you bring IN from sideboard requires one card moved OUT.\n");
        sb.append("- Use the EXACT card names as listed.\n");
        sb.append("- If no changes are justified, just write NONE on a line by itself.\n");
        sb.append("\n");
        sb.append("Reply in this format:\n");
        sb.append("IN:\n");
        sb.append("Card Name A\n");
        sb.append("Card Name B\n");
        sb.append("OUT:\n");
        sb.append("Card Name X\n");
        sb.append("Card Name Y\n");

        return sb.toString();
    }

    /**
     * Build a compact situation summary (no history, no actions).
     * Stored in conversation history instead of the full prompt to avoid
     * redundant data — the current prompt already contains full context.
     * ~200-400 characters.
     */
    public static String buildSituationSummary(Game game, UUID playerId) {
        Player player = game.getPlayer(playerId);
        if (player == null) throw new IllegalStateException("Player not found: " + playerId);

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(game.getTurnNum()).append("-").append(game.getTurnStepType()).append("] ");

        // Life totals
        sb.append(" ");
        for (Player p : game.getPlayers().values()) {
            sb.append(p.getName()).append("(").append(p.getLife()).append(")");
            if (!p.getId().equals(playerId)) sb.append(" hand:").append(p.getHand().size());
            sb.append(" ");
        }

        // Your hand
        List<String> handNames = player.getHand().getCards(game).stream()
                .map(Card::getName).collect(Collectors.toList());
        sb.append("hand:[").append(String.join(", ", handNames)).append("] ");

        // Battlefield counts — both sides (this summary is the historical record
        // that survives conversation summarization; asymmetric "my board only"
        // leaves future decisions without opponent-board context).
        long myPermanents = game.getBattlefield().getAllPermanents().stream()
                .filter(p -> p.isControlledBy(playerId)).count();
        sb.append("board:").append(myPermanents).append(" ");
        for (Player p : game.getPlayers().values()) {
            if (p.getId().equals(playerId)) continue;
            long oppPermanents = game.getBattlefield().getAllPermanents().stream()
                    .filter(x -> x.isControlledBy(p.getId())).count();
            long oppCreatures = game.getBattlefield().getAllPermanents().stream()
                    .filter(x -> x.isControlledBy(p.getId()) && x.isCreature(game)).count();
            sb.append(p.getName()).append(" board:").append(oppPermanents)
              .append(" (creatures:").append(oppCreatures).append(") ");
        }

        // Stack summary. Names go through stackObjectDisplayName: this summary
        // is the historical record that survives conversation summarization, so
        // a leaked face-down identity here would persist for the rest of the game.
        if (!game.getStack().isEmpty()) {
            String stackSummary = game.getStack().stream()
                    .map(so -> stackObjectDisplayName(so, game))
                    .collect(Collectors.joining(" > "));
            sb.append("stack:[").append(stackSummary).append("]");
        }

        return sb.toString();
    }

    /**
     * Compute a human-readable description of who won the game.
     * Unlike GameImpl.getWinner() which returns "Game is a draw" when winnerId
     * is null, this checks actual player win/loss state to determine the
     * real outcome — returning "You WON", "You LOST", or "Draw".
     */
    static String getWinnerDescription(Game game) {
        // Check who won based on player state
        for (Player p : game.getPlayers().values()) {
            if (p.hasWon()) return p.getName() + " WON";
        }
        for (Player p : game.getPlayers().values()) {
            if (p.hasLost()) return p.getName() + " LOST";
        }
        return "Draw";
    }

}
