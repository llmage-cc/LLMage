package mage.abilities.condition.common;

import mage.abilities.Ability;
import mage.abilities.condition.Condition;
import mage.abilities.hint.ConditionHint;
import mage.abilities.hint.Hint;
import mage.game.Game;
import mage.watchers.common.ScryOrSurveilWatcher;

/**
 * @author muz
 */
public enum ScryOrSurveilCondition implements Condition {
    instance;

    // Pinned 2026-10-02. An enum is serialized by constant NAME, not by class descriptor, so the
    // JVM reports UID 0 for every enum and this pin can never drift
    // Declared anyway so the drift gate sees a
    // deliberate pin instead of an unowned class.
    private static final long serialVersionUID = 0L;

    private static final Hint hint = new ConditionHint(instance);

    public static Hint getHint() {
        return hint;
    }

    @Override
    public boolean apply(Game game, Ability source) {
        ScryOrSurveilWatcher watcher = game.getState().getWatcher(ScryOrSurveilWatcher.class);
        return watcher != null && watcher.hasScriedOrSurveilled(source.getControllerId());
    }

    @Override
    public String toString() {
        return "you've scried or surveilled this turn";
    }
}
