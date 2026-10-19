package mage.player.p1;

import com.google.gson.JsonObject;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.PlayLandAbility;
import mage.abilities.SpellAbility;
import mage.abilities.keyword.DeathtouchAbility;
import mage.abilities.keyword.FlyingAbility;
import mage.abilities.keyword.IndestructibleAbility;
import mage.abilities.keyword.ReachAbility;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.cards.Card;
import mage.constants.RangeOfInfluence;
import mage.constants.SpellAbilityType;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.ai.ComputerPlayer;
import mage.players.PlayableObjectsList;
import mage.players.Player;
import mage.players.PlayerImpl;
import mage.target.Target;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Argentum-P1 policy-net seat.
 * <p>
 * Extends {@link ComputerPlayer} deliberately: the engine AI answers every <em>sub</em>-decision
 * (targets, mana payment, modes, mulligans) via the inherited {@code choose(...)} family,
 * which is exactly the Argentum P1 design ("the engine AI fills in sub-decisions; the model picks
 * only the top-level action"). This class overrides {@code priority(Game)}.
 * <p>
 * <strong>Combat (attackers/blockers) is the exception.</strong> The inherited
 * {@link ComputerPlayer#selectAttackers}/{@link ComputerPlayer#selectBlockers} are empty no-ops
 * (the real logic lives only in {@code ComputerPlayer6} in a different plugin), so this class
 * <em>does</em> implement combat explicitly — see {@link #selectAttackers} / {@link #selectBlockers}.
 * Combat is deliberately an engine-side heuristic stand-in, not a policy-net decision: the net
 * itself never chooses attackers or blockers; the heuristic exists so the seat participates in
 * combat at all.
 * <p>
 * At each priority step it enumerates top-level actions exactly the way the LLM plugin's
 * {@code buildActionList} does, projects the XMage state to an
 * {@code argentum-p1-selfplay-sample@v1} observation ({@link P1Projection}), asks the
 * out-of-process policy worker ({@link P1Worker}) for the argmax over the allowed candidates, and
 * executes the chosen action with the normal {@code Player} APIs. On any worker failure it passes
 * priority. If the worker has failed twice in a row the seat passes for the rest of the turn
 * (never blocks the game).
 * <p>
 * Required constructor shape {@code (String, RangeOfInfluence, int)} — {@code PlayerFactory}.
 */
public class P1Player extends ComputerPlayer {

    private static final Logger logger = Logger.getLogger(P1Player.class);

    private static final List<Zone> NON_HAND_PLAY_ZONES =
            Collections.unmodifiableList(Arrays.asList(Zone.GRAVEYARD, Zone.EXILED));

    /**
     * Which worker a seat gets is process-global configuration (env vars); the player object is
     * constructed by PlayerFactory through a plugin classloader that cannot take extra parameters,
     * so the worker is attached out-of-band by {@link #attachWorker(P1Worker)}.
     */
    private static final Map<UUID, P1Worker> WORKERS = Collections.synchronizedMap(new LinkedHashMap<>());

    public static void attachWorker(UUID playerId, P1Worker worker) {
        WORKERS.put(playerId, worker);
    }

    private P1Worker worker() {
        P1Worker w = WORKERS.get(getId());
        if (w == null) {
            // Lazy fallback: a seat constructed without the BotGameHandler hook still works.
            synchronized (WORKERS) {
                w = WORKERS.get(getId());
                if (w == null) {
                    w = new P1Worker(getName());
                    WORKERS.put(getId(), w);
                }
            }
        }
        return w;
    }

    /**
     * Close and unregister the P1 worker for {@code playerId}, exactly once, from whichever
     * terminal lifecycle hook fires first ({@link #won}/{@link #lost}/{@link #lostForced}/
     * {@link #drew}).
     * <p>
     * Idempotent and thread-safe: {@link #WORKERS} is a {@link Collections#synchronizedMap
     * synchronized} map, so {@code remove} is atomic — the first caller gets the worker and is the
     * only one that closes it; every later caller (a second terminal hook, or a concurrent one on a
     * different callback thread) gets {@code null} and returns immediately. The entry is removed
     * <em>before</em> the close, so nothing retains the worker even if the close itself throws, and
     * the process can never be leaked behind a stale map entry.
     * <p>
     * This single guard is the game-end safety net: a terminal path that skips a hook still reaps,
     * because every hook funnels through here and the atomic remove makes the reap exactly-once.
     *
     * @return {@code true} if this call reaped the worker, {@code false} if it was already gone.
     */
    static boolean reapWorker(UUID playerId) {
        P1Worker w = WORKERS.remove(playerId);
        if (w == null) {
            return false;
        }
        try {
            w.close();
        } catch (RuntimeException e) {
            // The map entry is already gone, so the process can no longer be resurrected; the game
            // is over and a close failure must never propagate out of a game-end hook.
            logger.warn("[P1] error closing worker for seat " + playerId + ": " + e, e);
        }
        logger.info("[P1] reaped worker for seat " + playerId + " (decisions=" + w.getDecisions()
                + ", errors=" + w.getErrors() + ", restarts=" + w.getRestarts() + ")");
        return true;
    }

    enum ActionType {
        PASS, PLAY_LAND, CAST_SPELL, ACTIVATE_ABILITY
    }

    /** One top-level candidate. */
    static final class Action {
        final ActionType actionType;
        final String kind;          // model kind
        final String description;   // diagnostics only
        final UUID sourceId;        // card id (hand/graveyard/exile zone) or ability id
        final UUID sourceCardId;    // for ACTIVATE_ABILITY: the card/permanent carrying the ability
        final Zone sourceZone;      // null = battlefield
        final String manaCost;
        final boolean hasX;
        final Ability abilityRef;   // ACTIVATE_ABILITY only (live ability object)
        final UUID spellAbilityId;  // CAST_SPELL only: which face/ability the model picked
                                    // (null = card default, resolved by chooseAbilityForCast)

        Action(ActionType t, String kind, String description, UUID sourceId, UUID sourceCardId,
               Zone sourceZone, String manaCost, boolean hasX, Ability abilityRef) {
            this(t, kind, description, sourceId, sourceCardId, sourceZone, manaCost, hasX,
                    abilityRef, null);
        }

        Action(ActionType t, String kind, String description, UUID sourceId, UUID sourceCardId,
               Zone sourceZone, String manaCost, boolean hasX, Ability abilityRef, UUID spellAbilityId) {
            this.actionType = t;
            this.kind = kind;
            this.description = description;
            this.sourceId = sourceId;
            this.sourceCardId = sourceCardId;
            this.sourceZone = sourceZone;
            this.manaCost = manaCost;
            this.hasX = hasX;
            this.abilityRef = abilityRef;
            this.spellAbilityId = spellAbilityId;
        }
    }

    // Per-decision loop guards.
    private boolean workerDeadThisTurn = false;
    private int consecutiveFailures = 0;
    private int lastTurn = -1;
    private final P1Stats stats = new P1Stats();

    public P1Player(String name, RangeOfInfluence range, int skill) {
        super(name, range);
        logger.info("[P1] P1Player constructed for seat '" + name + "' (skill=" + skill
                + ", id=" + getId() + "); worker will attach via P1Player.attachWorker");
    }

    /** Copy constructor for the engine's per-ability copies (no worker attached; shared by id). */
    protected P1Player(final P1Player player) {
        super(player);
    }

    @Override
    public P1Player copy() {
        return new P1Player(this);
    }

    /**
     * Choose which spell ability to use when casting a card.
     * <p>
     * For an Adventure/Omen multipart card the engine can cast either
     * the main-card face (its BASE {@link SpellAbility}, e.g. the creature "Hearth Elemental") or
     * the inset spell face (the {@code ADVENTURE_SPELL} "Stoke Genius"). {@link #buildCandidates}
     * names the candidate after the <em>main card</em> ({@code "Cast: Hearth Elemental"}), so
     * "cast this card" must resolve to the creature face. The inherited
     * {@link ComputerPlayer#chooseAbilityForCast} takes the first castable ability, which the
     * engine may order as the Adventure — so the net's "cast the creature" silently became
     * "cast the Adventure and exile the card" (observed 3× in P1-RUN-REPORT run 2). Prefer the
     * BASE (main-card) face whenever it is castable; only fall back to the inset face otherwise.
     */
    @Override
    public SpellAbility chooseAbilityForCast(Card card, Game game, boolean noMana) {
        Map<UUID, SpellAbility> usable = PlayerImpl.getCastableSpellAbilities(
                game, getId(), card, game.getState().getZone(card.getId()), noMana);
        List<SpellAbility> castable = usable.values().stream()
                .filter(a -> a.getTargets().canChoose(getId(), a, game))
                .collect(java.util.stream.Collectors.toList());
        if (castable.isEmpty()) {
            return null;
        }
        for (SpellAbility sa : castable) {
            if (sa.getSpellAbilityType() == SpellAbilityType.BASE) {
                return sa; // main-card face (the creature), not the Adventure
            }
        }
        return castable.get(0);
    }

    // ------------------------------------------------------------------
    //  Game-end hooks — emit the final stats line (observability gap)
    //  AND reap the out-of-process worker (every
    //  finished game used to leak a ~321 MB Python interpreter for the JVM's
    //  lifetime). Every terminal hook funnels through reapWorker(), which is
    //  exactly-once, so it does not matter which hook fires first.
    // ------------------------------------------------------------------

    @Override
    public void won(Game game) {
        super.won(game);
        logger.info("[P1] final stats for " + getName() + ": " + stats.summary());
        reapWorker(getId());
    }

    @Override
    public boolean lost(Game game) {
        // PlayerImpl.lost() routes through lostForced() (our override) when the seat can actually
        // lose, so the reap there normally fires first; reap again for the paths that set the
        // result without going through lostForced. The reap is idempotent, so this is a no-op then.
        boolean didLose = super.lost(game);
        if (didLose) {
            reapWorker(getId());
        }
        return didLose;
    }

    @Override
    public void lostForced(Game game) {
        super.lostForced(game);
        logger.info("[P1] final stats for " + getName() + ": " + stats.summary());
        reapWorker(getId());
    }

    @Override
    public void drew(Game game) {
        super.drew(game);
        logger.info("[P1] final stats for " + getName() + ": " + stats.summary());
        reapWorker(getId());
    }

    P1Stats stats() {
        return stats;
    }

    // ==================================================================
    //  COMBAT — engine-side heuristic sub-decision stand-in
    //
    //  WHY THIS EXISTS: the inherited ComputerPlayer.selectAttackers /
    //  selectBlockers are EMPTY no-op stubs (Mage.Player.AI/ComputerPlayer
    //  lines ~909-917, body: "do nothing, parent class must implement it").
    //  PlayerImpl implements neither, and P1Player does not extend
    //  ComputerPlayer6 (whose real combat logic lives in a different plugin).
    //  Without these overrides the P1 seat NEVER declares an attacker or a
    //  blocker and skips its combat step every turn — which invalidates the
    //  whole "policy net vs LLM" measurement.
    //
    //  WHAT THIS IS: a simple, obviously-correct heuristic. It is NOT a
    //  policy-net decision and NOT the MAD simulation loop (extending
    //  ComputerPlayer6 wholesale would re-introduce the 1 s/turn MCTS
    //  simulation the P1 design exists to avoid). The net still controls only
    //  priority()/top-level actions; combat here is the engine-side stand-in
    //  the original design assumed would exist.
    // ==================================================================

    /**
     * Declare attackers for the attacking seat.
     * <p>
     * Heuristic (v1, deliberately crude but safe):
     * <ol>
     *   <li>If a non-evasive attacker's power could deal lethal this combat, swing all
     *       non-evasive attackers (alpha strike).</li>
     *   <li>Otherwise attack only with "safe" creatures: those that cannot be blocked at all,
     *       or that fly over a board with no flyer/reach, or that have deathtouch, or that kill
     *       their worst potential blocker without dying. This is the same safety test the MAD AI
     *       uses for its "safe attackers" pass, minus its game simulation.</li>
     * </ol>
     */
    @Override
    public void selectAttackers(Game game, UUID attackingPlayerId) {
        if (!getId().equals(attackingPlayerId)) {
            return;
        }
        Player defender = null;
        for (UUID opponentId : game.getOpponents(getId(), true)) {
            Player p = game.getPlayer(opponentId);
            if (p != null && p.isInGame()) {
                defender = p;
                break;
            }
        }
        if (defender == null) {
            return;
        }

        List<Permanent> available = getAvailableAttackers(defender.getId(), game);
        logger.info("[P1] combat: selectAttackers invoked for " + getName() + " (availableAttackers="
                + available.size() + ", defender=" + defender.getName() + " life=" + defender.getLife() + ")");
        if (available.isEmpty()) {
            return;
        }
        List<Permanent> possibleBlockers = defender.getAvailableBlockers(game);

        // Pass 1 — alpha strike: if the total power of every non-evasive attacker is already
        // lethal, commit them all (the defender cannot profitably race a lethal swing).
        int alphaPower = 0;
        for (Permanent a : available) {
            if (!isUnblockable(a, possibleBlockers, game)) {
                alphaPower += Math.max(0, powerOf(a));
            }
        }
        if (alphaPower >= defender.getLife()) {
            int declared = commitAll(available, defender.getId(), game);
            logger.info("[P1] combat: alpha strike declared " + declared + " attacker(s) at "
                    + defender.getName() + " (life " + defender.getLife() + ")");
            return;
        }

        // Pass 2 — attack only with creatures whose attack is clearly favourable/safe.
        int declared = 0;
        for (Permanent a : available) {
            if (isSafeToAttack(a, possibleBlockers, game)) {
                declareAttacker(a.getId(), defender.getId(), game, false);
                if (a.isAttacking()) {
                    declared++;
                }
            }
        }
        if (declared > 0) {
            logger.info("[P1] combat: declared " + declared + " attacker(s) (safe sweep) at "
                    + defender.getName());
        }
    }

    private int commitAll(List<Permanent> attackers, UUID defenderId, Game game) {
        int declared = 0;
        for (Permanent a : attackers) {
            declareAttacker(a.getId(), defenderId, game, false);
            if (a.isAttacking()) {
                declared++;
            }
        }
        return declared;
    }

    /** Attacker cannot be blocked by anyone currently on the board. */
    private static boolean isUnblockable(Permanent attacker, List<Permanent> blockers, Game game) {
        for (Permanent b : blockers) {
            if (b.canBlock(attacker.getId(), game)) {
                return false;
            }
        }
        return true;
    }

    /**
     * "Safe" means the attack has no realistic downside: unblockable, or the attacker wins the
     * combat against the defender's best blocker, or evasion / deathtouch makes it one-sided.
     */
    private static boolean isSafeToAttack(Permanent attacker, List<Permanent> blockers, Game game) {
        int p = powerOf(attacker);
        int t = toughnessOf(attacker);
        if (p <= 0) {
            return false; // no pressure, never worth exposing
        }
        if (isUnblockable(attacker, blockers, game)) {
            return true;
        }
        boolean attackerDeathtouch = attacker.hasAbility(DeathtouchAbility.getInstance(), game);
        boolean attackerIndestructible = attacker.hasAbility(IndestructibleAbility.getInstance(), game);
        boolean attackerFlying = attacker.hasAbility(FlyingAbility.getInstance(), game);
        boolean safe = true;
        for (Permanent b : blockers) {
            if (!b.canBlock(attacker.getId(), game)) {
                continue;
            }
            int bp = powerOf(b);
            int bt = toughnessOf(b);
            boolean blockerReaches = b.hasAbility(FlyingAbility.getInstance(), game)
                    || b.hasAbility(ReachAbility.getInstance(), game);
            // Flying attacker over a blocker with neither flying nor reach: unblocked by it.
            if (attackerFlying && !blockerReaches) {
                continue;
            }
            boolean blockerKillsAttacker = bp >= t;
            if (blockerKillsAttacker && !attackerIndestructible && !attackerDeathtouch) {
                safe = false; // a blocker can trade up or kill us for free
                break;
            }
            // deathtouch / indestructible attacker survives-or-trades favourably
        }
        return safe;
    }

    /**
     * Declare blockers for the defending seat.
     * <p>
     * Heuristic: assign the cheapest blocker that profitably blocks each incoming attacker, in
     * order of the most dangerous (highest power) attacker first. A block is "profitable" when the
     * blocker survives, or when the blocker has deathtouch (kills the attacker), or when the trade
     * is even and we are not blocking with our last/only body. Never block with a creature that
     * simply dies for nothing while a better use exists.
     */
    @Override
    public void selectBlockers(Ability source, Game game, UUID defendingPlayerId) {
        if (!getId().equals(defendingPlayerId)) {
            return;
        }
        List<Permanent> attackers = new ArrayList<>();
        for (UUID attackerId : game.getCombat().getAttackers()) {
            Permanent p = game.getPermanent(attackerId);
            if (p != null) {
                attackers.add(p);
            }
        }
        if (attackers.isEmpty()) {
            return;
        }
        List<Permanent> blockers = getAvailableBlockers(game);
        logger.info("[P1] combat: selectBlockers invoked for " + getName() + " (availableBlockers="
                + blockers.size() + ", attackers=" + attackers.size() + ")");
        if (blockers.isEmpty()) {
            return;
        }
        // Most dangerous attacker first.
        attackers.sort((x, y) -> Integer.compare(powerOf(y), powerOf(x)));

        Set<UUID> used = new HashSet<>();
        int declared = 0;
        for (Permanent attacker : attackers) {
            Permanent chosen = pickBlocker(attacker, blockers, used, game);
            if (chosen != null
                    && chosen.canBlock(attacker.getId(), game)
                    && blockProfitably(chosen, attacker, game)) {
                declareBlocker(defendingPlayerId, chosen.getId(), attacker.getId(), game, false);
                used.add(chosen.getId());
                declared++;
            }
        }
        if (declared > 0) {
            logger.info("[P1] combat: declared " + declared + " blocker(s)");
        }
    }

    /** Cheapest available unused blocker that can legally block the attacker, else null. */
    private static Permanent pickBlocker(Permanent attacker, List<Permanent> blockers,
                                         Set<UUID> used, Game game) {
        Permanent best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Permanent b : blockers) {
            if (used.contains(b.getId()) || !b.canBlock(attacker.getId(), game)) {
                continue;
            }
            int score = Math.max(0, powerOf(b)) + Math.max(0, toughnessOf(b));
            if (score < bestScore) {
                bestScore = score;
                best = b;
            }
        }
        return best;
    }

    /** Would this block be a good trade (blocker survives, or kills/trades the attacker)? */
    private static boolean blockProfitably(Permanent blocker, Permanent attacker, Game game) {
        int bp = powerOf(blocker);
        int bt = toughnessOf(blocker);
        int ap = powerOf(attacker);
        int at = toughnessOf(attacker);
        if (blocker.hasAbility(DeathtouchAbility.getInstance(), game)) {
            return true; // deathtouch blocker kills any attacker it damages
        }
        boolean survives = ap < bt;
        boolean killsAttacker = bp >= at;
        return survives || killsAttacker;
    }

    private static int powerOf(Permanent p) {
        return p.getPower() == null ? 0 : p.getPower().getValue();
    }

    private static int toughnessOf(Permanent p) {
        return p.getToughness() == null ? 0 : p.getToughness().getValue();
    }

    @Override
    public boolean priority(Game game) {
        if (lastTurn != game.getTurnNum()) {
            lastTurn = game.getTurnNum();
            workerDeadThisTurn = false;
            consecutiveFailures = 0;
        }
        if (workerDeadThisTurn) {
            stats.passesOnDeadWorker++;
            pass(game);
            return false;
        }

        List<Action> candidates = buildCandidates(game);
        stats.decisionWindows++;
        if (candidates.isEmpty()) {
            stats.passOnlyWindows++;
        }
        if (stats.decisionWindows % 50 == 0) {
            logger.info("[P1] stats heartbeat for " + getName() + ": " + stats.summary());
        }
        // Guarantee the pass slot exists and is allowed: add it explicitly.
        Action passAction = new Action(ActionType.PASS, "PassPriority", "Pass priority",
                null, null, null, "", false, null);
        candidates.add(passAction);
        // Consult the net even on pass-only windows. The checkpoint's training
        // data (selfplay samples) includes PassPriority-only rows, so this is in-distribution;
        // skipping them starved the net of context (measured: 2/111 consulted in validation).

        Map<UUID, UUID> abilitySrc = new LinkedHashMap<>();
        for (Action a : candidates) {
            if (a.actionType == ActionType.ACTIVATE_ABILITY && a.sourceId != null) {
                abilitySrc.put(a.sourceId, a.sourceCardId);
            }
        }

        P1Worker w = worker();
        try {
            P1Projection.Projection projection = P1Projection.project(this, game, candidates, abilitySrc);
            JsonObject request = w.request(projection.sample, projection.allowed);
            JsonObject reply = w.decide(request, consecutiveFailures == 0);
            int idx = P1Worker.chosenIndex(reply, candidates.size());
            if (idx < 0) {
                consecutiveFailures++;
                stats.fallbacks++;
                if (consecutiveFailures >= 2) {
                    workerDeadThisTurn = true;
                    logger.error("[P1] worker failed twice in a row (" + getName()
                            + ") — passing for the rest of turn " + game.getTurnNum());
                }
                pass(game);
                return false;
            }
            consecutiveFailures = 0;
            Action chosen = candidates.get(idx);
            stats.recordChoice(chosen.kind);
            if (chosen.actionType == ActionType.PASS) {
                stats.passes++;
                pass(game);
                return false;
            }
            boolean executed = execute(chosen, game);
            if (executed) {
                stats.actionsExecuted++;
                return true;
            }
            stats.engineRejections++;
            logger.warn("[P1] engine rejected chosen action '" + chosen.description + "' -> passing");
            pass(game);
            return false;
        } catch (Throwable t) {
            logger.error("[P1] decision failed (" + getName() + "): " + t, t);
            stats.failures++;
            workerDeadThisTurn = true;
            pass(game);
            return false;
        }
    }

    private boolean execute(Action a, Game game) {
        try {
            switch (a.actionType) {
                case PLAY_LAND: {
                    Card c = game.getCard(a.sourceId);
                    if (c == null) {
                        return false;
                    }
                    return playLand(c, game, false);
                }
                case CAST_SPELL: {
                    Card c = game.getCard(a.sourceId);
                    if (c == null) {
                        return false;
                    }
                    if (a.spellAbilityId == null) {
                        // Single-face card (or legacy candidate): no face choice recorded —
                        // playCard routes through chooseAbilityForCast, which prefers the
                        // main-card face.
                        return playCard(c, game, false, null);
                    }
                    // Multi-face card: the model picked a specific face. Re-resolve the live
                    // ability from the card (candidate enumeration happened one projection ago;
                    // the ability object must be the live one so its zone-change counter and
                    // costs are current) and cast THAT face explicitly, bypassing the default
                    // face resolution in playCard/chooseAbilityForCast.
                    for (SpellAbility face : castableFaces(c, game)) {
                        if (face.getId().equals(a.spellAbilityId)) {
                            return cast(face, game, false, null);
                        }
                    }
                    logger.warn("[P1] chosen spell face no longer castable for '" + a.description
                            + "' (abilityId=" + a.spellAbilityId + ')');
                    return false;
                }
                case ACTIVATE_ABILITY: {
                    if (a.abilityRef == null) {
                        return false;
                    }
                    return activateAbility((ActivatedAbility) a.abilityRef, game);
                }
                default:
                    pass(game);
                    return false;
            }
        } catch (Exception e) {
            logger.warn("[P1] execute threw for '" + a.description + "': " + e, e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    //  Candidate enumeration (mirrors LLMPlayer.buildActionList, top-level only)
    // ------------------------------------------------------------------

    private List<Action> buildCandidates(Game game) {
        List<Action> actions = new ArrayList<>();
        PlayableObjectsList playables = getPlayableObjects(game, Zone.ALL);
        List<ActivatedAbility> handPlayable = getPlayable(game, true, Zone.HAND, false);
        Set<UUID> castableCardIds = new HashSet<>();
        for (ActivatedAbility ability : handPlayable) {
            if (ability instanceof SpellAbility
                    && ((SpellAbility) ability).getSpellAbilityType() == SpellAbilityType.BASE) {
                castableCardIds.add(ability.getSourceId());
            }
        }

        boolean canStillPlayLand = canPlayLand();
        for (UUID cardId : getHand()) {
            Card card = game.getCard(cardId);
            if (card != null && card.isLand(game) && canStillPlayLand
                    && playables.getPlayableAmount(cardId) > 0) {
                actions.add(new Action(ActionType.PLAY_LAND, "PlayLand",
                        "Play land: " + card.getName(), cardId, cardId, Zone.HAND, "", false, null));
            }
        }
        for (Zone zone : NON_HAND_PLAY_ZONES) {
            for (ActivatedAbility ability : getPlayable(game, true, zone, false)) {
                if (!(ability instanceof PlayLandAbility)) {
                    continue;
                }
                UUID cardId = ability.getSourceId();
                if (cardId == null) {
                    continue;
                }
                Card card = game.getCard(cardId);
                if (card == null || !card.isLand(game)) {
                    continue;
                }
                actions.add(new Action(ActionType.PLAY_LAND, "PlayLand",
                        "Play land from " + zone + ": " + card.getName(), cardId, cardId, zone, "", false, null));
            }
        }
        for (UUID cardId : getHand()) {
            Card card = game.getCard(cardId);
            if (card == null || card.isLand(game)) {
                continue;
            }
            if (castableCardIds.contains(cardId)) {
                // Residual case: an Adventure/Omen card is ONE castable card but
                // TWO distinct model intents ("cast the creature" vs "cast the Adventure"). The
                // projection maps candidates to a card by source index, so both faces share the
                // same source index — fine and intended. Enumerate each castable face as its own
                // candidate, carrying the chosen SpellAbility id so execute() can honor the net's
                // face choice explicitly instead of leaving it to chooseAbilityForCast's default.
                List<SpellAbility> faces = castableFaces(card, game);
                if (faces.size() <= 1) {
                    String cost = card.getManaCost() == null ? "" : card.getManaCost().getText();
                    boolean hasX = false;
                    try {
                        hasX = card.getManaCost() != null && card.getManaCost().containsX();
                    } catch (Exception ignore) {
                        hasX = false;
                    }
                    actions.add(new Action(ActionType.CAST_SPELL, "CastSpell",
                            "Cast: " + card.getName() + " (" + cost + ")", cardId, cardId,
                            Zone.HAND, cost, hasX, null));
                } else {
                    for (SpellAbility face : faces) {
                        String cost = face.getManaCosts() == null ? "" : face.getManaCosts().getText();
                        boolean hasX = false;
                        try {
                            hasX = face.getManaCosts() != null && face.getManaCosts().containsX();
                        } catch (Exception ignore) {
                            hasX = false;
                        }
                        String faceLabel = faceLabel(card, face);
                        actions.add(new Action(ActionType.CAST_SPELL, "CastSpell",
                                "Cast: " + faceLabel + " (" + cost + ")", cardId, cardId,
                                Zone.HAND, cost, hasX, null, face.getId()));
                    }
                }
            }
            for (ActivatedAbility ability : handPlayable) {
                if (!cardId.equals(ability.getSourceId())) {
                    continue;
                }
                if (ability instanceof SpellAbility) {
                    continue; // cast entries above
                }
                if (ability instanceof ActivatedManaAbilityImpl) {
                    continue; // auto-handled by payMana
                }
                String kind = (ability instanceof mage.abilities.keyword.CyclingAbility)
                        ? "CycleCard" : "ActivateAbility";
                actions.add(new Action(ActionType.ACTIVATE_ABILITY, kind,
                        "Activate: " + card.getName() + " — " + shortRule(ability.getRule()),
                        ability.getId(), cardId, Zone.HAND, "", false, ability));
            }
        }
        for (Permanent perm : game.getBattlefield().getAllPermanents()) {
            if (!perm.isControlledBy(getId())) {
                continue;
            }
            if (playables.getPlayableAmount(perm.getId()) <= 0) {
                continue;
            }
            Map<UUID, ActivatedAbility> abs = getPlayableActivatedAbilities(perm, Zone.BATTLEFIELD, game);
            for (ActivatedAbility ability : abs.values()) {
                if (ability instanceof ActivatedManaAbilityImpl) {
                    continue; // auto-handled by payMana
                }
                if (ability instanceof mage.abilities.keyword.CrewAbility) {
                    actions.add(new Action(ActionType.ACTIVATE_ABILITY, "CrewVehicle",
                            "Activate: " + perm.getName() + " — " + shortRule(ability.getRule()),
                            ability.getId(), perm.getId(), Zone.BATTLEFIELD, "", false, ability));
                    continue;
                }
                if (ability instanceof mage.abilities.keyword.SaddleAbility) {
                    actions.add(new Action(ActionType.ACTIVATE_ABILITY, "SaddleMount",
                            "Activate: " + perm.getName() + " — " + shortRule(ability.getRule()),
                            ability.getId(), perm.getId(), Zone.BATTLEFIELD, "", false, ability));
                    continue;
                }
                actions.add(new Action(ActionType.ACTIVATE_ABILITY, "ActivateAbility",
                        "Activate: " + perm.getName() + " — " + shortRule(ability.getRule()),
                        ability.getId(), perm.getId(), Zone.BATTLEFIELD, "", false, ability));
            }
        }
        return actions;
    }

    private static String shortRule(String rule) {
        return rule != null && rule.length() > 80 ? rule.substring(0, 77) + "..." : rule;
    }

    /**
     * All spell abilities of {@code card} that are castable right now, using the engine's own
     * gate ({@link PlayerImpl#getCastableSpellAbilities}) plus the target-choose check — the
     * exact same pair {@link #chooseAbilityForCast} uses. For a plain card this is one ability;
     * for an Adventure/Omen it is one per castable face (BASE creature + ADVENTURE_SPELL inset).
     * Fail-fast: only abilities passing the engine's own canCast check are returned, so a face
     * the engine could not actually cast is never offered to the model.
     */
    private List<SpellAbility> castableFaces(Card card, Game game) {
        Map<UUID, SpellAbility> usable = PlayerImpl.getCastableSpellAbilities(
                game, getId(), card, game.getState().getZone(card.getId()), false);
        return usable.values().stream()
                .filter(a -> a.getTargets().canChoose(getId(), a, game))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Display label for one castable face. The main-card face keeps the card's name (the worker's
     * vocab is card-name-based); the inset face is suffixed with its spell type so the two
     * candidates for one card are unambiguous, e.g. "Hearth Elemental" vs "Stoke Genius (Adventure)".
     */
    private static String faceLabel(Card card, SpellAbility face) {
        switch (face.getSpellAbilityType()) {
            case ADVENTURE_SPELL:
                return face.getCardName() + " (Adventure)";
            case OMEN_SPELL:
                return face.getCardName() + " (Omen)";
            default:
                return card.getName();
        }
    }

    // ------------------------------------------------------------------
    //  Stats for the run report
    // ------------------------------------------------------------------

    /**
     * Per-seat counters, kept for the run report. Serializable: the player object is written into
     * the end-of-game save, so a non-serializable field here aborts GameController.saveGame
     * (observed: {@code NotSerializableException: mage.player.p1.P1Player$P1Stats}).
     */
    static final class P1Stats implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        int decisionWindows;
        // Windows where buildCandidates returned zero non-pass actions (the net still gets
        // consulted on these — with the single PassPriority candidate).
        int passOnlyWindows;
        int passes;
        int passesOnDeadWorker;
        int actionsExecuted;
        int fallbacks;
        int engineRejections;
        int failures;
        final Map<String, Integer> byKind = new LinkedHashMap<>();

        void recordChoice(String kind) {
            byKind.merge(kind, 1, Integer::sum);
        }

        String summary() {
            return "windows=" + decisionWindows + " passOnlyWindows=" + passOnlyWindows
                    + " actions=" + actionsExecuted + " passes=" + passes
                    + " deadWorkerPasses=" + passesOnDeadWorker + " fallbacks=" + fallbacks
                    + " rejections=" + engineRejections + " failures=" + failures
                    + " kinds=" + byKind;
        }
    }
}
