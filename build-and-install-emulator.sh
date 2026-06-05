#!/bin/bash
# SeeNot: Build and install the latest debug APK, preferring any running emulator.

set -e

PACKAGE_NAME="com.seenot.app"
DEBUG_APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
ADB_BIN="${ADB:-adb}"

choose_target_device() {
    local devices_output
    devices_output="$("$ADB_BIN" devices | tail -n +2)"

    local -a emulator_devices=()
    local -a physical_devices=()

    while IFS=$'\t' read -r serial state; do
        [[ -z "$serial" ]] && continue
        [[ "$state" != "device" ]] && continue
        if [[ "$serial" == emulator-* ]]; then
            emulator_devices+=("$serial")
        else
            physical_devices+=("$serial")
        fi
    done <<< "$devices_output"

    if ((${#emulator_devices[@]} > 0)); then
        printf '%s\n' "${emulator_devices[0]}"
        return 0
    fi

    if ((${#physical_devices[@]} > 0)); then
        printf '%s\n' "${physical_devices[0]}"
        return 0
    fi

    return 1
}

TARGET_SERIAL="${TARGET_SERIAL:-}"
if [[ -z "$TARGET_SERIAL" ]]; then
    TARGET_SERIAL="$(choose_target_device)" || {
        echo "No connected Android device/emulator found."
        exit 1
    }
fi

adb_target() {
    "$ADB_BIN" -s "$TARGET_SERIAL" "$@"
}

echo "Building debug APK..."
./gradlew :app:assembleDebug

echo ""
echo "Target device: $TARGET_SERIAL"

echo ""
echo "Stopping old app..."
adb_target shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true

echo ""
echo "Uninstalling old app..."
adb_target shell pm uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true

echo ""
echo "Installing latest debug APK..."
adb_target install "$DEBUG_APK_PATH"

echo ""
echo "Starting app..."
adb_target shell am start -n "$PACKAGE_NAME"/.MainActivity

echo ""
echo "Install complete."
