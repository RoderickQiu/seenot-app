#!/bin/bash
# Build and install SeeNot on a phone paired through Android Wireless debugging.
# The phone must already be paired in Developer options > Wireless debugging.

set -euo pipefail

PACKAGE_NAME="com.seenot.app"
DEBUG_APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
ADB_BIN="${ADB:-adb}"
TARGET_SERIAL="${TARGET_SERIAL:-}"
FRESH_INSTALL=false
SKIP_BUILD=false

usage() {
    cat <<'EOF'
Usage: ./build-and-install-wireless.sh [--serial HOST:PORT] [--fresh] [--no-build]

Builds the debug APK, discovers a paired Wireless debugging phone over mDNS,
installs the APK while preserving data, and launches SeeNot.

Options:
  --serial HOST:PORT  Use a specific Wireless debugging connection.
  --fresh             Uninstall first, clearing SeeNot data.
  --no-build          Reuse the existing debug APK.
  -h, --help          Show this help.

Environment:
  TARGET_SERIAL       Same as --serial.
  ADB                 adb binary to use.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial)
            TARGET_SERIAL="${2:?--serial needs HOST:PORT}"
            shift 2
            ;;
        --fresh) FRESH_INSTALL=true; shift ;;
        --no-build) SKIP_BUILD=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
    esac
done

connected_phone() {
    "$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" && $1 !~ /^emulator-/ { print $1; exit }'
}

connect_discovered_phone() {
    local endpoint
    while IFS= read -r endpoint; do
        [[ -z "$endpoint" ]] && continue
        if "$ADB_BIN" connect "$endpoint" >/dev/null 2>&1; then
            TARGET_SERIAL="$endpoint"
            return 0
        fi
    done < <("$ADB_BIN" mdns services | awk '/_adb-tls-connect\._tcp/ { print $NF }' | sort -u)
    return 1
}

if [[ -z "$TARGET_SERIAL" ]]; then
    TARGET_SERIAL="$(connected_phone)"
fi

if [[ -z "$TARGET_SERIAL" ]]; then
    echo "Looking for a paired Wireless debugging phone…"
    connect_discovered_phone || {
        echo "No paired Wireless debugging phone found." >&2
        echo "On the phone: Developer options > Wireless debugging, then pair it once." >&2
        exit 1
    }
fi

# A Wireless-debugging port changes after reconnecting; reconnect once when needed.
if ! "$ADB_BIN" -s "$TARGET_SERIAL" get-state >/dev/null 2>&1; then
    "$ADB_BIN" connect "$TARGET_SERIAL" >/dev/null 2>&1 || {
        echo "Cannot connect to $TARGET_SERIAL. Run without --serial to rediscover the phone." >&2
        exit 1
    }
fi

adb_target() {
    "$ADB_BIN" -s "$TARGET_SERIAL" "$@"
}

if [[ "$SKIP_BUILD" == false ]]; then
    echo "Building debug APK…"
    ./gradlew :app:assembleDebug
fi

[[ -f "$DEBUG_APK_PATH" ]] || { echo "Missing $DEBUG_APK_PATH" >&2; exit 1; }

echo "Target: $TARGET_SERIAL"
adb_target shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true

if [[ "$FRESH_INSTALL" == true ]]; then
    echo "Removing the existing app and its data…"
    adb_target uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true
fi

echo "Installing debug APK…"
if ! adb_target install -r "$DEBUG_APK_PATH"; then
    echo "Install was not accepted by the phone." >&2
    echo "Unlock it and approve the update prompt, then run this command again." >&2
    exit 1
fi

echo "Launching SeeNot…"
adb_target shell monkey -p "$PACKAGE_NAME" 1 >/dev/null
echo "Done."
