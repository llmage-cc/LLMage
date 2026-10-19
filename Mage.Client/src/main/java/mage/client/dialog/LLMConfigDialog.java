package mage.client.dialog;

import mage.players.LLMPlayerConfig;

import mage.client.MageFrame;
import org.apache.log4j.Logger;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dialog for editing per-player LLM settings in the New Table UI.
 * Uses a save callback (same pattern as CustomOptionsDialog) since the
 * application runs in non-modal mode — setVisible(true) returns immediately.
 */
public class LLMConfigDialog extends MageDialog {

    private static final Logger logger = Logger.getLogger(LLMConfigDialog.class);

    // --- Core settings ---
    private JComboBox<String> cbModel;          // editable combo box — type or select
    private JTextField txtApiEndpoint;
    private JTextField txtApiKey;
    private JComboBox<String> cbReasoningEffort;
    private JCheckBox chkThinkingEnabled;
    private JCheckBox chkTemperatureEnabled;
    private JSpinner spnTemperature;
    private JSpinner spnMaxTokens;
    private JSpinner spnTimeoutSeconds;
    private JButton btnRefreshModels;
    private final AtomicBoolean modelsLoading = new AtomicBoolean(false);

    // --- Behavior ---
    private JCheckBox chkStreaming;
    private JCheckBox chkVerboseLogging;
    private JCheckBox chkDeckAssessment;
    private JTextField txtTranscriptDir;
    private JCheckBox chkConcede;
    private JCheckBox chkAskReason;

    // --- Personality ---
    private JCheckBox chkPersonalityEnabled;
    private JComboBox<String> cbPersonalityName;
    private JComboBox<String> cbBotName;

    // --- Conversation ---
    private JSpinner spnMaxExchanges;
    private JSpinner spnMaxHistoryChars;
    private JSpinner spnThinkCharsPerMsg;

    // --- Summarization ---
    private JSpinner spnSummarizeTriggerChars;
    private JSpinner spnSummarizeContextRatio;
    private JSpinner spnModelContextWindow;
    private JSpinner spnSummarizeTargetChars;
    private JSpinner spnSummarizeTargetRatio;
    private JSpinner spnSummarizeKeepExchanges;

    private Consumer<LLMPlayerConfig> saveListener;

    public LLMConfigDialog() {
        setTitle("LLM Player Configuration");
        setResizable(false);
        initComponents();
        pack();
        makeWindowCentered();
        MageFrame.getDesktop().add(this, JLayeredPane.MODAL_LAYER);
    }

    public void setSaveListener(Consumer<LLMPlayerConfig> listener) {
        this.saveListener = listener;
    }

    private void initComponents() {
        JTabbedPane tabs = new JTabbedPane();

        // --- Core tab ---
        JPanel corePanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 6, 3, 6);

        int row = 0;

        // Model: editable combo box + refresh button
        cbModel = new JComboBox<>();
        cbModel.setEditable(true);
        cbModel.setToolTipText("Model name. Use the refresh button to fetch available models from the endpoint.");
        JButton btnRefresh = new JButton("↻");
        btnRefresh.setToolTipText("Fetch available models from the API endpoint");
        btnRefresh.setMargin(new Insets(0, 4, 0, 4));
        btnRefresh.addActionListener(this::onRefreshModels);
        btnRefreshModels = btnRefresh;
        JPanel modelPanel = new JPanel(new BorderLayout(2, 0));
        modelPanel.add(cbModel, BorderLayout.CENTER);
        modelPanel.add(btnRefresh, BorderLayout.EAST);
        addField(corePanel, gbc, row++, "Model:", modelPanel);

        addField(corePanel, gbc, row++, "API Endpoint:", txtApiEndpoint = new JTextField(30));
        addField(corePanel, gbc, row++, "API Key:", txtApiKey = new JTextField(20));

        cbReasoningEffort = new JComboBox<>(new String[]{"default", "low", "medium", "high", "max"});
        addField(corePanel, gbc, row++, "Reasoning Effort:", cbReasoningEffort);

        chkThinkingEnabled = new JCheckBox("", true);
        addField(corePanel, gbc, row++, "Thinking Enabled:", chkThinkingEnabled);

        chkTemperatureEnabled = new JCheckBox("Override temperature (unchecked = use model default)", false);
        chkTemperatureEnabled.addActionListener(e -> spnTemperature.setEnabled(chkTemperatureEnabled.isSelected()));
        addField(corePanel, gbc, row++, "Temperature:", chkTemperatureEnabled);

        spnTemperature = new JSpinner(new SpinnerNumberModel(0.7, 0.0, 2.0, 0.1));
        spnTemperature.setEnabled(false); // disabled by default since temperatureEnabled defaults to false
        addField(corePanel, gbc, row++, "  Value:", spnTemperature);

        spnMaxTokens = new JSpinner(new SpinnerNumberModel(16384, 256, 131072, 256));
        addField(corePanel, gbc, row++, "Max Output Tokens:", spnMaxTokens);

        spnTimeoutSeconds = new JSpinner(new SpinnerNumberModel(600, 10, 3600, 10));
        addField(corePanel, gbc, row++, "Timeout (sec):", spnTimeoutSeconds);

        corePanel.setBorder(new TitledBorder("Core LLM Settings"));
        tabs.addTab("Core", corePanel);

        // --- Behavior tab ---
        JPanel behaviorPanel = new JPanel(new GridBagLayout());
        gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 6, 3, 6);
        row = 0;

        chkStreaming = new JCheckBox("", true);
        addField(behaviorPanel, gbc, row++, "Streaming:", chkStreaming);

        chkVerboseLogging = new JCheckBox("", false);
        addField(behaviorPanel, gbc, row++, "Verbose Logging:", chkVerboseLogging);

        chkDeckAssessment = new JCheckBox("", true);
        addField(behaviorPanel, gbc, row++, "Deck Assessment:", chkDeckAssessment);

        chkConcede = new JCheckBox("", true);
        addField(behaviorPanel, gbc, row++, "Allow Concede:", chkConcede);

        chkAskReason = new JCheckBox("", false);
        addField(behaviorPanel, gbc, row++, "Ask REASON line (hidden-reasoning models):", chkAskReason);

        txtTranscriptDir = new JTextField(30);
        addField(behaviorPanel, gbc, row++, "Transcript Dir:", txtTranscriptDir);

        behaviorPanel.setBorder(new TitledBorder("Behavior"));
        tabs.addTab("Behavior", behaviorPanel);

        // --- Personality tab ---
        JPanel persPanel = new JPanel(new GridBagLayout());
        gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 6, 3, 6);
        row = 0;

        chkPersonalityEnabled = new JCheckBox("", true);
        chkPersonalityEnabled.addActionListener(e -> cbPersonalityName.setEnabled(chkPersonalityEnabled.isSelected()));
        addField(persPanel, gbc, row++, "Personality Enabled:", chkPersonalityEnabled);

        java.util.List<String> allNames = new java.util.ArrayList<>();
        allNames.add("(auto-detect from name)");
        try {
            allNames.addAll(mage.players.Personality.allNames());
        } catch (NoClassDefFoundError | Exception e) {
            logger.warn("Personality class not available on client classpath: " + e.getMessage());
        }
        cbPersonalityName = new JComboBox<>(allNames.toArray(new String[0]));
        cbPersonalityName.setToolTipText("Name whose personality to use. 'auto-detect' uses the bot's assigned name.");
        addField(persPanel, gbc, row++, "Personality:", cbPersonalityName);

        // Bot name dropdown — "Random" picks from the pool, or choose a specific name
        java.util.List<String> botNameOptions = new java.util.ArrayList<>();
        botNameOptions.add("Random");
        try {
            botNameOptions.addAll(mage.players.Personality.allNames());
        } catch (NoClassDefFoundError | Exception e) {
            logger.warn("Personality class not available on client classpath: " + e.getMessage());
        }
        cbBotName = new JComboBox<>(botNameOptions.toArray(new String[0]));
        cbBotName.setToolTipText("Bot display name. 'Random' picks from the pool each game.");
        addField(persPanel, gbc, row++, "Bot Name:", cbBotName);

        persPanel.setBorder(new TitledBorder("Bot Personality"));
        tabs.addTab("Personality", persPanel);

        // --- Conversation tab ---
        JPanel convPanel = new JPanel(new GridBagLayout());
        gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 6, 3, 6);
        row = 0;

        // 0 = unlimited (infinity) is the default for all
        // three conversation limits (was 999/999999/999999).
        spnMaxExchanges = new JSpinner(new SpinnerNumberModel(0, 0, 99999, 1));
        spnMaxExchanges.setToolTipText("0 = unlimited (infinity)");
        addField(convPanel, gbc, row++, "Max Exchanges:", spnMaxExchanges);

        spnMaxHistoryChars = new JSpinner(new SpinnerNumberModel(0, 0, 99999999, 1000));
        spnMaxHistoryChars.setToolTipText("0 = unlimited (infinity)");
        addField(convPanel, gbc, row++, "Max History Chars:", spnMaxHistoryChars);

        spnThinkCharsPerMsg = new JSpinner(new SpinnerNumberModel(0, 0, 99999999, 1000));
        spnThinkCharsPerMsg.setToolTipText("0 = unlimited (infinity)");
        addField(convPanel, gbc, row++, "Think Chars/Msg:", spnThinkCharsPerMsg);

        convPanel.setBorder(new TitledBorder("Conversation Memory"));
        tabs.addTab("Conversation", convPanel);

        // --- Summarization tab ---
        JPanel sumPanel = new JPanel(new GridBagLayout());
        gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 6, 3, 6);
        row = 0;

        spnSummarizeTriggerChars = new JSpinner(new SpinnerNumberModel(0, 0, 1000000, 1000));
        addField(sumPanel, gbc, row++, "Trigger Chars:", spnSummarizeTriggerChars);

        spnSummarizeContextRatio = new JSpinner(new SpinnerNumberModel(0.65, 0.0, 1.0, 0.05));
        addField(sumPanel, gbc, row++, "Context Ratio:", spnSummarizeContextRatio);

        spnModelContextWindow = new JSpinner(new SpinnerNumberModel(0, 0, 10000000, 4096));
        spnModelContextWindow.setToolTipText("0 = auto-detect from Ollama at match start");
        addField(sumPanel, gbc, row++, "Context Window:", spnModelContextWindow);

        spnSummarizeTargetChars = new JSpinner(new SpinnerNumberModel(0, 0, 1000000, 500));
        addField(sumPanel, gbc, row++, "Target Chars:", spnSummarizeTargetChars);

        spnSummarizeTargetRatio = new JSpinner(new SpinnerNumberModel(0.25, 0.0, 1.0, 0.05));
        addField(sumPanel, gbc, row++, "Target Ratio:", spnSummarizeTargetRatio);

        // default 5 (was 2).
        spnSummarizeKeepExchanges = new JSpinner(new SpinnerNumberModel(5, 0, 20, 1));
        addField(sumPanel, gbc, row++, "Keep Exchanges:", spnSummarizeKeepExchanges);

        sumPanel.setBorder(new TitledBorder("Conversation Summarization"));
        tabs.addTab("Summarization", sumPanel);

        // --- Buttons ---
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnOK = new JButton("OK");
        btnOK.addActionListener(e -> {
            if (saveListener != null) saveListener.accept(saveTo());
            hideDialog();
        });
        JButton btnCancel = new JButton("Cancel");
        btnCancel.addActionListener(e -> hideDialog());
        buttonPanel.add(btnOK);
        buttonPanel.add(btnCancel);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(tabs, BorderLayout.CENTER);
        getContentPane().add(buttonPanel, BorderLayout.SOUTH);
    }

    // ── Model fetching ──────────────────────────────────────────────────

    /**
     * Fetch available models from the given API endpoint in a background thread.
     * Supports both Ollama (/api/tags) and OpenAI-compatible (/v1/models) APIs.
     * Updates the model combo box on the EDT when done.
     */
    private void fetchModelsInBackground(String endpoint, String apiKey) {
        if (endpoint == null || endpoint.trim().isEmpty()) return;
        if (!modelsLoading.compareAndSet(false, true)) return; // already loading

        Thread t = new Thread(() -> {
            List<String> models = fetchModelsFromEndpoint(endpoint, apiKey);
            SwingUtilities.invokeLater(() -> {
                modelsLoading.set(false);
                if (btnRefreshModels != null) btnRefreshModels.setEnabled(true);
                if (models.isEmpty()) return;
                String currentSelection = (String) cbModel.getSelectedItem();
                cbModel.removeAllItems();
                for (String m : models) {
                    cbModel.addItem(m);
                }
                // Restore previous selection if it's in the new list, else keep typed text
                if (currentSelection != null && !currentSelection.isEmpty()) {
                    cbModel.setSelectedItem(currentSelection);
                }
            });
        }, "LLM-ModelFetcher");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Query the API endpoint for available models.
     * Tries Ollama /api/tags first, then falls back to OpenAI-compatible /v1/models.
     * The API key is sent as a Bearer header on the OpenAI path (cloud providers
     * reject unauthenticated /v1/models with 401/403). Returns an empty list on
     * any failure.
     */
    private static List<String> fetchModelsFromEndpoint(String endpoint, String apiKey) {
        List<String> models = new ArrayList<>();
        try {
            // Derive base URL from the chat completions endpoint
            String baseUrl = endpoint;
            if (baseUrl.endsWith("/v1/chat/completions")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - "/v1/chat/completions".length());
            } else if (baseUrl.endsWith("/v1")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - "/v1".length());
            } else {
                baseUrl = baseUrl.replaceAll("/v1/chat/completions$", "").replaceAll("/v1$", "");
            }

            // Try Ollama /api/tags first
            if (endpoint.contains("ollama") || endpoint.contains("11434")) {
                models = fetchOllamaModels(baseUrl + "/api/tags");
                if (!models.isEmpty()) return models;
            }

            // Fall back to OpenAI-compatible /v1/models
            models = fetchOpenAIModels(baseUrl + "/v1/models", apiKey);
        } catch (Exception e) {
            logger.warn("Failed to fetch models from " + endpoint + ": " + e.getMessage());
        }
        return models;
    }

    private static List<String> fetchOllamaModels(String url) throws Exception {
        List<String> models = new ArrayList<>();
        URI uri = URI.create(url);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            logger.warn("Ollama models request to " + url + " returned non-2xx status: " + status);
            conn.disconnect();
            return models;
        }
        try (InputStream is = conn.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            conn.disconnect();

            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (root.has("models")) {
                for (com.google.gson.JsonElement el : root.getAsJsonArray("models")) {
                    com.google.gson.JsonObject m = el.getAsJsonObject();
                    if (m.has("name")) {
                        models.add(m.get("name").getAsString());
                    }
                }
            }
        }
        return models;
    }

    private static List<String> fetchOpenAIModels(String url, String apiKey) throws Exception {
        List<String> models = new ArrayList<>();
        URI uri = URI.create(url);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
        }
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            logger.warn("OpenAI models request to " + url + " returned non-2xx status: " + status);
            conn.disconnect();
            return models;
        }
        try (InputStream is = conn.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            conn.disconnect();

            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(sb.toString()).getAsJsonObject();
            if (root.has("data")) {
                for (com.google.gson.JsonElement el : root.getAsJsonArray("data")) {
                    com.google.gson.JsonObject m = el.getAsJsonObject();
                    if (m.has("id")) {
                        models.add(m.get("id").getAsString());
                    }
                }
            }
        }
        return models;
    }

    private void onRefreshModels(ActionEvent e) {
        String endpoint = txtApiEndpoint.getText().trim();
        if (endpoint.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter an API endpoint first.", "No Endpoint", JOptionPane.WARNING_MESSAGE);
            return;
        }
        btnRefreshModels.setEnabled(false);
        fetchModelsInBackground(endpoint, txtApiKey.getText().trim());
    }

    private static void addField(JPanel panel, GridBagConstraints gbc, int row,
                                  String label, JComponent field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(field, gbc);
    }

    public void loadFrom(LLMPlayerConfig config) {
        if (config.getModel() != null) {
            cbModel.setSelectedItem(config.getModel());
            // If the model isn't in the dropdown yet, set it as the typed text
            if (!config.getModel().equals(cbModel.getSelectedItem())) {
                cbModel.insertItemAt(config.getModel(), 0);
                cbModel.setSelectedItem(config.getModel());
            }
        }
        if (config.getApiEndpoint() != null) txtApiEndpoint.setText(config.getApiEndpoint());
        else txtApiEndpoint.setText("");
        if (config.getApiKey() != null) txtApiKey.setText(config.getApiKey());
        else txtApiKey.setText("");
        cbReasoningEffort.setSelectedItem(config.getReasoningEffort() != null ? config.getReasoningEffort() : "default");
        chkThinkingEnabled.setSelected(config.isThinkingEnabled() != null ? config.isThinkingEnabled() : true);
        boolean tempEnabled = config.getTemperatureEnabled() != null ? config.getTemperatureEnabled() : false;
        chkTemperatureEnabled.setSelected(tempEnabled);
        spnTemperature.setEnabled(tempEnabled);
        spnTemperature.setValue(config.getTemperature() != null ? config.getTemperature() : 0.7);
        spnMaxTokens.setValue(config.getMaxTokens() != null ? config.getMaxTokens() : 16384);
        spnTimeoutSeconds.setValue(config.getTimeoutSeconds() != null ? config.getTimeoutSeconds() : 600);
        chkStreaming.setSelected(config.isStreamingEnabled() != null ? config.isStreamingEnabled() : true);
        chkVerboseLogging.setSelected(config.isVerboseLogging() != null ? config.isVerboseLogging() : false);
        chkDeckAssessment.setSelected(config.isDeckAssessmentEnabled() != null ? config.isDeckAssessmentEnabled() : true);
        chkConcede.setSelected(config.isConcedeEnabled() != null ? config.isConcedeEnabled() : true);
        chkAskReason.setSelected(config.isAskReason() != null ? config.isAskReason() : false);
        chkPersonalityEnabled.setSelected(config.isPersonalityEnabled() != null ? config.isPersonalityEnabled() : true);
        String persName = config.getPersonalityName();
        cbPersonalityName.setSelectedItem(persName != null ? persName : "(auto-detect from name)");
        String botName = config.getBotName();
        cbBotName.setSelectedItem(botName != null ? botName : "Random");
        if (config.getTranscriptDir() != null) txtTranscriptDir.setText(config.getTranscriptDir());
        else txtTranscriptDir.setText("");
        spnMaxExchanges.setValue(config.getConversationMaxExchanges() != null ? config.getConversationMaxExchanges() : 0);
        spnMaxHistoryChars.setValue(config.getConversationMaxHistoryChars() != null ? config.getConversationMaxHistoryChars() : 0);
        spnThinkCharsPerMsg.setValue(config.getConversationThinkCharsPerMsg() != null ? config.getConversationThinkCharsPerMsg() : 0);
        spnSummarizeTriggerChars.setValue(config.getSummarizeTriggerChars() != null ? config.getSummarizeTriggerChars() : 0);
        spnSummarizeContextRatio.setValue(config.getSummarizeContextRatio() != null ? config.getSummarizeContextRatio() : 0.65);
        spnModelContextWindow.setValue(config.getModelContextWindow() != null ? config.getModelContextWindow() : 0);
        spnSummarizeTargetChars.setValue(config.getSummarizeTargetChars() != null ? config.getSummarizeTargetChars() : 0);
        spnSummarizeTargetRatio.setValue(config.getSummarizeTargetRatio() != null ? config.getSummarizeTargetRatio() : 0.25);
        spnSummarizeKeepExchanges.setValue(config.getSummarizeKeepExchanges() != null ? config.getSummarizeKeepExchanges() : 5);

        // Fetch models from the endpoint if it's set
        String endpoint = config.getApiEndpoint();
        if (endpoint != null && !endpoint.isEmpty()) {
            fetchModelsInBackground(endpoint, config.getApiKey() != null ? config.getApiKey() : "");
        }
    }

    public LLMPlayerConfig saveTo() {
        LLMPlayerConfig c = new LLMPlayerConfig();
        c.setModel(emptyToNull((String) cbModel.getSelectedItem()));
        c.setApiEndpoint(emptyToNull(txtApiEndpoint.getText()));
        c.setApiKey(emptyToNull(txtApiKey.getText()));
        String re = (String) cbReasoningEffort.getSelectedItem();
        c.setReasoningEffort("default".equals(re) ? null : re);
        c.setThinkingEnabled(chkThinkingEnabled.isSelected());
        c.setTemperatureEnabled(chkTemperatureEnabled.isSelected());
        c.setTemperature((Double) spnTemperature.getValue());
        c.setMaxTokens((Integer) spnMaxTokens.getValue());
        c.setTimeoutSeconds((Integer) spnTimeoutSeconds.getValue());
        c.setStreamingEnabled(chkStreaming.isSelected());
        c.setVerboseLogging(chkVerboseLogging.isSelected());
        c.setDeckAssessmentEnabled(chkDeckAssessment.isSelected());
        c.setConcedeEnabled(chkConcede.isSelected());
        c.setAskReason(chkAskReason.isSelected());
        c.setPersonalityEnabled(chkPersonalityEnabled.isSelected());
        String selName = (String) cbPersonalityName.getSelectedItem();
        c.setPersonalityName("(auto-detect from name)".equals(selName) ? null : selName);
        String selBotName = (String) cbBotName.getSelectedItem();
        c.setBotName("Random".equals(selBotName) ? null : selBotName);
        c.setTranscriptDir(emptyToNull(txtTranscriptDir.getText()));
        c.setConversationMaxExchanges((Integer) spnMaxExchanges.getValue());
        c.setConversationMaxHistoryChars((Integer) spnMaxHistoryChars.getValue());
        c.setConversationThinkCharsPerMsg((Integer) spnThinkCharsPerMsg.getValue());
        c.setSummarizeTriggerChars((Integer) spnSummarizeTriggerChars.getValue());
        c.setSummarizeContextRatio((Double) spnSummarizeContextRatio.getValue());
        c.setModelContextWindow(mapZeroToNull((Integer) spnModelContextWindow.getValue()));
        c.setSummarizeTargetChars((Integer) spnSummarizeTargetChars.getValue());
        c.setSummarizeTargetRatio((Double) spnSummarizeTargetRatio.getValue());
        c.setSummarizeKeepExchanges((Integer) spnSummarizeKeepExchanges.getValue());
        return c;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static Integer mapZeroToNull(Integer v) {
        return (v != null && v == 0) ? null : v;
    }

    public void showDialog() {
        this.setModal(true);
        this.setVisible(true);
    }
}