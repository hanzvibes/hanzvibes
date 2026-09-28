#!/usr/bin/env python3
from __future__ import annotations

import shutil
import sys
from pathlib import Path

BASE_SHA = "8629e632fcb95da272221be327db653fb24befe9"

SKIP_PREFIXES = (
    "app/src/main/java/com/termux/app/ai/",
    "app/src/main/assets/termux-ai/",
    "app/src/main/res/layout/activity_ai_home.xml",
    "app/src/main/res/layout/activity_termux.xml",
    "app/src/main/res/layout/item_ai_tool_",
    "app/src/main/res/values/ai_strings.xml",
    "app/src/main/res/values/ai_styles.xml",
)


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise SystemExit(f"Patch anchor not found in {path}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def should_skip(rel: Path) -> bool:
    value = rel.as_posix()
    return any(value.startswith(prefix) for prefix in SKIP_PREFIXES)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 apply.py /path/to/termux-app")

    repo = Path(sys.argv[1]).resolve()
    if not (repo / "app" / "build.gradle").exists():
        raise SystemExit(f"Not a termux-app checkout: {repo}")

    kit = Path(__file__).resolve().parent
    overlay = kit / "overlay"

    for src in overlay.rglob("*"):
        if src.is_dir():
            continue
        rel = src.relative_to(overlay)
        if should_skip(rel):
            continue
        dst = repo / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

    app_build = repo / "app/build.gradle"
    strings = repo / "app/src/main/res/values/strings.xml"
    manifest = repo / "app/src/main/AndroidManifest.xml"
    service = repo / "app/src/main/java/com/termux/app/TermuxService.java"
    constants = repo / "termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java"

    # Native UI dependency.
    replace_once(
        app_build,
        '        implementation "androidx.preference:preference:1.2.1"\n',
        '        implementation "androidx.preference:preference:1.2.1"\n'
        '        implementation "androidx.recyclerview:recyclerview:1.3.2"\n',
    )

    # User-facing branding. Package/runtime paths stay compatible internally.
    replace_once(
        strings,
        '<!ENTITY TERMUX_APP_NAME "Termux">',
        '<!ENTITY TERMUX_APP_NAME "AI Workspace">',
    )
    replace_once(
        constants,
        '    public static final String TERMUX_APP_NAME = "Termux"; // Default: "Termux"',
        '    public static final String TERMUX_APP_NAME = "AI Workspace"; // Native app branding',
    )

    # Native launcher is the only phone launcher. Legacy TermuxActivity stays internal.
    replace_once(
        manifest,
        '        <activity\n            android:name=".app.TermuxActivity"',
        '        <activity\n'
        '            android:name=".app.NativeAiActivity"\n'
        '            android:exported="true"\n'
        '            android:label="@string/application_name"\n'
        '            android:launchMode="singleTask"\n'
        '            android:resizeableActivity="true"\n'
        '            android:theme="@style/Theme.TermuxApp.DayNight.NoActionBar">\n'
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.MAIN" />\n'
        '                <category android:name="android.intent.category.LAUNCHER" />\n'
        '            </intent-filter>\n'
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.MAIN" />\n'
        '                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />\n'
        '            </intent-filter>\n'
        '        </activity>\n\n'
        '        <activity\n'
        '            android:name=".app.TermuxActivity"',
    )

    replace_once(
        manifest,
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.MAIN" />\n\n'
        '                <category android:name="android.intent.category.LAUNCHER" />\n'
        '            </intent-filter>\n'
        '            <intent-filter>\n'
        '                <action android:name="android.intent.action.MAIN" />\n\n'
        '                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />\n'
        '            </intent-filter>\n\n',
        '            <!-- Launcher moved to NativeAiActivity. -->\n\n',
    )

    replace_once(
        manifest,
        '            android:targetActivity=".app.TermuxActivity">',
        '            android:targetActivity=".app.NativeAiActivity">',
    )

    # Service can hand terminal callbacks to the native workspace without TermuxActivity.
    replace_once(
        service,
        '    private TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;\n',
        '    private TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;\n'
        '    private TermuxTerminalSessionClientBase mNativeTerminalSessionClient;\n',
    )

    replace_once(
        service,
        '        if (mTermuxTerminalSessionActivityClient != null)\n'
        '            unsetTermuxTerminalSessionClient();\n'
        '        return false;',
        '        if (mTermuxTerminalSessionActivityClient != null)\n'
        '            unsetTermuxTerminalSessionClient();\n'
        '        if (mNativeTerminalSessionClient != null)\n'
        '            unsetNativeTerminalSessionClient();\n'
        '        return false;',
    )

    replace_once(
        service,
        '    public synchronized TermuxTerminalSessionClientBase getTermuxTerminalSessionClient() {\n'
        '        if (mTermuxTerminalSessionActivityClient != null)\n'
        '            return mTermuxTerminalSessionActivityClient;\n'
        '        else\n'
        '            return mTermuxTerminalSessionServiceClient;\n'
        '    }',
        '    public synchronized TermuxTerminalSessionClientBase getTermuxTerminalSessionClient() {\n'
        '        if (mNativeTerminalSessionClient != null)\n'
        '            return mNativeTerminalSessionClient;\n'
        '        if (mTermuxTerminalSessionActivityClient != null)\n'
        '            return mTermuxTerminalSessionActivityClient;\n'
        '        return mTermuxTerminalSessionServiceClient;\n'
        '    }',
    )

    insert_anchor = (
        '    /** This should be called when {@link TermuxActivity#onServiceConnected} is called to set the\n'
    )
    native_methods = (
        '    public synchronized void setNativeTerminalSessionClient(TermuxTerminalSessionClientBase client) {\n'
        '        mNativeTerminalSessionClient = client;\n'
        '        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++)\n'
        '            mShellManager.mTermuxSessions.get(i).getTerminalSession().updateTerminalSessionClient(client);\n'
        '    }\n\n'
        '    public synchronized void unsetNativeTerminalSessionClient() {\n'
        '        TermuxTerminalSessionClientBase fallback = mTermuxTerminalSessionActivityClient != null\n'
        '            ? mTermuxTerminalSessionActivityClient\n'
        '            : mTermuxTerminalSessionServiceClient;\n'
        '        for (int i = 0; i < mShellManager.mTermuxSessions.size(); i++)\n'
        '            mShellManager.mTermuxSessions.get(i).getTerminalSession().updateTerminalSessionClient(fallback);\n'
        '        mNativeTerminalSessionClient = null;\n'
        '    }\n\n'
    )
    service_text = service.read_text(encoding="utf-8")
    if "public synchronized void setNativeTerminalSessionClient" not in service_text:
        if insert_anchor not in service_text:
            raise SystemExit("Native service method insertion anchor not found")
        service.write_text(
            service_text.replace(insert_anchor, native_methods + insert_anchor, 1),
            encoding="utf-8",
        )

    # Notifications and plugin-triggered foreground opens go to the native app.
    replace_once(
        service,
        '        Intent notificationIntent = TermuxActivity.newInstance(this);',
        '        Intent notificationIntent = NativeAiActivity.newInstance(this);',
    )
    replace_once(
        service,
        '            TermuxActivity.startTermuxActivity(this);',
        '            startActivity(NativeAiActivity.newInstance(this));',
    )

    print("AI Workspace v5 native Android patch applied successfully.")
    print("Base audited against upstream commit:", BASE_SHA)
    print("UI: NativeAiActivity + RecyclerView agent renderer")
    print("WebView/React TerminalViewport: not included in APK")


if __name__ == "__main__":
    main()
