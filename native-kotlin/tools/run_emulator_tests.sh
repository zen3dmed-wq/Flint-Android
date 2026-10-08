#!/usr/bin/env bash
# The EXIT trap runs before emulator-runner terminates the emulator, including failures.
set -uo pipefail
cd "$(dirname "$0")/.."
mkdir -p build evidence/phone evidence/tv
fixture_pid=''
collect() {
  timeout 15s adb pull /sdcard/Android/data/app.flint.vpn.kotlin/files/ui-evidence evidence/ >/dev/null 2>&1 || true
  timeout 10s adb logcat -d > evidence/emulator-logcat.txt 2>&1 || true
  if [ -n "$fixture_pid" ]; then kill "$fixture_pid" 2>/dev/null || true; fi
}
trap collect EXIT
python3 tools/start_vpn_fixture.py --binary engine/libs/xray-fixture --directory build/vpn-fixture > build/vpn-fixture-launch.log 2>&1 &
fixture_pid=$!
for n in $(seq 1 40); do
  test -f build/vpn-fixture/ready && break
  sleep 1
done
test -f build/vpn-fixture/ready || exit 1
gradle --no-daemon :app:connectedPhoneDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass=app.flint.prototype.NativeTvUiTest \
  -Pandroid.testInstrumentationRunnerArguments.flintLocalVpnTest=true
phone_result=$?
cp -a app/build/outputs/androidTest-results evidence/phone/ || true
cp -a app/build/reports/androidTests evidence/phone/ || true
timeout 15s adb pull /sdcard/Android/data/app.flint.vpn.kotlin/files/ui-evidence evidence/phone/ || true
adb shell wm size 1280x720
adb shell wm density 160
gradle --no-daemon :app:connectedPhoneDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.flint.prototype.NativeTvUiTest
tv_result=$?
cp -a app/build/outputs/androidTest-results evidence/tv/ || true
cp -a app/build/reports/androidTests evidence/tv/ || true
python3 - "$phone_result" "$tv_result" <<'PY'
import json, pathlib, sys
pathlib.Path('evidence/result.json').write_text(json.dumps({'phoneExit': int(sys.argv[1]), 'tvExit': int(sys.argv[2])}))
PY
test "$phone_result" = 0 && test "$tv_result" = 0
