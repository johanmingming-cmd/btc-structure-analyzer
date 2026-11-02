#!/usr/bin/env bash
set -u

LOGCAT_FILE="${RUNNER_TEMP:-/tmp}/android-instrumentation-logcat.txt"
adb logcat -c || true
adb logcat -v threadtime > "$LOGCAT_FILE" 2>&1 &
LOGCAT_PID=$!

echo "Emulator boot state:"
adb shell getprop sys.boot_completed || true
echo "Installed instrumentation targets:"
adb shell pm list instrumentation || true

set +e
timeout --signal=TERM --kill-after=30s 8m gradle --no-daemon --info connectedDebugAndroidTest
TEST_STATUS=$?
set -e

kill "$LOGCAT_PID" 2>/dev/null || true
wait "$LOGCAT_PID" 2>/dev/null || true

echo "Instrumentation command exit status: $TEST_STATUS"
adb devices -l || true
adb shell getprop sys.boot_completed || true
echo "Captured emulator logcat (last 1500 lines):"
tail -n 1500 "$LOGCAT_FILE" || true

exit "$TEST_STATUS"
