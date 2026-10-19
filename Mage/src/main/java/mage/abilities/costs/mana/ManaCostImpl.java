package mage.abilities.costs.mana;

import mage.ApprovingObject;
import mage.ConditionalMana;
import mage.Mana;
import mage.abilities.Ability;
import mage.abilities.AbilityImpl;
import mage.abilities.costs.Cost;
import mage.abilities.costs.CostImpl;
import mage.abilities.mana.ManaOptions;
import mage.constants.AsThoughEffectType;
import mage.constants.ColoredManaSymbol;
import mage.constants.ManaType;
import mage.filter.Filter;
import mage.filter.FilterMana;
import mage.game.Game;
import mage.players.ManaPool;
import mage.players.Player;
import mage.util.ManaUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public abstract class ManaCostImpl extends CostImpl implements ManaCost {

    // Pinned 2026-09-30:
    // this class has been edited repeatedly (a payability change was the
    // orphaning edit), and every unpinned edit silently invalidated every replay
    // saved by earlier builds. This value is the pre-change stream UID — old saves
    // stay loadable; future edits to this class must keep the pin unless a format
    // break is deliberate (same remedy as Deck.java).
    private static final long serialVersionUID = -3197308599568155798L;

    protected Mana payment;
    protected Mana usedManaToPay;
    protected Mana cost;
    protected ManaOptions options;
    protected Filter sourceFilter;
    protected boolean phyrexian = false;

    public ManaCostImpl() {
        payment = new Mana();
        usedManaToPay = new Mana();
        options = new ManaOptions();
    }

    protected ManaCostImpl(final ManaCostImpl manaCost) {
        super(manaCost);
        this.payment = manaCost.payment.copy();
        this.usedManaToPay = manaCost.usedManaToPay.copy();
        this.cost = manaCost.cost.copy();
        this.options = manaCost.options.copy();
        if (manaCost.sourceFilter != null) {
            this.sourceFilter = manaCost.sourceFilter.copy();
        }
        this.phyrexian = manaCost.phyrexian;
    }

    @Override
    abstract public ManaCostImpl copy();

    @Override
    public Mana getPayment() {
        return payment;
    }

    @Override
    public Mana getUsedManaToPay() {
        return usedManaToPay;
    }

    @Override
    public Mana getMana() {
        return cost;
    }

    @Override
    public List<Mana> getManaOptions() {
        List<Mana> manaList = new ArrayList<>();
        manaList.add(cost);
        return manaList;
    }

    @Override
    public final ManaOptions getOptions() {
        return getOptions(true);
    }

    @Override
    public ManaOptions getOptions(boolean canPayLifeCost) {
        if (!canPayLifeCost && this.isPhyrexian()) {
            ManaOptions optionsFiltered = new ManaOptions();
            optionsFiltered.add(this.cost);
            return optionsFiltered;
        } else {
            return options;
        }
    }

    @Override
    public void clearPaid() {
        super.clearPaid();
        payment.clear();
        usedManaToPay.clear();
    }

    @Override
    public Filter getSourceFilter() {
        return this.sourceFilter;
    }

    /*
     * Restrict the allowed mana sources to pay the cost
     *
     * e.g. Spend only mana produced by basic lands to cast Imperiosaur.
     * uses:
     *     private static final FilterLandPermanent filter = new FilterLandPermanent();
     *       static { filter.add(new SupertypePredicate("Basic")); }
     *
     * It will be cecked in ManaPool.pay method
     *
     */
    @Override
    public void setSourceFilter(Filter filter) {
        this.sourceFilter = filter;
    }

    protected boolean assignColored(Ability ability, Game game, ManaPool pool, ColoredManaSymbol mana, Cost costToPay) {
        // first check special mana
        switch (mana) {
            case W:
                if (pool.pay(ManaType.WHITE, ability, sourceFilter, game, costToPay, usedManaToPay)) {
                    this.payment.increaseWhite();
                    return true;
                }
                break;
            case U:
                if (pool.pay(ManaType.BLUE, ability, sourceFilter, game, costToPay, usedManaToPay)) {
                    this.payment.increaseBlue();
                    return true;
                }
                break;
            case B:
                if (pool.pay(ManaType.BLACK, ability, sourceFilter, game, costToPay, usedManaToPay)) {
                    this.payment.increaseBlack();
                    return true;
                }
                break;
            case R:
                if (pool.pay(ManaType.RED, ability, sourceFilter, game, costToPay, usedManaToPay)) {
                    this.payment.increaseRed();
                    return true;
                }
                break;
            case G:
                if (pool.pay(ManaType.GREEN, ability, sourceFilter, game, costToPay, usedManaToPay)) {
                    this.payment.increaseGreen();
                    return true;
                }
                break;
        }
        return false;
    }

    protected boolean assignColorless(Ability ability, Game game, ManaPool pool, int mana, Cost costToPay) {
        int conditionalCount = pool.getConditionalCount(ability, game, null, costToPay);
        if (mana > payment.count() && (pool.count() > 0 || conditionalCount > 0)
                && pool.pay(ManaType.COLORLESS, ability, sourceFilter, game, costToPay, usedManaToPay)) {
            this.payment.increaseColorless();
            return true;
        }

        return false;
    }

    protected boolean assignGeneric(Ability ability, Game game, ManaPool pool, int mana, FilterMana filterMana, Cost costToPay) {
        int conditionalCount = pool.getConditionalCount(ability, game, filterMana, costToPay);
        while (mana > payment.count() && (pool.count() > 0 || conditionalCount > 0)) {
            // try to use different mana to pay (conditional mana will used in pool.pay)
            // filterMana can be null, uses for spells like "spend only black mana on X"

            // {C}
            if ((filterMana == null || filterMana.isColorless()) && pool.pay(
                    ManaType.COLORLESS, ability, sourceFilter, game, costToPay, usedManaToPay
            )) {
                this.payment.increaseColorless();
                continue;
            }

            // {B}
            if ((filterMana == null || filterMana.isBlack()) && pool.pay(
                    ManaType.BLACK, ability, sourceFilter, game, costToPay, usedManaToPay
            )) {
                this.payment.increaseBlack();
                continue;
            }

            // {U}
            if ((filterMana == null || filterMana.isBlue()) && pool.pay(
                    ManaType.BLUE, ability, sourceFilter, game, costToPay, usedManaToPay
            )) {
                this.payment.increaseBlue();
                continue;
            }

            // {W}
            if ((filterMana == null || filterMana.isWhite()) && pool.pay(
                    ManaType.WHITE, ability, sourceFilter, game, costToPay, usedManaToPay
            )) {
                this.payment.increaseWhite();
                continue;
            }

            // {G}
            if ((filterMana == null || filterMana.isGreen()) && pool.pay(
                    ManaType.GREEN, ability, sourceFilter, game, costToPay, usedManaToPay
            )) {
                this.payment.increaseGreen();
                continue;
            }

            // {R}
            if ((filterMana == null || filterMana.isRed()) && pool.pay(
                    ManaType.RED, ability, sourceFilter, game, costToPay, usedManaToPay
            )) {
                this.payment.increaseRed();
                continue;
            }

            // nothing to pay
            break;
        }
        return mana > payment.count();
    }

    protected boolean isColoredPaid(ColoredManaSymbol mana) {
        switch (mana) {
            case B:
                return this.payment.getBlack() > 0;
            case U:
                return this.payment.getBlue() > 0;
            case W:
                return this.payment.getWhite() > 0;
            case G:
                return this.payment.getGreen() > 0;
            case R:
                return this.payment.getRed() > 0;
        }
        return false;
    }

    protected boolean isColorlessPaid(int mana) {
        return this.payment.count() >= mana;
    }

    /**
     * Guards against re-entrant payability checks. The payability model itself is built by
     * {@link Player#getManaAvailable}, which evaluates mana abilities' activation legality;
     * a mana ability whose cost list wraps a mana cost would recurse into itself forever.
     * While a check is in progress, nested checks keep the historical permissive answer —
     * they only ever happen inside the engine's own model calculation, never as the verdict
     * the player or the UI sees.
     */
    private static final ThreadLocal<Boolean> PAYABILITY_CHECK_IN_PROGRESS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Override
    public boolean canPay(Ability ability, Ability source, UUID controllerId, Game game) {
        ManaCosts<ManaCost> costsToCheck = new ManaCostsImpl<>();
        costsToCheck.add(this.copy());
        return canPayManaCost(ability, source, controllerId, game, costsToCheck);
    }

    /**
     * Real payability answer for a mana cost: the cost — with the engine's 601.2f cost
     * modifications applied, on copies only — is checked against the mana the player can
     * actually produce right now (mana pool plus available mana abilities), the same
     * accounting {@code PlayerImpl#canPayMinimumManaCost} and the alternative-cost
     * playability gates use.
     *
     * @param costsToCheck the mana cost to check (already copied; must not be live state)
     * @return false when payability cannot be established (no game/player data, unpayable
     * cost); never assumes true without a payable mana combination
     */
    static boolean canPayManaCost(Ability ability, Ability source, UUID controllerId, Game game, ManaCosts<ManaCost> costsToCheck) {
        if (game == null || game.getPlayer(controllerId) == null) {
            return false; // fail-fast: without a game/player there is no payability model to answer with
        }
        if (PAYABILITY_CHECK_IN_PROGRESS.get()) {
            return true; // see PAYABILITY_CHECK_IN_PROGRESS
        }
        PAYABILITY_CHECK_IN_PROGRESS.set(Boolean.TRUE);
        try {
            // the ability being checked is the one that will pay the cost (a spell ability for
            // alternative-cost checks); source only serves as fallback
            Ability checkAbility = ability != null ? ability : source;
            // never open dialogs or touch real state from a payability question
            Game checkGame = game.inCheckPlayableState() ? game : game.createSimulationForPlayableCalc();
            Player checkPlayer = checkGame.getPlayer(controllerId);
            if (checkPlayer == null) {
                return false;
            }
            ManaCosts<ManaCost> modifiedCosts = applyCostModificationsToCheck(checkAbility, checkGame, costsToCheck);
            return canPayWithAvailableMana(checkAbility, checkGame, checkPlayer, modifiedCosts);
        } finally {
            PAYABILITY_CHECK_IN_PROGRESS.set(Boolean.FALSE);
        }
    }

    /**
     * Applies the ability's cost modifications to the checked cost the same way the engine
     * does before real payments ({@code AbilityImpl.activate} 601.2f /
     * {@code PlayerImpl#canPlayCardByAlternateCost}): the pay-cost slot is replaced by the
     * checked cost, X costs are prepared and all cost modification effects run. Everything
     * happens on copies, so reduction effects (Warbringer, Ruby Medallion...) become
     * visible to the payability answer.
     */
    private static ManaCosts<ManaCost> applyCostModificationsToCheck(Ability ability, Game game, ManaCosts<ManaCost> costsToCheck) {
        if (ability == null) {
            return costsToCheck;
        }
        Ability checkAbility = ability.copy();
        checkAbility.clearManaCostsToPay();
        checkAbility.addManaCostsToPay(costsToCheck);
        checkAbility.adjustX(game);
        game.getContinuousEffects().costModification(checkAbility, game);
        return checkAbility.getManaCostsToPay();
    }

    /**
     * Checks the payment options of the given mana cost against the mana the player can
     * actually produce (mirrors {@code PlayerImpl#canPayMinimumManaCost}, including the
     * "spend mana as though it were mana of another color" allowance).
     */
    private static boolean canPayWithAvailableMana(Ability ability, Game game, Player player, ManaCosts<ManaCost> costsToCheck) {
        if (costsToCheck.isEmpty()) {
            return true; // fully reduced cost
        }
        ManaOptions optionsToCheck = costsToCheck.getOptions(ability == null || player.canPayLifeCost(ability));
        if (optionsToCheck.isEmpty()) {
            return true; // no mana symbols left to check (see ManaCost#getOptions)
        }
        ManaOptions availableMana = player.getManaAvailable(game);
        Set<ApprovingObject> approvingObjects = ability == null
                ? Collections.emptySet()
                : game.getContinuousEffects().asThough(
                        ability.getSourceId(),
                        AsThoughEffectType.SPEND_OTHER_MANA,
                        ability,
                        ability.getControllerId(),
                        game);
        for (Mana manaNeed : optionsToCheck) {
            if (manaNeed.count() == 0) {
                // 117.5 - a {0} cost is paid by the acknowledgment alone. Exception: for a
                // phyrexian cost the {0} option stands for "pay 2 life instead"
                // (see ManaCostImpl#setPhyrexian) and needs a life total that can pay it.
                if (player.getLife() < 2 && costsToCheck.stream().anyMatch(ManaCost::isPhyrexian)) {
                    continue;
                }
                return true;
            }
            for (Mana manaAvail : availableMana) {
                if (manaAvail instanceof ConditionalMana
                        && ability != null
                        && !((ConditionalMana) manaAvail).apply(ability, game, player.getId(), costsToCheck)) {
                    continue; // conditional mana whose condition does not fit this cost can't pay it
                }
                if (!approvingObjects.isEmpty() && manaNeed.count() <= manaAvail.count()) {
                    // a "spend mana as though it were mana of another color" effect may cover
                    // the remaining colors (mirrors PlayerImpl#canPayMinimumManaCost)
                    return true;
                }
                if (manaNeed.enough(manaAvail)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean pay(Ability ability, Game game, Ability source, UUID controllerId, boolean noMana, Cost costToPay) {
        if (noMana) {
            setPaid();
            return true;
        }
        Player player = game.getPlayer(controllerId);
        if (player == null) {
            return false;
        }

        // no needs to call
        //AbilityImpl.handlePhyrexianLikeEffects(game, source, ability, this);

        if (!player.getManaPool().isForcedToPay()) {
            assignPayment(game, ability, player.getManaPool(), costToPay != null ? costToPay : this);
        }
        game.getState().getSpecialActions().removeManaActions();
        while (player.canRespond() && !isPaid()) {
            ManaCost unpaid = this.getUnpaid();
            String promptText = ManaUtil.addSpecialManaPayAbilities(ability, game, unpaid);
            if (player.playMana(ability, unpaid, promptText, game)) {
                assignPayment(game, ability, player.getManaPool(), costToPay != null ? costToPay : this);
            } else {
                return false;
            }
            game.getState().getSpecialActions().removeManaActions();
        }
        return isPaid();
    }

    @Override
    public void setPaid() {
        this.paid = true;
    }

    @Override
    public void setPayment(Mana mana) {
        this.payment.add(mana);
    }

    protected void addColoredOption(ColoredManaSymbol symbol) {
        switch (symbol) {
            case B:
                this.options.add(Mana.BlackMana(1));
                return;
            case U:
                this.options.add(Mana.BlueMana(1));
                return;
            case W:
                this.options.add(Mana.WhiteMana(1));
                return;
            case R:
                this.options.add(Mana.RedMana(1));
                return;
            case G:
                this.options.add(Mana.GreenMana(1));
                return;
            default:
                this.options.add(Mana.ColorlessMana(1));
        }
    }

    @Override
    public String toString() {
        return getText();
    }

    @Override
    public boolean isPhyrexian() {
        return phyrexian;
    }

    @Override
    public void setPhyrexian(boolean phyrexian) {
        if (phyrexian) {
            this.options.add(Mana.GenericMana(0));
        }
        this.phyrexian = phyrexian;
    }
}
