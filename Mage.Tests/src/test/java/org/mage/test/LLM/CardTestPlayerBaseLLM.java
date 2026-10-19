package org.mage.test.LLM;

import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameException;
import mage.game.TwoPlayerDuel;
import mage.game.mulligan.MulliganType;
import org.mage.test.player.TestLLMPlayer;
import org.mage.test.player.TestPlayer;
import org.mage.test.serverside.base.impl.CardTestPlayerAPIImpl;

import java.io.FileNotFoundException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Base class for LLM player tests.
 * PlayerA is an LLM-driven player, PlayerB is a standard test player (passive).
 * <p>
 * Override getFullSimulatedPlayers() to control which players use the LLM.
 *
 * @author nox
 */
public abstract class CardTestPlayerBaseLLM extends CardTestPlayerAPIImpl {

    /**
     * Return a set of players that should be LLM-driven.
     * Default: only PlayerA.
     */
    public List<String> getFullSimulatedPlayers() {
        return Arrays.asList("PlayerA");
    }

    @Override
    protected Game createNewGameAndPlayers() throws GameException, FileNotFoundException {
        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);

        playerA = createPlayer(game, "PlayerA");
        playerB = createPlayer(game, "PlayerB");
        return game;
    }

    @Override
    protected TestPlayer createPlayer(String name, RangeOfInfluence rangeOfInfluence) {
        if (getFullSimulatedPlayers().contains(name)) {
            TestLLMPlayer llmPlayer = new TestLLMPlayer(name, RangeOfInfluence.ONE, 0);

            // Config source priority: system properties (set by the test runner) →
            // the LLMConfig defaults (localhost Ollama). No secrets in source:
            // -Dxmage.llm.key is injected at test-run time only, never committed.
            String endpoint = System.getProperty("xmage.llm.endpoint");
            String model = System.getProperty("xmage.llm.model");
            String apiKey = System.getProperty("xmage.llm.key");

            llmPlayer.getLLMConfig()
                    .apiEndpoint(endpoint != null ? endpoint : "http://localhost:11434/v1/chat/completions")
                    .model(model != null ? model : "deepseek-v4-flash:cloud")  // flash for tests, NOT pro — pro is
                                                       // expensive and reserved for production;
                                                       // flash is the strictly-better test model

                    .temperature(0.0)  // deterministic
                    .maxTokens(65536)  // production value (start-server.sh) — thinking models burn
                                       // 175+ tokens of reasoning before the answer; a 200-token
                                       // cap guarantees empty answers at every effort level
                    .timeoutSeconds(120)
                    // No deck assessment: the assessment runs on the fixture's default
                    // 71-Mountain deck and concludes "joke deck, do nothing" — which the
                    // model then obeys instead of casting the cards addCard() injected
                    // (observed: Silvercoat Lion in hand, model passed; stochastic because
                    // the assessment itself is a live-model call). Tests verify decision
                    // plumbing, not assessment quality.
                    .deckAssessmentEnabled(false)
                    .verboseLogging(true);
            if (apiKey != null && !apiKey.isEmpty()) {
                llmPlayer.getLLMConfig().apiKey(apiKey);
            }
            // Reasoning effort: test-driven override. The default "max" is only valid
            // for models whose effort ladder includes it; e.g. deepseek-v4.1-flash
            // accepts low/high only and rejects "max" with a 400 on every call.
            String effort = System.getProperty("xmage.llm.reasoningeffort");
            if (effort != null && !effort.isEmpty()) {
                llmPlayer.getLLMConfig().reasoningEffort(effort);
            }
            llmPlayer.reconnect();

            TestPlayer testPlayer = new TestPlayer(llmPlayer);
            testPlayer.setAIPlayer(true);
            return testPlayer;
        }
        return super.createPlayer(name, rangeOfInfluence);
    }

    /**
     * Override to add more cards to opponent's hand (e.g. for discard triggers).
     */
    public void useStrictDefault() {
        // Override in subclass if needed
    }
}
