# Flint Kotlin — isolated Android/TV prototype

This experimental client installs as `app.flint.vpn.kotlin` alongside the stable
Qt app (`app.flint.vpn`). It does not replace, migrate or delete the stable app's
account, profiles or APK. Only one Android VPN may be active at a time.

## Scope

Native Kotlin screens and Android VpnService, using the same family of Xray and
tun2socks engine as Flint 8.10.25. Imports subscription links, raw/Base64 lists,
VLESS/Reality, VMess, Trojan, Shadowsocks and Xray JSON. Imports from clipboard,
text, file or QR image. The phone and TV APKs include ARM32 and ARM64; Android
selects the correct architecture. Minimum Android version: 11 / API 30.

The service owns VPN state across navigation. Permission denial does not start
the engine. Russian routing uses the existing bundled catalog and bypasses the
Yandex Maps/Navigator Android apps. Changing server or routing rebuilds the tunnel.

This first prototype does **not** yet include login, purchases, device management,
referrals, widgets, automatic app updates or server-load balancing. Subscription
URLs can be imported directly. Automatic selection uses initial TCP response
time; this is not a test of a complete VPN handshake or a server-load metric.
The displayed connected state confirms local TUN/engine startup. Native Xray
traffic statistics are not available in the upstream wrapper and are not faked.
QR from camera and TV pairing through a phone will be ported after this base is
validated. Existing Windows and iOS clients are unchanged.

## Build

Use JDK 17, Gradle 8.13, Android SDK 36, NDK 27.0.11718014 and Go 1.26.0.

```sh
bash tools/build_xray.sh
gradle :app:testPhoneDebugUnitTest :app:assemblePhoneRelease :app:assembleTvRelease
```

`tools/build_xray.sh` verifies the upstream engine archive checksum, uses pinned
gomobile dependencies and adds 16 KiB page alignment. Native release APKs are
unsigned by CI and signed locally for delivery. Signing keys are not in source.
Generated engine AARs, caches and private configuration files are excluded.

Vendored Kotlin sources retain their upstream packages for JNI. Their provenance
is recorded in `engine/SOURCE-PROVENANCE.json`; the upstream license is included.
To regenerate them, run `tools/prepare_engine.py --upstream <Flint-8.10.25-patched-amnezia-source>`.
Normal builds need only the vendored files and the pinned engine build script.

## Try and return

1. Keep the existing Flint app installed.
2. Install the matching phone or TV prototype APK. Its launcher name includes
   `Kotlin (тест)`.
3. Import your subscription link. Allow the Android VPN prompt and test a server.
4. Check Russian sites and Yandex Maps, reopen the app and check the real status.
5. To return, disconnect the prototype and open your original Flint app. The
   prototype can be uninstalled independently; no downgrade of the stable app
   or restoration of its settings is needed.

Do not treat a smaller prototype APK as a promise of the same reduction for a
complete port: it omits non-Xray engines and product features as listed above.
Physical Samsung/ColorOS/TV verification is still needed before replacement.
