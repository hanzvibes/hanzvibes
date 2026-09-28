#!/usr/bin/env bash
set -euxo pipefail
SRC="app/src/main/java/com/kai/terminal/MainActivity.java"
grep -F 'LocalConnectProxy' "$SRC"
grep -F '__KAI_CODEX_BRIDGE_OK__' "$SRC"
grep -F 'syncCodexAuthFromOpenCode' "$SRC"
grep -F 'ChatGPT Pro/Plus (headless)' "$SRC"
grep -F 'agent masih berjalan' "$SRC"
grep -F 'waitForModeSwitch' "$SRC"
grep -F 'libagyld.so' "$SRC"
grep -F 'libagycore.so' "$SRC"
grep -F 'KAI_AGY_PREFIX' "$SRC"
grep -F 'Codex proxy:' "$SRC"
grep -F 'versionName = "3.3.0"' app/build.gradle.kts
