#!/usr/bin/env bash
set -euo pipefail

adb start-server
adb wait-for-device

echo "Waiting for Android framework/package manager..."
ready=0
for i in $(seq 1 240); do
  boot="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  devboot="$(adb shell getprop dev.bootcomplete 2>/dev/null | tr -d '\r' || true)"
  if [ "$boot" = "1" ] && [ "$devboot" = "1" ] &&      adb shell cmd package list packages >/dev/null 2>&1 &&      adb shell pm path android >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 2
done

if [ "$ready" != "1" ]; then
  echo "Android package manager did not become ready"
  adb shell getprop || true
  adb shell service list || true
  exit 1
fi

echo "Package manager ready; waiting 10s for system services"
sleep 10
adb shell input keyevent 82 || true
adb install --no-streaming -r /tmp/flint-smoke.apk
adb logcat -c

adb shell am force-stop app.flint.vpn || true
adb shell am start -W -n app.flint.vpn/org.amnezia.vpn.AmneziaActivity | tee /tmp/am-start.txt
sleep 12

adb logcat -d > /tmp/logcat.txt
pid="$(adb shell pidof app.flint.vpn | tr -d '\r' || true)"
echo "Flint pid: $pid"

echo "=== Flint/Qt startup log excerpt ==="
grep -E "AndroidRuntime|FATAL EXCEPTION|QQml|qml|Qt|AmneziaQt|flint|Unable to instantiate|Unable to start activity" /tmp/logcat.txt | tail -n 300 || true

adb exec-out screencap -p > /tmp/flint-launch.png || true

if [ -z "$pid" ]; then
  echo "Flint process exited after launch"
  exit 1
fi

if grep -E "FATAL EXCEPTION|Process: app\.flint\.vpn.*has died|Unable to instantiate application|Unable to start activity|QQmlApplicationEngine failed to load component" /tmp/logcat.txt; then
  echo "Flint crashed during launch"
  exit 1
fi

echo "Flint launch smoke: OK"
