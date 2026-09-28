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
        raise SystemExit(f"Patch anchor not found in {path}: {old[:90]!r}")
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
    strings = repo / "app/src/main/res/values/strings.xml"
    manifest = repo / "app/src/main/AndroidManifest.xml"

    replace_once(strings, '<!ENTITY TERMUX_APP_NAME "Termux">', '<!ENTITY TERMUX_APP_NAME "Termux AI">')

    replace_once(
        activity,
        "import android.content.IntentFilter;\n",
        "import android.content.IntentFilter;\nimport android.graphics.Color;\n",
    )
    replace_once(
        activity,
        "import android.widget.RelativeLayout;\n",
        "import android.widget.RelativeLayout;\nimport android.widget.TextView;\n",
    )
    replace_once(
        activity,
        "import com.termux.app.api.file.FileReceiverActivity;\n",
        "import com.termux.app.api.file.FileReceiverActivity;\n"
        "import com.termux.app.ai.AiCliManager;\n"
        "import com.termux.app.ai.AiHomeActivity;\n"
        "import com.termux.app.ai.AiWorkspaceBranding;\n",
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
        "        setToggleKeyboardView();\n\n        registerForContextMenu(mTerminalView);",
        "        setToggleKeyboardView();\n\n"
        "        setAiHubButtonView();\n"
        "        setWorkspaceChrome();\n\n"
        "        registerForContextMenu(mTerminalView);",
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
        "        AiWorkspaceBranding.applyMotdIfDefault();\n"
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
        "                        AiWorkspaceBranding.applyMotdIfDefault();\n"
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
        "        if (aiHubButton != null) {\n"
        "            aiHubButton.setOnClickListener(v -> {\n"
        "                getDrawer().closeDrawers();\n"
        "                AiCliManager.showHub(this);\n"
        "            });\n"
        "        }\n"
        "    }\n\n"
        "    private void setWorkspaceChrome() {\n"
        "        getWindow().setStatusBarColor(Color.rgb(13, 15, 18));\n"
        "        getWindow().setNavigationBarColor(Color.rgb(13, 15, 18));\n\n"
        "        View homeButton = findViewById(R.id.workspace_home_button);\n"
        "        if (homeButton != null) homeButton.setOnClickListener(v -> {\n"
        "            startActivity(new Intent(this, AiHomeActivity.class));\n"
        "            finish();\n"
        "        });\n\n"
        "        View drawerButton = findViewById(R.id.session_drawer_button);\n"
        "        if (drawerButton != null) drawerButton.setOnClickListener(v -> getDrawer().openDrawer(Gravity.LEFT));\n\n"
        "        EditText commandInput = findViewById(R.id.command_input);\n"
        "        View sendButton = findViewById(R.id.send_command_button);\n"
        "        if (sendButton != null) sendButton.setOnClickListener(v -> sendComposerCommand());\n"
        "        if (commandInput != null) commandInput.setOnEditorActionListener((v, actionId, event) -> {\n"
        "            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {\n"
        "                sendComposerCommand();\n"
        "                return true;\n"
        "            }\n"
        "            return false;\n"
        "        });\n"
        "        updateWorkspaceSessionTitle();\n"
        "    }\n\n"
        "    private void sendComposerCommand() {\n"
        "        EditText input = findViewById(R.id.command_input);\n"
        "        if (input == null || mTerminalView == null) return;\n"
        "        String command = input.getText().toString();\n"
        "        if (command.trim().isEmpty()) return;\n"
        "        TerminalSession session = mTerminalView.getCurrentSession();\n"
        "        if (session == null) return;\n"
        "        session.write(command + \"\\r\");\n"
        "        input.setText(\"\");\n"
        "        mTerminalView.requestFocus();\n"
        "    }\n\n"
        "    public void updateWorkspaceSessionTitle() {\n"
        "        TextView title = findViewById(R.id.workspace_session_title);\n"
        "        if (title == null || mTerminalView == null) return;\n"
        "        TerminalSession session = mTerminalView.getCurrentSession();\n"
        "        String label = \"Terminal\";\n"
        "        if (session != null && session.mSessionName != null && !session.mSessionName.trim().isEmpty()) {\n"
        "            label = session.mSessionName;\n"
        "        }\n"
        "        title.setText(label);\n"
        "    }\n\n"
        "    public void launchCommandInNewSession(String command, String sessionName) {\n"
        "        if (mTermuxTerminalSessionActivityClient == null) return;\n"
        "        mTermuxTerminalSessionActivityClient.addNewSessionAndRun(command, sessionName);\n"
        "    }\n\n"
        "    private void setNewSessionButtonView() {",
    )

    replace_once(
        session_client,
        "        checkAndScrollToSession(session);\n        updateBackgroundColor();",
        "        checkAndScrollToSession(session);\n"
        "        updateBackgroundColor();\n"
        "        mActivity.updateWorkspaceSessionTitle();",
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

    print("Termux AI Workspace v3 desktop-inspired patch applied successfully.")
    print("Base audited against upstream commit:", BASE_SHA)
    print("Next: ./gradlew assembleDebug")


if __name__ == "__main__":
    main()
