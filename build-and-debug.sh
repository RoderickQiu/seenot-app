#!/bin/bash
# SeeNot: Build, Install, and Debug
# One command to do everything

set -e  # Exit on error

PACKAGE_NAME="com.seenot.app"
DEBUG_APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

echo "📦 Building app only..."
./gradlew :app:assembleDebug

echo ""
echo "🛑 Stopping old app..."
adb shell am force-stop "$PACKAGE_NAME"

echo ""
echo "📲 Installing (bypassing confirmation)..."
adb push "$DEBUG_APK_PATH" /data/local/tmp/
adb shell pm install -r -t /data/local/tmp/app-debug.apk

echo ""
echo "🔑 Granting permissions..."
adb shell pm grant "$PACKAGE_NAME" android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell pm grant "$PACKAGE_NAME" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

echo ""
echo "🚀 Starting app..."
adb logcat -c
adb shell am start -n "$PACKAGE_NAME"/.MainActivity

echo ""
echo "♿ Enabling accessibility service..."
adb shell settings put secure enabled_accessibility_services "$PACKAGE_NAME"/com.seenot.app.service.SeenotAccessibilityService
adb shell settings put secure accessibility_enabled 1

echo ""
echo "🔍 Monitoring logs (Press Ctrl+C to stop)..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
adb logcat -v time | grep -E 'SeeNot|SessionManager|IntentParser|ScreenAnalyzer|FloatingIndicator|AndroidRuntime|SessionManager|pauseSession|resumeSession|SeenotAccessibility'
