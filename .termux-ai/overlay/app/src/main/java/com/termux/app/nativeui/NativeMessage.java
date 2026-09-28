package com.termux.app.nativeui;

public final class NativeMessage {

    public enum Type {
        USER,
        ASSISTANT,
        TOOL,
        THINKING,
        STATUS,
        ERROR,
        DIFF,
        CONSOLE
    }

    public final Type type;
    public final String label;
    public final String body;

    public NativeMessage(Type type, String label, String body) {
        this.type = type;
        this.label = label == null ? "" : label;
        this.body = body == null ? "" : body;
    }
}
