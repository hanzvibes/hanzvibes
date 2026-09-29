#!/usr/bin/env bash
set -euxo pipefail

APK="$RUNNER_TEMP/pd/modern/app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.watabou.pixeldungeon.hdremaster"

test -s "$APK"
adb install -r "$APK"

SIZE="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr -d '\r')"
W="${SIZE%x*}"
H="${SIZE#*x}"
test -n "$W"
test -n "$H"

# Headless Google API images occasionally surface a Pixel Launcher ANR unrelated
# to the game. Dismiss it before launching so screenshots contain only the app.
adb shell input keyevent 4 || true
sleep 1
adb shell input tap $((W * 30 / 100)) $((H * 52 / 100)) || true
sleep 1

adb logcat -c
COMPONENT="$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PACKAGE" | tail -1 | tr -d '\r')"
test -n "$COMPONENT"
adb shell am start -W -n "$COMPONENT"
sleep 6

PID="$(adb shell pidof "$PACKAGE" | tr -d '\r')"
test -n "$PID"
adb exec-out screencap -p > "$RUNNER_TEMP/pixel-dungeon-title.png"

# TitleScene portrait: Play is the upper-left dashboard tile.
adb shell input tap $((W * 30 / 100)) $((H * 50 / 100))
sleep 4
adb exec-out screencap -p > "$RUNNER_TEMP/pixel-dungeon-start.png"

# StartScene portrait: fresh install defaults to Warrior and New Game spans the
# lower content width.
adb shell input tap $((W * 50 / 100)) $((H * 82 / 100))
sleep 4
adb exec-out screencap -p > "$RUNNER_TEMP/pixel-dungeon-intro.png"

# Fresh installs show WndStory first. Back closes it and its hide callback
# transitions through InterlevelScene into the first dungeon floor.
adb shell input keyevent 4
sleep 10
adb exec-out screencap -p > "$RUNNER_TEMP/pixel-dungeon-gameplay.png"

PID="$(adb shell pidof "$PACKAGE" | tr -d '\r')"
test -n "$PID"
adb logcat -d > "$RUNNER_TEMP/pixel-dungeon-logcat.txt"

if grep -q "FATAL EXCEPTION" "$RUNNER_TEMP/pixel-dungeon-logcat.txt"; then
  grep -A100 -B20 "FATAL EXCEPTION" "$RUNNER_TEMP/pixel-dungeon-logcat.txt"
  exit 1
fi

if grep -q "Process: $PACKAGE" "$RUNNER_TEMP/pixel-dungeon-logcat.txt" && grep -q "AndroidRuntime" "$RUNNER_TEMP/pixel-dungeon-logcat.txt"; then
  grep -A100 -B20 "Process: $PACKAGE" "$RUNNER_TEMP/pixel-dungeon-logcat.txt"
  exit 1
fi

echo "HD Remaster runtime smoke path completed for PID $PID ($COMPONENT), screen ${W}x${H}."
