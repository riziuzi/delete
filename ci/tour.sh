#!/bin/bash
# Runs on the CI emulator: installs the app, walks every screen, saves screenshots and logs.
set -x
OUT=ci-out/tour
mkdir -p "$OUT"
PKG=com.riziuzi.utho
APK=app/build/outputs/apk/debug/app-x86_64-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
# The emulator's launcher sometimes freezes on CI; hide system "isn't responding" dialogs so they don't cover the app.
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure anr_show_background 0 || true
sleep 20
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true
adb shell getprop ro.build.version.release > "$OUT/emulator.txt"
adb install -r -g "$APK"
adb install -r "$TEST"
adb shell pm clear $PKG
adb logcat -c
adb shell am instrument -w -r $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/instrument.txt" 2>&1
adb pull /sdcard/Android/data/$PKG/files/screens "$OUT/" || true
adb logcat -d > "$OUT/logcat.txt"

# Smoke test of the shrunk release build: does it start without crashing?
adb uninstall $PKG.test || true
adb uninstall $PKG || true
adb install -r -g app/build/outputs/apk/release/app-x86_64-release.apk
adb logcat -c
adb shell am start -W -n $PKG/.ui.MainActivity
sleep 8
adb exec-out screencap -p > "$OUT/release-smoke.png"
adb shell pidof $PKG > "$OUT/release-pid.txt" || echo "NOT RUNNING" > "$OUT/release-pid.txt"
adb logcat -d | grep -E "FATAL|AndroidRuntime" -A 25 > "$OUT/release-crash.txt" || true

if grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed" "$OUT/instrument.txt"; then exit 1; fi
if [ ! -s "$OUT/release-pid.txt" ] || grep -q "NOT RUNNING" "$OUT/release-pid.txt"; then exit 1; fi
exit 0
