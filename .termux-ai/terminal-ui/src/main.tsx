import React from "react";
import { createRoot } from "react-dom/client";
import { TerminalViewport } from "@/TerminalViewport";
import "@/index.css";

export type TerminalSnapshot = {
  provider: "claude" | "codex" | "grok" | "generic";
  sessionName: string;
  cwd: string;
  transcript: string;
  running: boolean;
};

declare global {
  interface Window {
    TermuxNative?: {
      sendInput(value: string): void;
      interrupt(): void;
      ready(): void;
    };
    TermuxAI?: {
      render(snapshot: TerminalSnapshot): void;
    };
  }
}

function App() {
  const [snapshot, setSnapshot] = React.useState<TerminalSnapshot>({
    provider: "codex",
    sessionName: "AI Session",
    cwd: "~",
    transcript: "",
    running: true,
  });

  React.useEffect(() => {
    window.TermuxAI = { render: setSnapshot };
    window.TermuxNative?.ready();
    return () => {
      delete window.TermuxAI;
    };
  }, []);

  return <TerminalViewport snapshot={snapshot} />;
}

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
