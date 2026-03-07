#!/bin/bash
# SeeNot: Build, Install, and Debug
# One command to do everything

set -e  # Exit on error

echo "📦 Building..."
./gradlew assembleDebug

echo ""
echo "🛑 Stopping old app..."
adb shell am force-stop com.seenot.app

echo ""
echo "📲 Installing (bypassing confirmation)..."
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/
adb shell pm install -r -t /data/local/tmp/app-debug.apk

echo ""
echo "🔑 Granting permissions..."
adb shell pm grant com.seenot.app android.permission.RECORD_AUDIO 2>/dev/null || true
adb shell pm grant com.seenot.app android.permission.POST_NOTIFICATIONS 2>/dev/null || true

echo ""
echo "🚀 Starting app..."
adb logcat -c
adb shell am start -n com.seenot.app/.MainActivity

echo ""
echo "♿ Enabling accessibility service..."
adb shell settings put secure enabled_accessibility_services com.seenot.app/com.seenot.app.service.SeenotAccessibilityService
adb shell settings put secure accessibility_enabled 1

echo ""
echo "🔍 Monitoring logs (Press Ctrl+C to stop)..."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
adb logcat -v time | grep -E 'SeeNot|SessionManager|IntentParser|ScreenAnalyzer|FloatingIndicator|AndroidRuntime'
