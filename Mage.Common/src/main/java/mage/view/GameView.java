package mage.view;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import mage.MageObject;
import mage.abilities.costs.Cost;
import mage.cards.Card;
import mage.constants.PhaseStep;
import mage.constants.TurnPhase;
import mage.constants.Zone;
import mage.designations.Designation;
import mage.game.ExileZone;
import mage.game.Game;
import mage.game.GameState;
import mage.game.combat.CombatGroup;
import mage.game.command.Dungeon;
import mage.game.command.Emblem;
import mage.game.command.Plane;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentCard;
import mage.game.permanent.PermanentToken;
import mage.game.stack.Spell;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.players.PlayableObjectsList;
import mage.players.Player;
import mage.util.CardUtil;
import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author BetaSteward_at_googlemail.com, JayDi85
 */
public class GameView implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(GameView.class);

    // enable special cache to protect from non-game calls, e.g. fix/concede/watch/etc
    // TODO: implement RepeatedGameView to send back ref number instead full game view on non-changeable
    public static final boolean ENABLE_GAME_VIEW_CACHE = true;

    // test only: how many views were built, see GameViewTest
    public static final AtomicLong CREATED_COUNT = new AtomicLong();

    private final int priorityTime;
    private final int bufferTime;
    private final List<PlayerView> players = new ArrayList<>();
    private UUID myPlayerId = null; // null for watcher
    private final CardsView myHand = new CardsView();
    private final CardsView myHelperEmblems = new CardsView();
    private PlayableObjectsList canPlayObjects;
    private final Map<String, SimpleCardsView> opponentHands = new HashMap<>();
    private final Map<String, SimpleCardsView> watchedHands = new HashMap<>();
    private final CardsView stack = new CardsView();
    private final List<ExileView> exiles = new ArrayList<>();
    private final List<RevealedView> revealed = new ArrayList<>();
    private final List<LookedAtView> lookedAt = new ArrayList<>();
    private final List<RevealedView> companion = new ArrayList<>();
    private final List<CombatGroupView> combat = new ArrayList<>();
    private final TurnPhase phase;
    private final PhaseStep step;
    private final UUID activePlayerId;
    private String activePlayerName = "";
    private final String priorityPlayerName;
    private final int turn;
    private boolean special = false;
    private final boolean rollbackTurnsAllowed;

    // for debug only
    // TODO: implement and support in admin tools
    private int totalErrorsCount;
    private int totalEffectsCount;
    private List<LLMConversationView> llmDebug; // null unless LLM players present
    private int gameCycle;

    /**
     * Index of the replay state this view was built from
     * (GameReplay.getCurrentStateIndex()); -1 for live-game views.
     * Web-only: the desktop client never reads it. Additive 2026-09-20 so the
     * web replay viewer can show a true position indicator instead of a
     * delivery counter (a replay_update arrives on next AND previous).
     */
    private int replayStateIndex = -1;

    public int getReplayStateIndex() {
        return this.replayStateIndex;
    }

    public void setReplayStateIndex(int idx) {
        this.replayStateIndex = idx;
    }

    /**
     * Total number of states in the saved replay, so a viewer can size its
     * position control without knowing the save's contents.
     * Web-only, additive: the desktop client never reads it (same pattern as
     * replayStateIndex). -1 when the view was not built from a replay.
     */
    private int replayStateCount = -1;

    public int getReplayStateCount() {
        return this.replayStateCount;
    }

    /**
     * The replay game log (flat shape) + this frame's slice end, so a replay
     * viewer can show "the log at frame N" without any per-frame log duplication
     * in the save (measured:
     * flat costs +0.10% where per-frame costs +2.46%).
     *
     * The log is the saved game's FINAL log, sent once with every replay frame;
     * {@code replayGameLogCount} is how many of its entries existed at THIS
     * frame — the viewer renders entries 0..count-1. Both are -1/empty in live
     * GameViews (the live channel has its own `event` frames and never reads
     * these). The desktop client ignores them, like replayStateIndex.
     */
    private List<String> replayGameLog = Collections.emptyList();
    private int replayGameLogCount = -1;

    public List<String> getReplayGameLog() {
        return this.replayGameLog;
    }

    public void setReplayGameLog(List<String> log) {
        this.replayGameLog = log != null ? log : Collections.emptyList();
    }

    public int getReplayGameLogCount() {
        return this.replayGameLogCount;
    }

    public void setReplayGameLogCount(int count) {
        this.replayGameLogCount = count;
    }

    public void setReplayStateCount(int count) {
        this.replayStateCount = count;
    }

    /**
     * SKIPPED-RANGE LLM buckets for the web replay viewer

     *
     * A replay frame's own `llmDebug` carries only the exchanges recorded at
     * THIS frame's state index. Sequential playback delivers every index, so
     * the client's per-index accumulation stays complete — but replay_skip (10
     * moves) and replay_seek jump in ONE server-side step (ReplaySession
     * .next(int)/seek → one updateGame), so the buckets of the skipped indices
     * never reach the client and the viewer's cumulative history is missing
     * exactly those exchanges.
     *
     * ReplaySession attaches this map on a forward jump: buckets for the state
     * indices in (lastDeliveredIndex, currentIndex), each keyed by its TRUE
     * state index, so the client merges them idempotently into its per-index
     * store (a re-delivered index overwrites its own key — no duplicates).
     * Null for live views and for frames that skipped nothing (additive: an
     * older proxy/client simply never sees the field).
     *
     * Type note: Map<Integer, List<LLMConversationView>> — TreeMap order (state
     * index ascending) is part of the contract so the wire payload is
     * deterministic. The desktop client ignores it, like replayStateIndex.
     */
    private Map<Integer, List<LLMConversationView>> replayLLMBuckets;

    public Map<Integer, List<LLMConversationView>> getReplayLLMBuckets() {
        return replayLLMBuckets;
    }

    public void setReplayLLMBuckets(Map<Integer, List<LLMConversationView>> buckets) {
        this.replayLLMBuckets = buckets;
    }

    /**
     * Does the replay's GAME have at least one LLM player seat? Frame-independent
     * (computed once from the deserialized game's players, not from any frame's
     * own llmDebug), so the web viewer's empty-state copy can distinguish "this
     * game has no LLM" from "this game has LLM but this frame carries no
     * exchange".
     * Null on live views and on saves/replays from before this field; the viewer
     * then falls back to the exchange wording, never the live-only claim.
     * Additive, like replayStateIndex — the desktop client ignores it.
     */
    private Boolean replayHasLlmPlayers;

    public Boolean getReplayHasLlmPlayers() {
        return replayHasLlmPlayers;
    }

    public void setReplayHasLlmPlayers(Boolean hasLlmPlayers) {
        this.replayHasLlmPlayers = hasLlmPlayers;
    }

    public GameView(GameState state, Game game, UUID createdForPlayerId, UUID watcherUserId) {
        if (state == null) {
            // Fail Fast: a null/corrupt GameState would NPE at state.getPlayers() below
            // with no useful diagnostic. Surface the problem immediately.
            throw new IllegalStateException("GameView constructed with null state — likely corrupt replay");
        }
        // debug only
        // LOGGER.info("GameView create: game cycle " + game.getState().getApplyEffectsCounter()
        //     + ", player " + createdForPlayerId
        //     + ", watcher " + watcherUserId
        //     + ", thread " + Thread.currentThread().getName()
        // );
        CREATED_COUNT.incrementAndGet();

        Player createdForPlayer = null;
        this.priorityTime = game.getPriorityTime();
        this.bufferTime = game.getBufferTime();

        for (Player player : state.getPlayers().values()) {
            PlayerView playerView = new PlayerView(player, state, game, createdForPlayerId, watcherUserId);
            players.add(playerView);
            if (player.getId().equals(createdForPlayerId)) {
                createdForPlayer = player;
                this.myPlayerId = player.getId();
                this.myHand.putAll(new CardsView(game, player.getHand().getCards(game), createdForPlayerId));
                state.getHelperEmblems().stream()
                        .filter(emblem -> emblem.isControlledBy(player.getId()))
                        .forEach(emblem -> {
                            this.myHelperEmblems.put(emblem.getId(), new CardView(new EmblemView(emblem, game)));
                        });
            }
        }
        for (StackObject stackObject : state.getStack()) {
            if (stackObject instanceof Spell) {
                // Spell
                Spell spell = (Spell) stackObject;
                CardView spellView = new CardView(spell, game, CardUtil.canShowAsControlled(spell, createdForPlayerId));
                spellView.paid = spell.getSpellAbility().getManaCostsToPay().isPaid();
                stack.put(spell.getId(), spellView);
            } else if (stackObject instanceof StackAbility) {
                // Stack Ability
                MageObject object = game.getObject(stackObject.getSourceId());
                Card card = game.getCard(stackObject.getSourceId());
                if (card == null && (object instanceof PermanentCard)) {
                    card = ((PermanentCard) object).getCard();
                }
                if (card != null) {
                    if (object != null) {
                        if (object instanceof Permanent) {
                            boolean controlled = ((Permanent) object).getControllerId().equals(createdForPlayerId);
                            stack.put(stackObject.getId(), new StackAbilityView(game, (StackAbility) stackObject, object.getName(), object, new CardView(((Permanent) object), game, controlled, false)));
                        } else {
                            stack.put(stackObject.getId(), new StackAbilityView(game, (StackAbility) stackObject, card.getName(), card, new CardView(card, game, false, false)));
                        }
                    } else {
                        stack.put(stackObject.getId(), new StackAbilityView(game, (StackAbility) stackObject, "", card, new CardView(card, game)));
                    }
                    if (card.isTransformable()) {
                        updateLatestCardView(game, card, stackObject.getId());
                    }
                    checkPaid(stackObject.getId(), (StackAbility) stackObject);
                } else if (object != null) {
                    if (object instanceof PermanentToken) {
                        PermanentToken token = (PermanentToken) object;
                        stack.put(stackObject.getId(), new StackAbilityView(game, (StackAbility) stackObject, token.getName(), token, new CardView(token, game)));
                        checkPaid(stackObject.getId(), (StackAbility) stackObject);
                    } else if (object instanceof Emblem) {
                        CardView cardView = new CardView(new EmblemView((Emblem) object, game));
                        // Card sourceCard = (Card) ((Emblem) object).getSourceObject();
                        stackObject.setName(object.getName());
                        // ((StackAbility) stackObject).setExpansionSetCode(sourceCard.getExpansionSetCode());
                        stack.put(stackObject.getId(),
                                new StackAbilityView(game, (StackAbility) stackObject, object.getName(), object, cardView));
                        checkPaid(stackObject.getId(), ((StackAbility) stackObject));
                    } else if (object instanceof Dungeon) {
                        CardView cardView = new CardView(new DungeonView((Dungeon) object));
                        stackObject.setName(object.getName());
                        stack.put(stackObject.getId(),
                                new StackAbilityView(game, (StackAbility) stackObject, object.getName(), object, cardView));
                        checkPaid(stackObject.getId(), ((StackAbility) stackObject));
                    } else if (object instanceof Plane) {
                        CardView cardView = new CardView(new PlaneView((Plane) object, game));
                        stackObject.setName(object.getName());
                        stack.put(stackObject.getId(),
                                new StackAbilityView(game, (StackAbility) stackObject, object.getName(), object, cardView));
                        checkPaid(stackObject.getId(), ((StackAbility) stackObject));
                    } else if (object instanceof Designation) {
                        Designation designation = (Designation) game.getObject(object.getId());
                        if (designation != null) {
                            stack.put(stackObject.getId(), new StackAbilityView(game, (StackAbility) stackObject, designation.getName(), designation, new CardView(designation, (StackAbility) stackObject)));
                        } else {
                            throw new IllegalArgumentException("Designation object not found: " + object + " - " + object.getClass().toString());
                        }
                    } else if (object instanceof StackAbility) {
                        StackAbility stackAbility = ((StackAbility) object);
                        stackAbility.newId();
                        stack.put(stackObject.getId(), new CardView(stackObject, game));
                        checkPaid(stackObject.getId(), ((StackAbility) stackObject));
                    } else {
                        throw new IllegalArgumentException("Object can't be cast to StackAbility: " + object + " - " + object.getClass().toString());
                    }
                } else {
                    // can happen if a player times out while ability is on the stack
                    LOGGER.debug("Stack Object for stack ability not found: " + stackObject.getStackAbility().getRule());
                }
            } else if (stackObject != null) {
                throw new IllegalArgumentException("Unknown type of StackObject: " + stackObject + " - " + stackObject.getClass().toString());
            }
        }

        for (ExileZone exileZone : state.getExile().getExileZones()) {
            exiles.add(new ExileView(exileZone, game, createdForPlayerId));
        }
        for (String name : state.getRevealed().keySet()) {
            revealed.add(new RevealedView(name, state.getRevealed().get(name), game));
        }
        if (this.myPlayerId != null) {
            for (String name : state.getLookedAt(this.myPlayerId).keySet()){
                lookedAt.add(new LookedAtView(name, state.getLookedAt(this.myPlayerId).get(name), game));
            }
        }
        for (String name : state.getCompanion().keySet()) {
            // Only show the companion window when the companion is still outside the game.
            if (state.getCompanion().get(name).stream().anyMatch(cardId -> state.getZone(cardId) == Zone.OUTSIDE)) {
                companion.add(new RevealedView(name, state.getCompanion().get(name), game));
            }
        }
        this.phase = state.getTurnPhaseType();
        this.step = state.getTurnStepType();
        this.turn = state.getTurnNum();
        this.activePlayerId = state.getActivePlayerId();
        if (state.getActivePlayerId() != null) {
            this.activePlayerName = state.getPlayer(state.getActivePlayerId()).getName();
        } else {
            this.activePlayerName = "";
        }
        Player priorityPlayer = null;
        if (state.getPriorityPlayerId() != null) {
            priorityPlayer = state.getPlayer(state.getPriorityPlayerId());
            this.priorityPlayerName = priorityPlayer != null ? priorityPlayer.getName() : "";
        } else {
            this.priorityPlayerName = "";
        }
        for (CombatGroup combatGroup : state.getCombat().getGroups()) {
            combat.add(new CombatGroupView(combatGroup, game));
        }
        if (this.myPlayerId != null) { // no watcher
            // has only to be set for active player with priority (e.g. pay mana by delve or Quenchable Fire special action)
            if (priorityPlayer != null && createdForPlayer != null && createdForPlayer.isGameUnderControl()
                    && (createdForPlayerId.equals(priorityPlayer.getId()) // player controls the turn
                    || createdForPlayer.getPlayersUnderYourControl().contains(priorityPlayer.getId()))) { // player controls active players turn
                this.special = !state.getSpecialActions().getControlledBy(priorityPlayer.getId(), priorityPlayer.isInPayManaMode()).isEmpty();
            }
        } else {
            this.special = false;
        }
        this.rollbackTurnsAllowed = game.getOptions().rollbackTurnsAllowed;
        this.totalErrorsCount = game.getTotalErrorsCount();
        this.totalEffectsCount = game.getTotalEffectsCount();
        this.gameCycle = game.getState().getApplyEffectsCounter();
    }

    private void checkPaid(UUID uuid, StackAbility stackAbility) {
        for (Cost cost : stackAbility.getManaCostsToPay()) {
            if (!cost.isPaid()) {
                return;
            }
        }
        CardView cardView = stack.get(uuid);
        cardView.paid = true;
    }

    private void updateLatestCardView(Game game, Card card, UUID stackId) {
        if (!card.isTransformable()) {
            return;
        }
        Permanent permanent = game.getPermanent(card.getId());
        if (permanent == null) {
            permanent = (Permanent) game.getLastKnownInformation(card.getId(), Zone.BATTLEFIELD);
        }
        if (permanent != null) {
            if (permanent.isTransformed()) {
                StackAbilityView stackAbilityView = (StackAbilityView) stack.get(stackId);
                stackAbilityView.getSourceCard().setTransformed(true);
            }
        }
    }

    public List<PlayerView> getPlayers() {
        return players;
    }

    public CardsView getMyHand() {
        return myHand;
    }

    public CardsView getMyHelperEmblems() {
        return myHelperEmblems;
    }

    public PlayerView getMyPlayer() {
        if (this.myPlayerId == null) {
            return null;
        } else {
            return players.stream().filter(p -> p.getPlayerId().equals(this.myPlayerId)).findFirst().orElse(null);
        }
    }

    public Map<String, SimpleCardsView> getOpponentHands() {
        return opponentHands;
    }

    public Map<String, SimpleCardsView> getWatchedHands() {
        return watchedHands;
    }

    public TurnPhase getPhase() {
        return phase;
    }

    public PhaseStep getStep() {
        return step;
    }

    public CardsView getStack() {
        return stack;
    }

    public List<ExileView> getExile() {
        return exiles;
    }

    public List<RevealedView> getRevealed() {
        return revealed;
    }

    public List<LookedAtView> getLookedAt() {
        return lookedAt;
    }

    public List<RevealedView> getCompanion() {
        return companion;
    }

    public List<CombatGroupView> getCombat() {
        return combat;
    }

    public int getTurn() {
        return this.turn;
    }

    public String getActivePlayerName() {
        return activePlayerName;
    }

    public String getPriorityPlayerName() {
        return priorityPlayerName;
    }

    public boolean getSpecial() {
        return special;
    }

    public int getPriorityTime() {
        return priorityTime;
    }

    public int getBufferTime() {
        return bufferTime;
    }

    public UUID getActivePlayerId() {
        return activePlayerId;
    }

    public boolean isPlayer() {
        return this.myPlayerId != null;
    }

    public PlayableObjectsList getCanPlayObjects() {
        return canPlayObjects;
    }

    public void setCanPlayObjects(PlayableObjectsList canPlayObjects) {
        this.canPlayObjects = canPlayObjects;
    }

    public boolean isRollbackTurnsAllowed() {
        return rollbackTurnsAllowed;
    }

    public String toJson() {
        Gson gson = new GsonBuilder().create();
        return gson.toJson(this);
    }

    public int getTotalErrorsCount() {
        return this.totalErrorsCount;
    }

    public List<LLMConversationView> getLLMDebug() {
        if (llmDebug == null) {
            return null;
        }
        return Collections.unmodifiableList(llmDebug);
    }

    public void setLLMDebug(List<LLMConversationView> v) {
        this.llmDebug = v;
    }

    /**
     * Match-wide flag: may opponents/spectators see LLM chat during live play?
     * Replays set this true unconditionally (nothing is live to leak). Each live
     * session sets it from the match's options when building the GameView.
     */
    private boolean llmChatPublic = false;

    public boolean isLlmChatPublic() {
        return llmChatPublic;
    }

    public void setLlmChatPublic(boolean llmChatPublic) {
        this.llmChatPublic = llmChatPublic;
    }

    public int getTotalEffectsCount() {
        return this.totalEffectsCount;
    }

    public int getGameCycle() {
        return this.gameCycle;
    }
}
