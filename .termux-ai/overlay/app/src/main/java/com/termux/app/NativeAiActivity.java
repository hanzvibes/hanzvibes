package com.termux.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.termux.R;
import com.termux.app.nativeui.NativeMessageAdapter;
import com.termux.app.nativeui.NativeRuntimeManager;
import com.termux.app.nativeui.NativeSessionClient;
import com.termux.app.nativeui.NativeTranscriptParser;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class NativeAiActivity extends AppCompatActivity implements ServiceConnection {

    private static final int TAB_AGENT = 0;
    private static final int TAB_CONSOLE = 1;
    private static final int TAB_FILES = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<TerminalSession, String> pendingInitialPrompts = new HashMap<>();

    private TermuxService service;
    private NativeSessionClient sessionClient;
    private TerminalSession currentSession;

    private View homeScreen;
    private View workspaceScreen;
    private View loadingOverlay;
    private TextView loadingText;
    private TextView runtimeChip;
    private TextView runtimeStatus;
    private TextView sessionCount;
    private LinearLayout sessionsContainer;
    private GridLayout agentGrid;
    private EditText homePrompt;

    private TextView workspaceTitle;
    private TextView workspaceSubtitle;
    private TextView workspaceState;
    private EditText composerInput;
    private RecyclerView messageList;
    private NativeMessageAdapter messageAdapter;

    private View agentPanel;
    private View consolePanel;
    private View filesPanel;
    private MaterialButton tabAgent;
    private MaterialButton tabConsole;
    private MaterialButton tabFiles;
    private TextView consoleText;
    private TextView filesPath;
    private LinearLayout filesContainer;
    private File currentFilesDir;
    private int selectedWorkspaceTab = TAB_AGENT;

    private boolean serviceBound;
    private boolean runtimeReady;
    private boolean renderScheduled;

    public static Intent newInstance(Context context) {
        return new Intent(context, NativeAiActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(14, 14, 15));
        getWindow().setNavigationBarColor(Color.rgb(14, 14, 15));
        setContentView(R.layout.activity_native_ai);

        bindViews();
        setupWorkspace();
        populateAgents();
        setupHomeActions();
        startRuntimeService();
    }

    private void bindViews() {
        homeScreen = findViewById(R.id.native_home_screen);
        workspaceScreen = findViewById(R.id.native_workspace_screen);
        loadingOverlay = findViewById(R.id.native_loading_overlay);
        loadingText = findViewById(R.id.native_loading_text);
        runtimeChip = findViewById(R.id.native_runtime_chip);
        runtimeStatus = findViewById(R.id.native_runtime_status);
        sessionCount = findViewById(R.id.native_session_count);
        sessionsContainer = findViewById(R.id.native_sessions_container);
        agentGrid = findViewById(R.id.native_agent_grid);
        homePrompt = findViewById(R.id.native_home_prompt);

        workspaceTitle = findViewById(R.id.native_workspace_title);
        workspaceSubtitle = findViewById(R.id.native_workspace_subtitle);
        workspaceState = findViewById(R.id.native_workspace_state);
        composerInput = findViewById(R.id.native_composer_input);
        messageList = findViewById(R.id.native_message_list);

        agentPanel = findViewById(R.id.native_agent_panel);
        consolePanel = findViewById(R.id.native_console_panel);
        filesPanel = findViewById(R.id.native_files_panel);
        tabAgent = findViewById(R.id.native_tab_agent);
        tabConsole = findViewById(R.id.native_tab_console);
        tabFiles = findViewById(R.id.native_tab_files);
        consoleText = findViewById(R.id.native_console_text);
        filesPath = findViewById(R.id.native_files_path);
        filesContainer = findViewById(R.id.native_files_container);
    }

    private void setupWorkspace() {
        messageAdapter = new NativeMessageAdapter();
        LinearLayoutManager manager = new LinearLayoutManager(this);
        manager.setStackFromEnd(false);
        messageList.setLayoutManager(manager);
        messageList.setAdapter(messageAdapter);

        findViewById(R.id.native_back_button).setOnClickListener(v -> showHome());
        findViewById(R.id.native_send_button).setOnClickListener(v -> sendComposer());
        findViewById(R.id.native_interrupt_button).setOnClickListener(v -> interruptCurrent());
        findViewById(R.id.native_workspace_menu_button).setOnClickListener(v -> showNativeMenu());
        findViewById(R.id.native_files_up_button).setOnClickListener(v -> navigateFilesUp());

        tabAgent.setOnClickListener(v -> selectWorkspaceTab(TAB_AGENT));
        tabConsole.setOnClickListener(v -> selectWorkspaceTab(TAB_CONSOLE));
        tabFiles.setOnClickListener(v -> selectWorkspaceTab(TAB_FILES));

        composerInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        composerInput.setSingleLine(false);
        composerInput.setInputType(
            InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );
        composerInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendComposer();
                return true;
            }
            return false;
        });

        selectWorkspaceTab(TAB_AGENT);
    }

    private void setupHomeActions() {
        findViewById(R.id.native_home_build_button)
            .setOnClickListener(v -> {
                String prompt = homePrompt.getText().toString().trim();
                if (prompt.isEmpty()) {
                    showToast("Describe what you want to build.");
                    return;
                }
                homePrompt.setText("");
                launchAgentWithPrompt("opencode", "OpenCode", prompt);
            });

        findViewById(R.id.native_new_shell_button)
            .setOnClickListener(v -> createShellSession());

        findViewById(R.id.native_refresh_button)
            .setOnClickListener(v -> {
                refreshSessions();
                if (runtimeReady) {
                    runtimeStatus.setText("Runtime ready • local tools available");
                }
            });

        findViewById(R.id.native_settings_button)
            .setOnClickListener(v -> showNativeMenu());
    }

    private void populateAgents() {
        addAgentCard("C", "Codex", "OpenAI agent", "codex");
        addAgentCard("O", "OpenCode", "Open agent", "opencode");
        addAgentCard("A", "Antigravity", "Google agent", "antigravity");
        addAgentCard("C", "Claude Code", "Anthropic agent", "claude");
        addAgentCard("G", "Gemini", "Gemini CLI", "gemini");
        addAgentCard("X", "Grok Build", "xAI agent", "grok");
    }

    private void addAgentCard(
        String badge,
        String title,
        String subtitle,
        String action
    ) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(Color.rgb(24, 25, 27));
        card.setStrokeColor(Color.rgb(48, 50, 54));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(13));
        card.setCardElevation(0);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(title + ". " + subtitle);

        GridLayout.LayoutParams cardParams = new GridLayout.LayoutParams();
        cardParams.width = 0;
        cardParams.height = dp(92);
        cardParams.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        cardParams.setMargins(dp(4), dp(4), dp(4), dp(4));
        card.setLayoutParams(cardParams);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(11), dp(12), dp(10));

        TextView icon = new TextView(this);
        icon.setText(badge);
        icon.setTextColor(Color.rgb(244, 244, 245));
        icon.setTextSize(13);
        icon.setGravity(Gravity.CENTER);
        icon.setBackgroundResource(R.drawable.replit_agent_badge);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(Color.rgb(240, 240, 241));
        name.setTextSize(13);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        nameParams.topMargin = dp(6);
        name.setLayoutParams(nameParams);

        TextView desc = new TextView(this);
        desc.setText(subtitle);
        desc.setTextColor(Color.rgb(113, 115, 121));
        desc.setTextSize(10);
        desc.setMaxLines(1);

        body.addView(icon);
        body.addView(name);
        body.addView(desc);
        card.addView(body);
        card.setOnClickListener(v -> launchAgent(action, title));

        agentGrid.addView(card);
    }

    private void startRuntimeService() {
        showLoading("Preparing workspace…");
        try {
            Intent intent = new Intent(this, TermuxService.class);
            startService(intent);
            serviceBound = bindService(intent, this, Context.BIND_AUTO_CREATE);
            if (!serviceBound) {
                hideLoading();
                showToast("Could not start local runtime.");
            }
        } catch (Exception e) {
            hideLoading();
            showToast("Could not start local runtime.");
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((TermuxService.LocalBinder) binder).service;
        sessionClient = new NativeSessionClient(this, service);
        service.setNativeTerminalSessionClient(sessionClient);

        refreshSessions();
        ensureBootstrap(null);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
        runtimeReady = false;
        runtimeChip.setText("OFFLINE");
        runtimeStatus.setText("Runtime disconnected");
    }

    private void ensureBootstrap(@Nullable Runnable after) {
        if (runtimeReady) {
            if (after != null) after.run();
            return;
        }

        showLoading("Preparing local runtime…");
        TermuxInstaller.setupBootstrapIfNeeded(this, () -> runOnUiThread(() -> {
            runtimeReady = true;
            runtimeChip.setText("READY");
            runtimeStatus.setText("Runtime ready • local tools available");
            hideLoading();
            refreshSessions();
            if (after != null) after.run();
        }));
    }

    private void launchAgent(String action, String label) {
        if (service == null) {
            showToast("Runtime is still connecting.");
            return;
        }

        ensureBootstrap(() -> spawnManagedProcess(action, label, null));
    }

    private void launchAgentWithPrompt(String action, String label, String prompt) {
        if (service == null) {
            showToast("Runtime is still connecting.");
            return;
        }

        ensureBootstrap(() -> spawnManagedProcess(action, label, prompt));
    }

    private void createShellSession() {
        if (service == null) {
            showToast("Runtime is still connecting.");
            return;
        }

        ensureBootstrap(() -> {
            TermuxSession wrapper = service.createTermuxSession(
                null,
                null,
                null,
                TermuxConstants.TERMUX_HOME_DIR_PATH,
                false,
                "Shell"
            );

            if (wrapper == null) {
                showToast("Could not start shell.");
                return;
            }

            TerminalSession terminal = wrapper.getTerminalSession();
            initializeHeadlessSession(terminal);
            openSession(terminal);
            refreshSessions();
        });
    }

    private void openRuntimeStatus() {
        if (service == null) {
            showToast("Runtime is still connecting.");
            return;
        }

        ensureBootstrap(() -> spawnManagedProcess("status", "Runtime status", null));
    }

    private void spawnManagedProcess(
        String action,
        String label,
        @Nullable String initialPrompt
    ) {
        try {
            File launcher = NativeRuntimeManager.prepare(this);
            String bash = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";

            TermuxSession wrapper = service.createTermuxSession(
                bash,
                new String[] { launcher.getAbsolutePath(), action },
                null,
                TermuxConstants.TERMUX_HOME_DIR_PATH,
                false,
                label
            );

            if (wrapper == null) {
                showToast("Could not start " + label + ".");
                return;
            }

            TerminalSession terminal = wrapper.getTerminalSession();
            if (initialPrompt != null && !initialPrompt.trim().isEmpty()) {
                pendingInitialPrompts.put(
                    terminal,
                    normalizePrompt(initialPrompt)
                );
            }

            initializeHeadlessSession(terminal);
            openSession(terminal);
            refreshSessions();

            handler.postDelayed(() -> {
                maybeSendPendingPrompt(terminal);
                if (terminal == currentSession) {
                    renderCurrentSession();
                }
            }, 800);
        } catch (Exception e) {
            showToast("Could not prepare " + label + ".");
        }
    }

    private void initializeHeadlessSession(TerminalSession session) {
        if (session == null || session.getEmulator() != null) return;

        session.updateSize(
            120,
            40,
            8,
            16
        );
    }

    private void openSession(TerminalSession session) {
        if (session == null) return;

        initializeHeadlessSession(session);
        currentSession = session;
        currentFilesDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);

        homeScreen.setVisibility(View.GONE);
        workspaceScreen.setVisibility(View.VISIBLE);
        composerInput.setText("");

        selectWorkspaceTab(TAB_AGENT);
        renderFiles(currentFilesDir);
        renderCurrentSession();
    }

    private void showHome() {
        workspaceScreen.setVisibility(View.GONE);
        homeScreen.setVisibility(View.VISIBLE);
        refreshSessions();
    }

    private void sendComposer() {
        TerminalSession session = currentSession;
        if (session == null || !session.isRunning()) {
            showToast("This session is not running.");
            return;
        }

        String text = composerInput.getText().toString().trim();
        if (text.isEmpty()) return;

        session.write(normalizePrompt(text) + "\r");
        composerInput.setText("");
        scheduleRender();
    }

    private String normalizePrompt(String value) {
        return value
            .replace("\r", " ")
            .replace("\n", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private void interruptCurrent() {
        TerminalSession session = currentSession;
        if (session == null || !session.isRunning()) return;
        session.write("\u0003");
    }

    public void onNativeSessionTextChanged(TerminalSession session) {
        maybeSendPendingPrompt(session);
        if (session == currentSession) scheduleRender();
    }

    private void maybeSendPendingPrompt(TerminalSession session) {
        String prompt = pendingInitialPrompts.get(session);
        if (prompt == null || prompt.isEmpty()) return;

        try {
            String transcript = ShellUtils.getTerminalSessionTranscriptText(
                session,
                true,
                false
            );

            if (
                transcript != null &&
                transcript.contains("@@NATIVE_READY@@opencode")
            ) {
                pendingInitialPrompts.remove(session);
                session.write(prompt + "\r");
            }
        } catch (Throwable ignored) {
        }
    }

    public void onNativeSessionFinished(TerminalSession session) {
        runOnUiThread(() -> {
            pendingInitialPrompts.remove(session);

            if (session == currentSession) renderCurrentSession();

            if (
                service != null &&
                (session.getExitStatus() == 0 || session.getExitStatus() == 130)
            ) {
                service.removeTermuxSession(session);
            }

            refreshSessions();
        });
    }

    private void scheduleRender() {
        if (renderScheduled) return;
        renderScheduled = true;

        handler.postDelayed(() -> {
            renderScheduled = false;
            renderCurrentSession();
        }, 70);
    }

    private void renderCurrentSession() {
        TerminalSession session = currentSession;
        if (session == null) return;

        String title = session.mSessionName;
        if (title == null || title.trim().isEmpty()) title = "Workspace";
        workspaceTitle.setText(title);

        String cwd = session.getCwd();
        workspaceSubtitle.setText(
            cwd == null || cwd.trim().isEmpty()
                ? "Local workspace"
                : compactPath(cwd)
        );

        boolean started = session.getPid() > 0;
        boolean running = started && session.isRunning();

        workspaceState.setText(
            !started ? "STARTING" : (running ? "RUNNING" : "EXITED")
        );
        workspaceState.setTextColor(
            running
                ? Color.rgb(87, 208, 130)
                : Color.rgb(142, 144, 150)
        );

        messageAdapter.submit(NativeTranscriptParser.parse(session));
        if (messageAdapter.getItemCount() > 0) {
            messageList.post(() ->
                messageList.scrollToPosition(messageAdapter.getItemCount() - 1)
            );
        }

        consoleText.setText(readConsoleTranscript(session));
    }

    private String readConsoleTranscript(TerminalSession session) {
        try {
            String raw = ShellUtils.getTerminalSessionTranscriptText(
                session,
                true,
                false
            );
            if (raw == null || raw.isEmpty()) return "No console output yet.";

            return raw
                .replace("\u0000", "")
                .replaceAll("\\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
        } catch (Throwable ignored) {
            return "Console unavailable.";
        }
    }

    private void selectWorkspaceTab(int tab) {
        selectedWorkspaceTab = tab;

        agentPanel.setVisibility(tab == TAB_AGENT ? View.VISIBLE : View.GONE);
        consolePanel.setVisibility(tab == TAB_CONSOLE ? View.VISIBLE : View.GONE);
        filesPanel.setVisibility(tab == TAB_FILES ? View.VISIBLE : View.GONE);

        styleTab(tabAgent, tab == TAB_AGENT);
        styleTab(tabConsole, tab == TAB_CONSOLE);
        styleTab(tabFiles, tab == TAB_FILES);

        if (tab == TAB_CONSOLE) {
            renderCurrentSession();
        } else if (tab == TAB_FILES) {
            if (currentFilesDir == null) {
                currentFilesDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
            }
            renderFiles(currentFilesDir);
        }
    }

    private void styleTab(MaterialButton button, boolean active) {
        button.setTextColor(
            active
                ? Color.rgb(248, 248, 249)
                : Color.rgb(139, 141, 146)
        );
        button.setBackgroundTintList(
            ColorStateList.valueOf(
                active
                    ? Color.rgb(42, 33, 29)
                    : Color.rgb(14, 14, 15)
            )
        );
    }

    private void renderFiles(File directory) {
        if (filesContainer == null) return;

        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (directory == null || !isWithinHome(directory, home)) {
            directory = home;
        }

        currentFilesDir = directory;
        filesPath.setText(compactPath(directory.getAbsolutePath()));
        filesContainer.removeAllViews();

        File[] children = directory.listFiles();
        if (children == null || children.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("This folder is empty.");
            empty.setTextColor(Color.rgb(116, 118, 124));
            empty.setTextSize(12);
            empty.setPadding(dp(14), dp(16), dp(14), dp(16));
            filesContainer.addView(empty);
            return;
        }

        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        int shown = 0;
        for (File child : children) {
            if (shown >= 160) break;
            addFileRow(child);
            shown++;
        }
    }

    private void addFileRow(File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setClickable(true);
        row.setFocusable(true);

        TextView icon = new TextView(this);
        icon.setText(file.isDirectory() ? "▸" : "·");
        icon.setTextColor(
            file.isDirectory()
                ? Color.rgb(242, 98, 7)
                : Color.rgb(128, 130, 136)
        );
        icon.setTextSize(file.isDirectory() ? 14 : 18);
        icon.setGravity(Gravity.CENTER);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(32)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));

        TextView name = new TextView(this);
        name.setText(file.getName());
        name.setTextColor(Color.rgb(226, 227, 229));
        name.setTextSize(13);
        name.setMaxLines(1);

        TextView meta = new TextView(this);
        meta.setText(
            file.isDirectory()
                ? "Folder"
                : formatBytes(file.length())
        );
        meta.setTextColor(Color.rgb(101, 103, 109));
        meta.setTextSize(10);

        labels.addView(name);
        labels.addView(meta);
        row.addView(icon);
        row.addView(labels);

        row.setContentDescription(
            file.getName() + (file.isDirectory() ? ", folder" : ", file")
        );

        row.setOnClickListener(v -> {
            if (file.isDirectory()) {
                renderFiles(file);
            } else {
                previewFile(file);
            }
        });

        filesContainer.addView(row);

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(36, 37, 41));
        filesContainer.addView(
            divider,
            new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        );
    }

    private void navigateFilesUp() {
        if (currentFilesDir == null) return;

        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (currentFilesDir.equals(home)) return;

        File parent = currentFilesDir.getParentFile();
        if (parent != null && isWithinHome(parent, home)) {
            renderFiles(parent);
        }
    }

    private boolean isWithinHome(File file, File home) {
        try {
            String target = file.getCanonicalPath();
            String root = home.getCanonicalPath();
            return target.equals(root) || target.startsWith(root + File.separator);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void previewFile(File file) {
        if (file == null || !file.isFile()) return;

        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[4096];
            int read;
            int total = 0;
            final int max = 96 * 1024;

            while ((read = input.read(buffer)) >= 0 && total < max) {
                int allowed = Math.min(read, max - total);
                output.write(buffer, 0, allowed);
                total += allowed;
            }

            String body = output.toString("UTF-8");
            consoleText.setText(
                file.getName() +
                "\n" +
                compactPath(file.getAbsolutePath()) +
                "\n\n" +
                body +
                (file.length() > max ? "\n\n… preview truncated" : "")
            );
            selectWorkspaceTab(TAB_CONSOLE);
        } catch (Exception e) {
            showToast("Could not preview this file.");
        }
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format(
                java.util.Locale.US,
                "%.1f MB",
                bytes / (1024d * 1024d)
            );
        }
        if (bytes >= 1024) {
            return String.format(
                java.util.Locale.US,
                "%.1f KB",
                bytes / 1024d
            );
        }
        return bytes + " B";
    }

    private void refreshSessions() {
        if (sessionsContainer == null) return;
        sessionsContainer.removeAllViews();

        List<TermuxSession> sessions = service == null
            ? new ArrayList<>()
            : new ArrayList<>(service.getTermuxSessions());

        sessionCount.setText(sessions.size() + " active");

        if (sessions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No recent workspace sessions.");
            empty.setTextColor(Color.rgb(105, 107, 113));
            empty.setTextSize(12);
            empty.setPadding(dp(4), dp(12), dp(4), dp(12));
            sessionsContainer.addView(empty);
            return;
        }

        for (TermuxSession wrapper : sessions) {
            addSessionCard(wrapper.getTerminalSession());
        }
    }

    private void addSessionCard(TerminalSession session) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(Color.rgb(24, 25, 27));
        card.setStrokeColor(Color.rgb(48, 50, 54));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(12));
        card.setCardElevation(0);
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(7);
        card.setLayoutParams(cardParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(12), dp(12), dp(12));

        TextView mark = new TextView(this);
        mark.setText("●");
        mark.setTextColor(
            session.isRunning()
                ? Color.rgb(87, 208, 130)
                : Color.rgb(103, 105, 111)
        );
        mark.setTextSize(9);
        mark.setGravity(Gravity.CENTER);
        mark.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(30)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setLayoutParams(new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ));

        TextView title = new TextView(this);
        title.setText(
            session.mSessionName == null ? "Workspace" : session.mSessionName
        );
        title.setTextColor(Color.rgb(235, 235, 237));
        title.setTextSize(13);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        TextView subtitle = new TextView(this);
        subtitle.setText(
            (session.isRunning() ? "Running" : "Exited") +
            " • " +
            compactPath(session.getCwd())
        );
        subtitle.setTextColor(Color.rgb(112, 114, 120));
        subtitle.setTextSize(10);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(Color.rgb(106, 108, 114));
        arrow.setTextSize(22);

        labels.addView(title);
        labels.addView(subtitle);
        row.addView(mark);
        row.addView(labels);
        row.addView(arrow);
        card.addView(row);

        card.setContentDescription(
            title.getText() + ". " + subtitle.getText()
        );
        card.setOnClickListener(v -> openSession(session));

        sessionsContainer.addView(card);
    }

    private void showNativeMenu() {
        String[] items = new String[] {
            "Runtime status",
            "New shell",
            "Refresh workspaces",
            "About"
        };

        new AlertDialog.Builder(this)
            .setTitle("Workspace")
            .setItems(items, (dialog, which) -> {
                if (which == 0) {
                    openRuntimeStatus();
                } else if (which == 1) {
                    createShellSession();
                } else if (which == 2) {
                    refreshSessions();
                } else {
                    new AlertDialog.Builder(this)
                        .setTitle("AI Workspace")
                        .setMessage(
                            "A native Android coding workspace with local sessions, " +
                            "Agent, Console and Files views."
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showLoading(String text) {
        loadingText.setText(text);
        loadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
    }

    private void showToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private String compactPath(String path) {
        if (path == null || path.trim().isEmpty()) return "Local workspace";

        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        if (path.equals(home)) return "Local workspace";

        if (path.startsWith(home + "/")) {
            String relative = path.substring(home.length() + 1);
            if (relative.startsWith(".ai-workspace")) return "Local workspace";
            return "~/" + relative;
        }

        String normalized = path.replace("\\", "/");
        int rootfs = normalized.indexOf("/containers/debian/rootfs");

        if (rootfs >= 0) {
            String relative = normalized.substring(
                rootfs + "/containers/debian/rootfs".length()
            );

            if (relative.isEmpty() || "/".equals(relative)) {
                return "Local workspace";
            }

            if (relative.startsWith("/root")) {
                String rest = relative.substring("/root".length());
                return rest.isEmpty() ? "~" : "~" + rest;
            }

            return relative;
        }

        return path;
    }

    private int dp(int value) {
        return Math.round(
            value * getResources().getDisplayMetrics().density
        );
    }

    @Override
    public void onBackPressed() {
        if (
            workspaceScreen != null &&
            workspaceScreen.getVisibility() == View.VISIBLE
        ) {
            if (
                selectedWorkspaceTab == TAB_FILES &&
                currentFilesDir != null &&
                !currentFilesDir.equals(new File(TermuxConstants.TERMUX_HOME_DIR_PATH))
            ) {
                navigateFilesUp();
                return;
            }

            showHome();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        pendingInitialPrompts.clear();

        if (service != null) {
            service.unsetNativeTerminalSessionClient();
        }

        if (serviceBound) {
            try {
                unbindService(this);
            } catch (Exception ignored) {
            }
        }

        service = null;
        super.onDestroy();
    }
}
