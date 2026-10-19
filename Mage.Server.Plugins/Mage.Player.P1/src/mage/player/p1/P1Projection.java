package mage.player.p1;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import mage.MageObject;
import mage.ObjectColor;
import mage.abilities.Ability;
import mage.cards.Card;
import mage.constants.CardType;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.constants.TurnPhase;
import mage.constants.Zone;
import mage.counters.Counter;
import mage.game.ExileZone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.Spell;
import mage.game.stack.StackObject;
import mage.players.ManaPool;
import mage.players.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * XMage -> {@code argentum-p1-selfplay-sample@v1} observation projection.
 * <p>
 * Field-by-field implementation of the model's observation format. Everything is
 * read through ordinary server-side engine accessors ({@code Game}, {@code Player},
 * {@code Permanent}, {@code Card}, {@code StackObject}) — not the client {@code *View} layer, which
 * the spec's fallbacks also permit and which is the only way to reach keywords, live counters and
 * {@code GameState.getZone}.
 * <p>
 * Projection runs on the single game thread; the scratch map is per-call, never shared.
 */
final class P1Projection {

    private P1Projection() {
    }

    /** Model zone names. */
    private static String zoneName(Zone zone) {
        if (zone == null) {
            return "LIBRARY";
        }
        switch (zone) {
            case HAND:
                return "HAND";
            case GRAVEYARD:
                return "GRAVEYARD";
            case LIBRARY:
                return "LIBRARY";
            case BATTLEFIELD:
                return "BATTLEFIELD";
            case STACK:
                return "STACK";
            case EXILED:
                return "EXILE";
            default:
                return zone.name();
        }
    }

    /** Model phase names. XMage BEGINNING has no model value -> emits UNK (lossy by design). */
    private static String phaseName(TurnPhase phase) {
        if (phase == null) {
            return "PRECOMBAT_MAIN";
        }
        switch (phase) {
            case BEGINNING:
                // The checkpoint vocab has no BEGINNING phase (only PRECOMBAT_MAIN/COMBAT/
                // POSTCOMBAT_MAIN/ENDING), so emitting it resolved to the UNK id and fed the net
                // an out-of-distribution phase token for every untap/upkeep/draw window. Map it
                // to PRECOMBAT_MAIN — the same squash stepName already applies to those steps.
                return "PRECOMBAT_MAIN";
            case PRECOMBAT_MAIN:
                return "PRECOMBAT_MAIN";
            case COMBAT:
                return "COMBAT";
            case POSTCOMBAT_MAIN:
                return "POSTCOMBAT_MAIN";
            case END:
                return "ENDING";
            default:
                return phase.name();
        }
    }

    /** Model step names. XMage's extra steps map to the nearest model step. */
    private static String stepName(PhaseStep step) {
        if (step == null) {
            return "PRECOMBAT_MAIN";
        }
        switch (step) {
            case UNTAP:
            case UPKEEP:
            case DRAW:
                return "PRECOMBAT_MAIN"; // no model step exists for these early steps
            case PRECOMBAT_MAIN:
                return "PRECOMBAT_MAIN";
            case BEGIN_COMBAT:
            case DECLARE_ATTACKERS:
                return "DECLARE_ATTACKERS";
            case DECLARE_BLOCKERS:
                return "DECLARE_BLOCKERS";
            case FIRST_COMBAT_DAMAGE:
            case COMBAT_DAMAGE:
                return "FIRST_STRIKE_COMBAT_DAMAGE";
            case END_COMBAT:
                return "END_COMBAT";
            case POSTCOMBAT_MAIN:
                return "POSTCOMBAT_MAIN";
            case END_TURN:
            case CLEANUP:
                return "END";
            default:
                return step.name();
        }
    }

    /** Counter name map, with the catch-all uppercase/underscore rule. */
    static String counterName(String xmageName) {
        if (xmageName == null) {
            return "";
        }
        switch (xmageName) {
            case "blight":
                return "BLIGHT";
            case "charge":
                return "CHARGE";
            case "double strike":
                return "DOUBLE_STRIKE";
            case "finality":
                return "FINALITY";
            case "hexproof":
                return "HEXPROOF";
            case "incubation":
                return "INCUBATION";
            case "indestructible":
                return "INDESTRUCTIBLE";
            case "lore":
                return "LORE";
            case "loyalty":
                return "LOYALTY";
            case "plan":
                return "PLAN";
            case "quest":
                return "QUEST";
            case "stun":
                return "STUN";
            case "time":
                return "TIME";
            case "+1/+1":
                return "PLUS_ONE_PLUS_ONE";
            case "-1/-1":
                return "MINUS_ONE_MINUS_ONE";
            default:
                return xmageName.toUpperCase().replace(' ', '_');
        }
    }

    /** Counter truncation order (descending count, then this fixed order). */
    private static final List<String> COUNTER_ORDER = java.util.Arrays.asList(
            "PLUS_ONE_PLUS_ONE", "MINUS_ONE_MINUS_ONE", "LOYALTY", "CHARGE", "BLIGHT", "FINALITY",
            "HEXPROOF", "INCUBATION", "INDESTRUCTIBLE", "LORE", "PLAN", "QUEST", "STUN", "TIME",
            "DOUBLE_STRIKE", "HARNESS");

    /** Ability-class -> model keyword. Match on the base class so variants are caught. */
    static void mapKeyword(Ability ability, Set<String> out) {
        if (ability == null) {
            return;
        }
        if (ability instanceof mage.abilities.keyword.AffinityAbility) {
            out.add("AFFINITY");
        } else if (ability instanceof mage.abilities.keyword.CantBeBlockedSourceAbility) {
            out.add("CANT_BE_BLOCKED");
        } else if (ability instanceof mage.abilities.keyword.ChangelingAbility) {
            out.add("CHANGELING");
        } else if (ability instanceof mage.abilities.keyword.ConvokeAbility) {
            out.add("CONVOKE");
        } else if (ability instanceof mage.abilities.keyword.CrewAbility) {
            out.add("CREW");
        } else if (ability instanceof mage.abilities.keyword.DeathtouchAbility) {
            out.add("DEATHTOUCH");
        } else if (ability instanceof mage.abilities.keyword.DoubleStrikeAbility) {
            out.add("DOUBLE_STRIKE");
        } else if (ability instanceof mage.abilities.keyword.EvokeAbility) {
            out.add("EVOKE");
        } else if (ability instanceof mage.abilities.keyword.FirstStrikeAbility) {
            out.add("FIRST_STRIKE");
        } else if (ability instanceof mage.abilities.keyword.FlashAbility) {
            out.add("FLASH");
        } else if (ability instanceof mage.abilities.keyword.FlashbackAbility) {
            out.add("FLASHBACK");
        } else if (ability instanceof mage.abilities.keyword.FlyingAbility) {
            out.add("FLYING");
        } else if (ability instanceof mage.abilities.keyword.HarmonizeAbility) {
            out.add("HARMONIZE");
        } else if (ability instanceof mage.abilities.keyword.HasteAbility) {
            out.add("HASTE");
        } else if (ability instanceof mage.abilities.keyword.HexproofBaseAbility) {
            out.add("HEXPROOF");
        } else if (ability instanceof mage.abilities.keyword.ImpendingAbility) {
            out.add("IMPENDING");
        } else if (ability instanceof mage.abilities.keyword.IndestructibleAbility) {
            out.add("INDESTRUCTIBLE");
        } else if (ability instanceof mage.abilities.keyword.LifelinkAbility) {
            out.add("LIFELINK");
        } else if (ability instanceof mage.abilities.keyword.MayhemAbility) {
            out.add("MAYHEM");
        } else if (ability instanceof mage.abilities.keyword.MenaceAbility) {
            out.add("MENACE");
        } else if (ability instanceof mage.abilities.keyword.MobilizeAbility) {
            out.add("MOBILIZE");
        } else if (ability instanceof mage.abilities.keyword.NinjutsuAbility) {
            out.add("NINJUTSU");
        } else if (ability instanceof mage.abilities.keyword.OffspringAbility) {
            out.add("OFFSPRING");
        } else if (ability instanceof mage.abilities.keyword.ParadigmAbility) {
            out.add("PARADIGM");
        } else if (ability instanceof mage.abilities.keyword.PlotAbility) {
            out.add("PLOT");
        } else if (ability instanceof mage.abilities.keyword.ProwessAbility) {
            out.add("PROWESS");
        } else if (ability instanceof mage.abilities.keyword.ReachAbility) {
            out.add("REACH");
        } else if (ability instanceof mage.abilities.keyword.SaddleAbility) {
            out.add("SADDLE");
        } else if (ability instanceof mage.abilities.keyword.SneakAbility) {
            out.add("SNEAK");
        } else if (ability instanceof mage.abilities.keyword.StartYourEnginesAbility) {
            out.add("START_YOUR_ENGINES");
        } else if (ability instanceof mage.abilities.keyword.TeamworkAbility) {
            out.add("TEAMWORK");
        } else if (ability instanceof mage.abilities.keyword.TrampleAbility) {
            out.add("TRAMPLE");
        } else if (ability instanceof mage.abilities.keyword.VigilanceAbility) {
            out.add("VIGILANCE");
        } else if (ability instanceof mage.abilities.keyword.WardAbility) {
            out.add("WARD");
        } else if (ability instanceof mage.abilities.keyword.WebSlingingAbility) {
            out.add("WEB_SLINGING");
        }
    }

    // ------------------------------------------------------------------
    //  Per-object token fields
    // ------------------------------------------------------------------

    private static JsonArray typesOf(MageObject object, Game game) {
        JsonArray out = new JsonArray();
        if (object == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (CardType t : object.getCardType(game)) {
            seen.add(t.toString().toUpperCase());
        }
        for (SuperType t : object.getSuperType(game)) {
            seen.add(t.toString().toUpperCase());
        }
        for (SubType t : object.getSubtype(game)) {
            seen.add(t.toString()); // subtypes are already Title case in the model vocab
        }
        for (String s : seen) {
            out.add(s);
        }
        return out;
    }

    private static JsonArray colorsOf(MageObject object, Game game) {
        JsonArray out = new JsonArray();
        ObjectColor color = object == null ? null : object.getColor(game);
        if (color == null) {
            return out;
        }
        if (color.isWhite()) {
            out.add("W");
        }
        if (color.isBlue()) {
            out.add("U");
        }
        if (color.isBlack()) {
            out.add("B");
        }
        if (color.isRed()) {
            out.add("R");
        }
        if (color.isGreen()) {
            out.add("G");
        }
        return out;
    }

    private static JsonArray keywordsOf(MageObject object, Game game) {
        Set<String> out = new LinkedHashSet<>();
        if (object instanceof Permanent) {
            for (Ability a : ((Permanent) object).getAbilities(game)) {
                mapKeyword(a, out);
            }
        } else if (object instanceof Card) {
            for (Ability a : ((Card) object).getAbilities(game)) {
                mapKeyword(a, out);
            }
        }
        JsonArray arr = new JsonArray();
        for (String s : out) {
            arr.add(s);
        }
        return arr;
    }

    private static JsonObject countersOf(Card card, Game game) {
        Map<String, Integer> counts = new HashMap<>();
        if (card != null) {
            for (Counter c : card.getCounters(game).values()) {
                counts.merge(counterName(c.getName()), c.getCount(), Integer::sum);
            }
        }
        List<String> keys = new ArrayList<>(counts.keySet());
        keys.sort((a, b) -> {
            int byCount = Integer.compare(counts.get(b), counts.get(a));
            if (byCount != 0) {
                return byCount;
            }
            int ia = COUNTER_ORDER.indexOf(a);
            int ib = COUNTER_ORDER.indexOf(b);
            if (ia < 0) {
                ia = Integer.MAX_VALUE;
            }
            if (ib < 0) {
                ib = Integer.MAX_VALUE;
            }
            if (ia != ib) {
                return Integer.compare(ia, ib);
            }
            return a.compareTo(b);
        });
        JsonObject out = new JsonObject();
        for (String k : keys) {
            out.addProperty(k, counts.get(k));
        }
        return out;
    }

    /** Non-numeric P/T (`*`, `1+*`) or non-creatures -> null. */
    private static Integer parsePt(MageObject object, boolean power, Game game) {
        if (object == null || !object.getCardType(game).contains(CardType.CREATURE)) {
            return null;
        }
        try {
            String raw = power ? object.getPower().toString() : object.getToughness().toString();
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject cardToken(Card card, Game game, String zone, String side, int attachedTo) {
        JsonObject o = new JsonObject();
        o.addProperty("name", card.getName() == null ? "" : card.getName());
        o.addProperty("zone", zone);
        o.addProperty("side", side);
        o.add("types", typesOf(card, game));
        o.add("colors", colorsOf(card, game));
        o.add("keywords", keywordsOf(card, game));
        int manaValue = 0;
        try {
            manaValue = card.getManaValue();
        } catch (Exception ignore) {
            manaValue = 0;
        }
        o.addProperty("manaValue", manaValue);
        Integer p = parsePt(card, true, game);
        Integer t = parsePt(card, false, game);
        if (p == null) {
            o.add("power", JsonNull.INSTANCE);
        } else {
            o.addProperty("power", p);
        }
        if (t == null) {
            o.add("toughness", JsonNull.INSTANCE);
        } else {
            o.addProperty("toughness", t);
        }
        boolean tapped = false;
        boolean sick = false;
        boolean faceDown = false;
        int damage = 0;
        if (card instanceof Permanent) {
            Permanent perm = (Permanent) card;
            tapped = perm.isTapped();
            sick = perm.hasSummoningSickness();
            damage = perm.getDamage();
        }
        try {
            faceDown = card.isFaceDown(game);
        } catch (Exception ignore) {
            faceDown = false;
        }
        o.addProperty("tapped", tapped);
        o.addProperty("summoningSick", sick);
        o.addProperty("faceDown", faceDown);
        o.addProperty("damage", damage);
        o.add("counters", countersOf(card, game));
        o.addProperty("attachedTo", attachedTo);
        return o;
    }

    // ------------------------------------------------------------------
    //  Top-level projection
    // ------------------------------------------------------------------

    /** Projection output: the sample plus the parallel {@code allowed} mask. */
    static final class Projection {
        final JsonObject sample;
        final JsonArray allowed;

        Projection(JsonObject sample, JsonArray allowed) {
            this.sample = sample;
            this.allowed = allowed;
        }
    }

    /**
     * @param self       the acting (P1) seat
     * @param game       live game
     * @param candidates the candidate list (order defines the model's index space)
     * @param abilitySrc abilityId -> source permanent/card UUID (for ACTIVATE_ABILITY candidates)
     */
    static Projection project(P1Player self, Game game, List<P1Player.Action> candidates,
                              Map<UUID, UUID> abilitySrc) {
        UUID selfId = self.getId();

        List<Card> order = new ArrayList<>();
        Map<UUID, Integer> cardIndex = new LinkedHashMap<>();
        Set<UUID> emitted = new LinkedHashSet<>();
        Map<UUID, String> sides = new HashMap<>();

        Player selfPlayer = self;
        Player opponent = null;
        for (UUID pid : game.getState().getPlayerList()) {
            if (!pid.equals(selfId)) {
                opponent = game.getPlayer(pid);
                break;
            }
        }

        // 1/2. Battlefields, self first; deterministic (name, id) ordering.
        List<Permanent> bf = new ArrayList<>(game.getBattlefield().getAllPermanents());
        bf.sort(Comparator.comparing((Permanent p) -> p.getName() == null ? "" : p.getName())
                .thenComparing(p -> p.getId().toString()));
        for (int pass = 0; pass < 2; pass++) {
            boolean wantSelf = (pass == 0);
            String side = wantSelf ? "self" : "opponent";
            for (Permanent p : bf) {
                if (p.isControlledBy(selfId) != wantSelf) {
                    continue;
                }
                emit(order, cardIndex, emitted, sides, p, side);
            }
        }

        // 3/4. Graveyards.
        for (int pass = 0; pass < 2; pass++) {
            Player p = (pass == 0) ? selfPlayer : opponent;
            if (p == null) {
                continue;
            }
            String side = (pass == 0) ? "self" : "opponent";
            for (UUID id : p.getGraveyard()) {
                Card c = game.getCard(id);
                if (c != null) {
                    emit(order, cardIndex, emitted, sides, c, side);
                }
            }
        }

        // 5/6. Exile (global zone; side by owner).
        for (ExileZone zone : game.getExile().getExileZones()) {
            for (UUID id : zone) {
                Card c = game.getCard(id);
                if (c == null || game.getState().getZone(id) != Zone.EXILED) {
                    continue;
                }
                emit(order, cardIndex, emitted, sides, c,
                        selfId.equals(c.getOwnerId()) ? "self" : "opponent");
            }
        }

        // 7. Self hand — the only hand whose card objects the acting seat can see.
        for (UUID id : selfPlayer.getHand()) {
            Card c = game.getCard(id);
            if (c != null) {
                emit(order, cardIndex, emitted, sides, c, "self");
            }
        }

        // Stack tokens live after cards[] in the encoder's token space.
        List<StackObject> stackList = new ArrayList<>(game.getStack());
        Map<UUID, Integer> stackIndex = new HashMap<>();
        for (int i = 0; i < stackList.size(); i++) {
            stackIndex.put(stackList.get(i).getId(), i);
        }

        JsonArray cards = new JsonArray();
        for (Card mo : order) {
            UUID id = mo.getId();
            int attachedTo = -1;
            if (mo instanceof Permanent) {
                UUID att = ((Permanent) mo).getAttachedTo();
                if (att != null && cardIndex.containsKey(att) && emitted.contains(att)) {
                    attachedTo = cardIndex.get(att);
                }
            }
            cards.add(cardToken(mo, game, zoneName(game.getState().getZone(id)),
                    sides.getOrDefault(id, "opponent"), attachedTo));
        }

        JsonArray stack = new JsonArray();
        for (StackObject so : stackList) {
            JsonObject item = new JsonObject();
            item.addProperty("name", so.getName() == null ? "" : so.getName());
            item.addProperty("kind", (so instanceof Spell) ? "Spell" : "Ability");
            item.addProperty("controller", so.isControlledBy(selfId) ? "self" : "opponent");
            Integer src = cardIndex.get(so.getSourceId());
            item.addProperty("source", src == null ? -1 : src);
            JsonArray targets = new JsonArray();
            Ability stackAbility = so.getStackAbility();
            if (stackAbility != null) {
                for (UUID t : targetIds(stackAbility)) {
                    Integer ti = cardIndex.get(t);
                    if (ti != null && emitted.contains(t)) {
                        targets.add(ti);
                    } else {
                        Integer si = stackIndex.get(t);
                        if (si != null) {
                            targets.add(order.size() + si);
                        }
                    }
                }
            }
            item.add("targets", targets);
            stack.add(item);
        }

        JsonArray players = new JsonArray();
        players.add(playerToken(game, selfPlayer, "self"));
        players.add(opponent == null ? emptyPlayerToken("opponent") : playerToken(game, opponent, "opponent"));

        JsonArray candArr = new JsonArray();
        JsonArray allowed = new JsonArray();
        for (P1Player.Action a : candidates) {
            JsonObject c = new JsonObject();
            c.addProperty("kind", a.kind);
            UUID srcId = a.sourceId;
            if (a.actionType == P1Player.ActionType.ACTIVATE_ABILITY && srcId != null) {
                UUID mapped = abilitySrc.get(srcId);
                if (mapped != null) {
                    srcId = mapped;
                }
            }
            Integer idx = srcId == null ? null : cardIndex.get(srcId);
            boolean resolved = idx != null && emitted.contains(srcId);
            c.addProperty("source", resolved ? idx : -1);
            JsonArray targets = new JsonArray();
            if (a.actionType == P1Player.ActionType.ACTIVATE_ABILITY && a.abilityRef != null) {
                for (UUID t : targetIds(a.abilityRef)) {
                    Integer ti = cardIndex.get(t);
                    if (ti != null && emitted.contains(t)) {
                        targets.add(ti);
                    }
                }
            }
            // CAST_SPELL / PLAY_LAND at top-level priority: targets = [] (engine fills them).
            c.add("targets", targets);
            c.addProperty("manaCost", a.actionType == P1Player.ActionType.CAST_SPELL ? a.manaCost : "");
            c.addProperty("hasX", a.hasX);
            candArr.add(c);
            allowed.add(true);
        }

        JsonObject sample = new JsonObject();
        sample.addProperty("schema", "argentum-p1-selfplay-sample@v1");
        sample.addProperty("seat", "self");
        sample.addProperty("turn", game.getTurnNum());
        sample.addProperty("phase", phaseName(game.getPhase().getType()));
        sample.addProperty("step", stepName(game.getTurnStepType()));
        sample.addProperty("activeIsSelf", game.isActivePlayer(selfId));
        sample.add("players", players);
        sample.add("cards", cards);
        sample.add("stack", stack);
        sample.add("candidates", candArr);
        return new Projection(sample, allowed);
    }

    private static void emit(List<Card> order, Map<UUID, Integer> cardIndex, Set<UUID> emitted,
                             Map<UUID, String> sides, Card card, String side) {
        if (card == null || card.getId() == null || emitted.contains(card.getId())) {
            return;
        }
        emitted.add(card.getId());
        cardIndex.put(card.getId(), order.size());
        sides.put(card.getId(), side);
        order.add(card);
    }

    private static List<UUID> targetIds(Ability ability) {
        List<UUID> out = new ArrayList<>();
        try {
            for (mage.target.Target t : ability.getTargets()) {
                if (t.getTargets() != null) {
                    out.addAll(t.getTargets());
                }
            }
        } catch (Exception ignore) {
            // best effort — an ability without targets simply yields []
        }
        return out;
    }

    private static JsonArray manaArray(ManaPool pool) {
        JsonArray a = new JsonArray();
        if (pool == null) {
            for (int i = 0; i < 6; i++) {
                a.add(0);
            }
            return a;
        }
        a.add(pool.getWhite());
        a.add(pool.getBlue());
        a.add(pool.getBlack());
        a.add(pool.getRed());
        a.add(pool.getGreen());
        a.add(pool.getColorless());
        return a;
    }

    private static JsonObject emptyPlayerToken(String side) {
        JsonObject p = new JsonObject();
        p.addProperty("side", side);
        p.addProperty("life", 0);
        p.addProperty("hand", 0);
        p.addProperty("library", 0);
        p.addProperty("graveyard", 0);
        p.addProperty("exile", 0);
        p.add("mana", manaArray(null));
        p.addProperty("active", false);
        return p;
    }

    private static JsonObject playerToken(Game game, Player player, String side) {
        if (player == null) {
            return emptyPlayerToken(side);
        }
        JsonObject p = new JsonObject();
        p.addProperty("side", side);
        p.addProperty("life", player.getLife());
        p.addProperty("hand", player.getHand().size());
        p.addProperty("library", player.getLibrary().size());
        p.addProperty("graveyard", player.getGraveyard().size());
        p.addProperty("exile", countExileFor(game, player.getId()));
        p.add("mana", manaArray(player.getManaPool()));
        p.addProperty("active", game.isActivePlayer(player.getId()));
        return p;
    }

    private static int countExileFor(Game game, UUID ownerId) {
        int count = 0;
        for (ExileZone zone : game.getExile().getExileZones()) {
            for (UUID id : zone) {
                Card c = game.getCard(id);
                if (c != null && ownerId.equals(c.getOwnerId())
                        && game.getState().getZone(id) == Zone.EXILED) {
                    count++;
                }
            }
        }
        return count;
    }
}
