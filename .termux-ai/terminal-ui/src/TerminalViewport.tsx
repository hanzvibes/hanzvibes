import * as React from "react";

import { ClaudeHeader } from "@/components/brainless/claude/claude-header";
import { ClaudeMessage } from "@/components/brainless/claude/claude-message";
import { ClaudeToolCall } from "@/components/brainless/claude/claude-tool-call";
import { ClaudeThinking } from "@/components/brainless/claude/claude-thinking";
import { ClaudePrompt } from "@/components/brainless/claude/claude-prompt";

import { CodexHeader } from "@/components/brainless/codex/codex-header";
import { CodexMessage } from "@/components/brainless/codex/codex-message";
import { CodexExec } from "@/components/brainless/codex/codex-exec";
import { CodexWorking } from "@/components/brainless/codex/codex-working";
import { CodexPrompt } from "@/components/brainless/codex/codex-prompt";

import { GrokStatus } from "@/components/brainless/grok/grok-status";
import { GrokHeader } from "@/components/brainless/grok/grok-header";
import { GrokMessage } from "@/components/brainless/grok/grok-message";
import { GrokThought } from "@/components/brainless/grok/grok-thought";
import { GrokTool } from "@/components/brainless/grok/grok-tool";
import { GrokThinking } from "@/components/brainless/grok/grok-thinking";
import { GrokWorking } from "@/components/brainless/grok/grok-working";
import { GrokPrompt } from "@/components/brainless/grok/grok-prompt";

import type { TerminalSnapshot } from "@/main";

type LineEvent =
  | { kind: "user"; text: string }
  | { kind: "assistant"; text: string }
  | { kind: "tool"; text: string; detail?: string }
  | { kind: "thinking"; text?: string }
  | { kind: "working"; text?: string };

const MAX_EVENTS = 120;

function normalizeLines(transcript: string) {
  return transcript
    .replace(/\r/g, "")
    .split("\n")
    .map((line) => line.replace(/\s+$/g, ""))
    .filter((line, index, all) => {
      if (line.trim()) return true;
      return index > 0 && all[index - 1]?.trim();
    })
    .slice(-MAX_EVENTS);
}

function classify(line: string, provider: TerminalSnapshot["provider"]): LineEvent | null {
  const text = line.trim();
  if (!text) return null;

  if (/^(welcome to termux|docs:|donate:|community:|working with packages|subscribing to additional repositories|report issues at)/i.test(text)) {
    return null;
  }

  // Hide package-manager noise. The native PTY still receives every line,
  // but the agent viewport should read like a coding app, not apt output.
  if (/^(setting up |processing triggers|update-alternatives:|updating certificates|running hooks|0 added, 0 removed|done\.?$|selecting previously unselected package|preparing to unpack|unpacking |get:\d+ |fetched |reading package lists|building dependency tree|reading state information)/i.test(text)) {
    return null;
  }

  if (/^[~/$#]\s*$/.test(text)) return null;

  if (/^\[Termux AI\]/i.test(text) || /^==>/.test(text) || /installed successfully/i.test(text) || /^start .* now\?/i.test(text)) {
    return { kind: "working", text: "Preparing AI runtime…" };
  }

  if (/^(❯|›)\s*/.test(text)) {
    return { kind: "user", text: text.replace(/^(❯|›)\s*/, "") };
  }

  if (/thinking|pondering|percolating|levitating|noodling/i.test(text)) {
    return { kind: "thinking", text };
  }

  if (/working|waiting for response|running/i.test(text)) {
    return { kind: "working", text };
  }

  if (provider === "claude" && /^(⏺|⎿)/.test(text)) {
    return { kind: "tool", text: text.replace(/^(⏺|⎿)\s*/, "") };
  }

  if (provider === "codex" && /^•/.test(text)) {
    return { kind: "tool", text: text.replace(/^•\s*/, "") };
  }

  if (provider === "grok" && /^◆/.test(text)) {
    const cleaned = text.replace(/^◆\s*/, "");
    if (/thought|thinking/i.test(cleaned)) {
      return { kind: "thinking", text: cleaned };
    }
    return { kind: "tool", text: cleaned };
  }

  if (/^(read|edit|edited|write|wrote|run|ran|bash|shell|exec|update|updated)\b/i.test(text)) {
    return { kind: "tool", text };
  }

  return { kind: "assistant", text };
}

function buildEvents(lines: string[], provider: TerminalSnapshot["provider"]) {
  const events: LineEvent[] = [];

  for (const line of lines) {
    const next = classify(line, provider);
    if (!next) continue;

    const previous = events[events.length - 1];
    if (
      previous &&
      previous.kind === "working" &&
      next.kind === "working" &&
      previous.text === next.text
    ) {
      continue;
    }

    events.push(next);
  }

  return events.slice(-MAX_EVENTS);
}

function usePromptSender() {
  const [value, setValue] = React.useState("");

  const send = React.useCallback(() => {
    const prompt = value.trim();
    if (!prompt) return;
    window.TermuxNative?.sendInput(prompt);
    setValue("");
  }, [value]);

  const onKeyDown = React.useCallback(
    (event: React.KeyboardEvent<HTMLInputElement>) => {
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        send();
      }
      if (event.key === "Escape") {
        window.TermuxNative?.interrupt();
      }
    },
    [send],
  );

  return { value, setValue, onKeyDown };
}

function EmptyState({ name }: { name: string }) {
  return (
    <div className="flex min-h-40 items-center justify-center px-5 py-10 text-center">
      <div className="max-w-sm space-y-2">
        <div className="text-sm font-medium text-[#e8e8ea]">{name}</div>
        <div className="text-xs leading-5 text-[#727780]">
          Session output will appear here as structured agent events. The native PTY is still running underneath.
        </div>
      </div>
    </div>
  );
}

function ClaudeViewport({
  snapshot,
  events,
}: {
  snapshot: TerminalSnapshot;
  events: LineEvent[];
}) {
  const prompt = usePromptSender();

  return (
    <div className="space-y-4 font-mono text-[13px] leading-[1.6] text-[#c0caf5]">
      <ClaudeHeader
        cwd={snapshot.cwd || "~"}
        user="Termux AI"
        org="Local Android workspace"
        tips={["Use Home to switch agents or open a raw terminal session"]}
        whatsNew={["brainless semantic viewport enabled"]}
      />

      {events.length === 0 ? <EmptyState name={snapshot.sessionName} /> : null}

      <div className="space-y-3">
        {events.map((event, index) => {
          if (event.kind === "user") {
            return <ClaudeMessage key={index} role="user">{event.text}</ClaudeMessage>;
          }
          if (event.kind === "tool") {
            return (
              <ClaudeToolCall
                key={index}
                tool={event.text.split(/[(:]/, 1)[0] || "Tool"}
                arg={event.text}
                result="Tap to inspect"
                status="success"
              >
                <div className="whitespace-pre-wrap break-words">{event.detail ?? event.text}</div>
              </ClaudeToolCall>
            );
          }
          if (event.kind === "thinking" || event.kind === "working") {
            return <ClaudeThinking key={index} running={snapshot.running} showTokens={false} />;
          }
          return <ClaudeMessage key={index}>{event.text}</ClaudeMessage>;
        })}
      </div>

      <div className="sticky bottom-0 bg-[#090a0c]/95 pb-1 pt-3 backdrop-blur">
        <ClaudePrompt
          value={prompt.value}
          onChange={(event) => prompt.setValue(event.target.value)}
          onKeyDown={prompt.onKeyDown}
          placeholder="Ask Claude Code…"
          effort={false}
        />
      </div>
    </div>
  );
}

function CodexViewport({
  snapshot,
  events,
}: {
  snapshot: TerminalSnapshot;
  events: LineEvent[];
}) {
  const prompt = usePromptSender();

  return (
    <div className="space-y-4 font-mono text-[13px] leading-[1.6] text-[#ededed]">
      <CodexHeader directory={snapshot.cwd || "~"} />

      {events.length === 0 ? <EmptyState name={snapshot.sessionName} /> : null}

      <div className="space-y-3">
        {events.map((event, index) => {
          if (event.kind === "user") {
            return <CodexMessage key={index} role="user">{event.text}</CodexMessage>;
          }
          if (event.kind === "tool") {
            return (
              <CodexExec key={index} command={event.text} result="▸">
                <div className="whitespace-pre-wrap break-words">{event.detail ?? event.text}</div>
              </CodexExec>
            );
          }
          if (event.kind === "thinking" || event.kind === "working") {
            return <CodexWorking key={index} running={snapshot.running} />;
          }
          return <CodexMessage key={index}>{event.text}</CodexMessage>;
        })}
      </div>

      <div className="sticky bottom-0 bg-[#090a0c]/95 pb-1 pt-3 backdrop-blur">
        <CodexPrompt
          value={prompt.value}
          onChange={(event) => prompt.setValue(event.target.value)}
          onKeyDown={prompt.onKeyDown}
          directory={snapshot.cwd || "~"}
          placeholder="Describe a task…"
        />
      </div>
    </div>
  );
}

function GrokViewport({
  snapshot,
  events,
}: {
  snapshot: TerminalSnapshot;
  events: LineEvent[];
}) {
  const prompt = usePromptSender();

  return (
    <div className="space-y-4 font-mono text-[13px] leading-[1.6] text-[#e8e8e8]">
      <GrokStatus branch="main" directory={snapshot.cwd || "~"} contextUsed="local" contextLimit="session" />
      <GrokHeader />

      {events.length === 0 ? <EmptyState name={snapshot.sessionName} /> : null}

      <div className="space-y-3">
        {events.map((event, index) => {
          if (event.kind === "user") {
            return <GrokMessage key={index} role="user">{event.text}</GrokMessage>;
          }
          if (event.kind === "tool") {
            return <GrokTool key={index} variant="card" title={event.text} />;
          }
          if (event.kind === "thinking") {
            return event.text ? (
              <GrokThought key={index} streaming={snapshot.running}>{event.text}</GrokThought>
            ) : (
              <GrokThinking key={index} running={snapshot.running} />
            );
          }
          if (event.kind === "working") {
            return <GrokWorking key={index} label={event.text || "Waiting for response…"} running={snapshot.running} />;
          }
          return <GrokMessage key={index}>{event.text}</GrokMessage>;
        })}
      </div>

      <div className="sticky bottom-0 bg-[#090a0c]/95 pb-1 pt-3 backdrop-blur">
        <GrokPrompt
          value={prompt.value}
          onChange={(event) => prompt.setValue(event.target.value)}
          onKeyDown={prompt.onKeyDown}
          showShortcuts={false}
          placeholder="Ask Grok Build…"
        />
      </div>
    </div>
  );
}

export function TerminalViewport({ snapshot }: { snapshot: TerminalSnapshot }) {
  const lines = React.useMemo(() => normalizeLines(snapshot.transcript), [snapshot.transcript]);
  const events = React.useMemo(
    () => buildEvents(lines, snapshot.provider),
    [lines, snapshot.provider],
  );

  React.useEffect(() => {
    const root = document.querySelector(".viewport-scroll");
    if (root) root.scrollTop = root.scrollHeight;
  }, [snapshot.transcript]);

  return (
    <main className="h-full bg-[#090a0c]">
      <div className="viewport-scroll h-full overflow-y-auto px-3 pb-3 pt-3 sm:px-4">
        {snapshot.provider === "claude" ? (
          <ClaudeViewport snapshot={snapshot} events={events} />
        ) : snapshot.provider === "grok" ? (
          <GrokViewport snapshot={snapshot} events={events} />
        ) : (
          <CodexViewport snapshot={snapshot} events={events} />
        )}
      </div>
    </main>
  );
}
