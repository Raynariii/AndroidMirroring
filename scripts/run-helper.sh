#!/usr/bin/env bash
# Pushes the debug APK to the phone and starts the M0 mirroring helper as the shell user.
# Usage: scripts/run-helper.sh [max_size=1920] [bit_rate=8000000] [max_fps=60]
# Needs adb connected to the phone (USB, or `adb pair` + `adb connect` over Wi-Fi).
set -euo pipefail

APK="${APK:-android/app/build/outputs/apk/debug/app-debug.apk}"
TOKEN="${TOKEN:-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')}"
PORT="${PORT:-27183}"

adb push "$APK" /data/local/tmp/androidmirroring.apk >/dev/null
echo "Phone Wi-Fi address:"
adb shell ip -f inet addr show wlan0 | awk '/inet /{print "  " $2}'
echo "On the Mac run:"
echo "  (cd mac && swift run -c release MirrorSpike --host <phone-ip> --port $PORT --token $TOKEN)"
echo
exec adb shell CLASSPATH=/data/local/tmp/androidmirroring.apk app_process / \
  com.androidmirroring.helper.HelperMain "token=$TOKEN" "port=$PORT" "$@"
