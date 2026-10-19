package org.mage.test.player.p1;

import mage.cards.Card;
import mage.cards.CardSetInfo;
import mage.cards.basiclands.Mountain;
import mage.cards.decks.Deck;
import mage.cards.h.HearthElemental;
import mage.cards.l.LightningBolt;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.constants.Rarity;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import mage.game.turn.PreCombatMainPhase;
import mage.game.turn.PreCombatMainStep;
import mage.player.p1.P1Player;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Regression test for the residual case: P1's candidate enumeration must offer an
 * Adventure card's TWO castable faces as separate candidates ("cast the creature" vs "cast the
 * Adventure"), and {@code execute()} must honor the model's chosen face.
 * <p>
 * Fixture approach follows {@code LLMAdNauseamChainTest.llmSeatChainsMoreThanOneCard_unitLevel}:
 * a never-started {@link TwoPlayerDuel} with cards registered via {@code game.loadCards} — no
 * game thread, no policy worker ({@code buildCandidates} never touches the worker). The phase is
 * set to precombat main with the P1 seat active so sorcery-speed casts are legal, and the seat
 * has 6 Mountains so both faces ({5}{R} and {1}{R}) are affordable.
 * <p>
 * {@code buildCandidates} is private (internal enumeration seam); the test reaches it by
 * reflection, and reads the {@code Action} fields the same way, so it asserts exactly what the
 * projection would be offered. RED property: on the pre-fix code (single candidate per card, no
 * {@code spellAbilityId}), the Adventure assertions fail on candidate count (1, not 2).
 */
public class P1AdventureCastCandidatesTest {

    /** Wire the engine's gameOptions field (no public setter; tests set testMode this way). */
    private static void setGameOptions(Game game, GameOptions opts) throws Exception {
        Field f = mage.game.GameImpl.class.getDeclaredField("gameOptions");
        f.setAccessible(true);
        f.set(game, opts);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> buildCandidates(P1Player seat, Game game) throws Exception {
        Method m = P1Player.class.getDeclaredMethod("buildCandidates", Game.class);
        m.setAccessible(true);
        return (List<Object>) m.invoke(seat, game);
    }

    private static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static final class Fixture {
        P1Player seat;
        Game game;
        Card hearth;
    }

    /**
     * Never-started game: P1 seat (active, precombat main, turn 1) with Hearth Elemental in hand
     * and 6 untapped Mountains on the battlefield. Opponent is a second P1Player — StubPlayer is
     * unusable here because its {@code copy()} returns null, which breaks the nested game
     * simulation {@code getPlayable} performs (copy-of-copy NPE in {@code Players.copy}).
     */
    private static Fixture newFixture() throws Exception {
        Fixture fx = new Fixture();
        fx.seat = new P1Player("P1Seat", RangeOfInfluence.ONE, 0);
        P1Player opp = new P1Player("Opp", RangeOfInfluence.ONE, 0);

        fx.game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
        GameOptions opts = new GameOptions();
        opts.testMode = true;
        opts.skipInitShuffling = true;
        setGameOptions(fx.game, opts);

        fx.hearth = new HearthElemental(fx.seat.getId(),
                new CardSetInfo("Hearth Elemental", "TEST", "1", Rarity.RARE));
        fx.hearth.setOwnerId(fx.seat.getId());
        List<Card> mountains = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Card m = new Mountain(fx.seat.getId(),
                    new CardSetInfo("Mountain", "TEST", "10" + i, Rarity.LAND));
            m.setOwnerId(fx.seat.getId());
            mountains.add(m);
        }

        Set<Card> all = new LinkedHashSet<>();
        all.add(fx.hearth);
        all.addAll(mountains);
        fx.game.loadCards(all, fx.seat.getId());
        fx.game.addPlayer(fx.seat, new Deck());
        fx.game.addPlayer(opp, new Deck());

        fx.hearth.moveToZone(Zone.HAND, null, fx.game, false, new ArrayList<>());
        fx.game.getState().setZone(fx.hearth.getId(), Zone.HAND);
        fx.game.getState().setZone(
                ((mage.cards.CardWithSpellOption) fx.hearth).getSpellCard().getId(), Zone.HAND);
        fx.seat.getHand().add(fx.hearth.getId());
        for (Card m : mountains) {
            m.moveToZone(Zone.BATTLEFIELD, null, fx.game, false, new ArrayList<>());
            fx.game.getState().setZone(m.getId(), Zone.BATTLEFIELD);
            fx.game.getBattlefield().addPermanent(
                    new mage.game.permanent.PermanentCard(m, fx.seat.getId(), fx.game));
        }

        fx.game.getState().setTurnNum(1);
        fx.game.getState().setActivePlayerId(fx.seat.getId());
        fx.seat.updateRange(fx.game);
        opp.updateRange(fx.game);
        PreCombatMainPhase phase = new PreCombatMainPhase();
        fx.game.getState().getTurn().setPhase(phase);
        phase.setStep(new PreCombatMainStep());
        return fx;
    }

    /**
     * The residual fix: an Adventure card with both faces castable must produce TWO
     * CastSpell candidates — one per face — each carrying the SpellAbility id of the face the
     * model would be choosing, with display names that make the face unambiguous.
     */
    @Test
    public void adventureCardEnumeratesBothFaces() throws Exception {
        Fixture fx = newFixture();
        List<Object> actions = buildCandidates(fx.seat, fx.game);

        List<Object> casts = new ArrayList<>();
        for (Object a : actions) {
            if ("CastSpell".equals(field(a, "kind"))) {
                casts.add(a);
            }
        }
        Assert.assertEquals("Adventure card must offer exactly two cast candidates (one per face)",
                2, casts.size());

        Set<String> descriptions = new LinkedHashSet<>();
        Set<UUID> abilityIds = new LinkedHashSet<>();
        for (Object a : casts) {
            descriptions.add((String) field(a, "description"));
            Object said = field(a, "spellAbilityId");
            Assert.assertNotNull("multi-face candidate must carry the chosen SpellAbility id", said);
            abilityIds.add((UUID) said);
            // The projection maps candidates to a card by source index: both faces share the
            // same source card (that is intended — the disambiguator is spellAbilityId).
            Assert.assertEquals("both faces must reference the same source card",
                    fx.hearth.getId(), field(a, "sourceId"));
        }
        Assert.assertEquals("the two faces must carry distinct ability ids", 2, abilityIds.size());
        Assert.assertTrue("creature face must be offered under the card name",
                descriptions.contains("Cast: Hearth Elemental ({5}{R})"));
        Assert.assertTrue("adventure face must be offered with an unambiguous (Adventure) suffix",
                descriptions.contains("Cast: Stoke Genius (Adventure) ({1}{R})"));
    }

    /**
     * Non-Adventure cards keep the exact pre-fix shape: ONE candidate, card name only, and no
     * spellAbilityId (execute falls through to the card-default playCard path).
     */
    @Test
    public void plainCardKeepsSingleCandidateShape() throws Exception {
        Fixture fx = newFixture();
        // Swap the Adventure for a plain instant: Lightning Bolt, 3 Mountains is plenty.
        fx.seat.getHand().remove(fx.hearth.getId());
        fx.hearth.removeFromZone(fx.game, Zone.HAND, null);
        Card bolt = new LightningBolt(fx.seat.getId(),
                new CardSetInfo("Lightning Bolt", "TEST", "2", Rarity.COMMON));
        bolt.setOwnerId(fx.seat.getId());
        Set<Card> more = new LinkedHashSet<>();
        more.add(bolt);
        fx.game.loadCards(more, fx.seat.getId());
        bolt.moveToZone(Zone.HAND, null, fx.game, false, new ArrayList<>());
        fx.game.getState().setZone(bolt.getId(), Zone.HAND);
        fx.seat.getHand().add(bolt.getId());

        List<Object> actions = buildCandidates(fx.seat, fx.game);
        List<Object> casts = new ArrayList<>();
        for (Object a : actions) {
            if ("CastSpell".equals(field(a, "kind"))) {
                casts.add(a);
            }
        }
        Assert.assertEquals("plain card must offer exactly one cast candidate", 1, casts.size());
        Assert.assertEquals("Cast: Lightning Bolt ({R})", field(casts.get(0), "description"));
        Assert.assertNull("single-face candidate must not pin a face (legacy playCard path)",
                field(casts.get(0), "spellAbilityId"));
        Assert.assertEquals(bolt.getId(), field(casts.get(0), "sourceId"));
    }
}
