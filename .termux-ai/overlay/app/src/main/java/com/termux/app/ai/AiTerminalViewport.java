package com.termux.app.ai;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.json.JSONObject;

import java.util.Locale;

public final class AiTerminalViewport {

    private final TermuxActivity activity;
    private final WebView webView;
    private final TerminalView terminalView;
    private final View nativeComposer;
    private boolean pageReady;
    private String lastPayload;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    public AiTerminalViewport(TermuxActivity activity) {
        this.activity = activity;
        this.webView = activity.findViewById(R.id.ai_terminal_viewport);
        this.terminalView = activity.findViewById(R.id.terminal_view);
        this.nativeComposer = activity.findViewById(R.id.native_command_composer);

        if (webView == null) return;

        webView.setBackgroundColor(Color.rgb(9, 10, 12));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        webView.addJavascriptInterface(new Bridge(), "TermuxNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return url == null || !url.startsWith("file:///android_asset/terminal-ui/");
            }
        });

        webView.loadUrl("file:///android_asset/terminal-ui/index.html");
    }

    public void destroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("TermuxNative");
            webView.destroy();
        }
    }

    public void onSessionChanged(TerminalSession session) {
        if (webView == null || terminalView == null) return;

        boolean agent = isAgentSession(session);
        webView.setVisibility(agent ? View.VISIBLE : View.GONE);
        terminalView.setAlpha(agent ? 0f : 1f);
        terminalView.setImportantForAccessibility(agent
            ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);

        if (nativeComposer != null) {
            nativeComposer.setVisibility(agent ? View.GONE : View.VISIBLE);
        }

        if (agent) {
            render(session);
        }
    }

    public void onOutputChanged(TerminalSession session) {
        if (!isAgentSession(session)) return;
        if (activity.getCurrentSession() != session) return;
        render(session);
    }

    public boolean isAgentSession(TerminalSession session) {
        if (session == null || session.mSessionName == null) return false;
        String name = session.mSessionName.toLowerCase(Locale.ROOT);
        return name.contains("codex")
            || name.contains("claude")
            || name.contains("grok")
            || name.contains("opencode")
            || name.contains("antigravity")
            || name.contains("gemini");
    }

    private String providerFor(TerminalSession session) {
        if (session == null || session.mSessionName == null) return "generic";
        String name = session.mSessionName.toLowerCase(Locale.ROOT);
        if (name.contains("claude")) return "claude";
        if (name.contains("grok")) return "grok";
        return "codex";
    }

    private void render(TerminalSession session) {
        if (webView == null || session == null || !pageReady) return;

        try {
            String transcript = "";
            TerminalEmulator emulator = session.getEmulator();
            if (emulator != null && emulator.getScreen() != null) {
                transcript = emulator.getScreen().getTranscriptText();
            }

            JSONObject payload = new JSONObject();
            payload.put("provider", providerFor(session));
            payload.put("sessionName", session.mSessionName == null ? "AI Session" : session.mSessionName);
            payload.put("cwd", session.getCwd() == null ? "~" : session.getCwd());
            payload.put("transcript", transcript == null ? "" : transcript);
            payload.put("running", session.isRunning());

            String json = payload.toString();
            if (json.equals(lastPayload)) return;
            lastPayload = json;

            webView.post(() ->
                webView.evaluateJavascript(
                    "window.TermuxAI && window.TermuxAI.render(" + json + ");",
                    null
                )
            );
        } catch (Exception ignored) {
            // The fallback native terminal remains available if rendering fails.
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void ready() {
            pageReady = true;
            TerminalSession session = activity.getCurrentSession();
            if (session != null) render(session);
        }

        @JavascriptInterface
        public void sendInput(String value) {
            activity.runOnUiThread(() -> {
                TerminalSession session = activity.getCurrentSession();
                if (session == null || value == null) return;
                session.write(value + "\r");
            });
        }

        @JavascriptInterface
        public void interrupt() {
            activity.runOnUiThread(() -> {
                TerminalSession session = activity.getCurrentSession();
                if (session == null) return;
                session.write("\u0003");
            });
        }
    }
}
