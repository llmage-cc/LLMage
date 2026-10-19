package mage.filter.predicate.mageobject;

import mage.constants.SubType;
import mage.filter.FilterPermanent;
import mage.filter.predicate.ObjectSourcePlayer;
import mage.filter.predicate.ObjectSourcePlayerPredicate;
import mage.game.Game;
import mage.game.stack.StackObject;

/**
 * @author muz
 */
public class SpellOrTargetsPermanentPredicate implements ObjectSourcePlayerPredicate<StackObject> {
    // Pinned 2026-10-02 against silent UID drift orphaning the .game replay corpus
    // the third instance is
    // This is the value this class
    // computed when the pin was added: it FREEZES the wire UID, so later field/method/superclass
    // edits no longer change it. A deliberate format break must re-derive it explicitly.
    private static final long serialVersionUID = 2821881409499991948L;

    private final SubType spellSubtype;
    private final TargetsPermanentPredicate targetsPermanentPredicate;

    public SpellOrTargetsPermanentPredicate(SubType spellSubtype, FilterPermanent targetFilter) {
        this.spellSubtype = spellSubtype;
        this.targetsPermanentPredicate = new TargetsPermanentPredicate(targetFilter);
    }

    @Override
    public boolean apply(ObjectSourcePlayer<StackObject> input, Game game) {
        return input.getObject().hasSubtype(spellSubtype, game)
                || targetsPermanentPredicate.apply(input, game);
    }
}
