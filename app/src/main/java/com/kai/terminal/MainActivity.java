package com.kai.terminal;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(9, 12, 16);
    private static final int PANEL = Color.rgb(14, 19, 25);
    private static final int TEXT = Color.rgb(218, 225, 232);
    private static final int MUTED = Color.rgb(124, 138, 153);
    private static final int GREEN = Color.rgb(115, 245, 156);
    private static final int BLUE = Color.rgb(105, 183, 255);
    private static final int RED = Color.rgb(255, 117, 117);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<String> history = new ArrayList<>();
    private SpannableStringBuilder buffer;
    private TextView output;
    private ScrollView scroll;
    private EditText input;
    private TextView cwdLabel;
    private int historyIndex = 0;
    private File currentDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        currentDir = getFilesDir();
        buffer = new SpannableStringBuilder();
        buildUi();
        printBanner();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(12), dp(14), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(2), dp(2), dp(2), dp(10));

        TextView title = text("KAI TERMINAL", 15, GREEN, Typeface.BOLD);
        title.setLetterSpacing(0.16f);
        header.addView(title);

        cwdLabel = text("", 11, MUTED, Typeface.NORMAL);
        cwdLabel.setPadding(0, dp(4), 0, 0);
        header.addView(cwdLabel);
        root.addView(header);

        output = text("", 13, TEXT, Typeface.NORMAL);
        output.setTextIsSelectable(true);
        output.setLineSpacing(0f, 1.12f);
        output.setPadding(dp(12), dp(12), dp(12), dp(12));
        output.setBackgroundColor(PANEL);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(output, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(Gravity.END);
        quickRow.setPadding(0, dp(8), 0, dp(7));
        quickRow.addView(smallButton("↑", v -> previousHistory()));
        quickRow.addView(smallButton("↓", v -> nextHistory()));
        quickRow.addView(smallButton("CLEAR", v -> clearOutput()));
        root.addView(quickRow);

        LinearLayout commandRow = new LinearLayout(this);
        commandRow.setOrientation(LinearLayout.HORIZONTAL);
        commandRow.setGravity(Gravity.CENTER_VERTICAL);
        commandRow.setBackgroundColor(PANEL);
        commandRow.setPadding(dp(10), dp(2), dp(6), dp(2));

        TextView prompt = text("$", 15, GREEN, Typeface.BOLD);
        prompt.setPadding(0, 0, dp(8), 0);
        commandRow.addView(prompt);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setHint("type a command…");
        input.setTextSize(14f);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        input.setPadding(0, dp(8), dp(6), dp(8));
        commandRow.addView(input, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        commandRow.addView(smallButton("RUN", v -> submit()));
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
            @Override public void afterTextChanged(Editable s) { historyIndex = history.size(); }
        });

        setContentView(root);
        updateCwd();
        input.requestFocus();
    }

    private void printBanner() {
        append("Kai Terminal 1.0\n", GREEN);
        append("Android local shell · app sandbox · no root\n", MUTED);
        append("Type 'help' to see built-in commands.\n\n", MUTED);
    }

    private void submit() {
        String command = input.getText().toString().trim();
        if (command.isEmpty()) return;
        input.setText("");
        history.add(command);
        historyIndex = history.size();
        append("$ " + command + "\n", GREEN);

        if (command.equals("clear")) { clearOutput(); return; }
        if (command.equals("help")) { showHelp(); return; }
        if (command.equals("history")) { showHistory(); return; }
        if (command.equals("pwd")) { append(currentDir.getAbsolutePath() + "\n", TEXT); return; }
        if (command.equals("about")) {
            append("Kai Terminal runs commands through /system/bin/sh inside this app's Android sandbox.\n", TEXT);
            return;
        }
        if (command.equals("exit")) { finish(); return; }
        if (command.equals("cd") || command.startsWith("cd ")) { changeDirectory(command); return; }

        runShell(command);
    }

    private void runShell(String command) {
        input.setEnabled(false);
        executor.execute(() -> {
            StringBuffer result = new StringBuffer();
            int exitCode = -1;
            boolean timedOut = false;
            try {
                ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
                pb.directory(currentDir);
                pb.redirectErrorStream(true);
                pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
                Process process = pb.start();

                Thread readerThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        boolean truncated = false;
                        while ((line = reader.readLine()) != null) {
                            if (!truncated) {
                                result.append(line).append('\n');
                                if (result.length() > 120_000) {
                                    result.append("[output truncated]\n");
                                    truncated = true;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }, "kai-terminal-reader");
                readerThread.start();

                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    timedOut = true;
                    process.destroyForcibly();
                } else {
                    exitCode = process.exitValue();
                }
                readerThread.join(1000);
            } catch (Exception e) {
                result.append(e.getClass().getSimpleName()).append(": ")
                        .append(e.getMessage() == null ? "command failed" : e.getMessage())
                        .append('\n');
            }

            final int code = exitCode;
            final boolean timeout = timedOut;
            final String resultText = result.toString();
            runOnUiThread(() -> {
                if (!resultText.isEmpty()) append(resultText, code == 0 ? TEXT : RED);
                if (timeout) append("command timed out after 20s\n", RED);
                else if (code > 0) append("[exit " + code + "]\n", MUTED);
                input.setEnabled(true);
                input.requestFocus();
            });
        });
    }

    private void changeDirectory(String command) {
        String path = command.length() <= 2 ? getFilesDir().getAbsolutePath() : command.substring(2).trim();
        File target = path.startsWith("/") ? new File(path) : new File(currentDir, path);
        try {
            target = target.getCanonicalFile();
            if (!target.exists()) append("cd: no such file or directory: " + path + "\n", RED);
            else if (!target.isDirectory()) append("cd: not a directory: " + path + "\n", RED);
            else if (!target.canRead()) append("cd: permission denied: " + path + "\n", RED);
            else { currentDir = target; updateCwd(); }
        } catch (Exception e) {
            append("cd: " + e.getMessage() + "\n", RED);
        }
    }

    private void showHelp() {
        append("Built-ins:\n", BLUE);
        append("  help       show this help\n", TEXT);
        append("  clear      clear terminal output\n", TEXT);
        append("  pwd        print working directory\n", TEXT);
        append("  cd <dir>   change working directory\n", TEXT);
        append("  history    show command history\n", TEXT);
        append("  about      terminal runtime info\n", TEXT);
        append("  exit       close the app\n", TEXT);
        append("\nShell examples:\n", BLUE);
        append("  ls -la\n  echo hello\n  date\n  id\n  getprop ro.product.model\n\n", TEXT);
        append("Commands execute without root and remain constrained by Android permissions.\n", MUTED);
    }

    private void showHistory() {
        if (history.isEmpty()) { append("history is empty\n", MUTED); return; }
        for (int i = 0; i < history.size(); i++) {
            append(String.format(Locale.US, "%3d  %s\n", i + 1, history.get(i)), TEXT);
        }
    }

    private void previousHistory() {
        if (history.isEmpty()) return;
        historyIndex = Math.max(0, historyIndex - 1);
        input.setText(history.get(historyIndex));
        input.setSelection(input.length());
    }

    private void nextHistory() {
        if (history.isEmpty()) return;
        historyIndex = Math.min(history.size(), historyIndex + 1);
        if (historyIndex >= history.size()) input.setText("");
        else {
            input.setText(history.get(historyIndex));
            input.setSelection(input.length());
        }
    }

    private void clearOutput() {
        buffer.clear();
        output.setText(buffer);
    }

    private void append(String text, int color) {
        SpannableString span = new SpannableString(text);
        span.setSpan(new ForegroundColorSpan(color), 0, text.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        buffer.append(span);
        output.setText(buffer);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void updateCwd() {
        cwdLabel.setText("LOCAL SANDBOX  ·  " + currentDir.getAbsolutePath());
    }

    private Button smallButton(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11f);
        b.setTextColor(TEXT);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setAllCaps(false);
        b.setBackgroundColor(PANEL);
        b.setPadding(dp(10), dp(1), dp(10), dp(1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
        p.setMargins(dp(5), 0, 0, 0);
        b.setLayoutParams(p);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
