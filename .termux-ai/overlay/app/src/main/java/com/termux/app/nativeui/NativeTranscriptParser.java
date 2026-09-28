package com.termux.app.nativeui;

import com.termux.terminal.TerminalSession;
import com.termux.shared.shell.ShellUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class NativeTranscriptParser {

    private static final Pattern ANSI = Pattern.compile("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");
    private static final int MAX_ITEMS = 140;

    private NativeTranscriptParser() {}

    public static List<NativeMessage> parse(TerminalSession session) {
        List<NativeMessage> out = new ArrayList<>();
        if (session == null) return out;

        String transcript;
        try {
            transcript = ShellUtils.getTerminalSessionTranscriptText(session, true, false);
        } catch (Throwable ignored) {
            transcript = "";
        }

        if (transcript == null || transcript.isEmpty()) {
            out.add(new NativeMessage(
                NativeMessage.Type.STATUS,
                "SESSION",
                session.isRunning() ? "Starting local process…" : "Session ended."
            ));
            return out;
        }

        String provider = provider(session.mSessionName);
        String[] lines = transcript.replace("\r", "").split("\n");
        StringBuilder assistantBuffer = new StringBuilder();
        StringBuilder diffBuffer = new StringBuilder();
        boolean runtimeStatusAdded = false;

        for (String raw : lines) {
            String line = clean(raw);
            if (line.isEmpty()) continue;

            if (isPackageNoise(line)) {
                flushAssistant(out, assistantBuffer);
                flushDiff(out, diffBuffer);
                if (!runtimeStatusAdded) {
                    out.add(new NativeMessage(
                        NativeMessage.Type.STATUS,
                        "RUNTIME",
                        "Preparing local coding runtime…"
                    ));
                    runtimeStatusAdded = true;
                }
                continue;
            }

            if (isRuntimeLine(line)) {
                flushAssistant(out, assistantBuffer);
                flushDiff(out, diffBuffer);
                if (!runtimeStatusAdded) {
                    out.add(new NativeMessage(
                        NativeMessage.Type.STATUS,
                        "RUNTIME",
                        normalizeRuntimeLine(line)
                    ));
                    runtimeStatusAdded = true;
                }
                continue;
            }

            if (isPromptOnly(line)) continue;

            if (line.startsWith("+") || line.startsWith("-")) {
                flushAssistant(out, assistantBuffer);
                if (diffBuffer.length() > 0) diffBuffer.append("\n");
                diffBuffer.append(line);
                continue;
            } else {
                flushDiff(out, diffBuffer);
            }

            if (isUser(line)) {
                flushAssistant(out, assistantBuffer);
                out.add(new NativeMessage(
                    NativeMessage.Type.USER,
                    "YOU",
                    stripUserPrefix(line)
                ));
                continue;
            }

            if (isThinking(line)) {
                flushAssistant(out, assistantBuffer);
                out.add(new NativeMessage(
                    NativeMessage.Type.THINKING,
                    provider.toUpperCase(Locale.ROOT),
                    "Thinking…"
                ));
                continue;
            }

            if (isTool(line, provider)) {
                flushAssistant(out, assistantBuffer);
                out.add(new NativeMessage(
                    NativeMessage.Type.TOOL,
                    "TOOL",
                    stripToolPrefix(line)
                ));
                continue;
            }

            if (isError(line)) {
                flushAssistant(out, assistantBuffer);
                out.add(new NativeMessage(
                    NativeMessage.Type.ERROR,
                    "ERROR",
                    line
                ));
                continue;
            }

            if (assistantBuffer.length() > 0) assistantBuffer.append("\n");
            assistantBuffer.append(line);
        }

        flushAssistant(out, assistantBuffer);
        flushDiff(out, diffBuffer);

        if (out.isEmpty()) {
            out.add(new NativeMessage(
                NativeMessage.Type.STATUS,
                "SESSION",
                session.isRunning() ? "Waiting for agent output…" : "Session ended."
            ));
        }

        if (out.size() > MAX_ITEMS) {
            return new ArrayList<>(out.subList(out.size() - MAX_ITEMS, out.size()));
        }
        return out;
    }

    private static String provider(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.contains("claude")) return "claude";
        if (n.contains("grok")) return "grok";
        if (n.contains("gemini")) return "gemini";
        if (n.contains("opencode")) return "opencode";
        if (n.contains("antigravity")) return "antigravity";
        if (n.contains("codex")) return "codex";
        return "shell";
    }

    private static String clean(String value) {
        if (value == null) return "";
        String s = ANSI.matcher(value).replaceAll("");
        s = s.replace("\u0000", "").trim();
        return s;
    }

    private static boolean isPackageNoise(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("setting up ")
            || l.startsWith("processing triggers")
            || l.startsWith("update-alternatives:")
            || l.startsWith("updating certificates")
            || l.startsWith("running hooks")
            || l.startsWith("selecting previously unselected package")
            || l.startsWith("preparing to unpack")
            || l.startsWith("unpacking ")
            || l.startsWith("reading package lists")
            || l.startsWith("building dependency tree")
            || l.startsWith("reading state information")
            || l.matches("^get:\\d+ .*")
            || l.startsWith("fetched ")
            || l.equals("done.")
            || l.equals("done");
    }

    private static boolean isRuntimeLine(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("[workspace]")
            || l.startsWith("[termux ai]")
            || l.startsWith("==>")
            || l.contains("installing/updating")
            || l.contains("installed successfully")
            || l.contains("detected platform")
            || l.contains("resolved version")
            || l.contains("downloading ");
    }

    private static String normalizeRuntimeLine(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (l.contains("installed successfully")) return "Agent installed. Starting session…";
        if (l.contains("downloading")) return "Downloading agent runtime…";
        if (l.contains("install")) return "Installing agent runtime…";
        return "Preparing local coding runtime…";
    }

    private static boolean isPromptOnly(String s) {
        return s.equals("$") || s.equals("#") || s.equals("~ $") || s.equals("~ #");
    }

    private static boolean isUser(String s) {
        return s.startsWith("❯") || s.startsWith("›") || s.startsWith("> ");
    }

    private static String stripUserPrefix(String s) {
        return s.replaceFirst("^(❯|›|>)\\s*", "");
    }

    private static boolean isThinking(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.contains("thinking")
            || l.contains("pondering")
            || l.contains("working…")
            || l.contains("working...")
            || l.contains("waiting for response");
    }

    private static boolean isTool(String s, String provider) {
        String l = s.toLowerCase(Locale.ROOT);
        if (s.startsWith("•") || s.startsWith("⏺") || s.startsWith("⎿") || s.startsWith("◆")) return true;
        return l.matches("^(read|edit|edited|write|wrote|run|ran|bash|shell|exec|update|updated|search|grep|find|patch|apply)\\b.*");
    }

    private static String stripToolPrefix(String s) {
        return s.replaceFirst("^(•|⏺|⎿|◆)\\s*", "");
    }

    private static boolean isError(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        return l.startsWith("error:")
            || l.startsWith("fatal:")
            || l.contains("command not found")
            || l.contains("failed to")
            || l.contains("permission denied");
    }

    private static void flushAssistant(List<NativeMessage> out, StringBuilder buffer) {
        if (buffer.length() == 0) return;
        String body = buffer.toString().trim();
        if (!body.isEmpty()) {
            out.add(new NativeMessage(
                NativeMessage.Type.ASSISTANT,
                "ASSISTANT",
                body
            ));
        }
        buffer.setLength(0);
    }

    private static void flushDiff(List<NativeMessage> out, StringBuilder buffer) {
        if (buffer.length() == 0) return;
        out.add(new NativeMessage(
            NativeMessage.Type.DIFF,
            "DIFF",
            buffer.toString()
        ));
        buffer.setLength(0);
    }
}
