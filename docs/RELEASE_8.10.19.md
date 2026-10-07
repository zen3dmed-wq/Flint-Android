# Flint 8.10.19 / 2189 — application updates

The Android and iOS clients now check the authenticated Flint endpoint
`GET /api/v1/app/update` after login and from Settings → Application update.
The existing account token refresh mechanism is reused. No update traffic is
sent to the upstream Amnezia update service.

The screen shows the installed version, new version and plain-text release
notes. `latest:null` means no published update. Versions are compared numerically
and downgrades are not offered. Mandatory updates prevent starting a new VPN
connection; existing connections can still be stopped.

Downloads require a user action. The client refreshes the expiring signed URL,
accepts only HTTPS file URLs at the configured API origin, rejects redirects,
limits download time/size and verifies exact size plus SHA-256 before committing
the file. Authentication tokens are not sent to signed file URLs.

Android additionally checks the package ID, installed signing certificate,
versionCode, minimum Android version and supported ABI before opening the system
installer. The private FileProvider exposes only the update cache directory.
Android may require the user to allow package installation from Flint. No silent
installation or device-management permission is requested.

The Android workflow builds a universal arm64-v8a + armeabi-v7a APK using the
upstream multi-ABI build pipeline, so one published APK supports both the phone
and TV variants. TV keyboard focus remains limited to TV mode.

Before signing, `tools/trim_android_abis.py` removes dependency-only x86/x86_64
libraries from the universal APK. Those dependencies do not include the Flint
application for x86. The packaging step preserves the compiled ARM contents and
4-byte alignment of stored resources. Uncompressed JNI input is rejected rather
than repacked without Android page alignment. The step was verified against the
completed universal build; the original compile commit remains in provenance.

iOS accepts update destinations only at apps.apple.com or testflight.apple.com.
It cannot install a downloaded APK/unsigned IPA. Apple Developer signing and an
App Store/TestFlight release remain prerequisites for iPhone distribution.

The website and production release states are unchanged. The new client must
first be installed over the older client, which does not know this update API.
Thereafter the administrator publishes a newer compatible build as the current
release in the existing management screen. An unpublished draft is not offered.

Verification: controller/update policy and HTTP response tests; Windows TLS file
download tests cover integrity, expired links and refusal of redirects; QML/WPF
update screens are rendered and inspected separately. Build and device results
are recorded in the release artifact report, not implied by this source note.

Qt multi-ABI build reference: https://doc.qt.io/qt-6/android-building-projects-from-commandline.html
