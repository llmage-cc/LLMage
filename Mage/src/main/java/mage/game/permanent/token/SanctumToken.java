package mage.game.permanent.token;

import mage.abilities.mana.AnyColorManaAbility;
import mage.constants.CardType;

/**
 * @author muz
 */
public final class SanctumToken extends TokenImpl {
    // Pinned 2026-10-02 against silent UID drift orphaning the .game replay corpus
    // the third instance is
    // This is the value this class
    // computed when the pin was added: it FREEZES the wire UID, so later field/method/superclass
    // edits no longer change it. A deliberate format break must re-derive it explicitly.
    private static final long serialVersionUID = -1426367231911116642L;

    public SanctumToken() {
        super("Sanctum", "land token named Sanctum with \"{T}: Add one mana of any color.\"");
        cardType.add(CardType.LAND);
        this.addAbility(new AnyColorManaAbility());
    }

    private SanctumToken(final SanctumToken token) {
        super(token);
    }

    public SanctumToken copy() {
        return new SanctumToken(this);
    }
}
