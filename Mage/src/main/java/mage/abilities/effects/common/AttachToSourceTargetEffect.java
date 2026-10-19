package mage.abilities.effects.common;

import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.abilities.effects.OneShotEffect;
import mage.constants.Outcome;
import mage.game.Game;
import mage.game.permanent.Permanent;

import java.util.UUID;

/**
 * Attaches the targeted permanents (Equipment, say) to this ability's source. {@link AttachEffect} is the
 * other way round: it attaches the source to its target.
 *
 * @author notgreat
 */
public class AttachToSourceTargetEffect extends OneShotEffect {
    // Pinned 2026-10-02 against silent UID drift orphaning the .game replay corpus
    // the third instance is
    // This is the value this class
    // computed when the pin was added: it FREEZES the wire UID, so later field/method/superclass
    // edits no longer change it. A deliberate format break must re-derive it explicitly.
    private static final long serialVersionUID = -4505143709879460827L;

    public AttachToSourceTargetEffect() {
        super(Outcome.BoostCreature);
    }

    private AttachToSourceTargetEffect(final AttachToSourceTargetEffect effect) {
        super(effect);
    }

    @Override
    public AttachToSourceTargetEffect copy() {
        return new AttachToSourceTargetEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Permanent sourcePermanent = source.getSourcePermanentIfItStillExists(game);
        if (sourcePermanent == null) {
            return false;
        }
        for (UUID targetId : getTargetPointer().getTargets(game, source)) {
            Permanent attachment = game.getPermanent(targetId);
            if (attachment != null) {
                sourcePermanent.addAttachment(attachment.getId(), source, game);
            }
        }
        return true;
    }

    @Override
    public String getText(Mode mode) {
        if (staticText != null && !staticText.isEmpty()) {
            return staticText;
        }
        return "attach " + getTargetPointer().describeTargets(mode.getTargets(), "that permanent") + " to {this}";
    }
}
