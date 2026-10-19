package mage.server;

import mage.players.Player;
import mage.players.LLMPlayerConfig;
import org.apache.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Helper for interacting with LLMPlayer instances across the plugin classloader boundary.
 *
 * LLMPlayer is loaded by {@link mage.server.util.PluginClassLoader}, which creates a
 * separate {@code Class<mage.player.llm.LLMPlayer>} object from the one resolved by
 * the system classloader. This means {@code instanceof LLMPlayer} always returns false
 * and direct casts throw {@code ClassCastException}.
 *
 * This class provides static helpers that use class-name string comparison + reflection
 * to invoke LLMPlayer methods safely. This mirrors the pattern already used in
 * {@link mage.server.game.GameSessionWatcher#processLLMDebugData}.
 *
 * Fail Fast: reflection failures are rethrown as RuntimeException, never swallowed.
 */
public final class LLMPlayerHelper {

    private static final Logger logger = Logger.getLogger(LLMPlayerHelper.class);

    private static final String LLM_PLAYER_CLASS_NAME = "mage.player.llm.LLMPlayer";

    private LLMPlayerHelper() {}

    /**
     * Extract the root-cause message from a reflection failure chain.
     * Method.invoke wraps target exceptions in InvocationTargetException whose
     * getMessage() is null — without unwrapping, RPC error flattening
     * (MageServerImpl.handleException → message-only MageException) destroys
     * the diagnostic entirely and the client sees only "failed via reflection".
     */
    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /** Check if a Player is an LLMPlayer (classloader-safe). */
    public static boolean isLLMPlayer(Player p) {
        return p != null && p.getClass().getName().equals(LLM_PLAYER_CLASS_NAME);
    }

    /**
     * Call {@code applyOverrides(LLMPlayerConfig)} on the player via reflection.
     * LLMPlayerConfig is in Mage.Common (system classloader), so it's safe to pass.
     *
     * @return true if overrides were applied, false if the player is not an LLMPlayer
     */
    public static boolean applyOverrides(Player p, LLMPlayerConfig overrides) {
        if (!isLLMPlayer(p)) return false;
        try {
            Method m = p.getClass().getMethod("applyOverrides", LLMPlayerConfig.class);
            m.invoke(p, overrides);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("LLMPlayer.applyOverrides() failed via reflection: "
                    + rootMessage(e), e);
        }
    }

    /**
     * Create a fresh copy of an LLMPlayer via the copy constructor.
     * Used for tournament sub-tables to get clean conversation state.
     *
     * @return the copied Player, or the original player if not an LLMPlayer
     */
    public static Player copyIfNeeded(Player p) {
        if (!isLLMPlayer(p)) return p;
        try {
            Class<?> llmPlayerClass = p.getClass();
            Constructor<?> copyCtor = llmPlayerClass.getConstructor(llmPlayerClass);
            return (Player) copyCtor.newInstance(p);
        } catch (Exception e) {
            throw new RuntimeException("LLMPlayer copy constructor failed via reflection", e);
        }
    }

    /**
     * Call {@code reconnect()} on the player via reflection.
     */
    public static void reconnect(Player p) {
        if (!isLLMPlayer(p)) return;
        try {
            Method m = p.getClass().getMethod("reconnect");
            m.invoke(p);
        } catch (Exception e) {
            throw new RuntimeException("LLMPlayer.reconnect() failed via reflection", e);
        }
    }

    /**
     * Get the model name from the player's LLMConfig via reflection.
     *
     * @return the model name, or null if not an LLMPlayer
     */
    public static String getModelName(Player p) {
        if (!isLLMPlayer(p)) return null;
        try {
            Method getLLMConfig = p.getClass().getMethod("getLLMConfig");
            Object config = getLLMConfig.invoke(p);
            Method getModel = config.getClass().getMethod("getModel");
            return (String) getModel.invoke(config);
        } catch (Exception e) {
            throw new RuntimeException("LLMPlayer.getModelName() failed via reflection", e);
        }
    }

    /**
     * Get the bot name from the player's LLMConfig via reflection.
     *
     * @return the configured bot name, or null if not set or not an LLMPlayer
     */
    public static String getBotName(Player p) {
        if (!isLLMPlayer(p)) return null;
        try {
            Method getLLMConfig = p.getClass().getMethod("getLLMConfig");
            Object config = getLLMConfig.invoke(p);
            Method getBotName = config.getClass().getMethod("getBotName");
            return (String) getBotName.invoke(config);
        } catch (Exception e) {
            throw new RuntimeException("LLMPlayer.getBotName() failed via reflection", e);
        }
    }

    /**
     * Set a config field on the player's LLMConfig via reflection.
     * Used by BotGameHandler to apply server-side overrides (llm2 endpoint, model, etc.).
     *
     * COUPLING NOTE: This calls LLMConfig *builder-style* setters by name — e.g. "model", not
     * "setModel". LLMConfig uses Lombok @Builder-style setters (model(String)), not JavaBeans
     * convention (setModel(String)). If any LLMConfig setter is renamed, this reflection lookup
     * (config.getClass().getMethod(configField, valueClass)) will fail silently at runtime with
     * NoSuchMethodException wrapped as RuntimeException. There is no compile-time safety net.
     * If you rename a setter on LLMConfig, audit all callers of setConfigField accordingly.
     *
     * @param configField the builder-style setter method name on LLMConfig (e.g., "reasoningEffort")
     * @param value       the value to set
     */
    public static void setConfigField(Player p, String configField, Object value) {
        if (!isLLMPlayer(p)) return;
        try {
            Method getLLMConfig = p.getClass().getMethod("getLLMConfig");
            Object config = getLLMConfig.invoke(p);
            Class<?> valueClass = value.getClass();
            // Handle primitive wrappers
            if (value instanceof Boolean) valueClass = boolean.class;
            else if (value instanceof Integer) valueClass = int.class;
            else if (value instanceof Double) valueClass = double.class;
            Method setter = config.getClass().getMethod(configField, valueClass);
            setter.invoke(config, value);
        } catch (Exception e) {
            throw new RuntimeException("LLMPlayer.setConfigField(" + configField + ") failed via reflection", e);
        }
    }

    /**
     * Build LLMConversationView list for replay from the player's replay data.
     * Uses reflection to access LLMExchangeRecord fields across the classloader boundary.
     *
     * @param stateIdx the GameState index to look up LLM exchanges for
     * @return list of LLMConversationView, or null if no LLM data exists for this state
     */
    @SuppressWarnings("unchecked")
    public static java.util.List<mage.view.LLMConversationView> buildReplayViews(Player p, int stateIdx) {
        if (!isLLMPlayer(p)) return null;
        try {
            Method getReplayLLMData = p.getClass().getMethod("getReplayLLMData");
            Map<Integer, ?> replayData = (Map<Integer, ?>) getReplayLLMData.invoke(p);
            if (replayData == null) return null;
            Object recordsObj = replayData.get(stateIdx);
            if (recordsObj == null) return null;
            java.util.List<?> records = (java.util.List<?>) recordsObj;
            if (records.isEmpty()) return null;

            java.util.List<mage.view.LLMConversationView> result = new java.util.ArrayList<>();

            // Create one view per state index (not N views — that was an N² duplication bug)
            Object firstRecord = records.get(0);
            Class<?> firstClass = firstRecord.getClass();
            String modelName = (String) firstClass.getField("modelName").get(firstRecord);
            String playerName = (String) firstClass.getField("playerName").get(firstRecord);

            mage.view.LLMConversationView view = new mage.view.LLMConversationView(
                    playerName, modelName, records.size(), 0, 0);

            // Between-games sections (2026-09-30): the live tab shows the deck
            // assessment and the sideboard match review as labeled sections
            // (they ride the LLMConversationView as gamePlan/matchReview). A
            // deserialized replay player carries the same serialized fields —
            // attach them here so the replay viewer shows the identical
            // sections. This replaces the async match_review preamble RECORD
            // (which could never reach its own save: endGameWithResult saves
            // the .game before the post-game worker runs — see
            // LLMPlayer.recordReplayPreamble's threading note). matchReview in
            // game N's save is game N-1's review — the same thing the live
            // tab shows mid-sideboarding.
            // NOT attached: postGameReflection — every save path writes the
            // .game at game end, BEFORE the async reflection worker sets the
            // field, so it is always null on disk. The postgame *.txt files
            // are the record for reflection text; a replay section for it
            // would always be empty.
            try {
                Method getGamePlan = p.getClass().getMethod("getGamePlan");
                view.gamePlan = (String) getGamePlan.invoke(p);
                Method getMatchReview = p.getClass().getMethod("getMatchReview");
                view.matchReview = (String) getMatchReview.invoke(p);
            } catch (NoSuchMethodException oldPlugin) {
                // A plugin older than these getters (or a replay whose classes
                // predate them): no sections, exactly as before the change.
            }

            // Replay-viewer pre-game gap (2026-09-30): the live LLM tab leads
            // with the system prompt; the replay record store never carried it
            // (LLMClient stays behind the plugin classloader in a deserialized
            // replay). LLMPlayer snapshots it into the serializable
            // `replaySystemPrompt` field at useDeck()/ensureGamePlan() time —
            // attach it here as the leading system message, ahead of the
            // pre-game exchange records below.
            try {
                Method getReplaySystemPrompt = p.getClass().getMethod("getReplaySystemPrompt");
                String sysPrompt = (String) getReplaySystemPrompt.invoke(p);
                if (sysPrompt != null && !sysPrompt.isEmpty()) {
                    view.messages.add(new mage.view.LLMConversationView.LLMMessageView(
                            "system", sysPrompt));
                }
            } catch (NoSuchMethodException oldReplay) {
                // A replay written before the field existed deserializes
                // against the CURRENT plugin class, so the method is always
                // present at runtime; this guard is purely defensive.
            }

            for (Object r : records) {
                Class<?> rc = r.getClass();
                String promptType = (String) rc.getField("promptType").get(r);
                int turnNum = rc.getField("turnNum").getInt(r);
                String phaseStep = (String) rc.getField("phaseStep").get(r);
                String compactSummary = (String) rc.getField("compactSummary").get(r);
                String thinking = (String) rc.getField("thinking").get(r);
                String answer = (String) rc.getField("answer").get(r);
                // New (2026-09-30) fields — absent on pre-change replays, where
                // the reads below throw NoSuchFieldException; treat that as
                // "not a pre-game record" and keep the legacy rendering.
                String fullPrompt = null;
                boolean preGame = false;
                try {
                    fullPrompt = (String) rc.getField("fullPrompt").get(r);
                    preGame = rc.getField("preGame").getBoolean(r);
                } catch (NoSuchFieldException oldRecord) {
                    // legacy record shape — fall through to the legacy rendering
                }

                if (preGame && fullPrompt != null && !fullPrompt.isEmpty()) {
                    // Pre/post-game exchange: the FULL prompt is the content
                    // (there is no game frame to summarize against).
                    mage.view.LLMConversationView.LLMMessageView userMsg =
                            new mage.view.LLMConversationView.LLMMessageView("user", fullPrompt);
                    userMsg.promptType = promptType;
                    view.messages.add(userMsg);
                } else {
                    view.messages.add(new mage.view.LLMConversationView.LLMMessageView(
                            "user", "[" + promptType + " | Turn " + turnNum
                                    + " | " + phaseStep + "]\n" + compactSummary));
                }
                if (thinking != null && !thinking.isEmpty()) {
                    view.messages.add(new mage.view.LLMConversationView.LLMMessageView(
                            "assistant_thinking", thinking));
                }
                view.messages.add(new mage.view.LLMConversationView.LLMMessageView(
                        "assistant", answer));
            }
            result.add(view);
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            throw new RuntimeException("LLMPlayer replay data extraction failed via reflection", e);
        }
    }

    /**
     * Get the LLMConfig object from the player via reflection.
     * The returned Object is from the plugin classloader and cannot be cast to
     * the compile-time LLMConfig type. Use {@link #setConfigField} for mutations
     * or other reflection helpers for reads.
     */
    public static Object getLLMConfig(Player p) {
        if (!isLLMPlayer(p)) return null;
        try {
            Method getLLMConfig = p.getClass().getMethod("getLLMConfig");
            return getLLMConfig.invoke(p);
        } catch (Exception e) {
            throw new RuntimeException("LLMPlayer.getLLMConfig() failed via reflection", e);
        }
    }

    /**
     * Get the replay LLM data map from the player via reflection.
     * Used by GameReplay for replay-LLM correlation.
     *
     * @return the Map<Integer, List<LLMExchangeRecord>>, or null if not an LLMPlayer
     */
    @SuppressWarnings("unchecked")
    public static Map<Integer, ?> getReplayLLMData(Player p) {
        if (!isLLMPlayer(p)) return null;
        try {
            Method getReplayLLMData = p.getClass().getMethod("getReplayLLMData");
            return (Map<Integer, ?>) getReplayLLMData.invoke(p);
        } catch (Exception e) {
            throw new RuntimeException("LLMPlayer.getReplayLLMData() failed via reflection", e);
        }
    }
}