package com.termux.app.ai;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Window;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.SettingsActivity;

import java.io.File;
import java.io.IOException;

public final class AiHomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(13, 15, 19));
        window.setNavigationBarColor(Color.rgb(13, 15, 19));

        setContentView(R.layout.activity_ai_home);

        findViewById(R.id.ai_tool_codex).setOnClickListener(v -> openTool("codex", "Codex"));
        findViewById(R.id.ai_tool_opencode).setOnClickListener(v -> openTool("opencode", "OpenCode"));
        findViewById(R.id.ai_tool_antigravity).setOnClickListener(v -> openTool("antigravity", "Antigravity"));
        findViewById(R.id.ai_tool_claude).setOnClickListener(v -> openTool("claude", "Claude"));
        findViewById(R.id.ai_tool_gemini).setOnClickListener(v -> openTool("gemini", "Gemini"));
        findViewById(R.id.ai_tool_grok).setOnClickListener(v -> openTool("grok", "Grok"));

        findViewById(R.id.open_terminal_button).setOnClickListener(v ->
            startActivity(new Intent(this, TermuxActivity.class)));

        findViewById(R.id.manage_cli_button).setOnClickListener(v ->
            openTool("status", "AI CLI Status"));

        findViewById(R.id.update_cli_button).setOnClickListener(v ->
            openTool("update-all", "AI CLI Update"));

        findViewById(R.id.settings_button_home).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));

        updateRuntimeStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateRuntimeStatus();
    }

    private void updateRuntimeStatus() {
        TextView status = findViewById(R.id.runtime_status_text);
        File launcher = AiCliManager.getLauncherFile();
        if (launcher.isFile()) {
            status.setText(R.string.ai_runtime_ready);
        } else {
            status.setText(R.string.ai_runtime_first_run);
        }
    }

    private void openTool(String action, String sessionName) {
        try {
            File launcher = AiCliManager.prepareLauncher(this);
            Intent intent = new Intent(this, TermuxActivity.class);
            intent.putExtra(TermuxActivity.EXTRA_AI_COMMAND, launcher.getAbsolutePath() + " " + action);
            intent.putExtra(TermuxActivity.EXTRA_AI_SESSION_NAME, sessionName);
            startActivity(intent);
        } catch (IOException e) {
            Toast.makeText(this, R.string.msg_ai_launcher_error, Toast.LENGTH_LONG).show();
        }
    }
}
