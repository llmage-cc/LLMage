package mage.server;

import mage.cards.decks.DeckCardInfo;
import mage.cards.decks.DeckCardLists;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardCriteria;
import mage.constants.CardType;
import mage.constants.TableState;
import mage.game.match.MatchOptions;
import mage.game.Seat;
import mage.players.Player;
import mage.players.PlayerType;
import mage.player.llm.LLMConfig;
import mage.player.llm.LLMPlayer;

import mage.server.game.GamesRoom;
import mage.server.managers.ManagerFactory;
import mage.view.TableView;
import org.apache.log4j.Logger;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the {@code \botgame} chat command — creates LLM-vs-AI or LLM-vs-LLM
 * matches, autofills seats, generates random decks, and assigns unique bot names.
 * <p>
 * Extracted from {@link ChatManagerImpl} to keep the chat manager focused on
 * chat routing.
 */
public class BotGameHandler {

    private static final Logger logger = Logger.getLogger(BotGameHandler.class);

    private static final String[] BOT_NAMES = {
        "Alex", "Blake", "Casey", "Dana", "Ellis", "Finley", "Gray", "Harper", "Indigo", "Jay",
        "Kai", "Lane", "Morgan", "Nico", "Ollie", "Parker", "Quinn", "Reese", "Sage", "Tatum",
        "Uma", "Val", "Wren", "Xan", "Yuki", "Zion", "Akira", "Briar", "Cove", "Drew",
        "Echo", "Fox", "Gem", "Hollis", "Ira", "Joss", "Kit", "Leo", "Mica", "Nova",
        "Onyx", "Pax", "Remy", "Sky", "Teal", "Uli", "Vero", "Wyn", "Xen", "Yara",
        "Ash", "Billie", "Cruz", "Dion", "Ember", "Flynn", "Glenn", "Hero", "Iris", "Jean",
        "Koa", "Lux", "Moss", "Nyx", "Oren", "Peri", "Rue", "Shay", "True", "Uri",
        "Vale", "West", "Xi", "Yori", "Zade", "Arden", "Bloom", "Cedar", "Delta", "Ezra",
        "Fable", "Glynn", "Hayes", "Io", "Juno", "Kestrel", "Linden", "Marlowe", "Noor", "Oak",
        "Phoenix", "Quest", "Raven", "Soren", "Thorn", "Umber", "Vega", "Willow", "Xander", "Yarrow"
    };

    private final ManagerFactory managerFactory;
    private final Random rng = new Random();
    private final Set<String> usedNamesThisGame = ConcurrentHashMap.newKeySet();

    public BotGameHandler(ManagerFactory managerFactory) {
        this.managerFactory = managerFactory;
    }

    /**
     * Handle a {@code \botgame} or {@code \botgame ...} message.
     *
     * @param user    the user who sent the command
     * @param message the full message text (e.g. "\\botgame --llm2 Legacy")
     * @param chatId  the chat session ID
     */
    public void handle(User user, String message, UUID chatId) {
        ChatSession session = managerFactory.chatManager().getChatSessions().stream()
                .filter(cs -> cs.getChatId().equals(chatId))
                .findFirst().orElse(null);
        if (session == null) return;

        UUID roomId = session.getRoomId();
        if (roomId == null) {
            roomId = managerFactory.gamesRoomManager().getMainRoomId();
        }

        // Reset name pool for new game
        usedNamesThisGame.clear();

        // Parse arguments after "\botgame"
        String args = message.length() > 9 ? message.substring(9).trim() : "";
        String deckType = "Constructed - Freeform";
        String deckFile1 = null;
        String deckFile2 = null;
        boolean llmVsLlm = false;
        boolean bestOfThree = false;
        boolean p1VsLlm = false;

        if (!args.isEmpty()) {
            if (args.contains("--llm2")) {
                llmVsLlm = true;
                args = args.replace("--llm2", "").trim();
            }
            // Argentum-P1 seat: seat 0 = COMPUTER_P1, seat 1 = COMPUTER_LLM.
            if (args.contains("--p1")) {
                p1VsLlm = true;
                args = args.replace("--p1", "").trim();
            }
            if (args.contains("--bo3")) {
                bestOfThree = true;
                args = args.replace("--bo3", "").trim();
            }
            String[] parts = args.split("\\s+");

            // Fail Fast: an argument that LOOKS like a deck-file path but isn't
            // absolute used to fall through to the deck-*type* branch below and
            // silently substitute a generated random 40-card deck
            // — a whole verification cycle ran on the wrong
            // deck and nothing surfaced it). If it contains a path separator or
            // a deck-file extension, it was meant as a file — refuse, loudly.
            for (String part : parts) {
                if (part.isEmpty()) continue;
                boolean looksLikePath = part.contains("/") || part.contains("\\")
                        || part.endsWith(".txt") || part.endsWith(".dck") || part.endsWith(".dec");
                if (looksLikePath && !(part.startsWith("/") || part.startsWith("~"))) {
                    session.broadcastInfoToUser(user, "Error: deck path '" + part
                            + "' is relative — bot games require an absolute path (starting with '/' or '~').");
                    return;
                }
            }

            if (parts.length == 1 && (parts[0].startsWith("/") || parts[0].startsWith("~"))) {
                deckFile1 = parts[0];
            } else if (parts.length >= 2 && (parts[0].startsWith("/") || parts[0].startsWith("~"))) {
                deckFile1 = parts[0];
                deckFile2 = parts[1];
            } else if (parts.length >= 1 && !parts[0].isEmpty()) {
                deckType = String.join(" ", parts);
            }
        }

        try {
            MatchOptions options = new MatchOptions("Bot Game", "Two Player Duel", false);
            options.setDeckType(deckType);
            options.setLimited(false);
            options.setSpectatorsAllowed(true);
            options.setWinsNeeded(bestOfThree ? 2 : 1);

            if (p1VsLlm) {
                // Argentum-P1 policy net in seat 0, a normal COMPUTER_LLM seat in seat 1.
                // Seat order is the order added here (Table.createSeats -> seats[0], seats[1]).
                options.getPlayerTypes().add(PlayerType.COMPUTER_P1);
                options.getPlayerTypes().add(PlayerType.COMPUTER_LLM);
            } else if (llmVsLlm) {
                options.getPlayerTypes().add(PlayerType.COMPUTER_LLM);
                options.getPlayerTypes().add(PlayerType.COMPUTER_LLM);
            } else {
                options.getPlayerTypes().add(PlayerType.COMPUTER_LLM);
                options.getPlayerTypes().add(PlayerType.COMPUTER_MAD);
            }

            Optional<GamesRoom> roomOpt = managerFactory.gamesRoomManager().getRoom(roomId);
            if (!roomOpt.isPresent()) {
                session.broadcastInfoToUser(user, "Error: room not found.");
                return;
            }
            TableView tableView = roomOpt.get().createTable(user.getId(), options);

            Optional<TableController> ctrlOpt = managerFactory.tableManager().getController(tableView.getTableId());
            if (!ctrlOpt.isPresent()) {
                session.broadcastInfoToUser(user, "Error: table controller not found.");
                return;
            }
            TableController controller = ctrlOpt.get();

            // Fill seats
            Seat[] seats = controller.getTable().getSeats();
            for (int i = 0; i < seats.length; i++) {
                boolean isLlmPlayer = (seats[i].getPlayerType() == PlayerType.COMPUTER_LLM);
                boolean isSecondLlm = isLlmPlayer && i > 0;
                String deckPath = (i == 0) ? deckFile1 : deckFile2;

                // Signal LLMPlayer.init() to read xmage.llm2.* props at construction time.
                // The factory (createPlayer) can't pass a boolean through the plugin
                // classloader boundary, so we use a system property. init() reads
                // and clears it. This ensures fetchModelContextWindow() detects the
                // correct llm2 model, not llm1's.
                if (isSecondLlm) {
                    System.setProperty("xmage.llm.pending_second", "true");
                }

                DeckCardLists botDeck;
                if (deckPath != null) {
                    try {
                        botDeck = mage.cards.decks.importer.DeckImporter.importDeckFromFile(deckPath, new StringBuilder(), false);
                    } catch (Exception e) {
                        session.broadcastInfoToUser(user, "Error loading deck '" + deckPath + "': " + e.getMessage());
                        return;
                    }
                    // Fail Fast: an empty/near-empty deck starts a match that draws
                    // instantly and auto-restarts forever — burning real LLM API
                    // calls each cycle with no way to stop short of an admin kill
                    // DckDeckImporter silently
                    // skips any line without [SET:NUM], so a plain-name file gets
                    // here as an empty deck with no error raised. Refuse to start.
                    int totalCards = botDeck.getCards().size() + botDeck.getSideboard().size();
                    if (totalCards < 15) {
                        session.broadcastInfoToUser(user, "Error: deck '" + deckPath
                                + "' imported as only " + totalCards + " card(s) — refusing to start a degenerate match"
                                + " (plain-name .dck files import as 0 cards; use a .txt decklist or [SET:NUM] codes).");
                        logger.error("\\botgame aborted: deck '" + deckPath + "' has only " + totalCards + " cards");
                        return;
                    }
                } else if (seats[i].getPlayerType() == PlayerType.COMPUTER_P1) {
                    // Deterministic default for a P1 seat with no explicit deck:
                    // the pool's canonical default, NEVER an arbitrary random list
                    // (the argmax seat would be given an out-of-distribution deck
                    // it cannot read). See P1DeckPool.defaultDeckName().
                    botDeck = mage.cards.decks.P1DeckPool.instance().defaultDeck();
                    logger.info("\\botgame: P1 seat " + i + " has no explicit deck -> default '"
                            + mage.cards.decks.P1DeckPool.instance().defaultDeckName() + "' ("
                            + botDeck.getCards().size() + " cards)");
                } else {
                    botDeck = generateRandomDeck();
                }

                Optional<Player> bot = controller.createPlayer("tmp-bot", seats[i].getPlayerType(), 0);
                if (!bot.isPresent()) {
                    session.broadcastInfoToUser(user, "Error creating bot: seat " + i);
                    return;
                }
                Player botPlayer = bot.get();
                String botName;

                if (isLlmPlayer && LLMPlayerHelper.isLLMPlayer(botPlayer)) {
                    try {
                        Object llmConfig = LLMPlayerHelper.getLLMConfig(botPlayer);
                        applyLLMConfig(llmConfig, isSecondLlm);
                        LLMPlayerHelper.reconnect(botPlayer);

                        String modelShort = LLMPlayerHelper.getModelName(botPlayer).replaceAll("[:/].*", "");
                        // If a specific bot name is configured, use it (resolved against the registry)
                        String configBotName = LLMPlayerHelper.getBotName(botPlayer);
                        if (configBotName != null && !configBotName.isEmpty()) {
                            String resolved = mage.players.Personality.resolveBotName(configBotName);
                            if (resolved != null) {
                                botName = modelShort + "-" + resolved;
                            } else {
                                // Name not in registry — fall back to random
                                botName = modelShort + "-" + pickBotName();
                            }
                        } else {
                            botName = modelShort + "-" + pickBotName();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("LLMPlayer config/reconnect failed via reflection", e);
                    }
                } else if (seats[i].getPlayerType() == PlayerType.COMPUTER_P1) {
                    // Argentum-P1 seat: attach the out-of-process policy worker to this player id.
                    // The player class lives in its own plugin jar, so it is reached by reflection
                    // (same classloader-boundary reason the LLM branch uses helpers).
                    try {
                        Class<?> workerClass = Class.forName("mage.player.p1.P1Worker", true,
                                botPlayer.getClass().getClassLoader());
                        Object worker = workerClass.getConstructor(String.class).newInstance("seat" + i);
                        java.lang.reflect.Method start = workerClass.getMethod("start");
                        boolean started = (Boolean) start.invoke(worker);
                        if (!started) {
                            logger.error("\\botgame: P1 worker failed to start for seat " + i
                                    + " (check P1_PYTHON / P1_PKG_DIR / P1_CHECKPOINT env)");
                        }
                        java.lang.reflect.Method attach = botPlayer.getClass().getMethod(
                                "attachWorker", java.util.UUID.class, workerClass);
                        attach.invoke(botPlayer, botPlayer.getId(), worker);
                        logger.info("\\botgame: P1 worker attached to seat " + i
                                + " player " + botPlayer.getId() + " (started=" + started + ")");
                    } catch (Exception e) {
                        throw new RuntimeException("P1Player worker attach failed via reflection", e);
                    }
                    botName = deckPath != null
                            ? "P1-" + new File(deckPath).getName().replaceAll("\\.[^.]+$", "")
                            : "P1-Bot";
                } else if (deckPath != null) {
                    botName = new File(deckPath).getName().replaceAll("\\.[^.]+$", "");
                } else {
                    botName = "AI-Bot-" + pickBotName();
                }

                // Set bot name via reflection (Player.name field is in the hierarchy)
                setPlayerName(botPlayer, botName);

                mage.cards.decks.Deck deck = mage.cards.decks.Deck.load(botDeck, false, false);

                // P1 deck restriction (authoritative, botgame path). A P1 seat may
                // only run one of the 55 training lists; refuse loudly here rather
                // than add the player and let the seat face an unreadable deck.
                String p1Reason = P1DeckRestriction.rejectReason(seats[i], deck);
                if (p1Reason != null) {
                    logger.error(P1DeckRestriction.logLine("\\botgame", botName, controller.getTable().getId(), deck));
                    session.broadcastInfoToUser(user, "Error: P1 seat " + i + " deck refused — " + p1Reason);
                    return;
                }

                controller.getMatch().addPlayer(botPlayer, deck);
                controller.getTable().joinTable(botPlayer, seats[i]);
            }

            // Transition state from READY_TO_START to STARTING so startMatch() works
            controller.getTable().setState(TableState.STARTING);
            controller.startMatch();

            // Wait for the match to reach DUELING state before watching.
            // LLM deck assessment can take several minutes with large models, so poll up to 600 seconds.
            long deadline = System.currentTimeMillis() + 600_000;
            while (controller.getTable().getState() != TableState.DUELING
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
            }

            if (controller.getTable().getState() != TableState.DUELING) {
                session.broadcastInfoToUser(user, "Bot game timed out waiting to start.");
                return;
            }

            controller.watchTable(user.getId());

            StringBuilder info = new StringBuilder("Bot game started"
                    + (p1VsLlm ? " (P1 vs LLM)" : (llmVsLlm ? " (LLM vs LLM)" : "")));
            info.append(bestOfThree ? " | Best of 3" : " | Single game");
            info.append(" | Type: ").append(deckType);
            if (deckFile1 != null) info.append(" | Deck1: ").append(new File(deckFile1).getName());
            if (deckFile2 != null) info.append(" | Deck2: ").append(new File(deckFile2).getName());
            session.broadcastInfoToUser(user, info.toString());

        } catch (Exception e) {
            logger.error("\\botgame command failed: " + e.getMessage(), e);
            session.broadcastInfoToUser(user, "Error starting bot game: " + e.getMessage());
        }
    }

    /**
     * Apply LLM configuration from system properties and server config.xml.
     * Uses direct method calls — no reflection.
     */
    /**
     * Apply LLM configuration from system properties and server config.xml.
     * Works via reflection on the LLMConfig object from the plugin classloader.
     */
    private void applyLLMConfig(Object config, boolean isSecondLlm) {
        // Snapshot model/endpoint before overrides — used at the end to decide
        // whether fetchModelContextWindow() re-detection is needed.
        String modelBefore = (String) invokeGetter(config, "getModel");
        String endpointBefore = (String) invokeGetter(config, "getApiEndpoint");

        // Reasoning effort from config.xml
        if (isSecondLlm) {
            String re = System.getProperty("xmage.llm2.reasoningeffort");
            if (re == null || re.isEmpty())
                re = managerFactory.configSettings().getLLM2ReasoningEffort();
            if (re != null && !re.isEmpty())
                setConfigField(config, "reasoningEffort", re);

            String ep = System.getProperty("xmage.llm2.endpoint");
            if (ep == null || ep.isEmpty())
                ep = managerFactory.configSettings().getLLM2Endpoint();
            if (ep != null && !ep.isEmpty())
                setConfigField(config, "apiEndpoint", ep);

            String model = System.getProperty("xmage.llm2.model");
            if (model == null || model.isEmpty())
                model = managerFactory.configSettings().getLLM2Model();
            if (model != null && !model.isEmpty())
                setConfigField(config, "model", model);

            String key = System.getProperty("xmage.llm2.key");
            if (key == null || key.isEmpty())
                key = System.getenv("LLM2_KEY");
            if (key == null || key.isEmpty())
                key = managerFactory.configSettings().getLLM2ApiKey();
            if (key != null && !key.isEmpty())
                setConfigField(config, "apiKey", key);

            // Streaming: llm2.stream > llm.stream > false
            String streamStr = System.getProperty("xmage.llm2.stream");
            if (streamStr == null) streamStr = System.getProperty("xmage.llm.stream");
            setConfigField(config, "streamingEnabled", Boolean.parseBoolean(streamStr != null ? streamStr : "false"));

            // Verbose: llm2.verbose > llm.verbose
            String verboseStr = System.getProperty("xmage.llm2.verbose");
            if (verboseStr == null) verboseStr = System.getProperty("xmage.llm.verbose");
            if (verboseStr != null)
                setConfigField(config, "verboseLogging", Boolean.parseBoolean(verboseStr));
        } else {
            // Reasoning effort: xmage.llm.reasoningeffort property first (start-server.sh
            // -D flag), falling back to config.xml. Mirrors the llm2 branch above, which
            // reads the property — previously seat 0 ignored the -D flag entirely and
            // silently ran at the config.xml effort.
            String re = System.getProperty("xmage.llm.reasoningeffort");
            if (re == null || re.isEmpty())
                re = managerFactory.configSettings().getLLMReasoningEffort();
            if (re != null && !re.isEmpty())
                setConfigField(config, "reasoningEffort", re);

            // Streaming from system property (already set in LLMPlayer.init, but ensure it)
            setConfigField(config, "streamingEnabled", Boolean.parseBoolean(
                    System.getProperty("xmage.llm.stream", "false")));
        }

        // Concede from system property (both LLMs, with cascade)
        String concedeStr = System.getProperty(isSecondLlm ? "xmage.llm2.concede_enabled" : "xmage.llm.concede_enabled", "false");
        setConfigField(config, "concedeEnabled", Boolean.parseBoolean(concedeStr));

        // Ask-reason from system property (both LLMs, with cascade). Default off:
        // the extra REASON line is a format-compliance cost that visible-reasoning
        // models shouldn't pay; enable for hidden-reasoning models.
        String askReasonStr = System.getProperty(isSecondLlm ? "xmage.llm2.ask_reason" : "xmage.llm.ask_reason", "false");
        setConfigField(config, "askReason", Boolean.parseBoolean(askReasonStr));

        // Personality from system property (both LLMs, with cascade)
        String persEnabled = System.getProperty(isSecondLlm ? "xmage.llm2.personality_enabled" : "xmage.llm.personality_enabled", "true");
        setConfigField(config, "personalityEnabled", Boolean.parseBoolean(persEnabled));
        String persName = System.getProperty(isSecondLlm ? "xmage.llm2.personality_name" : "xmage.llm.personality_name");
        if (persName != null)
            setConfigField(config, "personalityName", persName);

        // Bot name from system property (if set, overrides random pick)
        String botName = System.getProperty(isSecondLlm ? "xmage.llm2.bot_name" : "xmage.llm.bot_name");
        if (botName != null && !botName.isEmpty())
            setConfigField(config, "botName", botName);

        // Transcript dir (both LLMs)
        String td = managerFactory.configSettings().getLLMTranscriptDir();
        if (td != null && !td.isEmpty())
            setConfigField(config, "transcriptDir", td);

        // Conversation memory limits (both LLMs, with per-LLM override)
        String prefix = isSecondLlm ? "xmage.llm2." : "xmage.llm.";
        String fallbackPrefix = isSecondLlm ? "xmage.llm." : null;
        String exchanges = System.getProperty(prefix + "conversation_max_exchanges");
        if (exchanges == null && fallbackPrefix != null)
            exchanges = System.getProperty(fallbackPrefix + "conversation_max_exchanges");
        if (exchanges != null)
            setConfigField(config, "conversationMaxExchanges", Integer.parseInt(exchanges));
        String historyChars = System.getProperty(prefix + "conversation_max_history_chars");
        if (historyChars == null && fallbackPrefix != null)
            historyChars = System.getProperty(fallbackPrefix + "conversation_max_history_chars");
        if (historyChars != null)
            setConfigField(config, "conversationMaxHistoryChars", Integer.parseInt(historyChars));
        String thinkChars = System.getProperty(prefix + "conversation_think_chars_per_msg");
        if (thinkChars == null && fallbackPrefix != null)
            thinkChars = System.getProperty(fallbackPrefix + "conversation_think_chars_per_msg");
        if (thinkChars != null)
            setConfigField(config, "conversationThinkCharsPerMsg", Integer.parseInt(thinkChars));

        // Max output tokens — caps total output (reasoning + answer) to prevent
        // reasoning loops from exhausting token budget. Both LLMs, with fallback.
        String maxTokens = System.getProperty(prefix + "max_tokens");
        if (maxTokens == null && fallbackPrefix != null)
            maxTokens = System.getProperty(fallbackPrefix + "max_tokens");
        if (maxTokens != null) {
            setConfigField(config, "maxTokens", Integer.parseInt(maxTokens));
        }

        // Summarization config (conversation compression for long games)
        String sumTrigger = System.getProperty(prefix + "summarize_trigger_chars");
        if (sumTrigger == null && fallbackPrefix != null)
            sumTrigger = System.getProperty(fallbackPrefix + "summarize_trigger_chars");
        if (sumTrigger != null)
            setConfigField(config, "summarizeTriggerChars", Integer.parseInt(sumTrigger));
        String sumTarget = System.getProperty(prefix + "summarize_target_chars");
        if (sumTarget == null && fallbackPrefix != null)
            sumTarget = System.getProperty(fallbackPrefix + "summarize_target_chars");
        if (sumTarget != null)
            setConfigField(config, "summarizeTargetChars", Integer.parseInt(sumTarget));
        String sumKeep = System.getProperty(prefix + "summarize_keep_exchanges");
        if (sumKeep == null && fallbackPrefix != null)
            sumKeep = System.getProperty(fallbackPrefix + "summarize_keep_exchanges");
        if (sumKeep != null)
            setConfigField(config, "summarizeKeepExchanges", Integer.parseInt(sumKeep));
        String sumRatio = System.getProperty(prefix + "summarize_context_ratio");
        if (sumRatio == null && fallbackPrefix != null)
            sumRatio = System.getProperty(fallbackPrefix + "summarize_context_ratio");
        if (sumRatio != null)
            setConfigField(config, "summarizeContextRatio", Double.parseDouble(sumRatio));
        String ctxWindow = System.getProperty(prefix + "model_context_window");
        if (ctxWindow == null && fallbackPrefix != null)
            ctxWindow = System.getProperty(fallbackPrefix + "model_context_window");
        if (ctxWindow != null)
            setConfigField(config, "modelContextWindow", Integer.parseInt(ctxWindow));
        String sumTargetRatio = System.getProperty(prefix + "summarize_target_ratio");
        if (sumTargetRatio == null && fallbackPrefix != null)
            sumTargetRatio = System.getProperty(fallbackPrefix + "summarize_target_ratio");
        if (sumTargetRatio != null)
            setConfigField(config, "summarizeTargetRatio", Double.parseDouble(sumTargetRatio));

        // Re-detect context window ONLY when the override changed the model or
        // endpoint. Re-running detection for an unchanged config is a redundant
        // failure mode: it can throw (Fail Fast on non-Ollama endpoints without
        // an explicit window) and kill the seat wiring even though the player
        // constructor already detected a valid window for this model/endpoint.
        if (!java.util.Objects.equals(invokeGetter(config, "getModel"), modelBefore)
                || !java.util.Objects.equals(invokeGetter(config, "getApiEndpoint"), endpointBefore)) {
            invokeNoArg(config, "fetchModelContextWindow");
        }
    }

    /**
     * Set a field on the LLMConfig object via reflection (plugin classloader boundary).
     */
    private static void setConfigField(Object config, String setterName, Object value) {
        Class<?> valueClass = value.getClass();
        try {
            if (value instanceof Boolean) valueClass = boolean.class;
            else if (value instanceof Integer) valueClass = int.class;
            else if (value instanceof Double) valueClass = double.class;
            Method setter = config.getClass().getMethod(setterName, valueClass);
            setter.invoke(config, value);
        } catch (Exception e) {
            throw new RuntimeException("LLMConfig." + setterName + "(" + valueClass + ") failed via reflection: " + e.getMessage(), e);
        }
    }

    /**
     * Invoke a no-arg method on the LLMConfig object via reflection.
     */
    private static void invokeNoArg(Object config, String methodName) {
        try {
            Method m = config.getClass().getMethod(methodName);
            m.invoke(config);
        } catch (Exception e) {
            throw new RuntimeException("LLMConfig." + methodName + "() failed via reflection: " + e.getMessage(), e);
        }
    }

    /**
     * Invoke a no-arg getter on the LLMConfig object via reflection.
     */
    private static Object invokeGetter(Object config, String getterName) {
        try {
            Method m = config.getClass().getMethod(getterName);
            return m.invoke(config);
        } catch (Exception e) {
            throw new RuntimeException("LLMConfig." + getterName + "() failed via reflection: " + e.getMessage(), e);
        }
    }

    /**
     * Set a player's name field via reflection (the name field lives up in
     * the PlayerImpl hierarchy and has no public setter).
     */
    private static void setPlayerName(Player player, String name) {
        try {
            Class<?> current = player.getClass();
            Field nameField = null;
            while (current != null && nameField == null) {
                try {
                    nameField = current.getDeclaredField("name");
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
            if (nameField != null) {
                nameField.setAccessible(true);
                nameField.set(player, name);
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to set bot name to '" + name + "': " + t.getMessage(), t);
        }
    }

    /**
     * Pick a unique random name from the bot name pool for this game.
     */
    private String pickBotName() {
        List<String> available = new ArrayList<>();
        for (String name : BOT_NAMES) {
            if (!usedNamesThisGame.contains(name)) {
                available.add(name);
            }
        }
        if (available.isEmpty()) {
            return "Bot" + rng.nextInt(10000);
        }
        String chosen = available.get(rng.nextInt(available.size()));
        usedNamesThisGame.add(chosen);
        return chosen;
    }

    /**
     * Generate a random 40-card deck: 10 creatures + 10 spells + 20 basic lands.
     */
    public static DeckCardLists generateRandomDeck() {
        DeckCardLists lists = new DeckCardLists();
        Random rng = new Random();

        try {
            List<CardInfo> creatures = CardRepository.instance.findCards(
                    new CardCriteria().types(CardType.CREATURE));
            List<CardInfo> spells = CardRepository.instance.findCards(
                    new CardCriteria().notTypes(CardType.CREATURE).notTypes(CardType.LAND));

            for (int i = 0; i < 10 && !creatures.isEmpty(); i++) {
                CardInfo ci = creatures.get(rng.nextInt(creatures.size()));
                lists.getCards().add(new DeckCardInfo(ci.getName(), ci.getSetCode(), ci.getCardNumber()));
            }
            for (int i = 0; i < 10 && !spells.isEmpty(); i++) {
                CardInfo ci = spells.get(rng.nextInt(spells.size()));
                lists.getCards().add(new DeckCardInfo(ci.getName(), ci.getSetCode(), ci.getCardNumber()));
            }
        } catch (Throwable t) {
            throw new RuntimeException("Failed to generate random deck cards: " + t.getMessage(), t);
        }

        for (int i = 0; i < 4; i++) lists.getCards().add(new DeckCardInfo("Swamp", null, null));
        for (int i = 0; i < 4; i++) lists.getCards().add(new DeckCardInfo("Mountain", null, null));
        for (int i = 0; i < 4; i++) lists.getCards().add(new DeckCardInfo("Forest", null, null));
        for (int i = 0; i < 4; i++) lists.getCards().add(new DeckCardInfo("Island", null, null));
        for (int i = 0; i < 4; i++) lists.getCards().add(new DeckCardInfo("Plains", null, null));

        return lists;
    }
}
