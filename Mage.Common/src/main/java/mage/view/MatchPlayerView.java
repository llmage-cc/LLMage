package mage.view;

import java.io.Serializable;

/**
 * One per-player row of a finished match, for the web client's replay browser
 * (searchable/sortable list). Lightweight on purpose — the desktop client
 * ignores it; the existing {@code players}/{@code result} display strings are
 * untouched.
 *
 * Matches what MatchView.initMatchTable can reach at construction time:
 * name, per-player match wins, quit flag, and the deck's NAME (the desktop
 * deliberately hides deck contents from opponents via getDeckForViewer — the
 * name is safe to expose on a FINISHED match; Tiny Leaders' viewerDeck name
 * masking applies to live decks, not to this summary row).
 */
public class MatchPlayerView implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final int wins;
    private final boolean quit;
    private final String deckName;

    public MatchPlayerView(String name, int wins, boolean quit, String deckName) {
        this.name = name;
        this.wins = wins;
        this.quit = quit;
        this.deckName = deckName;
    }

    public String getName() {
        return name;
    }

    public int getWins() {
        return wins;
    }

    public boolean hasQuit() {
        return quit;
    }

    public String getDeckName() {
        return deckName;
    }
}
