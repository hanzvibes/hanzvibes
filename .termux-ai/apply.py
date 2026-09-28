#!/usr/bin/env python3
from __future__ import annotations

import shutil
import sys
from pathlib import Path

BASE_SHA = "8629e632fcb95da272221be327db653fb24befe9"


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new and new in text:
        return
    if old not in text:
        raise SystemExit(f"Patch anchor not found in {path}: {old[:80]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


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
        dst = repo / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)

    activity = repo / "app/src/main/java/com/termux/app/TermuxActivity.java"
    session_client = repo / "app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionActivityClient.java"
    layout = repo / "app/src/main/res/layout/activity_termux.xml"
    strings = repo / "app/src/main/res/values/strings.xml"
    manifest = repo / "app/src/main/AndroidManifest.xml"

    replace_once(
        strings,
        '<!ENTITY TERMUX_APP_NAME "Termux">',
        '<!ENTITY TERMUX_APP_NAME "Termux AI">',
    )

    replace_once(
        activity,
        "import com.termux.app.api.file.FileReceiverActivity;\n",
        "import com.termux.app.api.file.FileReceiverActivity;\nimport com.termux.app.ai.AiCliManager;\n",
    )

    replace_once(
        activity,
        "public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {\n",
        "public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {\n\n"
        "    public static final String EXTRA_AI_COMMAND = \"com.termux.extra.AI_COMMAND\";\n"
        "    public static final String EXTRA_AI_SESSION_NAME = \"com.termux.extra.AI_SESSION_NAME\";\n",
    )

    replace_once(
        activity,
        "        setSettingsButtonView();\n\n        setNewSessionButtonView();",
        "        setSettingsButtonView();\n\n        setAiHubButtonView();\n\n        setNewSessionButtonView();",
    )

    replace_once(
        activity,
        "    @Override\n    public void onStart() {",
        "    @Override\n"
        "    protected void onNewIntent(Intent intent) {\n"
        "        super.onNewIntent(intent);\n"
        "        if (intent == null) return;\n"
        "        String aiCommand = intent.getStringExtra(EXTRA_AI_COMMAND);\n"
        "        if (aiCommand != null && mTermuxTerminalSessionActivityClient != null && mTermuxService != null) {\n"
        "            String sessionName = intent.getStringExtra(EXTRA_AI_SESSION_NAME);\n"
        "            mTermuxTerminalSessionActivityClient.addNewSessionAndRun(aiCommand, sessionName);\n"
        "        } else {\n"
        "            setIntent(intent);\n"
        "        }\n"
        "    }\n\n"
        "    @Override\n"
        "    public void onStart() {",
    )

    replace_once(
        activity,
        "        final Intent intent = getIntent();\n        setIntent(null);\n",
        "        final Intent intent = getIntent();\n"
        "        setIntent(null);\n"
        "        final String aiCommand = intent == null ? null : intent.getStringExtra(EXTRA_AI_COMMAND);\n"
        "        final String aiSessionName = intent == null ? null : intent.getStringExtra(EXTRA_AI_SESSION_NAME);\n",
    )

    replace_once(
        activity,
        "                        boolean launchFailsafe = false;\n"
        "                        if (intent != null && intent.getExtras() != null) {\n"
        "                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);\n"
        "                        }\n"
        "                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);",
        "                        if (aiCommand != null) {\n"
        "                            mTermuxTerminalSessionActivityClient.addNewSessionAndRun(aiCommand, aiSessionName);\n"
        "                        } else {\n"
        "                            boolean launchFailsafe = false;\n"
        "                            if (intent != null && intent.getExtras() != null) {\n"
        "                                launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);\n"
        "                            }\n"
        "                            mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);\n"
        "                        }",
    )

    replace_once(
        activity,
        "            if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {",
        "            if (aiCommand != null) {\n"
        "                mTermuxTerminalSessionActivityClient.addNewSessionAndRun(aiCommand, aiSessionName);\n"
        "            } else if (!mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction())) {",
    )

    replace_once(
        activity,
        "    private void setNewSessionButtonView() {",
        "    private void setAiHubButtonView() {\n"
        "        View aiHubButton = findViewById(R.id.ai_hub_button);\n"
        "        aiHubButton.setOnClickListener(v -> {\n"
        "            getDrawer().closeDrawers();\n"
        "            AiCliManager.showHub(this);\n"
        "        });\n"
        "    }\n\n"
        "    public void launchCommandInNewSession(String command, String sessionName) {\n"
        "        if (mTermuxTerminalSessionActivityClient == null) return;\n"
        "        mTermuxTerminalSessionActivityClient.addNewSessionAndRun(command, sessionName);\n"
        "    }\n\n"
        "    private void setNewSessionButtonView() {",
    )

    replace_once(
        session_client,
        "    public void setCurrentStoredSession() {",
        "    public void addNewSessionAndRun(String command, String sessionName) {\n"
        "        addNewSession(false, sessionName);\n"
        "        final TerminalSession session = mActivity.getCurrentSession();\n"
        "        if (session == null) return;\n"
        "        mActivity.getTerminalView().postDelayed(() -> session.write(command + \"\\r\"), 250);\n"
        "    }\n\n"
        "    public void setCurrentStoredSession() {",
    )

    replace_once(
        layout,
        "                    <ImageButton\n"
        "                        android:id=\"@+id/settings_button\"\n"
        "                        android:layout_width=\"40dp\"\n"
        "                        android:layout_height=\"40dp\"\n"
        "                        android:src=\"@drawable/ic_settings\"\n"
        "                        android:background=\"@null\"\n"
        "                        android:contentDescription=\"@string/action_open_settings\"\n"
        "                        app:tint=\"?attr/termuxActivityDrawerImageTint\" />",
        "                    <ImageButton\n"
        "                        android:id=\"@+id/settings_button\"\n"
        "                        android:layout_width=\"40dp\"\n"
        "                        android:layout_height=\"40dp\"\n"
        "                        android:src=\"@drawable/ic_settings\"\n"
        "                        android:background=\"@null\"\n"
        "                        android:contentDescription=\"@string/action_open_settings\"\n"
        "                        app:tint=\"?attr/termuxActivityDrawerImageTint\" />\n\n"
        "                    <com.google.android.material.button.MaterialButton\n"
        "                        android:id=\"@+id/ai_hub_button\"\n"
        "                        style=\"?android:attr/buttonBarButtonStyle\"\n"
        "                        android:layout_width=\"0dp\"\n"
        "                        android:layout_height=\"40dp\"\n"
        "                        android:layout_weight=\"1\"\n"
        "                        android:text=\"@string/action_ai_hub\"\n"
        "                        android:textAllCaps=\"false\" />",
    )

    replace_once(
        strings,
        "    <string name=\"action_toggle_soft_keyboard\">Keyboard</string>\n",
        "    <string name=\"action_toggle_soft_keyboard\">Keyboard</string>\n"
        "    <string name=\"action_ai_hub\">AI Hub</string>\n"
        "    <string name=\"title_ai_hub\">AI CLI Hub</string>\n"
        "    <string name=\"action_ai_update_all\">Update installed AI CLIs</string>\n"
        "    <string name=\"msg_ai_launcher_error\">Unable to prepare AI CLI launcher.</string>\n",
    )

    replace_once(
        manifest,
        "        <activity\n            android:name=\".app.TermuxActivity\"",
        "        <activity\n"
        "            android:name=\".app.ai.AiHomeActivity\"\n"
        "            android:exported=\"true\"\n"
        "            android:label=\"@string/application_name\"\n"
        "            android:theme=\"@style/Theme.TermuxApp.DayNight.NoActionBar\">\n"
        "            <intent-filter>\n"
        "                <action android:name=\"android.intent.action.MAIN\" />\n"
        "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
        "            </intent-filter>\n"
        "        </activity>\n\n"
        "        <activity\n"
        "            android:name=\".app.TermuxActivity\"",
    )

    replace_once(
        manifest,
        "            <intent-filter>\n"
        "                <action android:name=\"android.intent.action.MAIN\" />\n\n"
        "                <category android:name=\"android.intent.category.LAUNCHER\" />\n"
        "            </intent-filter>\n",
        "            <!-- Phone launcher moved to AiHomeActivity. -->\n",
    )

    print("Termux AI Workspace v2 patch applied successfully.")
    print("Base audited against upstream commit:", BASE_SHA)
    print("Next: ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
