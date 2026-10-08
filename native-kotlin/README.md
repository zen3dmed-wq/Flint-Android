# Flint 8.11.0 — native Android / Android TV

Release build of the native Kotlin client. The user confirmed that 0.2 carries
VPN traffic on their phone. This release restores Qt 8.10.25 product screen
structure and fixes home-screen switching; it keeps the same application ID and
signer as 0.1/0.2, so those installations update in place without losing data.

## Installation

Separate phone and Android TV APKs, each with automatic ARM32/ARM64 selection.
Android 11/API 30 or newer. Package `app.flint.vpn.kotlin`; launcher name `Flint`.
The older Qt application (`app.flint.vpn`) remains installed separately, allowing
rollback without deleting its account/settings. Only one VPN can run at a time.

## Product behavior

- Original Qt artwork and PT Root UI font, four-state mascot/ring/button colors,
  compact main page, small traffic bar, subscription selection, TV-only D-pad focus.
- Qt order and separate flows for settings, profile, login methods, referrals,
  purchase, subscriptions, devices, support and routing. Device management has its
  own subscription selection and never changes the active VPN subscription.
- Flint Assist logo/title/operator button, two inline shortcut buttons, close;
  support form, idempotent send/retry and replies displayed in the same panel.
- Same 20 starter direct sites as Qt, one-time migration preserving user entries;
  separate traffic/automatic switches, editable sites and searchable expanded rules.
- Widget sends a foreground-service PendingIntent: no main Activity is launched.
  A launcher shortcut uses an isolated transparent task only for Android's shortcut
  contract or VPN permission. Colors reflect service state. Disconnect flushes the
  launcher update before service teardown. Package replacement refreshes old widgets.
- Xray VLESS/Reality, VMess, Trojan and Shadowsocks; text/file/clipboard/camera/image
  QR imports; subscription caching, fingerprint tuning, server ping and load,
  automatic balancing with hysteresis/cooldown; manual servers never auto-switched.
- Email and Telegram login/linking, device identity, plan/payment flows, traffic,
  owner-only device controls, referrals and in-app updates through existing API.

## Validation and boundaries

The release gates exercise Android TUN forwarding, DNS, Reality, connection status,
permission rejection, activity lifecycle, widget/shortcut toggling, route migration,
QR imports, account/support UI with a fake backend, and TV remote navigation.
Generated UI screenshots explicitly identify synthetic data. Native packet tests
use a loopback-only VLESS/Reality fixture. See the delivered check report for the
exact run and results; a source README is not proof of a passing run.

Backend support, purchase completion, device revocation, referral website URLs and
update publication require their configured backend capabilities. The website,
admin settings, real support inbox and user payments are not modified by tests.
API APK uploads for this package must use `app.flint.vpn.kotlin` and its signing
certificate; a Qt APK is not an update for it. No certificate checks are bypassed.

This is an Xray client: WireGuard/AmneziaWG/OpenVPN engines and arbitrary external
geosite/geoip files are not bundled. This limits parity with the underlying Amnezia
engine even though the Flint product screens/flows are carried over. The built-in
expanded Russian catalog works offline. No claim of identical rendering on every
OEM font scale, launcher and physical TV is made from emulator evidence.

## Build and source

JDK 17, Gradle 8.13, SDK 36, NDK 27.0.11718014, Go 1.26.0:

```sh
bash tools/build_xray.sh
gradle :app:testPhoneDebugUnitTest :app:assemblePhoneRelease :app:assembleTvRelease
bash tools/run_emulator_tests.sh
```

Release APKs are unsigned in CI and signed locally with the existing Flint key.
No user keys/tokens/subscription URLs or signing keys are included in this source.
Pinned upstream provenance/license are in engine/. Original PT Root UI font is
licensed under SIL OFL 1.1 (engine/LICENSE-font.txt and the identical APK asset).
