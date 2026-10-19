package mage.game.permanent.token;

import mage.MageInt;
import mage.abilities.keyword.FlyingAbility;
import mage.constants.CardType;
import mage.constants.SubType;

/*
* @author muz
*/
public final class BlueAngelToken extends TokenImpl {
    // Pinned 2026-10-02 against silent UID drift orphaning the .game replay corpus
    // the third instance is
    // This is the value this class
    // computed when the pin was added: it FREEZES the wire UID, so later field/method/superclass
    // edits no longer change it. A deliberate format break must re-derive it explicitly.
    private static final long serialVersionUID = -9069992225952058273L;

    public BlueAngelToken() {
        super("Angel Token", "3/3 blue Angel creature token with flying");
        cardType.add(CardType.CREATURE);
        color.setBlue(true);
        subtype.add(SubType.ANGEL);
        power = new MageInt(3);
        toughness = new MageInt(3);

        addAbility(FlyingAbility.getInstance());
    }

    private BlueAngelToken(final BlueAngelToken token) {
        super(token);
    }

    public BlueAngelToken copy() {
        return new BlueAngelToken(this);
    }
}
