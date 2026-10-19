package mage.game.permanent.token;

import mage.MageInt;
import mage.abilities.token.TreasureAbility;
import mage.constants.CardType;
import mage.constants.SubType;

/**
 * @author muz
 */
public final class SculptureTreasureToken extends TokenImpl {
    // Pinned 2026-10-02 against silent UID drift orphaning the .game replay corpus
    // the third instance is
    // This is the value this class
    // computed when the pin was added: it FREEZES the wire UID, so later field/method/superclass
    // edits no longer change it. A deliberate format break must re-derive it explicitly.
    private static final long serialVersionUID = -4048894276367478684L;

    public SculptureTreasureToken() {
        super("Sculpture Treasure Token", "1/1 colorless Sculpture Treasure artifact creature token with \"{T}, Sacrifice this token: Add one mana of any color.\"");
        cardType.add(CardType.CREATURE);
        cardType.add(CardType.ARTIFACT);
        subtype.add(SubType.SCULPTURE);
        subtype.add(SubType.TREASURE);
        power = new MageInt(1);
        toughness = new MageInt(1);

        this.addAbility(new TreasureAbility(false));
    }

    private SculptureTreasureToken(final SculptureTreasureToken token) {
        super(token);
    }

    public SculptureTreasureToken copy() {
        return new SculptureTreasureToken(this);
    }

}
