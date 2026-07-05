#!/bin/bash
# SeeNot: Build Release APK
# Usage:
#   ./build-and-release.sh            # build release APK only
#   ./build-and-release.sh --install  # build, install, launch, and monitor logs if APK is signed

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

INSTALL_AFTER_BUILD=false
if [[ "${1:-}" == "--install" ]]; then
  INSTALL_AFTER_BUILD=true
fi

APK_OUTPUT_DIR="$SCRIPT_DIR/app/build/outputs/apk"
CURRENT_VARIANTS=("release" "debug")
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

if [[ -d "$APK_OUTPUT_DIR" ]]; then
  for variant_dir in "$APK_OUTPUT_DIR"/*; do
    [[ -d "$variant_dir" ]] || continue
    variant_name="$(basename "$variant_dir")"
    keep_dir=false
    for current_variant in "${CURRENT_VARIANTS[@]}"; do
      if [[ "$variant_name" == "$current_variant" ]]; then
        keep_dir=true
        break
      fi
    done
    if [[ "$keep_dir" == false ]]; then
      echo "🧹 Removing stale APK output: $variant_dir"
      rm -rf "$variant_dir"
    fi
  done
fi

echo "📦 Building release APK..."
./gradlew assembleRelease

RELEASE_DIR="$SCRIPT_DIR/app/build/outputs/apk/release"
SIGNED_APK="$RELEASE_DIR/app-release.apk"
UNSIGNED_APK="$RELEASE_DIR/app-release-unsigned.apk"
PACKAGE_NAME="com.seenot.app"
BUILT_APK=""

echo ""
echo "✅ Release build completed."
if [[ -f "$SIGNED_APK" ]]; then
  BUILT_APK="$SIGNED_APK"
  echo "📍 Signed APK: $BUILT_APK"
elif [[ -f "$UNSIGNED_APK" ]]; then
  BUILT_APK="$UNSIGNED_APK"
  echo "📍 Unsigned APK: $BUILT_APK"
  echo "ℹ️  This APK must be signed before distribution/install outside local testing."
else
  echo "⚠️  Could not find release APK in: $RELEASE_DIR"
  exit 1
fi

if [[ "$INSTALL_AFTER_BUILD" != true ]]; then
  exit 0
fi

if [[ ! -f "$SIGNED_APK" ]]; then
  echo ""
  echo "❌ --install requested, but only unsigned APK is available."
  echo "   Configure release signing in Gradle first, then rerun."
  exit 1
fi

TARGET_SERIAL="$(choose_target_device)" || {
  echo "❌ No connected Android device/emulator found."
  exit 1
}

adb_target() {
  "$ADB_BIN" -s "$TARGET_SERIAL" "$@"
}

echo ""
echo "🎯 Target device: $TARGET_SERIAL"

echo ""
echo "📲 Installing signed release APK..."
adb_target shell am force-stop "$PACKAGE_NAME" 2>/dev/null || true
adb_target install -r "$SIGNED_APK"

echo ""
echo "🚀 Starting app..."
adb_target logcat -c
adb_target shell am start -n "$PACKAGE_NAME"/.MainActivity

echo ""
echo "🎉 Release APK installed and launched."
echo ""
echo "🔍 Monitoring release logs (Press Ctrl+C to stop)..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
adb_target logcat -v time | grep -E 'SeeNot|SeeNotLogger|SeenotSync|SessionManager|IntentParser|ScreenAnalyzer|VoiceInputManager|VoiceInputOverlay|IntentInputDialog|SttEngine|DashScopeSTT|FloatingIndicator|pauseSession|resumeSession|SeenotAccessibility|TYPE_WINDOWS_CHANGED|sourceWindow|UsageStats|AndroidRuntime|FATAL EXCEPTION'
