# Emulator validation contract

These tests use an isolated `UiHarnessActivity` in the **debug** source set. It is
not present in release builds and never starts a VPN. Screenshots have a visible
`UI TEST · VPN не запущен` badge, and a matching JSON file records
`syntheticUiState: true` and `vpnStarted: false`. They validate the native layout
and controls; they do not establish that a remote VPN endpoint works.

## Build dependencies and target

- Existing AndroidJUnitRunner, AndroidX runner/rules/ext-junit dependencies.
- `androidx.test:core` supplies ActivityScenario (available transitively through
  the current test dependencies; an explicit 1.6.1 dependency is also suitable).
- `data.QrImageDecoder.decode(context, uri): String` must be present in main code.
- Build `:app:assemblePhoneDebug :app:assemblePhoneDebugAndroidTest`. This debug
  flavour includes x86_64 for an API 35 or 36 emulator. It can exercise both UI
  layouts through the harness's explicit `tv` intent extra; no TV system image is
  necessary for these UI tests.
- Verify release DEX contains no `app.flint.prototype.testing.UiHarnessActivity`.

## Phone run

Use a normal portrait phone emulator, ideally 1080x1920 px at density 420 (about
411x731 dp). Install the debug APK and its androidTest APK. Run this class filter:

```text
app.flint.prototype.NativePhoneUiTest,app.flint.prototype.ImportInstrumentationTest
```

For example, use `adb shell am instrument -w -r -e class <filter>
app.flint.vpn.kotlin.test/androidx.test.runner.AndroidJUnitRunner`, or the
equivalent Gradle runner argument.

When invoking the Gradle connected-test task, also pass
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`. Otherwise AGP
uninstalls the target APK at the end and removes its external-files screenshots
before `adb pull` can collect them. The CI script applies this to both runs and
copies the phone evidence before changing the viewport.

Phone tests exercise the file-import action from the real native dialog, check
that primary controls remain on screen, show all four connection states, and
recreate a connected screen without invoking the VPN-toggle callback. The QR
test creates its own PNG with ZXing, decodes it with the production decoder, and
passes the result to the production subscription parser. A private cache-file
test ensures two servers with the same country name remain distinct. All
addresses use reserved documentation IPs; these tests make no network requests.

## TV run

Set the emulator viewport to **1280x720 px at density 160** before running:

```text
adb shell wm size 1280x720
adb shell wm density 160
```

Run only `app.flint.prototype.NativeTvUiTest` with the same installed phone-debug
APK. The test requires a viewport of at least 960x540 dp, uses real D-pad key
events to move focus and activate buttons, opens the native server dialog, and
selects a different server while the injected state is connected. It asserts
that this route invokes a server-selection callback without a disconnect toggle.
The first D-pad event explicitly leaves the phone emulator's prior touch mode.
The TV controls also accept initial focus in touch mode for hybrid TV launchers;
this behaviour is disabled in the phone layout.

Restore `adb shell wm size reset` and `adb shell wm density reset` afterwards.
Do not apply these viewport overrides to a user's physical device.

## Evidence

Pull `/sdcard/Android/data/app.flint.vpn.kotlin/files/ui-evidence/` as a CI artifact.
Expected PNG/JSON pairs:

- `phone-disconnected-fixture`
- `phone-connecting-fixture`
- `phone-connected-fixture`
- `phone-error-fixture`
- `tv-server-picker-fixture`
- `tv-connected-fixture`

Keep instrumentation XML/results and logcat with the screenshots. A successful
render test does not replace MainActivity/service lifecycle tests, a real VPN
handshake, Android VPN-permission tests, or testing with a physical TV remote.

`res/raw/flint_dynamic_drawables.xml` explicitly retains the four mascot/logo/
background resources accessed with getIdentifier when resource shrinking is on.
