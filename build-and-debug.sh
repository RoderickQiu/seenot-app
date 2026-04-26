#!/bin/bash
# SeeNot: Build, Install, and Debug
# One command to do everything

set -e  # Exit on error

PACKAGE_NAME="com.seenot.app"
DEBUG_APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
ADB_BIN="${ADB:-adb}"

choose_target_device() {
    local devices_output
    devices_output="$("$ADB_BIN" devices | tail -n +2)"

    local -a physical_devices=()
    local -a emulator_devices=()

    while IFS=$'\t' read -r serial state; do
        [[ -z "$serial" ]] && continue
        [[ "$state" != "device" ]] && continue
        if [[ "$serial" == emulator-* ]]; then
            emulator_devices+=("$serial")
        else
            physical_devices+=("$serial")
        fi
    done <<< "$devices_output"

    if ((${#physical_devices[@]} > 0)); then
        printf '%s\n' "${physical_devices[0]}"
        return 0
    fi

    if ((${#emulator_devices[@]} > 0)); then
        printf '%s\n' "${emulator_devices[0]}"
        return 0
    fi

    return 1
}

TARGET_SERIAL="$(choose_target_device)" || {
    echo "❌ No connected Android device/emulator found."
    exit 1
}

adb_target() {
    "$ADB_BIN" -s "$TARGET_SERIAL" "$@"
}

echo "📦 Building app only..."
./gradlew :app:assembleDebug

echo ""
echo "🎯 Target device: $TARGET_SERIAL"

echo ""
echo "🛑 Stopping old app..."
adb_target shell am force-stop "$PACKAGE_NAME"

echo ""
echo "📲 Installing (bypassing confirmation)..."
adb_target push "$DEBUG_APK_PATH" /data/local/tmp/
adb_target shell pm install -r -t /data/local/tmp/app-debug.apk

echo ""
echo "🔑 Granting permissions..."
adb_target shell pm grant "$PACKAGE_NAME" android.permission.RECORD_AUDIO 2>/dev/null || true
adb_target shell pm grant "$PACKAGE_NAME" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

echo ""
echo "🚀 Starting app..."
adb_target logcat -c
adb_target shell am start -n "$PACKAGE_NAME"/.MainActivity

echo ""
echo "♿ Enabling accessibility service..."
adb_target shell settings put secure enabled_accessibility_services "$PACKAGE_NAME"/com.seenot.app.service.SeenotAccessibilityService
adb_target shell settings put secure accessibility_enabled 1

echo ""
echo "🔍 Monitoring logs (Press Ctrl+C to stop)..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
adb_target logcat -v time | grep -E 'SeeNot|SessionManager|IntentParser|ScreenAnalyzer|FloatingIndicator|AndroidRuntime|SessionManager|pauseSession|resumeSession|SeenotAccessibility'
