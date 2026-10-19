package mage.client.table;

import mage.cards.decks.P1DeckPool;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Desktop-client picker for a {@code COMPUTER_P1} seat's deck: the ONLY decks
 * offerable are the 55 Standard decklists the Argentum-P1 policy net was
 * trained on (see {@link P1DeckPool}).
 *
 * <p>{@link NewPlayerPanel} shows the "Training deck…" button when the seat
 * type is {@code COMPUTER_P1}. Picking a name writes the canonical decklist to a
 * {@code .txt} file and returns its path — the same shape the existing deck
 * field carries (the desktop joins AI seats via
 * {@code DeckImporter.importDeckFromFile(path)}), so no join-path change is
 * needed and the pool is the single source of truth.
 *
 * <p>The server is still authoritative: any hand-edited path that is not one of
 * the 55 lists is rejected at join with a clear message (TableController
 * P1DeckRestriction).
 */
final class P1TrainingDeckPicker {

    /**
     * The pool's canonical text materialized to a {@code .txt} file the desktop
     * deck importer understands. Returns the file path, or null on failure.
     */
    static String writeDeckTextFile(String deckName) {
        String text = P1DeckPool.instance().deckText(deckName);
        if (text == null) {
            return null;
        }
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "xmage-p1-decks");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            String safe = deckName.replaceAll("[^A-Za-z0-9._-]", "_");
            File out = new File(dir, safe + ".txt");
            Files.write(out.toPath(), text.getBytes(StandardCharsets.UTF_8));
            return out.getPath();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Modal picker over the 55 training deck names. Returns the chosen deck's
     * file path, or null when cancelled.
     */
    static String pick(java.awt.Component parent) {
        List<String> names = P1DeckPool.instance().deckNames();
        JComboBox<String> combo = new JComboBox<>(names.toArray(new String[0]));
        combo.setSelectedItem(P1DeckPool.instance().defaultDeckName());
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(new JLabel("Argentum-P1 training decks (" + names.size() + "):"));
        panel.add(combo);
        panel.add(new JLabel("<html><i>The P1 net may only face the decks it was trained on.</i></html>"));
        int ret = JOptionPane.showConfirmDialog(parent, panel,
                "Select a P1 training deck", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ret != JOptionPane.OK_OPTION) {
            return null;
        }
        Object sel = combo.getSelectedItem();
        if (sel == null) {
            return null;
        }
        return writeDeckTextFile(sel.toString());
    }

    private P1TrainingDeckPicker() {
    }
}
