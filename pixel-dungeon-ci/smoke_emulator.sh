#!/usr/bin/env bash
set -euxo pipefail

APK="$RUNNER_TEMP/pd/modern/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.watabou.pixeldungeon.modern"

test -s "$APK"
adb install -r "$APK"
adb logcat -c

COMPONENT="$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PACKAGE" | tail -1 | tr -d '\r')"
test -n "$COMPONENT"

adb shell am start -W -n "$COMPONENT"
sleep 6

PID="$(adb shell pidof "$PACKAGE" | tr -d '\r')"
test -n "$PID"

adb exec-out screencap -p > "$RUNNER_TEMP/pixel-dungeon-title.png"
adb logcat -d > "$RUNNER_TEMP/pixel-dungeon-logcat.txt"

if grep -q "FATAL EXCEPTION" "$RUNNER_TEMP/pixel-dungeon-logcat.txt"; then
  grep -A80 -B10 "FATAL EXCEPTION" "$RUNNER_TEMP/pixel-dungeon-logcat.txt"
  exit 1
fi

if grep -q "Process: $PACKAGE" "$RUNNER_TEMP/pixel-dungeon-logcat.txt" && grep -q "AndroidRuntime" "$RUNNER_TEMP/pixel-dungeon-logcat.txt"; then
  grep -A80 -B10 "Process: $PACKAGE" "$RUNNER_TEMP/pixel-dungeon-logcat.txt"
  exit 1
fi

echo "Runtime smoke test passed for PID $PID ($COMPONENT)"
