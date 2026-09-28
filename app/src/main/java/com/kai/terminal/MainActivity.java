package com.kai.terminal;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Bundle;
import android.system.Os;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(7, 10, 14);
    private static final int PANEL = Color.rgb(13, 18, 24);
    private static final int PANEL_2 = Color.rgb(18, 24, 31);
    private static final int TEXT = Color.rgb(221, 228, 236);
    private static final int MUTED = Color.rgb(126, 140, 156);
    private static final int GREEN = Color.rgb(120, 245, 164);
    private static final int BLUE = Color.rgb(112, 184, 255);
    private static final int RED = Color.rgb(255, 118, 126);
    private static final int AMBER = Color.rgb(255, 203, 112);
    private static final int BORDER = Color.rgb(37, 47, 58);

    private static final int MAX_SESSIONS = 6;
    private static final int MAX_HISTORY = 200;
    private static final int MAX_BUFFER = 240_000;
    private static final int PERSIST_BUFFER = 24_000;

    private static final String PREFS = "kai_terminal_v2";
    private static final String PREF_HISTORY = "history_json";
    private static final String PREF_SESSIONS = "sessions_json";
    private static final String PREF_ACTIVE = "active_session";

    private final List<TerminalSession> sessions = new ArrayList<>();
    private final List<String> history = new ArrayList<>();

    private SharedPreferences prefs;
    private LinearLayout tabsRow;
    private TextView output;
    private ScrollView scroll;
    private EditText input;
    private TextView cwdLabel;
    private TextView statusLabel;
    private Button runButton;
    private int activeIndex = 0;
    private int historyIndex = 0;

    private static final String[] COMMON_COMMANDS = new String[]{
            "ls", "cat", "echo", "date", "id", "getprop", "setprop", "env", "printenv",
            "pwd", "cd", "mkdir", "rmdir", "rm", "cp", "mv", "touch", "head", "tail",
            "grep", "sed", "awk", "sort", "uniq", "wc", "find", "df", "du", "ps",
            "top", "which", "sh", "sleep", "uname", "logcat", "am", "pm", "cmd"
    };

    private static final class TerminalSession {
        final int id;
        String name;
        File currentDir;
        String draft = "";
        final SpannableStringBuilder buffer = new SpannableStringBuilder();
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        volatile Process currentProcess;
        volatile OutputStream stdin;
        volatile boolean running;
        volatile boolean cancelRequested;

        TerminalSession(int id, String name, File currentDir) {
            this.id = id;
            this.name = name;
            this.currentDir = currentDir;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prepareNetworkRuntime();
        loadHistory();
        restoreSessions();
        buildUi();
        refreshAll();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(2), dp(2), dp(2), dp(8));

        LinearLayout titleStack = new LinearLayout(this);
        titleStack.setOrientation(LinearLayout.VERTICAL);

        TextView title = text("KAI TERMINAL  v3.1 · AI", 15, GREEN, Typeface.BOLD);
        title.setLetterSpacing(0.12f);
        titleStack.addView(title);

        statusLabel = text("", 10, MUTED, Typeface.NORMAL);
        statusLabel.setPadding(0, dp(3), 0, 0);
        titleStack.addView(statusLabel);

        top.addView(titleStack, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        top.addView(actionButton("+ SESSION", v -> createSession(true), BLUE));
        root.addView(top);

        HorizontalScrollView tabsScroll = new HorizontalScrollView(this);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsRow = new LinearLayout(this);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setPadding(0, 0, 0, dp(8));
        tabsScroll.addView(tabsRow);
        root.addView(tabsScroll);

        LinearLayout pathBar = new LinearLayout(this);
        pathBar.setOrientation(LinearLayout.HORIZONTAL);
        pathBar.setGravity(Gravity.CENTER_VERTICAL);
        pathBar.setPadding(dp(10), dp(7), dp(10), dp(7));
        pathBar.setBackground(rounded(PANEL, BORDER, 10));

        TextView marker = text("●", 9, GREEN, Typeface.BOLD);
        marker.setPadding(0, 0, dp(8), 0);
        pathBar.addView(marker);

        cwdLabel = text("", 10, MUTED, Typeface.NORMAL);
        cwdLabel.setSingleLine(true);
        pathBar.addView(cwdLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(pathBar);

        output = text("", 13, TEXT, Typeface.NORMAL);
        output.setTextIsSelectable(true);
        output.setLineSpacing(0f, 1.12f);
        output.setPadding(dp(12), dp(12), dp(12), dp(12));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(rounded(PANEL, BORDER, 12));
        scroll.addView(output, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams outputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        outputParams.topMargin = dp(8);
        root.addView(scroll, outputParams);

        HorizontalScrollView actionsScroll = new HorizontalScrollView(this);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, dp(5));
        actions.addView(chip("TAB", v -> completeInput()));
        actions.addView(chip("CTRL-C", v -> cancelActiveCommand()));
        actions.addView(chip("↑", v -> previousHistory()));
        actions.addView(chip("↓", v -> nextHistory()));
        actions.addView(chip("/", v -> insertText("/")));
        actions.addView(chip("-", v -> insertText("-")));
        actions.addView(chip("|", v -> insertText("|")));
        actions.addView(chip("~", v -> insertText("~")));
        actions.addView(chip("COPY", v -> copyOutput()));
        actions.addView(chip("SHARE", v -> shareOutput()));
        actions.addView(chip("CLEAR", v -> clearOutput()));
        actionsScroll.addView(actions);
        root.addView(actionsScroll);

        LinearLayout commandRow = new LinearLayout(this);
        commandRow.setOrientation(LinearLayout.HORIZONTAL);
        commandRow.setGravity(Gravity.CENTER_VERTICAL);
        commandRow.setPadding(dp(10), dp(2), dp(6), dp(2));
        commandRow.setBackground(rounded(PANEL_2, BORDER, 12));

        TextView prompt = text("❯", 18, GREEN, Typeface.BOLD);
        prompt.setPadding(0, 0, dp(8), 0);
        commandRow.addView(prompt);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setHint("command…");
        input.setTextSize(14f);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setPadding(0, dp(8), dp(6), dp(8));
        commandRow.addView(input, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        runButton = actionButton("RUN", v -> {
            TerminalSession session = activeSession();
            if (session.running) cancelCommand(session);
            else submit();
        }, GREEN);
        commandRow.addView(runButton);
        root.addView(commandRow);

        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                            event.getAction() == KeyEvent.ACTION_DOWN);
            if (enter) {
                submit();
                return true;
            }
            return false;
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                historyIndex = history.size();
                if (!sessions.isEmpty()) activeSession().draft = s.toString();
            }
        });

        setContentView(root);
        input.requestFocus();
    }

    private void restoreSessions() {
        String raw = prefs.getString(PREF_SESSIONS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length() && sessions.size() < MAX_SESSIONS; i++) {
                JSONObject o = array.getJSONObject(i);
                String name = o.optString("name", "S" + (i + 1));
                File dir = safeDirectory(o.optString("cwd", getFilesDir().getAbsolutePath()));
                TerminalSession session = new TerminalSession(i + 1, name, dir);
                String persisted = o.optString("buffer", "");
                if (!persisted.isEmpty()) appendRaw(session, persisted, MUTED);
                session.draft = o.optString("draft", "");
                sessions.add(session);
            }
        } catch (Exception ignored) {}

        if (sessions.isEmpty()) {
            TerminalSession first = new TerminalSession(1, "S1", getFilesDir());
            sessions.add(first);
            printBanner(first);
        }

        activeIndex = Math.max(0, Math.min(
                prefs.getInt(PREF_ACTIVE, 0), sessions.size() - 1));
    }

    private void loadHistory() {
        String raw = prefs.getString(PREF_HISTORY, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String command = array.optString(i, "");
                if (!command.isEmpty()) history.add(command);
            }
        } catch (Exception ignored) {}
        if (history.size() > MAX_HISTORY) {
            history.subList(0, history.size() - MAX_HISTORY).clear();
        }
        historyIndex = history.size();
    }

    private void persistState() {
        try {
            JSONArray sessionArray = new JSONArray();
            for (TerminalSession session : sessions) {
                JSONObject o = new JSONObject();
                o.put("name", session.name);
                o.put("cwd", session.currentDir.getAbsolutePath());
                o.put("draft", session.draft);
                String plain = session.buffer.toString();
                if (plain.length() > PERSIST_BUFFER) {
                    plain = "[older output trimmed after restart]\n" +
                            plain.substring(plain.length() - PERSIST_BUFFER);
                }
                o.put("buffer", plain);
                sessionArray.put(o);
            }

            JSONArray historyArray = new JSONArray();
            int start = Math.max(0, history.size() - MAX_HISTORY);
            for (int i = start; i < history.size(); i++) {
                historyArray.put(history.get(i));
            }

            prefs.edit()
                    .putString(PREF_SESSIONS, sessionArray.toString())
                    .putString(PREF_HISTORY, historyArray.toString())
                    .putInt(PREF_ACTIVE, activeIndex)
                    .apply();
        } catch (Exception ignored) {}
    }

    private void submit() {
        TerminalSession session = activeSession();

        String command = input.getText().toString().trim();
        if (command.isEmpty()) return;

        if (session.running) {
            sendToRunningProcess(session, command);
            input.setText("");
            return;
        }

        input.setText("");
        addHistory(command);
        append(session, "❯ " + command + "\n", GREEN);

        if (handleBuiltin(session, command)) {
            persistState();
            return;
        }

        runShell(session, command);
    }

    private boolean handleBuiltin(TerminalSession session, String command) {
        if (command.equals("ai") || command.equals("ai status")) {
            showAiHub(session);
            return true;
        }
        if (command.equals("ai doctor")) {
            runAiDoctor(session);
            return true;
        }
        if (command.equals("ask-codex")) {
            append(session, "usage: ask-codex <prompt>\n", MUTED);
            return true;
        }
        if (command.startsWith("ask-codex ")) {
            runShell(session, "codex exec " + shellQuote(command.substring(10).trim()));
            return true;
        }
        if (command.equals("ask-opencode")) {
            append(session, "usage: ask-opencode <prompt>\n", MUTED);
            return true;
        }
        if (command.startsWith("ask-opencode ")) {
            runShell(session, "opencode run " + shellQuote(command.substring(13).trim()));
            return true;
        }
        if (command.equals("ask-agy")) {
            append(session, "usage: ask-agy <prompt>\n", MUTED);
            return true;
        }
        if (command.startsWith("ask-agy ")) {
            runShell(session, "agy -p " + shellQuote(command.substring(8).trim()));
            return true;
        }
        if (command.equals("clear")) {
            clearOutput();
            return true;
        }
        if (command.equals("help")) {
            showHelp(session);
            return true;
        }
        if (command.equals("history")) {
            showHistory(session);
            return true;
        }
        if (command.equals("pwd")) {
            append(session, session.currentDir.getAbsolutePath() + "\n", TEXT);
            return true;
        }
        if (command.equals("home")) {
            session.currentDir = getFilesDir();
            refreshAll();
            return true;
        }
        if (command.equals("files")) {
            showFiles(session);
            return true;
        }
        if (command.equals("sessions")) {
            showSessions(session);
            return true;
        }
        if (command.equals("new")) {
            createSession(true);
            return true;
        }
        if (command.equals("close")) {
            closeSession(activeIndex);
            return true;
        }
        if (command.startsWith("session ")) {
            switchByCommand(session, command);
            return true;
        }
        if (command.startsWith("rename ")) {
            String newName = command.substring(7).trim();
            if (newName.isEmpty()) append(session, "rename: provide a name\n", RED);
            else {
                session.name = newName.length() > 14 ? newName.substring(0, 14) : newName;
                rebuildTabs();
                updateStatus();
            }
            return true;
        }
        if (command.equals("about")) {
            append(session,
                    "Kai Terminal 3.1.0\n" +
                    "Local Android shell with persistent multi-session workspace.\n" +
                    "Built-in AI CLIs: OpenCode, OpenAI Codex CLI, Google Antigravity CLI.\n" +
                    "Commands run inside Android app permissions. No root required.\n",
                    TEXT);
            return true;
        }
        if (command.equals("exit")) {
            finish();
            return true;
        }
        if (command.equals("cd") || command.startsWith("cd ")) {
            changeDirectory(session, command);
            return true;
        }
        return false;
    }

    private void runShell(TerminalSession session, String command) {
        session.running = true;
        session.cancelRequested = false;
        refreshAll();

        session.executor.execute(() -> {
            int exitCode = -1;
            try {
                prepareNetworkRuntime();
                String expandedCommand = expandBundledCli(command);
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", expandedCommand);
                pb.directory(session.currentDir);
                pb.redirectErrorStream(true);
                pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
                pb.environment().put("TERM", "dumb");
                pb.environment().put("NO_COLOR", "1");
                pb.environment().put("CLICOLOR", "0");
                pb.environment().put("SHELL", "/system/bin/sh");
                pb.environment().put("GODEBUG", "netdns=go");
                pb.environment().put("KAI_RESOLV_CONF",
                        new File(getFilesDir(), "resolv.conf").getAbsolutePath());
                pb.environment().put("KAI_CA_FILE",
                        new File(getFilesDir(), "kai-ca.pem").getAbsolutePath());
                pb.environment().put("KAI_GLIBC_DIR",
                        new File(getFilesDir(), "agy-runtime").getAbsolutePath());
                pb.environment().put("PROOT_TMP_DIR", getCacheDir().getAbsolutePath());
                pb.environment().put("PATH",
                        getApplicationInfo().nativeLibraryDir + ":/system/bin:/system/xbin");

                Process process = pb.start();
                session.currentProcess = process;
                session.stdin = process.getOutputStream();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String chunk = stripAnsi(line) + "\n";
                        runOnUiThread(() -> append(session, chunk, TEXT));
                    }
                }

                exitCode = process.waitFor();
            } catch (Exception e) {
                final String message = e.getClass().getSimpleName() + ": " +
                        (e.getMessage() == null ? "command failed" : e.getMessage()) + "\n";
                runOnUiThread(() -> append(session, message, RED));
            } finally {
                int code = exitCode;
                boolean cancelled = session.cancelRequested;
                session.currentProcess = null;
                session.stdin = null;
                session.running = false;
                session.cancelRequested = false;

                runOnUiThread(() -> {
                    if (cancelled) {
                        append(session, "[terminated]\n", AMBER);
                    } else if (code > 0) {
                        append(session, "[exit " + code + "]\n", MUTED);
                    }
                    refreshAll();
                    persistState();
                    if (activeSession() == session) input.requestFocus();
                });
            }
        });
    }

    private void cancelActiveCommand() {
        cancelCommand(activeSession());
    }

    private void cancelCommand(TerminalSession session) {
        if (!session.running) {
            append(session, "^C\n", MUTED);
            return;
        }
        session.cancelRequested = true;
        append(session, "^C\n", AMBER);
        Process process = session.currentProcess;
        try {
            if (session.stdin != null) {
                session.stdin.write(3);
                session.stdin.flush();
            }
        } catch (Exception ignored) {}
        if (process != null) {
            try {
                process.destroy();
                if (process.isAlive()) process.destroyForcibly();
            } catch (Exception ignored) {}
        }
        refreshAll();
    }

    private void sendToRunningProcess(TerminalSession session, String line) {
        try {
            OutputStream stream = session.stdin;
            if (stream == null) {
                append(session, "[stdin unavailable]\n", RED);
                return;
            }
            stream.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stream.flush();
            append(session, "› " + line + "\n", MUTED);
        } catch (Exception e) {
            append(session, "[stdin error: " + e.getMessage() + "]\n", RED);
        }
    }

    private String expandBundledCli(String command) {
        String nativeDir = getApplicationInfo().nativeLibraryDir;

        String codex = shellQuote(new File(nativeDir, "libcodex.so").getAbsolutePath());
        String opencode = shellQuote(new File(nativeDir, "libopencode.so").getAbsolutePath());
        String musl = shellQuote(new File(nativeDir, "libmusl-loader.so").getAbsolutePath());

        String agy = shellQuote(new File(nativeDir, "libagy.so").getAbsolutePath());
        String agyLoader = shellQuote(new File(nativeDir, "libagyld.so").getAbsolutePath());
        String proot = shellQuote(new File(nativeDir, "libproot.so").getAbsolutePath());
        String prootLoader = shellQuote(new File(nativeDir, "libproot-loader.so").getAbsolutePath());
        String libraryPath = shellQuote(nativeDir);

        StringBuilder prelude = new StringBuilder();
        prelude.append("codex() { ").append(codex).append(" \"$@\"; }\n");
        prelude.append("opencode() { ").append(musl)
                .append(" --library-path ").append(libraryPath).append(" ")
                .append(opencode).append(" \"$@\"; }\n");
        prelude.append("agy() { ")
                .append("SSL_CERT_FILE=\"$KAI_CA_FILE\" GODEBUG=netdns=go ")
                .append("PROOT_NO_SECCOMP=1 PROOT_LOADER=").append(prootLoader).append(" ")
                .append(proot)
                .append(" -b \"$KAI_RESOLV_CONF:/etc/resolv.conf\" ")
                .append(" -b /system/bin/sh:/bin/sh ")
                .append(agyLoader)
                .append(" --library-path \"$KAI_GLIBC_DIR\":").append(libraryPath).append(" ")
                .append(agy).append(" \"$@\"; }\n");

        prelude.append(command);
        return prelude.toString();
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void showAiHub(TerminalSession session) {
        append(session, "AI CLI HUB · BUILT-IN\n", GREEN);
        append(session, "  opencode   OpenCode CLI (ARM64 musl)\n", TEXT);
        append(session, "  codex      OpenAI Codex CLI (ARM64 musl)\n", TEXT);
        append(session, "  agy        Google Antigravity CLI (Android patched)\n\n", TEXT);
        append(session, "Quick one-shot usage:\n", BLUE);
        append(session, "  ask-opencode Explain this folder\n", TEXT);
        append(session, "  ask-codex Review this project\n", TEXT);
        append(session, "  ask-agy Find the bug in this code\n\n", TEXT);
        append(session, "Native commands:\n", BLUE);
        append(session, "  opencode --version\n", TEXT);
        append(session, "  opencode run \"Explain this repository\"\n", TEXT);
        append(session, "  codex --version\n", TEXT);
        append(session, "  codex exec \"Explain this repository\"\n", TEXT);
        append(session, "  agy --version\n", TEXT);
        append(session, "  agy -p \"Explain this repository\"\n\n", TEXT);
        append(session, "Run ai doctor to verify the bundled executables.\n", MUTED);
    }

    private void runAiDoctor(TerminalSession session) {
        append(session, "AI Doctor · runtime self-test\n", GREEN);
        append(session, "Testing the actual executables, not only checking files...\n", MUTED);
        runShell(session,
                "set -e\n" +
                "printf 'Codex: '; codex --version\n" +
                "printf 'OpenCode: '; opencode --version\n" +
                "printf 'Antigravity: '; agy --version\n" +
                "printf 'All AI runtimes: READY\\n'");
    }

    private synchronized void prepareNetworkRuntime() {
        writeResolverConfig();
        writeSystemCaBundle();
        prepareGlibcRuntimeLinks();
    }

    private void prepareGlibcRuntimeLinks() {
        File runtimeDir = new File(getFilesDir(), "agy-runtime");
        if (!runtimeDir.exists() && !runtimeDir.mkdirs()) return;

        String nativeDir = getApplicationInfo().nativeLibraryDir;
        String[][] links = new String[][]{
                {"ld-linux-aarch64.so.1", "libagyld.so"},
                {"libc.so.6", "libagyc.so"},
                {"libm.so.6", "libagym.so"},
                {"libpthread.so.0", "libagypthread.so"},
                {"libdl.so.2", "libagydl.so"},
                {"librt.so.1", "libagyrt.so"},
                {"libresolv.so.2", "libagyresolv.so"},
                {"libnss_dns.so.2", "libagynssdns.so"},
                {"libnss_files.so.2", "libagynssfiles.so"},
                {"libgcc_s.so.1", "libagygcc.so"},
                {"libstdc++.so.6", "libagystdcpp.so"}
        };

        for (String[] entry : links) {
            File link = new File(runtimeDir, entry[0]);
            File target = new File(nativeDir, entry[1]);
            if (!target.exists()) continue;

            try {
                if (link.exists() || android.system.Os.lstat(link.getAbsolutePath()) != null) {
                    try {
                        String existing = android.system.Os.readlink(link.getAbsolutePath());
                        if (target.getAbsolutePath().equals(existing)) continue;
                    } catch (Exception ignored) {}
                    try { android.system.Os.unlink(link.getAbsolutePath()); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}

            try {
                Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
            } catch (Exception ignored) {}
        }
    }

    private void writeResolverConfig() {
        File target = new File(getFilesDir(), "resolv.conf");
        LinkedHashSet<String> servers = new LinkedHashSet<>();

        try {
            ConnectivityManager cm =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = cm == null ? null : cm.getActiveNetwork();
            LinkProperties props =
                    (cm == null || network == null) ? null : cm.getLinkProperties(network);
            if (props != null) {
                for (java.net.InetAddress dns : props.getDnsServers()) {
                    if (dns != null && dns.getHostAddress() != null) {
                        servers.add(dns.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}

        if (servers.isEmpty()) {
            servers.add("8.8.8.8");
            servers.add("1.1.1.1");
        }

        try (PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(target, false),
                        java.nio.charset.StandardCharsets.UTF_8))) {
            for (String server : servers) {
                writer.println("nameserver " + server);
            }
            writer.println("options timeout:2 attempts:2");
        } catch (Exception ignored) {}
    }

    private void writeSystemCaBundle() {
        File target = new File(getFilesDir(), "kai-ca.pem");
        File sourceDir = new File("/system/etc/security/cacerts");
        File[] certs = sourceDir.listFiles();
        if (certs == null || certs.length == 0) return;

        Arrays.sort(certs, Comparator.comparing(File::getName));
        try (FileOutputStream outputStream = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            for (File cert : certs) {
                if (!cert.isFile() || !cert.canRead()) continue;
                try (FileInputStream inputStream = new FileInputStream(cert)) {
                    int read;
                    while ((read = inputStream.read(buffer)) > 0) {
                        outputStream.write(buffer, 0, read);
                    }
                    outputStream.write('\n');
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private String stripAnsi(String value) {
        if (value == null) return "";
        return value
                .replaceAll("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)", "")
                .replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "")
                .replace("\r", "");
    }

    private void changeDirectory(TerminalSession session, String command) {
        String path = command.length() <= 2
                ? getFilesDir().getAbsolutePath()
                : command.substring(2).trim();

        if (path.equals("~")) path = getFilesDir().getAbsolutePath();

        File target = path.startsWith("/")
                ? new File(path)
                : new File(session.currentDir, path);
        try {
            target = target.getCanonicalFile();
            if (!target.exists()) {
                append(session, "cd: no such file or directory: " + path + "\n", RED);
            } else if (!target.isDirectory()) {
                append(session, "cd: not a directory: " + path + "\n", RED);
            } else if (!target.canRead()) {
                append(session, "cd: permission denied: " + path + "\n", RED);
            } else {
                session.currentDir = target;
                refreshAll();
            }
        } catch (Exception e) {
            append(session, "cd: " + e.getMessage() + "\n", RED);
        }
    }

    private void showFiles(TerminalSession session) {
        File[] children = session.currentDir.listFiles();
        if (children == null) {
            append(session, "files: directory cannot be read\n", RED);
            return;
        }

        List<File> list = new ArrayList<>(Arrays.asList(children));
        list.sort(Comparator
                .comparing((File f) -> !f.isDirectory())
                .thenComparing(f -> f.getName().toLowerCase(Locale.ROOT)));

        if (list.isEmpty()) {
            append(session, "(empty)\n", MUTED);
            return;
        }

        int shown = 0;
        for (File file : list) {
            if (shown >= 200) {
                append(session, "… more items omitted\n", MUTED);
                break;
            }
            append(session,
                    (file.isDirectory() ? "d  " : "-  ") +
                            file.getName() +
                            (file.isDirectory() ? "/" : "") + "\n",
                    file.isDirectory() ? BLUE : TEXT);
            shown++;
        }
    }

    private void showHelp(TerminalSession session) {
        append(session, "Kai Terminal v3 built-ins\n", GREEN);
        append(session, "  ai                show built-in AI CLI hub\n", TEXT);
        append(session, "  ai doctor         verify bundled AI binaries\n", TEXT);
        append(session, "  codex ...         OpenAI Codex CLI\n", TEXT);
        append(session, "  opencode ...      OpenCode CLI\n", TEXT);
        append(session, "  agy ...           Google Antigravity CLI\n", TEXT);
        append(session, "  ask-codex <text>  one-shot Codex prompt\n", TEXT);
        append(session, "  ask-opencode <t>  one-shot OpenCode prompt\n", TEXT);
        append(session, "  ask-agy <text>    one-shot Antigravity prompt\n", TEXT);
        append(session, "  help              command reference\n", TEXT);
        append(session, "  clear             clear active session output\n", TEXT);
        append(session, "  pwd               print working directory\n", TEXT);
        append(session, "  cd <dir>          change directory\n", TEXT);
        append(session, "  home              jump to app home\n", TEXT);
        append(session, "  files             list current directory\n", TEXT);
        append(session, "  history           persistent command history\n", TEXT);
        append(session, "  sessions          list sessions\n", TEXT);
        append(session, "  new               create session\n", TEXT);
        append(session, "  close             close active session\n", TEXT);
        append(session, "  session <n>       switch session\n", TEXT);
        append(session, "  rename <name>     rename active session\n", TEXT);
        append(session, "  about             runtime information\n", TEXT);
        append(session, "  exit              close Kai Terminal\n\n", TEXT);

        append(session, "V2 controls\n", BLUE);
        append(session, "  TAB       autocomplete command/file names\n", TEXT);
        append(session, "  CTRL-C    terminate the active command\n", TEXT);
        append(session, "  ↑ / ↓     browse persistent history\n", TEXT);
        append(session, "  COPY      copy active terminal output\n", TEXT);
        append(session, "  SHARE     share active terminal output\n\n", TEXT);

        append(session,
                "Shell examples: ls -la · date · id · getprop ro.product.model · df -h\n",
                MUTED);
    }

    private void showHistory(TerminalSession session) {
        if (history.isEmpty()) {
            append(session, "history is empty\n", MUTED);
            return;
        }
        int start = Math.max(0, history.size() - 100);
        for (int i = start; i < history.size(); i++) {
            append(session,
                    String.format(Locale.US, "%3d  %s\n", i + 1, history.get(i)),
                    TEXT);
        }
    }

    private void showSessions(TerminalSession session) {
        for (int i = 0; i < sessions.size(); i++) {
            TerminalSession item = sessions.get(i);
            append(session,
                    String.format(Locale.US, "%s %d  %-14s  %s\n",
                            i == activeIndex ? "*" : " ",
                            i + 1,
                            item.name,
                            item.running ? "RUNNING" : "READY"),
                    item.running ? AMBER : TEXT);
        }
    }

    private void switchByCommand(TerminalSession session, String command) {
        try {
            int index = Integer.parseInt(command.substring(8).trim()) - 1;
            if (index < 0 || index >= sessions.size()) {
                append(session, "session: index out of range\n", RED);
                return;
            }
            switchSession(index);
        } catch (Exception e) {
            append(session, "session: use session <number>\n", RED);
        }
    }

    private void addHistory(String command) {
        if (history.isEmpty() || !history.get(history.size() - 1).equals(command)) {
            history.add(command);
        }
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
        historyIndex = history.size();
    }

    private void previousHistory() {
        if (history.isEmpty()) return;
        historyIndex = Math.max(0, historyIndex - 1);
        setInput(history.get(historyIndex));
    }

    private void nextHistory() {
        if (history.isEmpty()) return;
        historyIndex = Math.min(history.size(), historyIndex + 1);
        if (historyIndex >= history.size()) setInput("");
        else setInput(history.get(historyIndex));
    }

    private void completeInput() {
        String source = input.getText().toString();
        int cursor = input.getSelectionStart();
        if (cursor < 0) cursor = source.length();

        String before = source.substring(0, cursor);
        int tokenStart = before.length();
        while (tokenStart > 0 && !Character.isWhitespace(before.charAt(tokenStart - 1))) {
            tokenStart--;
        }

        String token = before.substring(tokenStart);
        List<String> matches = completionMatches(activeSession(), token);
        if (matches.isEmpty()) return;

        if (matches.size() == 1) {
            replaceToken(source, tokenStart, cursor, matches.get(0));
            return;
        }

        String common = longestCommonPrefix(matches);
        if (common.length() > token.length()) {
            replaceToken(source, tokenStart, cursor, common);
        } else {
            append(activeSession(), String.join("    ", matches) + "\n", BLUE);
        }
    }

    private List<String> completionMatches(TerminalSession session, String token) {
        Set<String> candidates = new LinkedHashSet<>();

        boolean pathMode = token.contains("/") || token.startsWith(".");
        if (!pathMode) {
            candidates.addAll(Arrays.asList(COMMON_COMMANDS));
            candidates.addAll(Arrays.asList(
                    "help", "clear", "history", "home", "files", "sessions",
                    "new", "close", "session", "rename", "about", "exit",
                    "ai", "opencode", "codex", "agy", "ask-opencode", "ask-codex", "ask-agy"
            ));
        }

        try {
            String parentPart = "";
            String namePrefix = token;
            File parent = session.currentDir;

            int slash = token.lastIndexOf('/');
            if (slash >= 0) {
                parentPart = token.substring(0, slash + 1);
                namePrefix = token.substring(slash + 1);
                String parentPath = token.substring(0, slash);
                if (parentPath.isEmpty() && token.startsWith("/")) {
                    parent = new File("/");
                } else if (!parentPath.isEmpty()) {
                    parent = parentPath.startsWith("/")
                            ? new File(parentPath)
                            : new File(session.currentDir, parentPath);
                }
            }

            File[] files = parent.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith(namePrefix)) {
                        candidates.add(parentPart + f.getName() + (f.isDirectory() ? "/" : ""));
                    }
                }
            }
        } catch (Exception ignored) {}

        List<String> matches = new ArrayList<>();
        for (String c : candidates) {
            if (c.startsWith(token)) matches.add(c);
        }
        Collections.sort(matches);
        if (matches.size() > 30) return new ArrayList<>(matches.subList(0, 30));
        return matches;
    }

    private String longestCommonPrefix(List<String> values) {
        if (values.isEmpty()) return "";
        String prefix = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            String value = values.get(i);
            int len = Math.min(prefix.length(), value.length());
            int j = 0;
            while (j < len && prefix.charAt(j) == value.charAt(j)) j++;
            prefix = prefix.substring(0, j);
            if (prefix.isEmpty()) break;
        }
        return prefix;
    }

    private void replaceToken(String source, int start, int end, String replacement) {
        String updated = source.substring(0, start) + replacement + source.substring(end);
        input.setText(updated);
        input.setSelection(Math.min(updated.length(), start + replacement.length()));
    }

    private void insertText(String value) {
        int start = Math.max(0, input.getSelectionStart());
        int end = Math.max(0, input.getSelectionEnd());
        Editable editable = input.getText();
        editable.replace(Math.min(start, end), Math.max(start, end), value);
    }

    private void createSession(boolean switchToIt) {
        if (sessions.size() >= MAX_SESSIONS) {
            Toast.makeText(this, "Maximum " + MAX_SESSIONS + " sessions", Toast.LENGTH_SHORT).show();
            return;
        }

        int nextId = 1;
        for (TerminalSession s : sessions) nextId = Math.max(nextId, s.id + 1);

        TerminalSession session = new TerminalSession(
                nextId,
                "S" + nextId,
                activeSession().currentDir
        );
        sessions.add(session);
        printBanner(session);

        if (switchToIt) activeIndex = sessions.size() - 1;
        refreshAll();
        persistState();
    }

    private void closeSession(int index) {
        if (sessions.size() <= 1) {
            Toast.makeText(this, "Keep at least one session", Toast.LENGTH_SHORT).show();
            return;
        }
        if (index < 0 || index >= sessions.size()) return;

        TerminalSession session = sessions.get(index);
        cancelCommand(session);
        session.executor.shutdownNow();
        sessions.remove(index);

        if (activeIndex >= sessions.size()) activeIndex = sessions.size() - 1;
        else if (index < activeIndex) activeIndex--;

        refreshAll();
        persistState();
    }

    private void switchSession(int index) {
        if (index < 0 || index >= sessions.size() || index == activeIndex) return;
        activeSession().draft = input.getText().toString();
        activeIndex = index;
        refreshAll();
        input.requestFocus();
        persistState();
    }

    private void rebuildTabs() {
        if (tabsRow == null) return;
        tabsRow.removeAllViews();

        for (int i = 0; i < sessions.size(); i++) {
            final int index = i;
            TerminalSession session = sessions.get(i);

            Button tab = new Button(this);
            String indicator = session.running ? " ●" : "";
            tab.setText(session.name + indicator);
            tab.setTextSize(11f);
            tab.setTypeface(Typeface.MONOSPACE, i == activeIndex ? Typeface.BOLD : Typeface.NORMAL);
            tab.setTextColor(i == activeIndex ? BG : (session.running ? AMBER : TEXT));
            tab.setAllCaps(false);
            tab.setMinHeight(0);
            tab.setMinimumHeight(0);
            tab.setMinWidth(0);
            tab.setMinimumWidth(0);
            tab.setPadding(dp(13), dp(7), dp(13), dp(7));
            tab.setBackground(rounded(i == activeIndex ? GREEN : PANEL, BORDER, 18));
            tab.setOnClickListener(v -> switchSession(index));
            tab.setOnLongClickListener(v -> {
                closeSession(index);
                return true;
            });

            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            p.setMargins(0, 0, dp(6), 0);
            tabsRow.addView(tab, p);
        }
    }

    private void refreshAll() {
        if (sessions.isEmpty() || output == null) return;
        TerminalSession session = activeSession();
        output.setText(session.buffer);
        cwdLabel.setText(session.currentDir.getAbsolutePath());

        if (!input.getText().toString().equals(session.draft)) {
            input.setText(session.draft);
            input.setSelection(input.length());
        }

        runButton.setText(session.running ? "STOP" : "RUN");
        runButton.setTextColor(session.running ? RED : GREEN);
        runButton.setBackground(rounded(PANEL_2, session.running ? RED : GREEN, 10));

        rebuildTabs();
        updateStatus();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void updateStatus() {
        if (statusLabel == null) return;
        int running = 0;
        for (TerminalSession s : sessions) if (s.running) running++;
        statusLabel.setText(
                "LOCAL SHELL  ·  " +
                sessions.size() + (sessions.size() == 1 ? " SESSION" : " SESSIONS") +
                "  ·  " + (running == 0 ? "READY" : running + " RUNNING")
        );
    }

    private void printBanner(TerminalSession session) {
        appendRaw(session, "Kai Terminal 3.1.0\n", GREEN);
        appendRaw(session, "AI-native Android shell · ARM64 edition\n", MUTED);
        appendRaw(session, "Built in: opencode · codex · agy\n", BLUE);
        appendRaw(session, "TAB autocomplete · CTRL-C cancel · type 'ai' or 'help'\n\n", MUTED);
    }

    private void clearOutput() {
        TerminalSession session = activeSession();
        session.buffer.clear();
        output.setText(session.buffer);
        persistState();
    }

    private void copyOutput() {
        String terminalText = activeSession().buffer.toString();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Kai Terminal output", terminalText));
        Toast.makeText(this, "Terminal output copied", Toast.LENGTH_SHORT).show();
    }

    private void shareOutput() {
        String terminalText = activeSession().buffer.toString();
        if (terminalText.isEmpty()) {
            Toast.makeText(this, "Nothing to share", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, terminalText);
        startActivity(Intent.createChooser(intent, "Share terminal output"));
    }

    private void append(TerminalSession session, String value, int color) {
        appendRaw(session, value, color);
        if (session == activeSession()) {
            output.setText(session.buffer);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void appendRaw(TerminalSession session, String value, int color) {
        SpannableString span = new SpannableString(value);
        span.setSpan(new ForegroundColorSpan(color), 0, value.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        session.buffer.append(span);

        if (session.buffer.length() > MAX_BUFFER) {
            int remove = session.buffer.length() - MAX_BUFFER + 20_000;
            if (remove > 0 && remove < session.buffer.length()) {
                session.buffer.delete(0, remove);
                SpannableString notice = new SpannableString("[older output trimmed]\n");
                notice.setSpan(new ForegroundColorSpan(MUTED), 0, notice.length(),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                session.buffer.insert(0, notice);
            }
        }
    }

    private TerminalSession activeSession() {
        return sessions.get(activeIndex);
    }

    private File safeDirectory(String path) {
        try {
            File file = new File(path).getCanonicalFile();
            if (file.exists() && file.isDirectory() && file.canRead()) return file;
        } catch (Exception ignored) {}
        return getFilesDir();
    }

    private void setInput(String value) {
        input.setText(value);
        input.setSelection(input.length());
    }

    private Button chip(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(10f);
        b.setTextColor(TEXT);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(11), dp(6), dp(11), dp(6));
        b.setBackground(rounded(PANEL_2, BORDER, 14));
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, dp(6), 0);
        b.setLayoutParams(p);
        return b;
    }

    private Button actionButton(String label, View.OnClickListener listener, int accent) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(10f);
        b.setTextColor(accent);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setAllCaps(false);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), dp(7), dp(12), dp(7));
        b.setBackground(rounded(PANEL_2, accent, 10));
        b.setOnClickListener(listener);
        return b;
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.MONOSPACE, style);
        return t;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onPause() {
        persistState();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        for (TerminalSession session : sessions) {
            Process process = session.currentProcess;
            if (process != null) {
                try { process.destroyForcibly(); } catch (Exception ignored) {}
            }
            session.executor.shutdownNow();
        }
        persistState();
        super.onDestroy();
    }
}
