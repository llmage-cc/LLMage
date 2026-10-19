package mage.abilities.effects.common;

import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.Mode;
import mage.abilities.effects.OneShotEffect;
import mage.cards.Card;
import mage.constants.Outcome;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.Spell;
import mage.players.Player;

import java.util.*;

/**
 * @author BetaSteward_at_googlemail.com
 */
public class ReturnToHandTargetEffect extends OneShotEffect {

    public ReturnToHandTargetEffect() {
        super(Outcome.ReturnToHand);
    }

    protected ReturnToHandTargetEffect(final ReturnToHandTargetEffect effect) {
        super(effect);
    }

    @Override
    public ReturnToHandTargetEffect copy() {
        return new ReturnToHandTargetEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        List<UUID> copyIds = new ArrayList<>();
        Set<Card> cards = new LinkedHashSet<>();
        for (UUID targetId : getTargetPointer().getTargets(game, source)) {
            MageObject mageObject = game.getObject(targetId);
            if (mageObject != null) {
                if (mageObject instanceof Spell
                        && mageObject.isCopy()) {
                    copyIds.add(targetId);
                } else if (mageObject instanceof Card) {
                    // Tokens that leave the battlefield cease to exist (CR 111.5).
                    // Don't add them to cards — moveCards would log "put into hand"
                    // even though ZonesHandler won't actually place the token there.
                    // The token is still removed from the battlefield by moveToZone.
                    if (mageObject instanceof Permanent && ((Permanent) mageObject).isToken()) {
                        // Token — will be removed from battlefield by moveCards,
                        // but won't be placed in any zone. Skip adding to cards set
                        // and handle removal separately.
                        Permanent perm = (Permanent) mageObject;
                        perm.moveToZone(Zone.HAND, source, game, false);
                    } else {
                        cards.add((Card) mageObject);
                    }
                }
            }
        }
        for (UUID copyId : copyIds) {
            game.getStack().remove(game.getSpell(copyId), game);
        }
        if (!cards.isEmpty()) {
            return controller.moveCards(cards, Zone.HAND, source, game);
        }
        return !getTargetPointer().getTargets(game, source).isEmpty();
    }

    @Override
    public String getText(Mode mode) {
        if (staticText != null && !staticText.isEmpty()) {
            return staticText;
        }
        return "return " + getTargetPointer().describeTargets(mode.getTargets(), "that creature") +
                (getTargetPointer().isPlural(mode.getTargets()) ? " to their owners' hands" : " to its owner's hand");
    }
}
