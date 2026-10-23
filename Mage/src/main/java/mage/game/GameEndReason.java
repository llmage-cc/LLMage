package mage.game;

/**
 * Values of {@link GameState#getEndReason()} — why a game ended, for the seat
 * this end-of-game view belongs to.
 * <p>
 * Deliberately plain Strings, not an enum: the value rides the .game save and
 * the END_GAME_INFO RPC payload, and both ends may deserialize it with a build
 * older than the producer. An unknown enum constant fails to deserialize; an
 * unknown String field is skipped by name when the serialVersionUID matches
 * (GameState and GameEndView both pin theirs).
 * <p>
 * {@link #UNKNOWN} is the absence default: a played-out win or a draw records
 * nothing. The engine sets a reason only when something other than playing the
 * game ended it.
 */
public final class GameEndReason {

    /** LLMPlayer.concedeOnTerminalFailure — the seat's provider failed for good. */
    public static final String LLM_FAILURE = "llm_failure";

    /** A player conceded: the concede button, quit, timeout, idle or a forced concede. */
    public static final String CONCEDE = "concede";

    /** The seat ran out of priority time (Player.hasTimerTimeout). */
    public static final String TIMEOUT = "timeout";

    /** The seat lost for being idle (Player.hasIdleTimeout). */
    public static final String IDLE = "idle";

    /** The seat quit the match (MatchPlayer.hasQuit). */
    public static final String QUIT = "quit";

    /** Nothing was recorded: a played-out win/draw, an old save, or an old producer. */
    public static final String UNKNOWN = "unknown";

    private GameEndReason() {
    }

    /**
     * Reason for a SUBJECT player (the player whose view this is).
     * <p>
     * The match-level flags are authoritative where they are set — they are
     * copied from the same Player the view reads, at game end — so they win over
     * the reason recorded on the state (which a timeout/idle/quit seat shares
     * with an ordinary concede, because all of them enter through
     * PlayerImpl.concede). Then the recorded reason, then {@link #UNKNOWN}.
     *
     * @param timerTimeout the subject player's timer-timeout flag
     * @param idleTimeout  the subject player's idle-timeout flag
     * @param quit         the subject player's MatchPlayer quit flag
     * @param recorded     GameState.getEndReason(), may be null
     */
    public static String resolve(boolean timerTimeout, boolean idleTimeout, boolean quit, String recorded) {
        if (timerTimeout) {
            return TIMEOUT;
        }
        if (idleTimeout) {
            return IDLE;
        }
        if (quit) {
            return QUIT;
        }
        return recorded != null ? recorded : UNKNOWN;
    }
}
