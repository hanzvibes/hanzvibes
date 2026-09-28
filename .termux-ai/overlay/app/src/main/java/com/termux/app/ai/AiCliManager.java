package com.termux.app.ai;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class AiCliManager {

    private static final String ASSET_PATH = "termux-ai/termux-ai";
    private static final String LAUNCHER_DIR = ".termux-ai/bin";
    private static final String LAUNCHER_NAME = "termux-ai";

    private AiCliManager() {}

    public static File getLauncherFile() {
        return new File(TermuxConstants.TERMUX_HOME_DIR, LAUNCHER_DIR + "/" + LAUNCHER_NAME);
    }

    public static void showHub(final TermuxActivity activity) {
        final String[] labels = new String[] {
            "Codex CLI",
            "OpenCode",
            "Antigravity CLI",
            "Claude Code",
            "Gemini CLI",
            activity.getString(R.string.action_ai_update_all)
        };

        new AlertDialog.Builder(activity)
            .setTitle(R.string.title_ai_hub)
            .setItems(labels, (dialog, which) -> {
                final String action;
                final String sessionName;
                switch (which) {
                    case 0:
                        action = "codex";
                        sessionName = "Codex";
                        break;
                    case 1:
                        action = "opencode";
                        sessionName = "OpenCode";
                        break;
                    case 2:
                        action = "antigravity";
                        sessionName = "Antigravity";
                        break;
                    case 3:
                        action = "claude";
                        sessionName = "Claude";
                        break;
                    case 4:
                        action = "gemini";
                        sessionName = "Gemini";
                        break;
                    default:
                        action = "update-all";
                        sessionName = "AI CLI Update";
                        break;
                }

                try {
                    File launcher = prepareLauncher(activity);
                    activity.launchCommandInNewSession(launcher.getAbsolutePath() + " " + action, sessionName);
                } catch (IOException e) {
                    Toast.makeText(activity, R.string.msg_ai_launcher_error, Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    public static File prepareLauncher(Context context) throws IOException {
        File launcherDir = new File(TermuxConstants.TERMUX_HOME_DIR, LAUNCHER_DIR);
        if (!launcherDir.exists() && !launcherDir.mkdirs()) {
            throw new IOException("Unable to create " + launcherDir);
        }

        File launcher = new File(launcherDir, LAUNCHER_NAME);
        try (InputStream input = context.getAssets().open(ASSET_PATH);
             OutputStream output = new FileOutputStream(launcher, false)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            output.flush();
        }

        if (!launcher.setExecutable(true, false)) {
            throw new IOException("Unable to mark launcher executable");
        }
        return launcher;
    }
}
