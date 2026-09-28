#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys

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

# Branding: keep upstream build target, change user-facing identity/package.
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
replace(
    "client/cmake/branding/android.cmake",
    'set(CLIENT_ANDROID_PACKAGE "org.amnezia.vpn" CACHE STRING "Android package name for Play Store version lookup")',
    'set(CLIENT_ANDROID_PACKAGE "app.flint.vpn" CACHE STRING "Android package name for Play Store version lookup")'
)

gradle = root / "client/android/build.gradle.kts"
gs = gradle.read_text(encoding="utf-8")
if 'applicationId = "org.amnezia.vpn"' in gs:
    gs = gs.replace('applicationId = "org.amnezia.vpn"', 'applicationId = "app.flint.vpn"')
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
        '        importConfigFromData(uri);\n'
        '    });\n'
    )
    s = s.replace(anchor, block, 1)
cpp.write_text(s, encoding="utf-8")

print("Flint Android 8.9.5 patch applied")
