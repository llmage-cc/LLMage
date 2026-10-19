package mage.game.permanent.token;

import mage.abilities.Ability;
import mage.abilities.costs.common.SacrificeSourceCost;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.effects.mana.AddManaOfAnyColorEffect;
import mage.abilities.mana.SimpleManaAbility;
import mage.constants.CardType;

/**
 * @author muz
 */
public final class LotusToken extends TokenImpl {
    // Pinned 2026-10-02 against silent UID drift orphaning the .game replay corpus
    // the third instance is
    // This is the value this class
    // computed when the pin was added: it FREEZES the wire UID, so later field/method/superclass
    // edits no longer change it. A deliberate format break must re-derive it explicitly.
    private static final long serialVersionUID = -6603041440864492621L;

    public LotusToken() {
        super("Lotus", "Lotus token");
        cardType.add(CardType.ARTIFACT);

        Ability ability = new SimpleManaAbility(new AddManaOfAnyColorEffect(3), new TapSourceCost());
        ability.addCost(new SacrificeSourceCost());
        this.addAbility(ability);
    }

    private LotusToken(final LotusToken token) {
        super(token);
    }

    public LotusToken copy() {
        return new LotusToken(this);
    }
}
