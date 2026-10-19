package mage.client.dialog;

import mage.client.MageFrame;
import mage.client.components.MageDesktopIconifySupport;
import mage.view.LLMConversationView;
import mage.view.LLMConversationView.LLMMessageView;
import mage.view.GameView;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LLMDebugDialog extends MageDialog implements MageDesktopIconifySupport {

    private final JTabbedPane tabbedPane;
    private volatile int lastUpdateCount = -1;
    private volatile String lastContentKey = "";

    // Keeps JTextArea instances stable across updates so scroll/caret position is preserved.
    // Keyed by player name + model name (same as tab title without streaming prefix).
    private final Map<String, JTextArea> textAreas = new HashMap<>();

    // Streaming indicator — pulsing dot chars
    private static final String STREAMING_PREFIX = "● ";
    private static final String BLANK_PREFIX = "  ";

    public LLMDebugDialog() {
        setTitle("LLM History");
        setModal(false);
        setClosable(true);
        setResizable(true);
        setMaximizable(true);

        getContentPane().removeAll();
        getContentPane().setLayout(new BorderLayout());

        tabbedPane = new JTabbedPane();
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        getContentPane().add(tabbedPane, BorderLayout.CENTER);

        setSize(640, 500);
        makeWindowCentered();
    }

    public void updateFromGameView(GameView gameView) {
        List<LLMConversationView> debugList = gameView.getLLMDebug();
        if (debugList == null || debugList.isEmpty()) {
            return;
        }

        int totalMsgs = debugList.stream().mapToInt(d -> d.messages.size()).sum();
        boolean anyStreaming = debugList.stream().anyMatch(d -> d.streaming);

        // Always update when streaming (partial tokens arriving) or when msg count changes.
        // During replay, consecutive states may have the same msg count but different content,
        // so also check if the player/exchange changed by comparing the first message content.
        String firstContent = debugList.get(0).messages.isEmpty() ? "" : debugList.get(0).messages.get(0).content;
        String contentKey = firstContent;
        if (!anyStreaming && totalMsgs == lastUpdateCount && contentKey.equals(lastContentKey)) return;
        lastUpdateCount = totalMsgs;
        lastContentKey = contentKey;

        SwingUtilities.invokeLater(() -> rebuildTabs(debugList));
    }

    private void rebuildTabs(List<LLMConversationView> debugList) {
        int selectedIndex = tabbedPane.getSelectedIndex();

        // Build the set of keys that should exist after this update.
        java.util.Set<String> activeKeys = new java.util.HashSet<>();
        for (LLMConversationView data : debugList) {
            String key = data.playerName + " (" + data.modelName + ")";
            activeKeys.add(key);

            // Tab title with streaming indicator
            String tabTitle = key;
            if (data.streaming) {
                tabTitle = "● " + tabTitle;
            }

            // Find or create the text area for this conversation
            JTextArea textArea = textAreas.get(key);
            if (textArea == null) {
                // New tab — create the text area and panel
                textArea = new JTextArea();
                textArea.setEditable(false);
                textArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
                textArea.setLineWrap(true);
                textArea.setWrapStyleWord(false);
                textAreas.put(key, textArea);

                JPanel panel = new JPanel(new BorderLayout());

                // Header with streaming indicator
                JPanel headerPanel = new JPanel(new BorderLayout());
                updateHeader(headerPanel, data);
                panel.add(headerPanel, BorderLayout.NORTH);

                JScrollPane scroll = new JScrollPane(textArea);
                panel.add(scroll, BorderLayout.CENTER);

                tabbedPane.addTab(tabTitle, panel);
            } else {
                // Existing tab — update title and header in place
                int idx = findTabIndex(key);
                if (idx >= 0) {
                    tabbedPane.setTitleAt(idx, tabTitle);
                    // Update the header panel (streaming indicator, elapsed time, context info)
                    JPanel panel = (JPanel) tabbedPane.getComponentAt(idx);
                    if (panel != null) {
                        JPanel headerPanel = (JPanel) panel.getComponent(0);
                        if (headerPanel != null) {
                            headerPanel.removeAll();
                            updateHeader(headerPanel, data);
                            headerPanel.revalidate();
                            headerPanel.repaint();
                        }
                    }
                }
            }

            // Build the text content
            StringBuilder sb = new StringBuilder();
            int msgNum = 0;

            // Existing messages
            for (LLMMessageView msg : data.messages) {
                msgNum++;
                sb.append("═══════════════ #").append(msgNum)
                  .append(" [").append(msg.role.toUpperCase()).append("] ═══════════════\n");
                sb.append(msg.content).append("\n\n");
            }

            // Streaming content — appended live below all existing messages
            if (data.streaming) {
                msgNum++;
                sb.append("═══════════════ #").append(msgNum)
                  .append(" [STREAMING] ═══════════════\n");

                if (data.streamingReasoning != null && !data.streamingReasoning.isEmpty()) {
                    sb.append("--- THINKING ---\n");
                    sb.append(data.streamingReasoning);
                    sb.append("\n\n");
                }

                if (data.streamingContent != null && !data.streamingContent.isEmpty()) {
                    sb.append("--- ANSWER ---\n");
                    sb.append(data.streamingContent);
                    sb.append("\n\n");
                }

                // If neither has arrived yet, show a placeholder
                if ((data.streamingReasoning == null || data.streamingReasoning.isEmpty())
                        && (data.streamingContent == null || data.streamingContent.isEmpty())) {
                    sb.append("(Waiting for first token...)\n\n");
                }

                sb.append("[STREAM IN PROGRESS — partial output above]\n\n");
            }

            // Update text in place — preserve scroll/caret for non-streaming tabs
            int currentCaret = textArea.getCaretPosition();
            textArea.setText(sb.toString());

            // Auto-scroll to bottom when streaming, preserve position otherwise
            if (data.streaming) {
                textArea.setCaretPosition(textArea.getDocument().getLength());
            } else {
                // Restore caret to the same position (or clamp if text shrank)
                int docLen = textArea.getDocument().getLength();
                if (currentCaret > docLen) {
                    currentCaret = docLen;
                }
                textArea.setCaretPosition(currentCaret);
            }
        }

        // Remove tabs that no longer have data
        for (String key : new java.util.ArrayList<>(textAreas.keySet())) {
            if (!activeKeys.contains(key)) {
                int idx = findTabIndex(key);
                if (idx >= 0) {
                    tabbedPane.removeTabAt(idx);
                }
                textAreas.remove(key);
            }
        }

        // Auto-select the streaming tab if any bot is currently streaming
        int streamingIdx = -1;
        for (LLMConversationView data : debugList) {
            if (data.streaming) {
                String key = data.playerName + " (" + data.modelName + ")";
                streamingIdx = findTabIndex(key);
                break;
            }
        }
        if (streamingIdx >= 0) {
            tabbedPane.setSelectedIndex(streamingIdx);
        } else if (selectedIndex >= 0 && selectedIndex < tabbedPane.getTabCount()) {
            tabbedPane.setSelectedIndex(selectedIndex);
        }
    }

    /**
     * Populates a header panel with streaming indicator, player/model info, context, and elapsed time.
     */
    private void updateHeader(JPanel headerPanel, LLMConversationView data) {
        String prefix = data.streaming ? STREAMING_PREFIX : BLANK_PREFIX;
        String ctxInfo = formatContext(data.totalContextChars, data.modelContextWindow);
        JLabel header = new JLabel(prefix
                + "Player: " + data.playerName
                + "  |  Model: " + data.modelName
                + "  |  Exchanges: " + data.exchangeCount
                + "  |  Context: " + ctxInfo
                + "  |  Messages: " + data.messages.size());
        header.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        header.setFont(header.getFont().deriveFont(Font.BOLD, 12));
        if (data.streaming) {
            header.setForeground(new Color(0, 140, 0)); // green when streaming
        }
        headerPanel.add(header, BorderLayout.CENTER);

        if (data.streaming) {
            String elapsed = formatElapsed(data.streamingStartedAt);
            JLabel elapsedLabel = new JLabel("Streaming: " + elapsed + "  ");
            elapsedLabel.setFont(elapsedLabel.getFont().deriveFont(Font.PLAIN, 10));
            elapsedLabel.setForeground(Color.GRAY);
            headerPanel.add(elapsedLabel, BorderLayout.EAST);
        }
    }

    /**
     * Finds the tab index by the base key (without streaming prefix).
     */
    private int findTabIndex(String key) {
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            String title = tabbedPane.getTitleAt(i);
            // Strip streaming prefix if present
            if (title != null) {
                String baseTitle = title.startsWith("● ") ? title.substring(3) : title;
                if (baseTitle.equals(key)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String formatElapsed(long startedAt) {
        if (startedAt <= 0) return "0.0s";
        long elapsed = System.currentTimeMillis() - startedAt;
        return String.format("%.1fs", elapsed / 1000.0);
    }

    private static String formatContext(int chars, int contextWindow) {
        if (chars < 1000) {
            return formatPct(chars, contextWindow) + " (" + chars + " ch)";
        }
        if (chars < 1_000_000) {
            return formatPct(chars, contextWindow) + " (" + String.format("%.1fK", chars / 1000.0) + " ch)";
        }
        return formatPct(chars, contextWindow) + " (" + String.format("%.1fM", chars / 1_000_000.0) + " ch)";
    }

    private static String formatPct(int chars, int contextWindow) {
        if (contextWindow <= 0) return "??%";
        double pct = chars / (double) contextWindow * 100.0;
        if (pct >= 10.0) return String.format("%.0f%%", pct);
        return String.format("%.1f%%", pct);
    }

    @Override
    public void removeDialog() {
        lastUpdateCount = -1;
        lastContentKey = "";
        textAreas.clear();
        tabbedPane.removeAll();
        super.removeDialog();
    }
}
