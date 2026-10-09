# Flint 8.11.5 — native Android / Android TV

Native Kotlin client, now using the original production application ID
`app.flint.vpn` as requested. Version 8.11.5 / code 3005. Release signing must
match the deployed Qt certificate; inspect the delivered APK checks for evidence.

## Installation and migration

Separate phone and Android TV APKs, ARM32/ARM64 automatically selected.
Android 11/API 30 or newer. The release replaces an older Qt Flint with the same
application ID and certificate. Its data directory is preserved, but Qt's encrypted
QSettings are not read by Kotlin: sign in again to restore account subscriptions
and reimport manually added links when moving from Qt.
The earlier `app.flint.vpn.kotlin` test/native packages remain separate; Android
does not transfer their private account/profile data into the production package.
Only one VPN can run at a time. Save manually imported links before removing them.

## QR camera update

The camera has a square decode crop in portrait and landscape, no red scan line,
and accepts QR codes only. Image imports and the existing QR sharing flow are retained.

## Product behavior

- Exact Qt monochrome husky status icon, immediate persistent service notification,
  DEFAULT importance with sound/vibration disabled. Existing user channel blocks or
  custom settings are retained. Settings explain Android/OEM icon controls.
- Original Qt artwork and PT Root UI font, four-state mascot/ring/button colors,
  compact main page, small traffic bar, subscription selection, TV-only D-pad focus.
- Qt order and separate flows for settings, profile, login methods, referrals,
  purchase, subscriptions, devices, support and routing. Device management has its
  own subscription selection and never changes the active VPN subscription.
- Support opens the conversation list with statuses and Create Request, then a chat.
  Idempotent retries preserve the message and respect API limits.
- All product dialogs wrap content and center within the available window. Only
  overflowing content scrolls; header/footer remain visible when the keyboard opens.
- Location telemetry uses authenticated /locations with token refresh. The VPN
  process requests measurements from the account process over a bounded private
  Binder channel; tokens are never copied to native profiles or the VPN process.
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
API APK uploads use `app.flint.vpn` and the existing production signing
certificate. No certificate checks are bypassed.

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

## Quick Settings button

Phone Settings → «Добавить кнопку в шторку». Android 13+ can show the system
add-tile prompt; Android 11/12 and OEM launchers also support manual placement
through the shade editor. The tile is disabled in the TV manifest. It subscribes
to the real VPN service while visible and sends the existing service toggle,
without starting MainActivity after the initial profile/VPN permission setup.
Android chooses the active/inactive tint; the tile uses Flint's white husky icon.
The emulator test adds the actual SystemUI tile and verifies stop/start, state
after reopening the shade, real TUN traffic and hidden MainActivity.

New support conversations use the selected category title as subject; message
text remains separate, including line breaks. Saved idempotent retries stay unchanged.

РОССИЙСКИЕ ПРИЛОЖЕНИЯ — 8.11.5
При включённых «Сайты РФ» в автоматическом режиме Госуслуги, АЗС Газпромнефть,
банки, карты и магазины из встроенного списка работают напрямую целиком.
Сайты РФ → Приложения напрямую: можно изменить выбор или добавить приложение.
Сохранение при подключённом VPN переподключает его. После смены маршрута
закройте и откройте проблемное приложение заново. Другие приложения сохраняют VPN.
Список приложений хранится на устройстве и не передаётся на сервер.
Проверка реальных аккаунтов Госуслуг/банков/АЗС не выполнялась.
