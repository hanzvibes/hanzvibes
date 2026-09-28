package com.termux.app.ai;

import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class AiWorkspaceBranding {

    private static final String DEFAULT_TERMUX_MARKER = "Welcome to Termux!";

    private static final String MOTD =
        "\nTermux AI Workspace\n" +
        "Local coding environment ready.\n\n" +
        "Open Home to launch Codex, OpenCode, Antigravity, Claude Code, or Gemini CLI.\n\n";

    private AiWorkspaceBranding() {}

    public static void applyMotdIfDefault() {
        File motd = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/motd");
        if (!motd.isFile()) return;

        try {
            String current = readText(motd);
            if (!current.contains(DEFAULT_TERMUX_MARKER)) return;

            try (FileOutputStream output = new FileOutputStream(motd, false)) {
                output.write(MOTD.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        } catch (Exception ignored) {
            // Branding must never block the terminal from starting.
        }
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }
}
