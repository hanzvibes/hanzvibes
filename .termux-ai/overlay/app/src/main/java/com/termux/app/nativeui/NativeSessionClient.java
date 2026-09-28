package com.termux.app.nativeui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.NativeAiActivity;
import com.termux.app.TermuxService;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;

public final class NativeSessionClient extends TermuxTerminalSessionClientBase {

    private final NativeAiActivity activity;
    private final TermuxService service;

    public NativeSessionClient(NativeAiActivity activity, TermuxService service) {
        this.activity = activity;
        this.service = service;
    }

    @Override
    public void onTextChanged(@NonNull TerminalSession changedSession) {
        activity.onNativeSessionTextChanged(changedSession);
    }

    @Override
    public void onTitleChanged(@NonNull TerminalSession changedSession) {
        activity.onNativeSessionTextChanged(changedSession);
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        activity.onNativeSessionFinished(finishedSession);
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxSession session = service.getTermuxSessionForTerminalSession(terminalSession);
        if (session != null) {
            session.getExecutionCommand().mPid = pid;
        }
    }

    @Override
    public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("AI Workspace", text));
        }
    }

    @Override
    public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
        if (session == null) return;
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return;
        if (clipboard.getPrimaryClip() == null || clipboard.getPrimaryClip().getItemCount() == 0) return;
        CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity);
        if (text != null) session.write(text.toString());
    }
}
