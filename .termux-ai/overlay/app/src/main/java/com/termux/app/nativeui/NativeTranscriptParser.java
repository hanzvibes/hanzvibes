package com.termux.app.nativeui;

import android.util.Base64;

import com.termux.shared.shell.ShellUtils;
import com.termux.terminal.TerminalSession;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class NativeTranscriptParser {

    private static final Pattern ANSI = Pattern.compile(
        "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))"
    );
    private static final int MAX_ITEMS = 140;
    private static final int MAX_TOOL_OUTPUT = 5000;
    private static final String OPENCODE_READY = "@@NATIVE_READY@@opencode";
    private static final String USER_B64 = "@@NATIVE_USER_B64@@";
    private static final String TURN_END = "@@NATIVE_TURN_END@@";

    private NativeTranscriptParser() {}

    public static List<NativeMessage> parse(TerminalSession session) {
        List<NativeMessage> out = new ArrayList<>();
        if (session == null) return out;

        String transcript = transcript(session);
        if (transcript == null || transcript.isEmpty()) {
            out.add(new NativeMessage(
                NativeMessage.Type.STATUS,
                "SESSION",
                session.getPid() > 0 ? "Starting local process…" : "Preparing process…"
            ));
            return out;
        }

        String provider = provider(session.mSessionName);

        if ("opencode".equals(provider) && transcript.contains(OPENCODE_READY)) {
            return limit(parseStructuredOpenCode(transcript));
        }

        return limit(parseLegacyTerminal(transcript, provider, session));
    }

    private static String transcript(TerminalSession session) {
        try {
            return ShellUtils.getTerminalSessionTranscriptText(session, true, false);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static List<NativeMessage> parseStructuredOpenCode(String transcript) {
        List<NativeMessage> out = new ArrayList<>();

        int start = transcript.indexOf(OPENCODE_READY);
        if (start >= 0) {
            transcript = transcript.substring(start + OPENCODE_READY.length());
        }

        String[] lines = transcript.replace("\r", "").split("\n");

        for (String raw : lines) {
            String line = clean(raw);
            if (line.isEmpty()) continue;

            if (line.startsWith(USER_B64)) {
                String encoded = line.substring(USER_B64.length()).trim();
                String prompt = decodeBase64(encoded);
                if (!prompt.isEmpty()) {
                    appendOrMerge(out, new NativeMessage(
                        NativeMessage.Type.USER,
                        "",
                        prompt
                    ));
                }
                continue;
            }

            if (TURN_END.equals(line)) continue;

            if (!line.startsWith("{")) {
                if (isRuntimeLine(line)) {
                    appendRuntimeStatus(out, normalizeRuntimeLine(line));
                } else if (isError(line)) {
                    appendOrMerge(out, new NativeMessage(
                        NativeMessage.Type.ERROR,
                        "ERROR",
                        line
                    ));
                }
                continue;
            }

            try {
                JSONObject event = new JSONObject(line);
                String type = event.optString("type", "");

                switch (type) {
                    case "text": {
                        JSONObject part = event.optJSONObject("part");
                        if (part == null || part.optBoolean("synthetic", false)) break;

                        String text = part.optString("text", "").trim();
                        if (!text.isEmpty()) {
                            appendOrMerge(out, new NativeMessage(
                                NativeMessage.Type.ASSISTANT,
                                "",
                                text
                            ));
                        }
                        break;
                    }

                    case "reasoning": {
                        JSONObject part = event.optJSONObject("part");
                        if (part == null) break;

                        String text = part.optString("text", "").trim();
                        if (text.isEmpty()) text = "Thinking…";

                        appendOrMerge(out, new NativeMessage(
                            NativeMessage.Type.THINKING,
                            "",
                            text
                        ));
                        break;
                    }

                    case "tool_use": {
                        JSONObject part = event.optJSONObject("part");
                        if (part == null) break;

                        String tool = part.optString("tool", "Tool");
                        JSONObject state = part.optJSONObject("state");
                        String status = state == null ? "" : state.optString("status", "");
                        String title = state == null ? "" : state.optString("title", "");
                        String output = state == null ? "" : state.optString("output", "");
                        String error = state == null ? "" : state.optString("error", "");

                        String body = !title.isEmpty() ? title : tool;
                        if (!output.isEmpty()) body += "\n" + truncate(output, MAX_TOOL_OUTPUT);
                        if (!error.isEmpty()) body += "\n" + error;

                        appendOrMerge(out, new NativeMessage(
                            "error".equals(status)
                                ? NativeMessage.Type.ERROR
                                : NativeMessage.Type.TOOL,
                            tool.toUpperCase(Locale.ROOT),
                            body
                        ));
                        break;
                    }

                    case "step_start":
                        break;

                    case "step_finish": {
                        JSONObject part = event.optJSONObject("part");
                        if (part == null) break;

                        JSONObject tokens = part.optJSONObject("tokens");
                        long total = 0;
                        if (tokens != null) {
                            total = tokens.optLong("total", 0);
                            if (total == 0) {
                                total =
                                    tokens.optLong("input", 0) +
                                    tokens.optLong("output", 0) +
                                    tokens.optLong("reasoning", 0);
                            }
                        }

                        double cost = part.optDouble("cost", 0d);
                        StringBuilder meta = new StringBuilder("Completed");
                        if (total > 0) {
                            meta.append(" · ")
                                .append(formatCompactNumber(total))
                                .append(" tokens");
                        }
                        if (cost > 0d) {
                            meta.append(String.format(Locale.US, " · $%.4f", cost));
                        }

                        appendOrMerge(out, new NativeMessage(
                            NativeMessage.Type.STATUS,
                            "",
                            meta.toString()
                        ));
                        break;
                    }

                    case "error": {
                        JSONObject error = event.optJSONObject("error");
                        appendOrMerge(out, new NativeMessage(
                            NativeMessage.Type.ERROR,
                            "ERROR",
                            extractError(error)
                        ));
                        break;
                    }

                    default:
                        break;
                }
            } catch (Exception ignored) {
                // A partial JSON line can exist briefly while the PTY is receiving data.
            }
        }

        if (out.isEmpty()) {
            out.add(new NativeMessage(
                NativeMessage.Type.STATUS,
                "",
                "OpenCode is ready."
            ));
        }

        return out;
    }

    private static List<NativeMessage> parseLegacyTerminal(
        String transcript,
        String provider,
        TerminalSession session
    ) {
        List<NativeMessage> out = new ArrayList<>();
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
                    appendRuntimeStatus(out, "Preparing local coding runtime…");
                    runtimeStatusAdded = true;
                }
                continue;
            }

            if (isRuntimeLine(line)) {
                flushAssistant(out, assistantBuffer);
                flushDiff(out, diffBuffer);
                if (!runtimeStatusAdded) {
                    appendRuntimeStatus(out, normalizeRuntimeLine(line));
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
                appendOrMerge(out, new NativeMessage(
                    NativeMessage.Type.USER,
                    "",
                    stripUserPrefix(line)
                ));
                continue;
            }

            if (isThinking(line)) {
                flushAssistant(out, assistantBuffer);
                appendOrMerge(out, new NativeMessage(
                    NativeMessage.Type.THINKING,
                    "",
                    "Thinking…"
                ));
                continue;
            }

            if (isTool(line, provider)) {
                flushAssistant(out, assistantBuffer);
                appendOrMerge(out, new NativeMessage(
                    NativeMessage.Type.TOOL,
                    "TOOL",
                    stripToolPrefix(line)
                ));
                continue;
            }

            if (isError(line)) {
                flushAssistant(out, assistantBuffer);
                appendOrMerge(out, new NativeMessage(
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
                "",
                session.getPid() > 0 && session.isRunning()
                    ? "Waiting for agent output…"
                    : "Session ended."
            ));
        }

        return out;
    }

    private static void appendRuntimeStatus(List<NativeMessage> out, String text) {
        appendOrMerge(out, new NativeMessage(
            NativeMessage.Type.STATUS,
            "",
            text
        ));
    }

    private static void appendOrMerge(List<NativeMessage> out, NativeMessage next) {
        if (next.body.trim().isEmpty()) return;

        if (!out.isEmpty()) {
            NativeMessage previous = out.get(out.size() - 1);
            if (
                previous.type == next.type &&
                (next.type == NativeMessage.Type.ASSISTANT ||
                 next.type == NativeMessage.Type.THINKING ||
                 next.type == NativeMessage.Type.STATUS) &&
                previous.label.equals(next.label)
            ) {
                out.set(
                    out.size() - 1,
                    new NativeMessage(
                        previous.type,
                        previous.label,
                        previous.body + "\n" + next.body
                    )
                );
                return;
            }
        }

        out.add(next);
    }

    private static String extractError(JSONObject error) {
        if (error == null) return "OpenCode reported an unknown error.";

        JSONObject data = error.optJSONObject("data");
        if (data != null) {
            String message = data.optString("message", "");
            if (!message.isEmpty()) return message;
        }

        String message = error.optString("message", "");
        if (!message.isEmpty()) return message;

        String name = error.optString("name", "");
        return name.isEmpty() ? error.toString() : name;
    }

    private static String decodeBase64(String encoded) {
        try {
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, max) + "\n…";
    }

    private static String formatCompactNumber(long value) {
        if (value >= 1_000_000) {
            return String.format(Locale.US, "%.1fM", value / 1_000_000d);
        }
        if (value >= 1_000) {
            return String.format(Locale.US, "%.1fK", value / 1_000d);
        }
        return Long.toString(value);
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
        return s.replace("\u0000", "").trim();
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
        if (l.contains("structured opencode")) return "Starting native OpenCode session…";
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
        if (
            s.startsWith("•") ||
            s.startsWith("⏺") ||
            s.startsWith("⎿") ||
            s.startsWith("◆")
        ) return true;

        return l.matches(
            "^(read|edit|edited|write|wrote|run|ran|bash|shell|exec|update|updated|search|grep|find|patch|apply)\\b.*"
        );
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

    private static void flushAssistant(
        List<NativeMessage> out,
        StringBuilder buffer
    ) {
        if (buffer.length() == 0) return;
        String body = buffer.toString().trim();
        if (!body.isEmpty()) {
            appendOrMerge(out, new NativeMessage(
                NativeMessage.Type.ASSISTANT,
                "",
                body
            ));
        }
        buffer.setLength(0);
    }

    private static void flushDiff(
        List<NativeMessage> out,
        StringBuilder buffer
    ) {
        if (buffer.length() == 0) return;
        appendOrMerge(out, new NativeMessage(
            NativeMessage.Type.DIFF,
            "DIFF",
            buffer.toString()
        ));
        buffer.setLength(0);
    }

    private static List<NativeMessage> limit(List<NativeMessage> items) {
        if (items.size() <= MAX_ITEMS) return items;
        return new ArrayList<>(
            items.subList(items.size() - MAX_ITEMS, items.size())
        );
    }
}
