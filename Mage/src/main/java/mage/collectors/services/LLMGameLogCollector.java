package mage.collectors.services;

import mage.game.Game;
import mage.game.GameState;

/**
 * Collects game log messages into GameState for LLM player context.
 * <p>
 * Upstream's informPlayers() calls DataCollectorServices.onGameLog() for each game event.
 * This collector forwards those events into GameState's in-memory gameLog list, which the
 * LLM player reads to build its prompt context. The gameLogEnabled flag on GameState gates
 * accumulation — it's a no-op for non-LLM games (flag defaults to false, only enabled by
 * LLMPlayer.init).
 * <p>
 * This replaces a direct state.addGameLog() call in GameImpl.informPlayers(), keeping that
 * hot method unmodified and aligned with upstream.
 *
 * @author LLM integration
 */
public class LLMGameLogCollector extends EmptyDataCollector {

    public static final String SERVICE_CODE = "llmGameLog";

    @Override
    public String getServiceCode() {
        return SERVICE_CODE;
    }

    @Override
    public String getInitInfo() {
        return "LLM game log collector";
    }

    @Override
    public void onGameLog(Game game, String message) {
        GameState state = game.getState();
        // addGameLog checks gameLogEnabled internally — no-op for non-LLM games
        state.addGameLog("[" + state.getTurnNum() + ":" + state.getTurnStepType() + "] " + message);
    }
}