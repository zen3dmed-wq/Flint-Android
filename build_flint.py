#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys
import base64

root = Path(sys.argv[1]).resolve()
flint = Path(__file__).resolve().parent / "flint"

def replace(path, old, new, required=True):
    p = root / path
    s = p.read_text(encoding="utf-8")
    if old not in s:
        if required:
            raise RuntimeError(f"anchor not found in {path}: {old[:80]!r}")
        return
    p.write_text(s.replace(old, new, 1), encoding="utf-8")

# Branding: the VPN engine stays upstream internally; every user-visible Android identity is Flint.
replace(
    "CMakeLists.txt",
    'set(AMNEZIAVPN_VERSION 5.0.3.0 CACHE STRING "Client app version")',
    'set(AMNEZIAVPN_VERSION 8.9.10 CACHE STRING "Client app version")'
)
replace(
    "CMakeLists.txt",
    'set(APP_ANDROID_VERSION_CODE 2163)',
    'set(APP_ANDROID_VERSION_CODE 2166)'
)
replace(
    "client/cmake/branding/common.cmake",
    'set(CLIENT_APPLICATION_NAME "AmneziaVPN" CACHE STRING "Application display and executable name")',
    'set(CLIENT_APPLICATION_NAME "Flint" CACHE STRING "Application display and executable name")'
)
replace(
    "client/cmake/branding/common.cmake",
    'set(CLIENT_ORGANIZATION_NAME "AmneziaVPN.ORG" CACHE STRING "QSettings organization name")',
    'set(CLIENT_ORGANIZATION_NAME "Flint" CACHE STRING "QSettings organization name")'
)
replace(
    "client/cmake/branding/common.cmake",
    'set(CLIENT_APP_INSTANCE_NAME "AmneziaVPNInstance" CACHE STRING "Single-instance local server name")',
    'set(CLIENT_APP_INSTANCE_NAME "FlintInstance" CACHE STRING "Single-instance local server name")'
)
# Keep the engine's Android package namespace intact. User-visible identity is
# still Flint (label/icon/UI); this avoids JNI/package assumptions in the native bridge.

gradle = root / "client/android/build.gradle.kts"
gs = gradle.read_text(encoding="utf-8")
# Do NOT change applicationId: Qt/JNI bridge code is built around org.amnezia.vpn.
# CI builds an unsigned release APK; it is signed with Flint's private key
# only after the artifact is downloaded into the private build environment.
gs = gs.replace('signingConfig = signingConfigs["release"]', 'signingConfig = null')
gradle.write_text(gs, encoding="utf-8")

# Russian services: keep the list deliberately small. Android split tunneling
# works on resolved IP routes, so resolving 100+ domains at app startup caused
# the freezes seen in older Flint builds.
domains = [
    "zakupki.gov.ru", "lk.zakupki.gov.ru", "eruz.zakupki.gov.ru",
    "gosuslugi.ru", "esia.gosuslugi.ru",
    "nalog.gov.ru", "roskazna.gov.ru",
    "tbank.ru", "sberbank.ru", "vtb.ru", "alfabank.ru",
    "yandex.ru", "vk.com", "mail.ru",
    "ozon.ru", "wildberries.ru", "wb.ru",
    "2gis.ru", "rzd.ru", "avito.ru"
]

repo = root / "client/core/repositories/secureAppSettingsRepository.cpp"
s = repo.read_text(encoding="utf-8")
anchor = '    m_gatewayEndpoint = storedEndpoint.isEmpty() ? gatewayEndpoint : storedEndpoint;\n'
if "FLINT_RU_DIRECT_DEFAULTS_BEGIN" not in s:
    if anchor not in s:
        raise RuntimeError("SecureAppSettingsRepository constructor anchor changed")
    items = ",\n            ".join(f'QStringLiteral("{d}")' for d in domains)
    insert = (
        anchor +
        '\n    // FLINT_RU_DIRECT_DEFAULTS_BEGIN\n'
        '    if (!value("Conf/flintRuDirectInitialized", false).toBool()) {\n'
        '        const QStringList flintRuDirectSites = {\n'
        '            ' + items + '\n'
        '        };\n'
        '        QVariantMap directSites;\n'
        '        for (const QString &site : flintRuDirectSites)\n'
        '            directSites.insert(site, QStringList{});\n'
        '        setValue("Conf/ExceptSites", directSites);\n'
        '        setValue("Conf/routeMode", static_cast<int>(RouteMode::VpnAllExceptSites));\n'
        '        setValue("Conf/sitesSplitTunnelingEnabled", true);\n'
        '        setValue("Conf/flintRuDirectInitialized", true);\n'
        '    }\n'
        '    // FLINT_RU_DIRECT_DEFAULTS_END\n'
    )
    s = s.replace(anchor, insert, 1)
    repo.write_text(s, encoding="utf-8")

# Only resolve critical direct-route sites once on startup.
critical = [
    "zakupki.gov.ru", "lk.zakupki.gov.ru", "eruz.zakupki.gov.ru",
    "gosuslugi.ru", "esia.gosuslugi.ru",
    "nalog.gov.ru", "roskazna.gov.ru",
    "tbank.ru", "sberbank.ru", "ozon.ru", "wildberries.ru", "yandex.ru"
]

ctl = root / "client/core/controllers/ipSplitTunnelingController.cpp"
s = ctl.read_text(encoding="utf-8")
constructor_marker = "FLINT_CRITICAL_RU_RESOLVE_BEGIN"
if constructor_marker not in s:
    old = '    fillSites();\n}\n'
    if old not in s:
        raise RuntimeError("IpSplitTunnelingController constructor anchor changed")
    values = ", ".join(f'QStringLiteral("{d}")' for d in critical)
    new = (
        '    fillSites();\n\n'
        '    // FLINT_CRITICAL_RU_RESOLVE_BEGIN\n'
        '    const QStringList flintCriticalRu = { ' + values + ' };\n'
        '    for (const QString &host : flintCriticalRu) {\n'
        '        for (const auto &site : m_sites) {\n'
        '            if (site.first == host && site.second.isEmpty()) {\n'
        '                QHostInfo::lookupHost(host, this, SLOT(onHostResolved(QHostInfo)));\n'
        '                break;\n'
        '            }\n'
        '        }\n'
        '    }\n'
        '    // FLINT_CRITICAL_RU_RESOLVE_END\n'
        '}\n'
    )
    s = s.replace(old, new, 1)
    ctl.write_text(s, encoding="utf-8")

# Keep Flint tokens/subscription in the app's protected settings.
secure = root / "client/secureQSettings.cpp"
s = secure.read_text(encoding="utf-8")
old = 'encryptedKeys({ "Servers/serversList" })'
new = ('encryptedKeys({ "Servers/serversList", "Conf/flintAccessToken", '
       '"Conf/flintRefreshToken", "Conf/flintSubscriptionUrl", '
       '"Conf/flintTelegramVerifier", "Conf/flintTelegramLoginId" })')
if old in s:
    s = s.replace(old, new, 1)
secure.write_text(s, encoding="utf-8")

# Install Flint API controller and the phone UI.
shutil.copy2(flint / "flintController.h",
             root / "client/ui/controllers/flintController.h")
shutil.copy2(flint / "flintController.cpp",
             root / "client/ui/controllers/flintController.cpp")
shutil.copy2(flint / "PageHome.qml",
             root / "client/ui/qml/Pages2/PageHome.qml")
shutil.copy2(flint / "PageStart.qml",
             root / "client/ui/qml/Pages2/PageStart.qml")

# Flint's user-visible assets. The Amnezia engine remains internal only.
qml_assets = root / "client/ui/qml/Assets"
qml_assets.mkdir(parents=True, exist_ok=True)
for asset in ["flint-dog.svg", "flint-background.svg", "flint-main.png", "flint-background.jpg", "flint-logo.svg", "flint-logo.png"]:
    shutil.copy2(flint / asset, qml_assets / asset)

qml_qrc = root / "client/ui/qml/qml.qrc"
qrc = qml_qrc.read_text(encoding="utf-8")
for asset in ["flint-dog.svg", "flint-background.svg", "flint-main.png", "flint-background.jpg", "flint-logo.svg", "flint-logo.png"]:
    entry = f"        <file>Assets/{asset}</file>\n"
    if entry.strip() not in qrc:
        qrc = qrc.replace("    </qresource>", entry + "    </qresource>", 1)
qml_qrc.write_text(qrc, encoding="utf-8")

# Android launcher icon: exact approved Flint icon.
launcher_png = root / "client/android/res/drawable/flint_launcher.png"
launcher_png.write_bytes(base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAMAAABlApw1AAABgFBMVEVUZHPw7Ojx8/MHGSgLFBpOW2kECRARJC9ka3Ta5enR1tk3R1QpOUfU+OzU2+LFyc5sdHp7hY2PlJmwtbeFipBxeoSVnKbo5N2lqq9MVFokKS60vMRESlCZpKoxNjm5w8mu5tNs16pZ26QONDPP5Ny3+uNU46et181syaSPxreL27mX2MQ+UWGRt69QdXSF4rodM0EwVla48tfi3dgPQjtspZl2tKXE8t0rY1lVlYdtl5KZ5cQMIB4ZTEY9TmAyaWJcbYHh3uE2dWpMiHlj4q6KoZusxL287eFFTmBYjINjXml4gX5stpxjx5zAvbseLkAgHiFfzqFb0Z5+oKHBwL0bWUw/QD5EP0FOZV5fcIVdoZJcz55jYl9gb4KAgX+MzMCjnpygnqKioZ3Av8QAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACRKyLaAAAAgHRSTlP/////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABWU8B4AABpJSURBVHjazV2Je9s2licoQjwt3hJJiZQTu3XcNGna9Jq2M22nx7TTY46dve/d//9/WDxcBC8JpK18iy+xZVsi3w/vfngADVNvoDcz6K3MOcPokdm+FP/R7Gs+ypCkINSdx7MA+DSIV8qF6HBd+uWRBpIXQ+yOPXI7ABQutX8fA9Chnb0fuZtwX2yTOM5zxzEMA2NclodDiR82ykMJlzAMJ8rzPEvqvbdxJZGD6dYCYHZxkpebsNhmeWTgQ1DZ9sqyrPVjj9VqZdt2FZRGlMcAw0Vj8tQDMKED8j1X5J8bHtOtcwjslRyWtbrcIJOzasp8m+6PnsupuuJkS/mYBNAxA1fm1ZX74jPCWQcfmhWbJnXYMB5I7+AS5DaW7WOQqLi+YxCuXEUZ+qoyaYWA/LvaCJrDH4nQ48BevZlh20FJdAKXvm/EqeRC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"))

# Keep the Flint vector for quick settings/monochrome fallback.
flint_drawable = root / "client/android/res/drawable/ic_flint_round.xml"
shutil.copy2(flint / "ic_flint_round.xml", flint_drawable)

for rel in ["client/android/res/mipmap-anydpi-v26/icon.xml",
            "client/android/res/mipmap-anydpi-v26/icon_round.xml"]:
    p = root / rel
    text = p.read_text(encoding="utf-8")
    text = text.replace('@mipmap/ic_launcher_foreground', '@drawable/ic_flint_round')
    text = text.replace('@drawable/ic_launcher_monochrome', '@drawable/ic_flint_round')
    p.write_text(text, encoding="utf-8")

launcher_bg = root / "client/android/res/drawable/ic_launcher_background.xml"
launcher_bg.write_text("""<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:type="linear" android:angle="135"
        android:startColor="#0B3146" android:centerColor="#071A29" android:endColor="#06111D" />
</shape>
""", encoding="utf-8")

manifest = root / "client/android/AndroidManifest.xml"
mt = manifest.read_text(encoding="utf-8")
# Hard-code the Android launcher/task label. Do not rely on Qt's generated placeholder.
mt = mt.replace('android:label="-- %%INSERT_APP_NAME%% --"', 'android:label="Flint"')
# applicationId is Flint, while the native Qt/Android bridge classes remain in
# org.amnezia.vpn. Fully qualify every relative Android component so Android
# never tries to resolve them under app.flint.vpn at process startup.
for component in [
    "AmneziaApplication", "AmneziaActivity", "CameraActivity", "VpnRequestActivity",
    "AuthActivity", "TvFilePicker", "ImportConfigActivity", "AwgService",
    "OpenVpnService", "XrayService", "AmneziaTileService"
]:
    mt = mt.replace(f'android:name=".{component}"',
                    f'android:name="org.amnezia.vpn.{component}"')
mt = mt.replace('android:icon="@drawable/ic_amnezia_round"', 'android:icon="@drawable/flint_launcher"')
mt = mt.replace('android:roundIcon="@mipmap/icon_round"', 'android:roundIcon="@drawable/flint_launcher"')
mt = mt.replace('android:roundIcon="@drawable/ic_amnezia_round"', 'android:roundIcon="@drawable/flint_launcher"')
# Keep the FileProvider authority aligned with the stable engine namespace.
manifest.write_text(mt, encoding="utf-8")

# Remove remaining user-visible Amnezia naming from Android system dialogs.
for rel in ["client/android/res/values/strings.xml",
            "client/android/res/values-ru/strings.xml"]:
    p = root / rel
    text = p.read_text(encoding="utf-8")
    text = text.replace("AmneziaVPN", "Flint").replace("Amnezia VPN", "Flint")
    p.write_text(text, encoding="utf-8")

main_qml = root / "client/ui/qml/main2.qml"
text = main_qml.read_text(encoding="utf-8")
text = text.replace('title: "AmneziaVPN"', 'title: "Flint"')
text = text.replace("This legacy Amnezia subscription type", "This legacy subscription type")
main_qml.write_text(text, encoding="utf-8")

# Expose FlintController to QML and feed the cached subscription URL into
# Amnezia's proven profile importer.
hdr = root / "client/core/controllers/coreController.h"
s = hdr.read_text(encoding="utf-8")
inc_anchor = '#include "ui/controllers/networkReachabilityController.h"\n'
inc = '#include "ui/controllers/flintController.h"\n'
if inc not in s:
    if inc_anchor not in s:
        raise RuntimeError("CoreController include anchor changed")
    s = s.replace(inc_anchor, inc_anchor + inc, 1)

member = '    FlintController* m_flintController;\n'
member_anchor = '    UpdateUiController* m_updateUiController;\n'
if member not in s:
    if member_anchor not in s:
        raise RuntimeError("CoreController member anchor changed")
    s = s.replace(member_anchor, member_anchor + member, 1)
hdr.write_text(s, encoding="utf-8")

cpp = root / "client/core/controllers/coreController.cpp"
s = cpp.read_text(encoding="utf-8")
anchor = (
    '    m_networkReachabilityController = new NetworkReachabilityController(this);\n'
    '    setQmlContextProperty("NetworkReachabilityController", m_networkReachabilityController);\n'
    '    setQmlContextProperty("NetworkReachability", m_networkReachabilityController);\n'
)
if 'setQmlContextProperty("FlintController"' not in s:
    if anchor not in s:
        raise RuntimeError("CoreController init anchor changed")
    block = (
        anchor +
        '\n    m_flintController = new FlintController(m_settings, this);\n'
        '    setQmlContextProperty("FlintController", m_flintController);\n'
        '    connect(m_flintController, &FlintController::profileReady,\n'
        '            this, [this](const QString &uri) {\n'
        '        if (!m_importController || !m_serversUiController) return;\n'
        '        if (m_connectionUiController &&\n'
        '            (m_connectionUiController->isConnected() || m_connectionUiController->isConnectionInProgress())) {\n'
        '            emit m_pageController->showNotificationMessage(QStringLiteral("Отключите Flint перед сменой локации."));\n'
        '            return;\n'
        '        }\n'
        '        const QString oldFlintId = m_settings->value("Conf/flintProfileServerId").toString();\n'
        '        const int before = m_serversUiController->getServersCount();\n'
        '        if (!m_importController->extractConfigFromData(uri)) return;\n'
        '        m_importController->importConfig();\n'
        '        m_serversUiController->updateModel();\n'
        '        const int after = m_serversUiController->getServersCount();\n'
        '        if (after <= before) return;\n'
        '        const QString newFlintId = m_serversUiController->getServerId(after - 1);\n'
        '        if (newFlintId.isEmpty()) return;\n'
        '        if (!oldFlintId.isEmpty() && oldFlintId != newFlintId &&\n'
        '            m_serversUiController->getServerIndexById(oldFlintId) >= 0) {\n'
        '            m_serversUiController->removeServer(oldFlintId);\n'
        '        }\n'
        '        m_serversUiController->updateModel();\n'
        '        m_serversUiController->setDefaultServer(newFlintId);\n'
        '        m_serversUiController->setProcessedServerId(newFlintId);\n'
        '        m_settings->setValue("Conf/flintProfileServerId", newFlintId);\n'
        '    });\n'
    )
    s = s.replace(anchor, block, 1)
cpp.write_text(s, encoding="utf-8")

# Startup resilience: never terminate the whole Android process merely because
# the JNI/logging bridge is unavailable. Flint Home must remain visible so the
# problem can be diagnosed instead of looking like an instant app close.
core_cpp = root / "client/core/controllers/coreController.cpp"
core_text = core_cpp.read_text(encoding="utf-8")
core_text = core_text.replace(
    '    if (!AndroidController::initLogging()) {\n        qFatal("Android logging initialization failed");\n    }',
    '    if (!AndroidController::initLogging()) {\n        qCritical() << "Android logging initialization failed; continuing in UI safe mode";\n    }'
)
core_text = core_text.replace(
    '    if (!AndroidController::instance()->initialize()) {\n        qFatal("Android controller initialization failed");\n    }',
    '    if (!AndroidController::instance()->initialize()) {\n        qCritical() << "Android controller initialization failed; continuing in UI safe mode";\n    }'
)
core_cpp.write_text(core_text, encoding="utf-8")

# A broken QML from 8.9.7 may leave a persistent compiled QML/shader cache.
# Android keeps that cache when the APK is updated, so a fixed APK can still
# close immediately. Clear Qt caches before constructing QQmlApplicationEngine.
app_cpp = root / "client/amneziaApplication.cpp"
app_text = app_cpp.read_text(encoding="utf-8")
app_text = app_text.replace(
    'void AmneziaApplication::init()\n{\n    m_engine = new QQmlApplicationEngine;',
    'void AmneziaApplication::init()\n{\n#ifdef Q_OS_ANDROID\n    clearQtCaches();\n#endif\n    m_engine = new QQmlApplicationEngine;'
)
app_cpp.write_text(app_text, encoding="utf-8")

# Build-time guardrails: fail instead of shipping an Amnezia-looking client.
assert 'android:label="Flint"' in manifest.read_text(encoding="utf-8")
assert 'android:name="org.amnezia.vpn.AmneziaApplication"' in manifest.read_text(encoding="utf-8")
assert 'android:name="org.amnezia.vpn.AmneziaActivity"' in manifest.read_text(encoding="utf-8")
assert 'applicationId = "org.amnezia.vpn"' in gradle.read_text(encoding="utf-8")
assert 'PageSetupWizardStart' not in (root / "client/ui/qml/Pages2/PageStart.qml").read_text(encoding="utf-8")
assert 'source: "PageHome.qml"' in (root / "client/ui/qml/Pages2/PageStart.qml").read_text(encoding="utf-8")
assert 'Loader {' in (root / "client/ui/qml/Pages2/PageStart.qml").read_text(encoding="utf-8")
assert 'Flickable' not in (root / "client/ui/qml/Pages2/PageHome.qml").read_text(encoding="utf-8")
assert 'zakupki.gov.ru' in repo.read_text(encoding="utf-8")
assert '/auth/telegram/bot/start' in (root / "client/ui/controllers/flintController.cpp").read_text(encoding="utf-8")
assert 'parseSubscriptionProfiles' in (root / "client/ui/controllers/flintController.cpp").read_text(encoding="utf-8")
assert 'flintProfileServerId' in (root / "client/core/controllers/coreController.cpp").read_text(encoding="utf-8")
assert 'clearQtCaches();' in (root / "client/amneziaApplication.cpp").read_text(encoding="utf-8")

print("Flint Android 8.9.10 startup-safe patch applied and statically verified")
