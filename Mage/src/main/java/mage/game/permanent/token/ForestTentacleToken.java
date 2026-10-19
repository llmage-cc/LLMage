package mage.game.permanent.token;

import mage.MageInt;
import mage.abilities.mana.GreenManaAbility;
import mage.constants.CardType;
import mage.constants.SubType;

/**
 * @author muz
 */
public final class ForestTentacleToken extends TokenImpl {
    // Pinned 2026-10-02 against silent UID drift orphaning the .game replay corpus
    // the third instance is
    // This is the value this class
    // computed when the pin was added: it FREEZES the wire UID, so later field/method/superclass
    // edits no longer change it. A deliberate format break must re-derive it explicitly.
    private static final long serialVersionUID = 6781361087889443144L;

    public ForestTentacleToken() {
        super("Forest Tentacle", "Forest Tentacle token");
        cardType.add(CardType.LAND);
        cardType.add(CardType.CREATURE);
        subtype.add(SubType.FOREST);
        subtype.add(SubType.TENTACLE);
        color.setGreen(true);

        this.power = new MageInt(3);
        this.toughness = new MageInt(3);

        this.addAbility(new GreenManaAbility());
    }

    private ForestTentacleToken(final ForestTentacleToken token) {
        super(token);
    }

    public ForestTentacleToken copy() {
        return new ForestTentacleToken(this);
    }
}
