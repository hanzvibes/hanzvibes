package com.termux.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
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
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class NativeAiActivity extends AppCompatActivity implements ServiceConnection {

    private final Handler handler = new Handler(Looper.getMainLooper());

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

    private TextView workspaceTitle;
    private TextView workspaceSubtitle;
    private TextView workspaceState;
    private EditText composerInput;
    private RecyclerView messageList;
    private NativeMessageAdapter messageAdapter;

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

        getWindow().setStatusBarColor(Color.rgb(11, 13, 16));
        getWindow().setNavigationBarColor(Color.rgb(11, 13, 16));
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

        workspaceTitle = findViewById(R.id.native_workspace_title);
        workspaceSubtitle = findViewById(R.id.native_workspace_subtitle);
        workspaceState = findViewById(R.id.native_workspace_state);
        composerInput = findViewById(R.id.native_composer_input);
        messageList = findViewById(R.id.native_message_list);
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
    }

    private void setupHomeActions() {
        findViewById(R.id.native_new_shell_button)
            .setOnClickListener(v -> createShellSession());

        findViewById(R.id.native_refresh_button)
            .setOnClickListener(v -> {
                refreshSessions();
                if (runtimeReady) {
                    runtimeStatus.setText("Local runtime ready. Agents install on demand.");
                }
            });

        findViewById(R.id.native_settings_button)
            .setOnClickListener(v -> showNativeMenu());
    }

    private void populateAgents() {
        addAgentCard("C", "Codex", "OpenAI coding agent", "codex");
        addAgentCard("O", "OpenCode", "Open coding agent", "opencode");
        addAgentCard("A", "Antigravity", "Google agentic CLI", "antigravity");
        addAgentCard("C", "Claude Code", "Anthropic coding agent", "claude");
        addAgentCard("G", "Gemini", "Google Gemini CLI", "gemini");
        addAgentCard("X", "Grok Build", "xAI coding agent", "grok");
    }

    private void addAgentCard(
        String badge,
        String title,
        String subtitle,
        String action
    ) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(Color.rgb(19, 22, 27));
        card.setStrokeColor(Color.rgb(38, 43, 51));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(18));
        card.setCardElevation(0);
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(title + ". " + subtitle);

        GridLayout.LayoutParams cardParams = new GridLayout.LayoutParams();
        cardParams.width = 0;
        cardParams.height = dp(112);
        cardParams.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        cardParams.setMargins(dp(4), dp(4), dp(4), dp(4));
        card.setLayoutParams(cardParams);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(13), dp(14), dp(12));

        TextView icon = new TextView(this);
        icon.setText(badge);
        icon.setTextColor(Color.rgb(245, 246, 248));
        icon.setTextSize(16);
        icon.setGravity(Gravity.CENTER);
        icon.setBackgroundResource(R.drawable.ai_tool_badge);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        icon.setLayoutParams(iconParams);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(Color.rgb(242, 244, 246));
        name.setTextSize(14);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        nameParams.topMargin = dp(8);
        name.setLayoutParams(nameParams);

        TextView desc = new TextView(this);
        desc.setText(subtitle);
        desc.setTextColor(Color.rgb(117, 124, 135));
        desc.setTextSize(11);
        desc.setMaxLines(1);

        body.addView(icon);
        body.addView(name);
        body.addView(desc);
        card.addView(body);
        card.setOnClickListener(v -> launchAgent(action, title));

        agentGrid.addView(card);
    }

    private void startRuntimeService() {
        showLoading("Preparing local runtime…");
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
        runtimeStatus.setText("Local runtime disconnected.");
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
            runtimeStatus.setText("Local runtime ready. Agents install on demand.");
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

        ensureBootstrap(() -> spawnManagedProcess(action, label));
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

        ensureBootstrap(() -> spawnManagedProcess("status", "Runtime status"));
    }

    private void spawnManagedProcess(String action, String label) {
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
            initializeHeadlessSession(terminal);
            openSession(terminal);
            refreshSessions();

            handler.postDelayed(() -> {
                if (terminal == currentSession) {
                    renderCurrentSession();
                }
            }, 1500);
        } catch (Exception e) {
            showToast("Could not prepare " + label + ".");
        }
    }

    private void initializeHeadlessSession(TerminalSession session) {
        if (session == null || session.getEmulator() != null) return;

        // TerminalSession only spawns its subprocess from updateSize().
        // The stock Termux UI gets this from TerminalView. Our native app has no
        // TerminalView, so initialize a headless PTY explicitly.
        session.updateSize(
            120, // columns
            40,  // rows
            8,   // cell width px, only used for PTY window metadata
            16   // cell height px
        );
    }

    private void openSession(TerminalSession session) {
        if (session == null) return;
        initializeHeadlessSession(session);
        currentSession = session;
        homeScreen.setVisibility(View.GONE);
        workspaceScreen.setVisibility(View.VISIBLE);
        composerInput.setText("");
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

        session.write(text + "\r");
        composerInput.setText("");
        scheduleRender();
    }

    private void interruptCurrent() {
        TerminalSession session = currentSession;
        if (session == null || !session.isRunning()) return;
        session.write("\u0003");
    }

    public void onNativeSessionTextChanged(TerminalSession session) {
        if (session == currentSession) scheduleRender();
    }

    public void onNativeSessionFinished(TerminalSession session) {
        runOnUiThread(() -> {
            if (session == currentSession) renderCurrentSession();

            if (service != null &&
                (session.getExitStatus() == 0 || session.getExitStatus() == 130)) {
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
        if (title == null || title.trim().isEmpty()) title = "Session";

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
                ? Color.rgb(154, 255, 69)
                : Color.rgb(155, 162, 172)
        );

        messageAdapter.submit(NativeTranscriptParser.parse(session));
        if (messageAdapter.getItemCount() > 0) {
            messageList.post(() ->
                messageList.scrollToPosition(messageAdapter.getItemCount() - 1)
            );
        }
    }

    private void refreshSessions() {
        if (sessionsContainer == null) return;
        sessionsContainer.removeAllViews();

        List<TermuxSession> sessions = service == null
            ? new ArrayList<>()
            : new ArrayList<>(service.getTermuxSessions());

        sessionCount.setText(
            sessions.size() + (sessions.size() == 1 ? " active" : " active")
        );

        if (sessions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No active sessions yet.");
            empty.setTextColor(Color.rgb(105, 112, 122));
            empty.setTextSize(13);
            empty.setPadding(dp(4), dp(10), dp(4), dp(10));
            sessionsContainer.addView(empty);
            return;
        }

        for (TermuxSession wrapper : sessions) {
            addSessionCard(wrapper.getTerminalSession());
        }
    }

    private void addSessionCard(TerminalSession session) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(Color.rgb(18, 21, 26));
        card.setStrokeColor(Color.rgb(38, 43, 51));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(17));
        card.setCardElevation(0);
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        );
        labels.setLayoutParams(labelParams);

        TextView title = new TextView(this);
        title.setText(
            session.mSessionName == null ? "Session" : session.mSessionName
        );
        title.setTextColor(Color.rgb(238, 240, 243));
        title.setTextSize(14);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        TextView subtitle = new TextView(this);
        subtitle.setText(
            (session.isRunning() ? "Running" : "Exited") +
            " • " +
            compactPath(session.getCwd())
        );
        subtitle.setTextColor(Color.rgb(112, 120, 130));
        subtitle.setTextSize(11);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(Color.rgb(106, 113, 123));
        arrow.setTextSize(26);

        labels.addView(title);
        labels.addView(subtitle);
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
            "Refresh sessions",
            "About"
        };

        new AlertDialog.Builder(this)
            .setTitle("AI Workspace")
            .setItems(items, (dialog, which) -> {
                if (which == 0) {
                    openRuntimeStatus();
                } else if (which == 1) {
                    refreshSessions();
                } else {
                    new AlertDialog.Builder(this)
                        .setTitle("AI Workspace")
                        .setMessage(
                            "Native Android interface with a local process engine. " +
                            "Codex, OpenCode, Antigravity, Claude Code, Gemini and Grok " +
                            "run as local sessions behind the UI."
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
        if (path == null || path.trim().isEmpty()) return "~";
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        if (path.equals(home)) return "~";
        if (path.startsWith(home + "/")) return "~/" + path.substring(home.length() + 1);
        return path;
    }

    private int dp(int value) {
        return Math.round(
            value * getResources().getDisplayMetrics().density
        );
    }

    @Override
    public void onBackPressed() {
        if (workspaceScreen != null &&
            workspaceScreen.getVisibility() == View.VISIBLE) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);

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
