package org.mage.test.player;

import mage.abilities.Ability;
import mage.cards.Cards;
import mage.choices.Choice;
import mage.constants.Outcome;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.llm.LLMPlayer;
import mage.target.Target;
import mage.target.TargetCard;

/**
 * LLM: helper class for tests
 * <p>
 * Bridges {@link LLMPlayer} into the test framework. Delegates to LLMPlayer
 * when the test framework wants AI decisions, falls back to TestPlayer when
 * the test framework wants scripted decisions.
 * <p>
 * Follows the same pattern as {@link TestComputerPlayer7}.
 *
 * @author nox
 */
public class TestLLMPlayer extends LLMPlayer {

    private TestPlayer testPlayerLink;

    public TestLLMPlayer(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
    }

    /**
     * Tests run without network access — this hook disables BOTH init-time
     * network calls in LLMPlayer.init(): the startup thinking probe AND the
     * context-window detection probe (which used to dial LLMConfig's default
     * endpoint and threw "Connection refused" on hosts without a local Ollama,
     * making the LLM suite silently host-dependent). The test fixtures apply
     * their endpoint/model AFTER construction, so detection there was also
     * detecting the wrong model.
     */
    @Override
    protected boolean isTestHarness() { return true; }

    public void setTestPlayerLink(TestPlayer testPlayerLink) {
        this.testPlayerLink = testPlayerLink;
    }

    /**
     * Test hook: replace the live LLM client with a stub (effort-ladder tests).
     */
    public void injectTestClient(mage.player.llm.LLMClient stub) {
        this.client = stub;
    }

    /**
     * Test hook: the injected (or live) client, for asserting on the seeded
     * conversation system prompt and other client-side state.
     */
    public mage.player.llm.LLMClient clientForTest() {
        return this.client;
    }

    /**
     * Test hook: the cached pre-game deck-assessment prompt text (reflection —
     * the field is private). Null until useDeck() builds it; only cleared when
     * ensureGamePlan() fires the assessment call.
     */
    public String cachedAssessmentPromptForTest() {
        try {
            java.lang.reflect.Field f =
                    mage.player.llm.LLMPlayer.class.getDeclaredField("cachedAssessmentPrompt");
            f.setAccessible(true);
            return (String) f.get(this);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("cannot read cachedAssessmentPrompt", e);
        }
    }

    /**
     * Test hook: expose the protected retry ladder for unit tests.
     */
    public mage.player.llm.LLMResponse chatAndRecordWithRetryTest(String prompt, String compactSummary,
                                                                   String promptType, Integer turn, String phaseStep) {
        return chatAndRecordWithRetry(prompt, compactSummary, promptType, turn, phaseStep);
    }

    /**
     * Deterministic mulligan for tests: always KEEP, no LLM call.
     *
     * The fixture deck (RB Aggro.dck) is 71 Mountains — a dummy deck for card
     * tests. The real chooseMulligan shows the model the all-Mountain decklist
     * next to a hand of injected test cards, and a live model reasonably
     * concludes "uncastable in this deck → mulligan" and throws the test's
     * cards away (observed: Silvercoat Lion mulliganed in
     * LLMBasicTest.testCastCreatureWhenManaAvailable). Tests that WANT mulligan
     * decisions can still override this back to super.chooseMulligan(game).
     */
    @Override
    public boolean chooseMulligan(Game game) {
        return false;  // false = keep
    }

    @Override
    public boolean choose(Outcome outcome, Target target, Ability source, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.choose(outcome, target, source, game);
        } else {
            return testPlayerLink.choose(outcome, target, source, game);
        }
    }

    @Override
    public boolean choose(Outcome outcome, Choice choice, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.choose(outcome, choice, game);
        } else {
            return testPlayerLink.choose(outcome, choice, game);
        }
    }

    @Override
    public boolean choose(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.choose(outcome, cards, target, source, game);
        } else {
            return testPlayerLink.choose(outcome, cards, target, source, game);
        }
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Target target, Ability source, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.chooseTarget(outcome, target, source, game);
        } else {
            return testPlayerLink.chooseTarget(outcome, target, source, game);
        }
    }

    @Override
    public boolean chooseTarget(Outcome outcome, Cards cards, TargetCard target, Ability source, Game game) {
        if (testPlayerLink.canChooseByComputer()) {
            return super.chooseTarget(outcome, cards, target, source, game);
        } else {
            return testPlayerLink.chooseTarget(outcome, cards, target, source, game);
        }
    }

    @Override
    public boolean flipCoinResult(Game game) {
        // same random results must be same in any mode
        return testPlayerLink.flipCoinResult(game);
    }

    @Override
    public boolean isTestMode() {
        return true;
    }
}
