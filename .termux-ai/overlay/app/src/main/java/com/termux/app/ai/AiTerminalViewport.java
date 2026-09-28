package com.termux.app.ai;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.net.Uri;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.json.JSONObject;

import java.util.Locale;

public final class AiTerminalViewport {

    private static final String APP_ASSET_URL =
        "https://appassets.androidplatform.net/assets/terminal-ui/index.html";

    private final TermuxActivity activity;
    private final WebView webView;
    private final TerminalView terminalView;
    private final View nativeComposer;
    private final View terminalToolbar;
    private final View loadingPanel;
    private final android.widget.TextView loadingText;
    private final View rawTerminalButton;
    private final int terminalToolbarDefaultVisibility;

    private volatile boolean pageReady;
    private String lastPayload;
    private boolean rawTerminalOverride;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    public AiTerminalViewport(TermuxActivity activity) {
        this.activity = activity;
        this.webView = activity.findViewById(R.id.ai_terminal_viewport);
        this.terminalView = activity.findViewById(R.id.terminal_view);
        this.nativeComposer = activity.findViewById(R.id.native_command_composer);
        this.terminalToolbar = activity.findViewById(R.id.terminal_toolbar_view_pager);
        this.loadingPanel = activity.findViewById(R.id.ai_terminal_loading_panel);
        this.loadingText = activity.findViewById(R.id.ai_terminal_loading_text);
        this.rawTerminalButton = activity.findViewById(R.id.ai_raw_terminal_button);
        this.terminalToolbarDefaultVisibility = terminalToolbar == null
            ? View.GONE
            : terminalToolbar.getVisibility();

        if (rawTerminalButton != null) {
            rawTerminalButton.setOnClickListener(v -> {
                rawTerminalOverride = true;
                showNativeTerminal();
            });
        }

        if (webView == null) return;

        webView.setBackgroundColor(Color.rgb(9, 10, 12));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                new WebViewAssetLoader.AssetsPathHandler(activity)
            )
            .build();

        webView.addJavascriptInterface(new Bridge(), "TermuxNative");
        webView.setWebViewClient(new LocalContentWebViewClient(assetLoader));
        webView.loadUrl(APP_ASSET_URL);
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
        if (!agent || rawTerminalOverride) {
            showNativeTerminal();
            return;
        }

        showAgentSurface(pageReady);
        if (pageReady) render(session);
    }

    public void onOutputChanged(TerminalSession session) {
        if (!pageReady || rawTerminalOverride) return;
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

    private void showAgentSurface(boolean ready) {
        rawTerminalOverride = false;
        webView.setVisibility(View.VISIBLE);

        terminalView.setAlpha(0f);
        terminalView.setImportantForAccessibility(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        );

        setAgentChrome(true);

        if (loadingPanel != null) {
            loadingPanel.setVisibility(ready ? View.GONE : View.VISIBLE);
        }
        if (loadingText != null && !ready) {
            loadingText.setText("Starting AI workspace…");
        }
        if (rawTerminalButton != null) {
            rawTerminalButton.setVisibility(View.GONE);
        }
    }

    private void showRendererError(String message) {
        if (loadingPanel != null) loadingPanel.setVisibility(View.VISIBLE);
        if (loadingText != null) loadingText.setText(message);
        if (rawTerminalButton != null) rawTerminalButton.setVisibility(View.VISIBLE);
    }

    private void showNativeTerminal() {
        if (webView != null) webView.setVisibility(View.GONE);
        if (loadingPanel != null) loadingPanel.setVisibility(View.GONE);

        if (terminalView != null) {
            terminalView.setAlpha(1f);
            terminalView.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            );
        }
        setAgentChrome(false);
    }

    private void setAgentChrome(boolean agent) {
        if (nativeComposer != null) {
            nativeComposer.setVisibility(agent ? View.GONE : View.VISIBLE);
        }
        if (terminalToolbar != null) {
            terminalToolbar.setVisibility(
                agent ? View.GONE : terminalToolbarDefaultVisibility
            );
        }
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
            payload.put(
                "sessionName",
                session.mSessionName == null ? "AI Session" : session.mSessionName
            );
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
            showRendererError("AI interface hit a rendering error.");
        }
    }

    private final class LocalContentWebViewClient extends WebViewClientCompat {
        private final WebViewAssetLoader assetLoader;

        LocalContentWebViewClient(WebViewAssetLoader assetLoader) {
            this.assetLoader = assetLoader;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
            WebView view,
            WebResourceRequest request
        ) {
            return assetLoader.shouldInterceptRequest(request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(
            WebView view,
            String url
        ) {
            return assetLoader.shouldInterceptRequest(Uri.parse(url));
        }

        @Override
        public boolean shouldOverrideUrlLoading(
            WebView view,
            WebResourceRequest request
        ) {
            return !request.getUrl().toString().startsWith(
                "https://appassets.androidplatform.net/"
            );
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return url == null || !url.startsWith(
                "https://appassets.androidplatform.net/"
            );
        }
    }

    private final class Bridge {
        @JavascriptInterface
        public void ready() {
            pageReady = true;
            activity.runOnUiThread(() -> {
                TerminalSession session = activity.getCurrentSession();
                if (session == null || !isAgentSession(session)) return;
                showAgentSurface(true);
                render(session);
            });
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
