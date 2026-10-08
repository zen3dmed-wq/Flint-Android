# Flint Kotlin 0.2 — Android / TV preview

This isolated native client installs as `app.flint.vpn.kotlin` alongside the
working Qt app (`app.flint.vpn`). Stable Qt APKs and their data remain available
for rollback. Android allows only one active VPN at a time.

## Implemented

- Compact native home, state colors, original Flint artwork and expression fade;
  separate phone and TV layouts, with focus outlines only on TV.
- VLESS/Reality, VMess, Trojan, Shadowsocks and Xray JSON subscription imports;
  clipboard, text, file, camera and QR from image. HTTPS import can retry through
  the same public proxy seed pool as Qt; TLS validation remains enabled.
- Email registration/login, Telegram PKCE login and account linking, TV login QR,
  subscription selection/traffic/expiry, plan cards with savings, payment orders
  with persistent idempotency keys and payment polling, referrals, device/session
  controls and support UI using the existing Flint API.
- Russian service routing from the API/catalog plus user domains and IP ranges;
  Yandex Maps/Navigator application bypass; active server switching.
- Service-owned connection state and automatic balancing, bounded failed-server
  retry, per-server fingerprint checks/cache, fresh load percentages and cooldowns.
- 1x1 widget and pinned VPN shortcut; state colors and authoritative service query.
- Update API client and validated Android installer (package, signature, hash,
  size, version, architecture, distribution). Kotlin needs its own published
  update channel; a Qt APK cannot update this isolated application.

The connected state requires a successful HTTP(S) response on the Android VPN
network. A locally created TUN alone does not confirm success. This check does not
promise reachability of every website. Error diagnostics contain fixed categories,
not endpoint credentials. Server-list port latency is a TCP measurement; tuning
checks the actual proxy handshake and HTTPS.

## Limits and release gate

This remains a preview, not a certified full replacement for Qt. Minimum Android
is 11/API 30. ARM32 and ARM64 are included in each APK and selected automatically.
WireGuard/AmneziaWG/OpenVPN engines and arbitrary external geosite/geoip database
imports are not included. The bundled Flint Russian catalog is expanded JSON.

Purchase completion, VPN-device revocation, support delivery and website referral
links depend on the corresponding backend capability. Unsupported endpoints are
reported honestly. Session logout is not revocation of a shared VPN key. Sessions
with equal model names are grouped for display, not assumed to be one physical
device. The production website/admin/API has not been changed by this port.

Automated tests exercise Android TUN forwarding, DNS, Reality, failed-server
state, permission denial, activity reentry, QR imports, TV keys, routing/balancing
policy and account UI/API behavior with a fake backend. See the delivered test
report for actual results. Physical Samsung/ColorOS/TV, real purchase and delivery
must still be checked before replacing Qt. User credentials and real subscription
links are not included in source, CI, logs or screenshots.

## Build

JDK 17, Gradle 8.13, SDK 36, NDK 27.0.11718014, Go 1.26.0:

```sh
bash tools/build_xray.sh
gradle :app:testPhoneDebugUnitTest :app:assemblePhoneRelease :app:assembleTvRelease
```

CI runs tools/run_emulator_tests.sh against a loopback-only synthetic server.
The pinned engine archive checksum and 16 KiB native alignment are checked.
Release APKs are unsigned in CI and signed locally. Signing keys are not included.
Vendored source provenance and upstream license are in engine/.

To return to Qt: disconnect this preview, then open the existing Flint app.
Uninstalling only Flint Kotlin (тест) does not delete the Qt app's data.
