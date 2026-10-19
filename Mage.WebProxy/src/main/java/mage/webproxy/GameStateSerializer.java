package mage.webproxy;

import mage.constants.PhaseStep;
import mage.constants.TurnPhase;
import mage.view.AbilityView;
import mage.view.CardView;
import mage.view.CombatGroupView;
import mage.view.CommandObjectView;
import mage.view.CounterView;
import mage.view.EmblemView;
import mage.view.GameView;
import mage.view.ManaPoolView;
import mage.view.PermanentView;
import mage.view.PlaneView;
import mage.view.PlayerView;
import mage.view.CardsView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Converts XMage's GameView into the JSON format that game-renderer.js
 * expects. This is a FRESH implementation — NOT a port of BridgeGameStateBuilder.
 *
 * BridgeGameStateBuilder outputs snake_case keys for LLM text consumption.
 * The browser renderer's normalizeLiveState/normalizeCard/normalizeLivePlayer
 * expect camelCase input fields. This serializer produces those exact fields.
 *
 * See the renderer's normalizeCard (line 12) and normalizeLivePlayer (line 37)
 * for the authoritative field list.
 */
public final class GameStateSerializer {

    private static final com.google.gson.Gson GSON = WebProxySession.GSON;

    /**
     * Cache for Scryfall image URLs to avoid repeated URLEncoder.encode() calls.
     * Bounded to 500 entries via a synchronized access-order LinkedHashMap with
     * removeEldestEntry. Access-order evicts least-recently-used entries (LRU).
     */
    private static final int IMAGE_URL_CACHE_MAX = 500;
    private static final java.util.Map<String, String> imageUrlCache =
            java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                        return size() > IMAGE_URL_CACHE_MAX;
                    }
                });

    /**
     * Mock cards for foreign-hand serialization, keyed "SET|NUMBER".
     *
     * The stock client caches the same objects in GamePanel's `loadedCards` map
     * (CardsViewUtil.convertSimple(view, loadedCards)) precisely so a hand that is
     * re-serialized on every state push does not re-query the card DB per card.
     * A {@link mage.cards.Card} is immutable game-input here (the mock is only a
     * name/art carrier — never part of a game), so sharing one instance across
     * serializations is safe. Bounded like imageUrlCache; the state serializer
     * runs on JBoss callback threads, hence the synchronized wrapper.
     */
    private static final int FOREIGN_CARD_CACHE_MAX = 500;
    private static final java.util.Map<String, mage.cards.Card> foreignCardCache =
            java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<String, mage.cards.Card>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, mage.cards.Card> eldest) {
                        return size() > FOREIGN_CARD_CACHE_MAX;
                    }
                });

    private GameStateSerializer() {}

    /**
     * Serialize a GameView to a JSON string matching what game-renderer.js's
     * normalizeLiveState expects.
     *
     * @param gameView  the XMage game state
     * @param myPlayerId  the player ID this session belongs to (null for spectators)
     * @param stateSeq  the state version counter (incremented per state send)
     * @return JSON string
     */
    public static String serialize(GameView gameView, UUID myPlayerId, long stateSeq) {
        return GSON.toJson(buildStateMap(gameView, myPlayerId, stateSeq));
    }

    /**
     * Build the state Map that serialize() converts to JSON.
     * Exposed so callers can wrap it in an envelope without re-parsing the JSON string.
     */
    public static Map<String, Object> buildStateMap(GameView gameView, UUID myPlayerId, long stateSeq) {
        Map<String, Object> state = new HashMap<>();

        // Top-level fields (normalizeLiveState reads these)
        // status is always "playing" for the state-update path. Game-over is
        // delivered via a separate game_over message, not via a status change
        // here (GameView has no status field). The frontend's handleGameState
        // accordingly treats only "playing" as live.
        state.put("status", "playing");
        state.put("turn", gameView.getTurn());
        state.put("isSpectator", myPlayerId == null);

        TurnPhase phase = gameView.getPhase();
        state.put("phase", mapPhaseToClient(phase));

        PhaseStep step = gameView.getStep();
        state.put("step", step != null ? step.name() : null);

        state.put("activePlayer", gameView.getActivePlayerName());
        state.put("priorityPlayer", gameView.getPriorityPlayerName());

        // Stack
        List<Map<String, Object>> stack = new ArrayList<>();
        if (gameView.getStack() != null) {
            for (CardView card : gameView.getStack().values()) {
                stack.add(serializeCard(card));
            }
        }
        state.put("stack", stack);

        // Combat groups
        List<Map<String, Object>> combat = new ArrayList<>();
        if (gameView.getCombat() != null) {
            for (CombatGroupView group : gameView.getCombat()) {
                Map<String, Object> cg = new HashMap<>();
                cg.put("defenderId", group.getDefenderId() != null ? group.getDefenderId().toString() : null);
                cg.put("defenderName", group.getDefenderName());
                cg.put("blocked", group.isBlocked());

                List<Map<String, Object>> attackers = new ArrayList<>();
                if (group.getAttackers() != null) {
                    for (CardView card : group.getAttackers().values()) {
                        Map<String, Object> a = serializeCard(card);
                        a.put("id", card.getId() != null ? card.getId().toString() : null);
                        attackers.add(a);
                    }
                }
                cg.put("attackers", attackers);

                List<Map<String, Object>> blockers = new ArrayList<>();
                if (group.getBlockers() != null) {
                    for (CardView card : group.getBlockers().values()) {
                        Map<String, Object> b = serializeCard(card);
                        b.put("id", card.getId() != null ? card.getId().toString() : null);
                        blockers.add(b);
                    }
                }
                cg.put("blockers", blockers);

                combat.add(cg);
            }
        }
        state.put("combat", combat);

        // Players
        List<Map<String, Object>> players = new ArrayList<>();
        for (PlayerView player : gameView.getPlayers()) {
            players.add(serializePlayer(player, gameView, myPlayerId));
        }
        state.put("players", players);

        // Root-level LLM presence flag — UNFILTERED, unlike the `llmDebug` list
        // below. GameView.getLLMDebug() is "null unless LLM players present"
        // (GameView.java:70), so a non-null non-empty list proves at least one
        // LLM seat exists. Computed BEFORE/OUTSIDE the per-viewer filter on
        // purpose: an opponent in a live game with private LLM chat sees an
        // EMPTY filtered llmDebug, so a client gating the rail's LLM tab on
        // llmDebug.length would wrongly hide the tab even though LLM players
        // ARE in the game.
        // Additive: the filtering semantics below are untouched.
        //
        // Exactness note: the source list is attached only once an LLM player
        // HAS debug data (conversation history or a stream in progress), so the
        // flag can read false for the first moments of a game with an LLM seat,
        // before its first exchange. The first state push carrying data flips
        // it, and the client-side delta applicator forwards that key — the tab
        // appears then. This is the pinned shape: "hasLlmPlayers = getLLMDebug()
        // non-empty", emitted unfiltered.
        //
        // NOTE: not used by the replay viewer's tab gate — a replay frame's
        // llmDebug is attached per state index (ReplaySession), so this flag
        // follows the frame; replays keep their pre-existing offer (the frame's
        // own llmDebug drives the PANEL content) to avoid a flickering tab bar.
        List<mage.view.LLMConversationView> llmDebugSource = gameView.getLLMDebug();
        state.put("hasLlmPlayers", llmDebugSource != null && !llmDebugSource.isEmpty());

        // LLM debug data — conversation history for each LLM player.
        // Visibility is per-VIEWER, driven by the match's llmChatPublic setting:
        //  - gameView.isLlmChatPublic() is true on REPLAY frames (ReplaySession always
        //    sets it — the match is over, nothing live to leak) and on live views when
        //    the table opted in. Then everyone sees every LLM's chat.
        //  - When false (live, default): a seated viewer sees only THEIR OWN LLM's
        //    chat; a spectator (myPlayerId == null) sees none. Prevents opponents and
        //    spectators reading hidden-hand reasoning mid-game.
        String viewerName = null;
        PlayerView myPlayer = gameView.getMyPlayer();
        if (myPlayer != null) {
            viewerName = myPlayer.getName();
        }
        boolean shareAll = gameView.isLlmChatPublic();
        List<Map<String, Object>> llmDebug = new ArrayList<>();
        if (gameView.getLLMDebug() != null) {
            for (mage.view.LLMConversationView llmView : gameView.getLLMDebug()) {
                boolean own = viewerName != null && viewerName.equals(llmView.playerName);
                if (!shareAll && !own) {
                    continue; // hide others' LLM chat from opponents/spectators live
                }
                llmDebug.add(serializeLlmView(llmView));
            }
        }
        state.put("llmDebug", llmDebug);

        // Skipped-range LLM buckets (
        // bug 2). A forward jump (replay_skip / replay_seek) delivers ONE frame;
        // ReplaySession attaches the buckets of the indices the viewer never saw
        // so its per-index cumulative store stays complete. Each entry is keyed
        // by its TRUE state index and shaped exactly like one llmDebug entry —
        // the frontend merges them idempotently (a re-delivered index overwrites
        // its own key). Absent on live frames and on jumps that skipped nothing,
        // so the field is purely additive.
        java.util.Map<Integer, java.util.List<mage.view.LLMConversationView>> bucketSource = gameView.getReplayLLMBuckets();
        if (bucketSource != null && !bucketSource.isEmpty()) {
            List<Map<String, Object>> buckets = new ArrayList<>();
            for (java.util.Map.Entry<Integer, java.util.List<mage.view.LLMConversationView>> e : bucketSource.entrySet()) {
                List<Map<String, Object>> entries = new ArrayList<>();
                for (mage.view.LLMConversationView llmView : e.getValue()) {
                    boolean own = viewerName != null && viewerName.equals(llmView.playerName);
                    if (!shareAll && !own) {
                        continue; // same per-viewer rule as llmDebug above
                    }
                    entries.add(serializeLlmView(llmView));
                }
                if (entries.isEmpty()) {
                    continue;
                }
                Map<String, Object> bucket = new HashMap<>();
                bucket.put("stateIndex", e.getKey());
                bucket.put("entries", entries);
                buckets.add(bucket);
            }
            if (!buckets.isEmpty()) {
                state.put("replayLLMBuckets", buckets);
            }
        }

        // Frame-independent "this replay's GAME has an LLM seat" flag. Distinct
        // from the per-frame `hasLlmPlayers` above: this one answers "does the
        // game have LLM players at all", which is what the viewer's empty state
        // needs to pick its copy (exchange wording vs no-LLM wording). Null on
        // live views / old replays — the frontend then uses the exchange wording
        // and never the live-only claim.
        if (gameView.getReplayHasLlmPlayers() != null) {
            state.put("replayHasLlmPlayers", gameView.getReplayHasLlmPlayers());
        }

        // Controlled / watched hands (switch-hands flow).
        //
        // Desktop parity: GamePanel.updateGame collects `handCards` from
        // gameView.getMyHand() ("Your hand"), gameView.getOpponentHands()
        // (players whose TURN this session controls — filled by
        // GameSessionPlayer.processControlledPlayers) and
        // gameView.getWatchedHands() (players who granted this user permission
        // to see their hand — GameSessionWatcher.processWatchedHands), and its
        // btnSwitchHands switches the rendered hand between them
        // (GamePanel.java:1094-1134: the button is visible only when
        // handCards.size() > 1). Before this change the web wire carried only
        // MY hand ('hand' below), so the browser had nothing to switch to.
        //
        // Additive shape — the existing 'hand' contract is untouched:
        //   controlledHands: [{name, cards:[Card…], kind:"controlled"|"watched"}]
        // 'name' is the engine's own player name (the desktop uses it as the
        // handCards key). `kind` tells the two grant reasons apart so the UI can
        // label them (turn control vs permission), matching the two separate
        // sources merged by the desktop's single map.
        //
        // Visibility is decided SERVER-side already: getWatchedHands is only
        // populated for users a player granted permission to (or a spectator
        // watching a seat whose owner shared), and getOpponentHands only while
        // this session controls another player's turn — so a viewer never
        // receives a hand they may not see. The list is ALWAYS present (empty
        // when there is nothing to switch to) so the frontend's "more than one
        // hand" gate is a plain length check.
        //
        // SimpleCardsView carries no name (SimpleCardView = id + set/number),
        // so cards are resolved through the card DB exactly like
        // CardsViewUtil.convertSimple does in the stock client; an unresolvable
        // set+number is a Fail-Fast IllegalStateException (same contract as
        // WebProxyClient.serializeSimpleCardList).
        List<Map<String, Object>> controlledHands = new ArrayList<>();
        for (Map.Entry<String, mage.view.SimpleCardsView> entry : gameView.getOpponentHands().entrySet()) {
            controlledHands.add(serializeForeignHand(entry.getKey(), entry.getValue(), "controlled"));
        }
        for (Map.Entry<String, mage.view.SimpleCardsView> entry : gameView.getWatchedHands().entrySet()) {
            controlledHands.add(serializeForeignHand(entry.getKey(), entry.getValue(), "watched"));
        }
        state.put("controlledHands", controlledHands);

        // Revealed cards (e.g. Thoughtseize reveals opponent's hand)
        List<Map<String, Object>> revealed = new ArrayList<>();
        if (gameView.getRevealed() != null) {
            for (mage.view.RevealedView rv : gameView.getRevealed()) {
                Map<String, Object> r = new HashMap<>();
                r.put("name", rv.getName());
                List<Map<String, Object>> cards = new ArrayList<>();
                if (rv.getCards() != null) {
                    for (CardView card : rv.getCards().values()) {
                        cards.add(serializeCard(card));
                    }
                }
                r.put("cards", cards);
                revealed.add(r);
            }
        }
        state.put("revealed", revealed);

        // Looked-at cards (e.g. scry, surveil)
        List<Map<String, Object>> lookedAt = new ArrayList<>();
        if (gameView.getLookedAt() != null) {
            for (mage.view.LookedAtView lv : gameView.getLookedAt()) {
                Map<String, Object> l = new HashMap<>();
                l.put("name", lv.getName());
                List<Map<String, Object>> cards = new ArrayList<>();
                if (lv.getCards() != null) {
                    for (mage.view.SimpleCardView card : lv.getCards().values()) {
                        Map<String, Object> c = new HashMap<>();
                        c.put("id", card.getId() != null ? card.getId().toString() : null);
                        c.put("setCode", card.getExpansionSetCode());
                        c.put("cardNumber", card.getCardNumber());
                        // Build Scryfall image URL from set code + card number
                        if (card.getExpansionSetCode() != null && card.getCardNumber() != null) {
                            c.put("imageUrl", "https://api.scryfall.com/cards/" +
                                card.getExpansionSetCode().toLowerCase() + "/" +
                                card.getCardNumber() + "?format=image&version=small");
                        }
                        cards.add(c);
                    }
                }
                l.put("cards", cards);
                lookedAt.add(l);
            }
        }
        state.put("lookedAt", lookedAt);

        // Watched hands — hands the viewer has been GRANTED
        // permission to see. The server populates GameView.getWatchedHands() per
        // USER id (GameSessionWatcher.processWatchedHands, called from BOTH
        // GameSessionPlayer.prepareGameView and GameSessionWatcher.getGameView),
        // keyed by player NAME with a SimpleCardsView. The desktop client renders
        // them exactly like opponent hands (GamePanel.updateGame:1095-1099 → the
        // hand container), which is what the browser's hand viewer mirrors.
        //
        // Shape: [{name, cards:[serializeCard…]}] — same per-card shape as the
        // `hand` list on a serialized player, so the frontend renders one card
        // row convention (the view_limited_deck precedent). Additive: an
        // older bundle ignores it, and an ungranted session gets [] (the server
        // already cleared the map on the way here).
        List<Map<String, Object>> watchedHands = new ArrayList<>();
        if (gameView.getWatchedHands() != null) {
            for (Map.Entry<String, mage.view.SimpleCardsView> hand : gameView.getWatchedHands().entrySet()) {
                Map<String, Object> h = new HashMap<>();
                h.put("name", hand.getKey());
                List<Map<String, Object>> cards = new ArrayList<>();
                if (hand.getValue() != null) {
                    for (mage.view.SimpleCardView simple : hand.getValue().values()) {
                        // SimpleCardView carries no name — resolve it via the same
                        // CardRepository lookup the stock client's CardsViewUtil
                        // .convertSimple uses, then serialize as a full card view so
                        // the browser's hand tiles render art + text identically to
                        // its own hand.
                        mage.cards.repository.CardInfo info = mage.cards.repository.CardRepository.instance
                                .findCard(simple.getExpansionSetCode(), simple.getCardNumber());
                        if (info != null) {
                            cards.add(serializeCard(new CardView(info.createMockCard(), simple)));
                            continue;
                        }
                        // A proxy card DB that lags the server's is a KNOWN operational
                        // condition (CardsDbGuard / staging docs), and this runs inside
                        // the state channel — throwing here would take the whole game
                        // view down for one unknown printing. Log loudly and emit the
                        // same minimal shape this serializer already uses for
                        // SimpleCardView-sourced cards (the `lookedAt` list above:
                        // id/setCode/cardNumber/imageUrl), so the tile still renders
                        // with Scryfall art. (The desktop's CardsViewUtil.convertSimple
                        // silently SKIPS such a card entirely — this keeps the tile.)
                        LOGGER.error("watched hand: card not in proxy DB, sending minimal card view: set="
                                + simple.getExpansionSetCode() + " number=" + simple.getCardNumber());
                        Map<String, Object> fallback = new HashMap<>();
                        fallback.put("id", simple.getId() != null ? simple.getId().toString() : null);
                        fallback.put("setCode", simple.getExpansionSetCode());
                        fallback.put("cardNumber", simple.getCardNumber());
                        if (simple.getExpansionSetCode() != null && simple.getCardNumber() != null) {
                            fallback.put("imageUrl", "https://api.scryfall.com/cards/"
                                    + simple.getExpansionSetCode().toLowerCase() + "/"
                                    + simple.getCardNumber() + "?format=image&version=small");
                        }
                        cards.add(fallback);
                    }
                }
                h.put("cards", cards);
                watchedHands.add(h);
            }
        }
        state.put("watchedHands", watchedHands);

        // NOTE: events are NOT included in the state payload. They are pushed
        // separately via WebProxyClient.handleChatMessage/handleGameInformPersonal
        // as individual "event" messages, which the frontend appends via
        // appendEvent(). Keeping an always-empty "events" array here would be
        // dead weight (the frontend's processEvents was a no-op on this array).

        // Replay game log (flat shape): only REPLAY frames carry it — the log is
        // set on the GameView by ReplaySession (desktop ignores the fields), so a
        // live-game GameView leaves these absent and the live channel keeps using
        // `event` messages. `gameLog` is the saved game's final log; `gameLogCount`
        // is how many entries existed at THIS frame — entries 0..count-1.
        // See  (option A: +0.10% size).
        if (gameView.getReplayGameLog() != null && !gameView.getReplayGameLog().isEmpty()) {
            state.put("gameLog", new ArrayList<>(gameView.getReplayGameLog()));
            state.put("gameLogCount", gameView.getReplayGameLogCount());
        }

        // State version for gap detection + resync (see frontend _onStateVersionReceived)
        state.put("stateSeq", stateSeq);

        return state;
    }

    /**
     * Serialize ONE LLMConversationView into the wire shape the rail renders.
     *
     * Extracted so the per-frame `llmDebug` list and the skipped-range
     * `replayLLMBuckets` entries are byte-identical in shape — the frontend
     * merges buckets produced here into the same per-index store llmDebug
     * feeds, so a shape drift between the two would be a silent divergence

     */
    private static Map<String, Object> serializeLlmView(mage.view.LLMConversationView llmView) {
        Map<String, Object> llm = new HashMap<>();
        llm.put("playerName", llmView.playerName);
        llm.put("modelName", llmView.modelName);
        llm.put("exchangeCount", llmView.exchangeCount);
        llm.put("totalContextChars", llmView.totalContextChars);
        llm.put("modelContextWindow", llmView.modelContextWindow);
        llm.put("streaming", llmView.streaming);
        if (llmView.streamingReasoning != null) llm.put("streamingReasoning", llmView.streamingReasoning);
        if (llmView.streamingContent != null) llm.put("streamingContent", llmView.streamingContent);
        llm.put("streamingStartedAt", llmView.streamingStartedAt);
        // Pre-game deck assessment / gameplan
        if (llmView.gamePlan != null) llm.put("gamePlan", llmView.gamePlan);
        // Post-game reflection
        if (llmView.postGameReflection != null) llm.put("postGameReflection", llmView.postGameReflection);
        // Match review for sideboarding
        if (llmView.matchReview != null) llm.put("matchReview", llmView.matchReview);
        List<Map<String, Object>> msgs = new ArrayList<>();
        if (llmView.messages != null) {
            for (mage.view.LLMConversationView.LLMMessageView msg : llmView.messages) {
                Map<String, Object> m = new HashMap<>();
                m.put("role", msg.role);
                m.put("content", msg.content);
                if (msg.promptType != null) m.put("promptType", msg.promptType);
                if (msg.turn != null) m.put("turn", msg.turn);
                if (msg.phaseStep != null) m.put("phaseStep", msg.phaseStep);
                if (msg.compactSummary != null) m.put("compactSummary", msg.compactSummary);
                msgs.add(m);
            }
        }
        llm.put("messages", msgs);
        return llm;
    }

    // ── Player serialization ──

    /**
     * Serialize one hand whose cards are known only as {@link mage.view.SimpleCardView}s
     * (id + set code + collector number) — the controlled-turn and watched-hand
     * sources (GameView.getOpponentHands / getWatchedHands).
     *
     * Name resolution goes through the card DB the same way the stock client's
     * CardsViewUtil.convertSimple does, then the FULL card serializer renders the
     * card so a foreign hand tile is shaped exactly like a card in `hand`
     * (imageUrl, typeLine, manaCost, flip button, …).
     *
     * Failure policy is KIND-DEPENDENT (merge repair 2026-09-27):
     *  - "controlled" — the cards are PLAYABLE (the play path resolves them via
     *    WebProxyClient.findCardViewById), so a set+number the DB does not know is
     *    Fail Fast: silently emitting a nameless card would render a blank tile,
     *    hide a real card-pool/staging mismatch, and the play path could not label it.
     *  - "watched" — a view-only grant: a lagging proxy card DB is a KNOWN
     *    operational condition (CardsDbGuard / staging docs) and this runs inside
     *    the state channel, so throwing would take the whole game view down for
     *    one unknown printing. Log loudly and emit the same minimal lookedAt-style
     *    shape (id/setCode/cardNumber/imageUrl) so the tile still renders with
     *    Scryfall art.
     *
     * @param kind "controlled" (this session controls that player's turn) or
     *             "watched" (that player granted this user permission to see
     *             their hand) — the UI labels the two differently.
     */
    private static Map<String, Object> serializeForeignHand(
            String playerName, mage.view.SimpleCardsView simpleCards, String kind) {
        Map<String, Object> hand = new HashMap<>();
        hand.put("name", playerName);
        hand.put("kind", kind);
        List<Map<String, Object>> cards = new ArrayList<>();
        if (simpleCards != null) {
            for (mage.view.SimpleCardView simple : simpleCards.values()) {
                String setCode = simple.getExpansionSetCode() != null ? simple.getExpansionSetCode() : "";
                String cardNumber = simple.getCardNumber() != null ? simple.getCardNumber() : "";
                String cacheKey = setCode + "|" + cardNumber;
                mage.cards.Card mock = foreignCardCache.get(cacheKey);
                if (mock == null) {
                    mage.cards.repository.CardInfo info =
                            mage.cards.repository.CardRepository.instance.findCard(setCode, cardNumber);
                    if (info == null) {
                        // Kind-dependent failure policy — see the method javadoc.
                        if (!"watched".equals(kind)) {
                            throw new IllegalStateException("Unknown card in hand of player " + playerName
                                    + ": set=" + setCode + " number=" + cardNumber);
                        }
                        LOGGER.error("watched hand: card not in proxy DB, sending minimal card view: set="
                                + setCode + " number=" + cardNumber);
                        Map<String, Object> fallback = new HashMap<>();
                        fallback.put("id", simple.getId() != null ? simple.getId().toString() : null);
                        fallback.put("setCode", simple.getExpansionSetCode());
                        fallback.put("cardNumber", simple.getCardNumber());
                        if (simple.getExpansionSetCode() != null && simple.getCardNumber() != null) {
                            fallback.put("imageUrl", "https://api.scryfall.com/cards/"
                                    + simple.getExpansionSetCode().toLowerCase() + "/"
                                    + simple.getCardNumber() + "?format=image&version=small");
                        }
                        cards.add(fallback);
                        continue;
                    }
                    mock = info.createMockCard();
                    if (mock == null) {
                        throw new IllegalStateException("Card DB row cannot create a card for player "
                                + playerName + " hand: set=" + setCode + " number=" + cardNumber);
                    }
                    foreignCardCache.put(cacheKey, mock);
                }
                // CardView(card, SimpleCardView) copies id/choosable/selected/playable
                // stats from the simple view — the game-object UUID is what the
                // frontend keys card tiles on.
                CardView cardView = new CardView(mock, simple);
                cards.add(serializeCard(cardView));
            }
        }
        hand.put("cards", cards);
        return hand;
    }

    private static Map<String, Object> serializePlayer(PlayerView player, GameView gameView, UUID myPlayerId) {
        Map<String, Object> p = new HashMap<>();

        p.put("name", player.getName());
        p.put("playerId", player.getPlayerId() != null ? player.getPlayerId().toString() : null);
        p.put("life", player.getLife());
        p.put("isMe", myPlayerId != null && myPlayerId.equals(player.getPlayerId()));
        // Human vs computer seat — mirrors PlayerView.isHuman() (PlayerView.java:67,184).
        // The desktop client gates its in-game "View deck"/"View sideboard" menu items
        // on it (PlayAreaPanel.java:440 `options.playerItself || !options.isHuman`), so
        // the web player menu needs the same flag to offer the item only for self /
        // computer seats. Additive field; older bundles ignore it.
        p.put("isHuman", player.isHuman());
        // Avatar id — PlayerView.getUserData().getAvatarId() (PlayerView.java:110-114,
        // which falls back to UserData.getDefaultUserDataView() = id 0 when a seat carries
        // no user data). The desktop renders it as /avatars/<id>.jpg (PlayerPanelExt.java:
        // 359-377); computer seats carry 64 (ComputerPlayer/LLMPlayer constructors) which the
        // desktop maps to /avatars/i64.jpg. The frontend resolver clamps anything it cannot
        // draw to the default avatar, so 0 never renders as a broken image. Additive field;
        // older bundles ignore it.
        p.put("avatarId", player.getUserData() != null ? player.getUserData().getAvatarId() : 0);
        p.put("libraryCount", player.getLibraryCount());
        p.put("handCount", player.getHandCount());
        p.put("isActive", player.isActive());
        p.put("hasPriority", player.hasPriority());
        // Undo availability: >0 = the player has a stored undo bookmark on the
        // server (PlayerView.getStatesSavedSize maps to Player.getStoredBookmark()).
        // The desktop client gates its Undo button on this (GamePanel.java:2139);
        // the web client must do the same instead of always showing the button.
        p.put("statesSavedSize", player.getStatesSavedSize());
        p.put("hasLeft", player.hasLeft());
        p.put("timerActive", player.isTimerActive());
        p.put("priorityTimeLeftSecs", player.getPriorityTimeLeftSecs());
        p.put("isMonarch", player.isMonarch());
        p.put("hasInitiative", player.isInitiative());
        // Hand-request state: does this seat currently ACCEPT hand
        // requests? Mirrors PlayerView.getUserData().isAllowRequestHandToAll(), which
        // is what the desktop client's own-header checkbox reads
        // (PlayAreaPanel.update:517 `allowViewHandCardsMenuItem.setSelected(...)`).
        // PlayerView.userData is never null (the core substitutes
        // UserData.getDefaultUserDataView() for a null source), so reading it here
        // cannot invent a state. Additive field; older bundles ignore it.
        p.put("allowHandRequests", player.getUserData().isAllowRequestHandToAll());

        // Auto-pass ("skip") flags — PlayerView.isPassed* (PlayerView.java:53-58,
        // 290-312). The engine sets one in PlayerImpl.sendPlayerAction (2732-2775)
        // and CLEARS it when that skip fires (HumanPlayer.priority():
        // `passedUntilNextMain = false` :1315, `passedUntilEndOfTurn = false` :1330,
        // …) or on PASS_PRIORITY_CANCEL_ALL_ACTIONS (resetPlayerPassedActions,
        // PlayerImpl.java:2684-2697). The desktop client mirrors its skip-button
        // pressed state from exactly these flags on every game update
        // GamePanel.updatePressState:1417-1424) — the only state that knows a
        // skip has been consumed. Without them the web rail had to keep its own
        // local highlight, so a FIRED skip stayed lit and the next click sent a
        // no-op cancel_skip.
        p.put("passedTurn", player.isPassedTurn());
        p.put("passedUntilEndOfTurn", player.isPassedUntilEndOfTurn());
        p.put("passedUntilNextMain", player.isPassedUntilNextMain());
        p.put("passedAllTurns", player.isPassedAllTurns());
        p.put("passedUntilStackResolved", player.isPassedUntilStackResolved());
        p.put("passedUntilEndStepBeforeMyTurn", player.isPassedUntilEndStepBeforeMyTurn());

        // Mana pool
        ManaPoolView manaPool = player.getManaPool();
        if (manaPool != null) {
            Map<String, Object> mp = new HashMap<>();
            mp.put("red", manaPool.getRed());
            mp.put("blue", manaPool.getBlue());
            mp.put("green", manaPool.getGreen());
            mp.put("white", manaPool.getWhite());
            mp.put("black", manaPool.getBlack());
            mp.put("colorless", manaPool.getColorless());
            p.put("manaPool", mp);
        }

        // Counters
        List<Map<String, Object>> counters = new ArrayList<>();
        if (player.getCounters() != null) {
            for (CounterView counter : player.getCounters()) {
                Map<String, Object> c = new HashMap<>();
                c.put("name", counter.getName());
                c.put("count", counter.getCount());
                counters.add(c);
            }
        }
        p.put("counters", counters);

        // Commanders — renderer calls .map(normalizeCard) on these, so they must be card objects
        List<Map<String, Object>> commanders = new ArrayList<>();
        // Emblems and planes live in the command zone too, but are distinct views.
        // Split them out so the frontend can show them under their own labels
        // (e.g. "Emblem — Elspeth, Sun's Champion" as a badge, not a commander).
        List<Map<String, Object>> emblems = new ArrayList<>();
        List<Map<String, Object>> planes = new ArrayList<>();
        if (player.getCommandObjectList() != null) {
            for (CommandObjectView cmd : player.getCommandObjectList()) {
                Map<String, Object> cmdMap;
                // CardView commanders (CommanderView extends CardView) go through
                // the FULL card serializer so they carry secondFace (MDFC
                // commanders must get a flip button like any other card) and
                // every other card field the frontend renders. Non-card command
                // objects (emblems/planes/dungeons) keep the minimal map.
                if (cmd instanceof CardView) {
                    cmdMap = serializeCard((CardView) cmd);
                } else {
                    cmdMap = new HashMap<>();
                    cmdMap.put("name", cmd.getName());
                    cmdMap.put("imageUrl", buildImageUrl(cmd.getName()));
                }
                cmdMap.put("id", cmd.getId() != null ? cmd.getId().toString() : null);
                cmdMap.put("typeLine", "");
                // If it's a CardView, get more data
                if (cmd instanceof CardView) {
                    CardView cv = (CardView) cmd;
                    // keep the (possibly empty) legacy typeLine slot, then fill it
                    cmdMap.put("typeLine", cv.getTypeText());
                    cmdMap.put("manaCost", cv.getManaCostStr());
                    cmdMap.put("rules", cv.getRules());
                }
                if (cmd instanceof EmblemView) {
                    cmdMap.put("rules", ((EmblemView) cmd).getRules());
                    emblems.add(cmdMap);
                } else if (cmd instanceof PlaneView) {
                    cmdMap.put("rules", ((PlaneView) cmd).getRules());
                    planes.add(cmdMap);
                } else {
                    commanders.add(cmdMap);
                }
            }
        }
        p.put("commanders", commanders);
        p.put("emblems", emblems);
        p.put("planes", planes);

        // Battlefield
        List<Map<String, Object>> battlefield = new ArrayList<>();
        if (player.getBattlefield() != null) {
            List<PermanentView> sortedBattlefield = new ArrayList<>(player.getBattlefield().values());
            sortedBattlefield.sort(Comparator.comparing(CardView::getName));
            for (PermanentView perm : sortedBattlefield) {
                battlefield.add(serializePermanent(perm));
            }
        }
        p.put("battlefield", battlefield);

        // Hand — only for the session's player
        boolean isMyPlayer = myPlayerId != null && myPlayerId.equals(player.getPlayerId());
        if (isMyPlayer && gameView.getMyHand() != null) {
            List<Map<String, Object>> hand = new ArrayList<>();
            for (CardView card : gameView.getMyHand().values()) {
                hand.add(serializeCard(card));
            }
            p.put("hand", hand);
        } else {
            p.put("hand", new ArrayList<>());
        }

        // Graveyard
        List<Map<String, Object>> graveyard = new ArrayList<>();
        if (player.getGraveyard() != null) {
            for (CardView card : player.getGraveyard().values()) {
                graveyard.add(serializeCard(card));
            }
        }
        p.put("graveyard", graveyard);

        // Exile
        List<Map<String, Object>> exile = new ArrayList<>();
        if (player.getExile() != null) {
            for (CardView card : player.getExile().values()) {
                exile.add(serializeCard(card));
            }
        }
        p.put("exile", exile);

        // Top library card (revealed if player.isTopCardRevealed())
        if (player.getTopCard() != null) {
            p.put("topCard", serializeCard(player.getTopCard()));
        }

        // Sideboard (visible to the player who owns it)
        if (isMyPlayer && player.getSideboard() != null && !player.getSideboard().isEmpty()) {
            List<Map<String, Object>> sideboard = new ArrayList<>();
            for (CardView card : player.getSideboard().values()) {
                sideboard.add(serializeCard(card));
            }
            p.put("sideboard", sideboard);
        }

        return p;
    }

    // ── Card serialization (normalizeCard fields) ──

    private static Map<String, Object> serializeCard(CardView card) {
        Map<String, Object> c = new HashMap<>();

        // StackAbilityView or AbilityView: the stack item is an activated/triggered
        // ability. Both extend CardView but name="Ability", and have a sourceCard
        // with the real card name and image. Extract it so the web client
        // can render the source card's art.
        CardView sourceCard = null;
        String sourceName = null;
        if (card instanceof mage.view.StackAbilityView) {
            mage.view.StackAbilityView sav = (mage.view.StackAbilityView) card;
            sourceCard = sav.getSourceCard();
            sourceName = sourceCard != null ? sourceCard.getName() : "Unknown";
        } else if (card instanceof AbilityView) {
            AbilityView av = (AbilityView) card;
            sourceCard = av.getSourceCard();
            sourceName = sourceCard != null ? sourceCard.getName() : "Unknown";
        }

        if (sourceName != null) {
            c.put("name", sourceName);
            c.put("imageUrl", buildImageUrl(sourceName));
            c.put("typeLine", sourceCard != null ? sourceCard.getTypeText() : "");
            c.put("manaCost", card.getManaCostStr());
            c.put("rules", card.getRules());
            c.put("id", card.getId() != null ? card.getId().toString() : null);
            markAbility(c, card, sourceName);

            // P/T from source card
            if (sourceCard != null && sourceCard.getPower() != null && !sourceCard.getPower().isEmpty()) {
                c.put("power", sourceCard.getPower());
            }
            if (sourceCard != null && sourceCard.getToughness() != null && !sourceCard.getToughness().isEmpty()) {
                c.put("toughness", sourceCard.getToughness());
            }

            // Targets
            if (card.getTargets() != null && !card.getTargets().isEmpty()) {
                List<String> targetIds = new ArrayList<>();
                for (UUID targetId : card.getTargets()) {
                    targetIds.add(targetId.toString());
                }
                c.put("targets", targetIds);
            }

            return c;
        }

        c.put("name", card.getName());
        // Face down (2026-10-04, official card back): the engine already
        // sanitises a face-down card's visible name to "Morph"/"Manifest"/
        // "Cloak"/"Disguise"/"Face Down" (CardView's FACE DOWN block, opposed
        // view) or "Morph: RealName" (controller view). Emitted honestly for
        // every CardView (battlefield permanents, stack items, looked-at
        // cards) so the frontend can paint the REAL card back instead of the
        // text-fallback box. imageUrl is dropped even though the offline map
        // INDEXES these names: the indexed "Morph"/"Manifest"/"Foretell"
        // entries resolve to uuids that 404 on the CDN (measured 2026-10-04),
        // so the map-hit is a dead URL, never art. Gson's default omission of
        // null map values means "nilled" = key absent on the wire; the
        // frontend treats an undefined imageUrl identically. The frontend's
        // faceDown branch covers the art instead. No name leak: the
        // non-controller view never carries the real name in `name`.
        if (card.isFaceDown()) {
            c.put("faceDown", true);
            c.put("imageUrl", null);
        } else {
            c.put("imageUrl", buildImageUrl(card.getName()));
        }
        c.put("typeLine", card.getTypeText());
        c.put("manaCost", card.getManaCostStr());
        c.put("rules", card.getRules());
        c.put("id", card.getId() != null ? card.getId().toString() : null);

        // Stack ability fallback: card.isAbility() true but no sourceCard was
        // extracted above (not a StackAbilityView/AbilityView). Use the card's
        // own name as the source. Shares markAbility() with the branch above.
        if (card.isAbility()) {
            markAbility(c, card, card.getName());
        }

        // P/T
        if (card.getPower() != null && !card.getPower().isEmpty()) {
            c.put("power", card.getPower());
        }
        if (card.getToughness() != null && !card.getToughness().isEmpty()) {
            c.put("toughness", card.getToughness());
        }

        // Loyalty
        if (card.getLoyalty() != null && !card.getLoyalty().isEmpty()) {
            c.put("loyalty", card.getLoyalty());
        }

        // Defense (battles)
        if (card.getDefense() != null && !card.getDefense().isEmpty()) {
            c.put("defense", card.getDefense());
        }

        // Alternate name (transform/MDFC/flip/meld) — the OTHER face's name,
        // used by the frontend's flip button to resolve that face's image
        // (Scryfall exact= resolves every alternate-face name).
        if (card.getAlternateName() != null && !card.getAlternateName().isEmpty()) {
            c.put("originalCard", card.getAlternateName());
        }

        // Second-face data for the frontend's flip button + flipped preview
        // (transformables, MDFCs, meld, flip cards). See serializeSecondFace().
        Map<String, Object> secondFace = serializeSecondFace(card);
        if (secondFace != null) {
            c.put("secondFace", secondFace);
        }

        // Copy
        if (card.isOriginalACopy()) {
            c.put("copy", true);
        }

        // Targets
        if (card.getTargets() != null && !card.getTargets().isEmpty()) {
            List<String> targetIds = new ArrayList<>();
            for (UUID targetId : card.getTargets()) {
                targetIds.add(targetId.toString());
            }
            c.put("targets", targetIds);
        }

        return c;
    }

    // ── Permanent serialization (extends card with battlefield-only fields) ──

    private static Map<String, Object> serializePermanent(PermanentView perm) {
        Map<String, Object> c = serializeCard(perm);

        c.put("tapped", perm.isTapped());
        c.put("damage", perm.getDamage());

        // Real mana-production data (engine-derived) for the frontend auto-tapper.
        // Only present when at least one plain "{T}: Add ..." mana ability was
        // characterised — absent means "unknown", and the frontend must never
        // auto-tap an unknown permanent.
        List<String> manaProduction = manaProductionOf(perm);
        if (manaProduction != null) {
            c.put("manaProduction", manaProduction);
        }

        // Counters
        if (perm.getCounters() != null && !perm.getCounters().isEmpty()) {
            Map<String, Integer> counters = new HashMap<>();
            for (CounterView counter : perm.getCounters()) {
                counters.put(counter.getName(), counter.getCount());
            }
            c.put("counters", counters);
        }

        // Summoning sickness
        if (perm.isCreature() && perm.hasSummoningSickness()) {
            c.put("summoningSick", true);
        }

        // Token
        if (perm.isToken()) {
            c.put("token", true);
            // Token printing identity — the engine's own choice of which set
            // and which image index within that set to render (set by
            // TokenImpl.generateTokenInfo from tokens-database.txt). The web
            // client needs BOTH because a token name is not addressable on
            // Scryfall: `named?exact=Soldier` resolves to an unrelated card and
            // `named?exact=Soldier Token` 404s. The exact-printing route is
            // `api.scryfall.com/cards/{t<set>}/{number}`.
            //
            // tokenSetCode is always present for a token (generateTokenInfo
            // throws rather than return null); tokenImageNumber is 0 when the
            // engine did not index this token's art — 0 is "unset", not "art 0".
            String tokenSetCode = perm.getExpansionSetCode();
            if (tokenSetCode != null && !tokenSetCode.isEmpty()) {
                c.put("tokenSetCode", tokenSetCode);
                c.put("tokenImageNumber", perm.getImageNumber());
            }
        }

        // Face down (2026-10-04, official card back) — see serializeCard's
        // face-down branch; nothing extra is needed here beyond what the card
        // layer already emits (PermanentView IS-A CardView and carries the
        // encrypted-engine faceDown flag).

        // Attached to
        if (perm.getAttachedTo() != null) {
            c.put("attachedTo", perm.getAttachedTo().toString());
        }

        // Transformed (back face up) — the frontend keeps rendering the
        // current face's art and needs the flag to know the card is on its
        // back face.
        if (perm.isTransformed()) {
            c.put("transformed", true);
        }

        // Original card (for modified permanents)
        CardView original = perm.getOriginal();
        if (original != null && !original.getName().equals(perm.getName())) {
            c.put("originalCard", original.getName());
        }

        return c;
    }

    // ── Ability marking (shared by both branches of serializeCard) ──

    /**
     * Serialize the OTHER face of a two-faced card (transform / MDFC / meld /
     * flip) so the web client can show a flip button and preview that face.
     *
     * Preference order: a real {@code secondCardFace} CardView (full data —
     * type line, rules, P/T) when one is present; else just the alternate
     * name (flip cards outside battlefield/stack set alternateName but leave
     * secondCardFace null; the frontend resolves the image from the name —
     * Scryfall's exact= lookup resolves every alternate-face name).
     *
     * Never returns a name-less map: a null/empty face name means "this
     * object has no other face" and the flip button must stay hidden
     * (Copies, transforms of copies etc.).
     *
     * @return map with name/imageUrl (+typeLine/manaCost/rules/power/
     *         toughness/loyalty/defense when known), or null when no other face
     */
    private static Map<String, Object> serializeSecondFace(CardView card) {
        mage.view.CardView second = card.getSecondCardFace();
        if (second != null && second.getName() != null && !second.getName().isEmpty()) {
            Map<String, Object> face = new HashMap<>();
            String faceName = second.getName();
            face.put("name", faceName);
            face.put("imageUrl", buildImageUrl(faceName));
            if (second.getTypeText() != null && !second.getTypeText().isEmpty()) {
                face.put("typeLine", second.getTypeText());
            }
            if (second.getManaCostStr() != null && !second.getManaCostStr().isEmpty()) {
                face.put("manaCost", second.getManaCostStr());
            }
            if (second.getRules() != null && !second.getRules().isEmpty()) {
                face.put("rules", second.getRules());
            }
            if (second.getPower() != null && !second.getPower().isEmpty()) {
                face.put("power", second.getPower());
            }
            if (second.getToughness() != null && !second.getToughness().isEmpty()) {
                face.put("toughness", second.getToughness());
            }
            if (second.getLoyalty() != null && !second.getLoyalty().isEmpty()) {
                face.put("loyalty", second.getLoyalty());
            }
            if (second.getDefense() != null && !second.getDefense().isEmpty()) {
                face.put("defense", second.getDefense());
            }
            return face;
        }
        // No secondCardFace but an alternate face name exists (flip cards in
        // zones CardView does not cover, e.g. hand): still show the flip
        // button, with image-only data.
        String alternateName = card.getAlternateName();
        if (alternateName != null && !alternateName.isEmpty()) {
            Map<String, Object> face = new HashMap<>();
            face.put("name", alternateName);
            face.put("imageUrl", buildImageUrl(alternateName));
            return face;
        }
        return null;
    }

    /**
     * Marks a serialized card map as a stack ability and adds the ability text
     * + source card name. Used by both the StackAbilityView/AbilityView branch
     * (which has a real sourceCard) and the card.isAbility() fallback branch
     * (which uses the card's own name). Consolidates the previously duplicated
     * isAbility/source_card/ability_text setup.
     */
    private static void markAbility(Map<String, Object> c, CardView card, String sourceName) {
        c.put("isAbility", true);
        c.put("source_card", sourceName);
        // Rules contain the ability text for stack abilities
        if (card.getRules() != null && !card.getRules().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String rule : card.getRules()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(rule);
            }
            c.put("ability_text", sb.toString());
        }
    }

    // ── Mana production characterisation ──

    private static final org.apache.log4j.Logger LOGGER = org.apache.log4j.Logger.getLogger(GameStateSerializer.class);

    /** Unrestricted "any colour" production text, e.g. "one mana of any color." / "three mana of any one color." */
    private static final java.util.regex.Pattern ANY_COLOR_MANA =
            java.util.regex.Pattern.compile("^(one|two|three|four|five)? ?mana of any( one)? color\\.?$");

    /** Mana pips in produced-mana text, e.g. "{W}", "{C}{C}". */
    private static final java.util.regex.Pattern MANA_PIP = java.util.regex.Pattern.compile("\\{([^}]+)\\}");

    /**
     * Card texts already reported as uncharacterisable, so the warn line fires once per
     * distinct ability text instead of on every state serialization (this runs per
     * permanent on every game update). Bounded like imageUrlCache.
     */
    private static final int MANA_WARN_CACHE_MAX = 200;
    private static final java.util.Set<String> manaWarned =
            java.util.Collections.newSetFromMap(
                new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> eldest) {
                        return size() > MANA_WARN_CACHE_MAX;
                    }
                });

    /**
     * Derive the colours this permanent's own mana abilities can actually produce,
     * from the engine's rendered ability text — the ONLY mana data a CardView /
     * PermanentView exposes.
     *
     * {@code CardView.rules} is the engine's own rule rendering for each of the
     * object's abilities (CardView's constructor fills it from {@code card.getRules(game)},
     * which renders every ability via {@code AbilityImpl.getRule()}). For a mana
     * ability that text is exactly {@code "{T}: Add {W}."} / {@code "{T}: Add one mana
     * of any color."} — verified against the built engine for basics, shocklands,
     * City of Brass, Gilded Lotus, Sol Ring, Wastes, Temple Garden, Watery Grave,
     * Vitu-Ghazi and Urborg. Nothing is re-derived from the card NAME (the previous
     * frontend approach: a basic-lands-only name table).
     *
     * Only abilities of the exact shape {@code "{T}: Add ..."} with NO other cost are
     * characterised — that is the only shape the auto-tapper may activate (tap alone).
     * Anything else (e.g. {@code "{2}, {T}: Add {B} for each Swamp"}, {@code "{T}, Pay 1
     * life: Add one mana of any color."}, {@code "{T}: Add one mana of any color in your
     * commander's color identity."}) requires an extra payment, a choice, or knowledge the
     * view does not carry, so it is deliberately NOT advertised: tapping cannot be known
     * to pay it.
     *
     * The characterisation UNDER-approximates on purpose. Text it cannot read exactly is
     * reported once (WARN) and contributes nothing, never a guess — the permanent then
     * advertises less than it can do, which is the safe direction: the frontend only taps
     * a permanent that is KNOWN to produce a needed colour.
     *
     * @return sorted colour letters ("W","U","B","R","G","C"), or null when no
     *         tap-only mana ability could be characterised — i.e. "unknown", which the
     *         frontend treats as "never auto-tap".
     */
    private static List<String> manaProductionOf(PermanentView perm) {
        return characteriseManaAbilities(
                perm.getRules(), perm.getName(), perm.getId() == null ? "?" : perm.getId().toString());
    }

    /**
     * Pure text characterisation of a permanent's abilities. Split out from
     * {@link #manaProductionOf(PermanentView)} so it can be exercised directly with
     * engine-rendered rule strings (no Game/Permanent graph required) — the only
     * correct way to test this, since {@code {T}}: mana text is the entire input.
     *
     * @param rules engine-rendered rules for the permanent (CardView.getRules())
     * @param name  permanent name, for diagnostics only — never used for the decision
     * @param id    permanent id, for diagnostics only
     * @return sorted colour letters, or null for "unknown" (never auto-tap)
     */
    static List<String> characteriseManaAbilities(List<String> rules, String name, String id) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        java.util.TreeSet<String> colors = new java.util.TreeSet<>();
        for (String rule : rules) {
            if (rule == null) {
                continue;
            }
            String text = rule.trim();
            // Only a pure "{T}: Add ..." activation is auto-tappable.
            String prefix = "{T}: ";
            if (!text.startsWith(prefix)) {
                continue;
            }
            String body = text.substring(prefix.length()).trim();
            if (!body.toLowerCase().startsWith("add ")) {
                continue;
            }
            String produced = body.substring(4).trim();

            // Unrestricted "any colour" production.
            if (ANY_COLOR_MANA.matcher(produced.toLowerCase()).matches()) {
                colors.add("W");
                colors.add("U");
                colors.add("B");
                colors.add("R");
                colors.add("G");
                continue;
            }

            // Plain pips: {W}, {U}{U}, {C}{C}, {W}{R} ...
            // Hybrid ({W/U}), phyrexian ({W/P}), snow ({S}), generic ({2}) and dynamic
            // text are NOT advertised — they need a choice or cannot be known from text.
            java.util.regex.Matcher m = MANA_PIP.matcher(produced);
            boolean sawPip = false;
            while (m.find()) {
                String symbol = m.group(1);
                if (symbol.matches("[WUBRG]")) {
                    colors.add(symbol);
                    sawPip = true;
                } else if (symbol.equals("C")) {
                    colors.add("C");
                    sawPip = true;
                }
            }
            if (!sawPip) {
                // Report once per distinct text; the permanent is simply not auto-tappable.
                if (manaWarned.add(name + " | " + rule)) {
                    LOGGER.warn("Mana ability text not auto-tappable (uncharacterisable, will never be"
                            + " auto-tapped): " + name + " [" + id + "] — " + rule);
                }
            }
        }
        if (colors.isEmpty()) {
            return null; // unknown — the frontend must never auto-tap this permanent
        }
        return new ArrayList<>(colors);
    }

    // ── Image URL construction ──

    /**
     * Resolve a card image URL from the offline Scryfall bulk-data map
     * (ScryfallImageMap), replacing the old per-card {@code api.scryfall.com}
     * named-query URL. The map is loaded at static-init; a MISS returns null
     * and the client renders the card-text placeholder instead of hitting the
     * rate-limited API.
     */
    public static String buildImageUrl(String cardName) {
        if (cardName == null || cardName.isEmpty()) {
            return null;
        }
        return imageUrlCache.computeIfAbsent(cardName, name -> {
            String uuid = ScryfallImageMap.cardUuid(name);
            return uuid != null ? ScryfallImageMap.cardUrl(uuid, "small", false) : null;
        });
    }

    /**
     * Map a Java {@link TurnPhase} enum to the short string the frontend expects.
     *
     * The frontend's {@code Phase} type (see enums.ts) uses abbreviated values:
     * M1 (precombat main), C1 (combat), M2 (postcombat main), E1 (end),
     * C2 (cleanup), M3 (not used by Java), E2 (not used by Java).
     * The Java enum uses PRECOMBAT_MAIN / COMBAT / POSTCOMBAT_MAIN / END / BEGINNING.
     * Without this mapping, the frontend receives "PRECOMBAT_MAIN" but expects "M1",
     * causing the phase indicator to fail to highlight the current phase.
     *
     * @param phase the Java TurnPhase, or null
     * @return the frontend phase string ("M1", "C1", "M2", "E1"), or null if phase is null
     */
    private static String mapPhaseToClient(TurnPhase phase) {
        if (phase == null) return null;
        switch (phase) {
            case BEGINNING:       return null; // Beginning phase is transient; frontend doesn't model it
            case PRECOMBAT_MAIN:   return "M1";
            case COMBAT:           return "C1";
            case POSTCOMBAT_MAIN:  return "M2";
            case END:              return "E1";
            default:               return null;
        }
    }
}