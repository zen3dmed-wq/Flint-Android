#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
repo = Path(__file__).resolve().parents[1]
overlay = repo / "overlay"

required = [
    "client/cmake/branding/common.cmake",
    "client/cmake/branding/android.cmake",
    "client/android/build.gradle.kts",
    "client/core/controllers/coreController.cpp",
    "client/core/controllers/coreController.h",
    "client/core/repositories/secureAppSettingsRepository.cpp",
    "client/core/controllers/ipSplitTunnelingController.cpp",
    "client/secureQSettings.cpp",
]
for rel in required:
    if not (root / rel).exists():
        raise SystemExit(f"Amnezia source mismatch, missing {rel}")

def replace(path, pairs):
    p = root / path
    s = p.read_text(encoding="utf-8")
    for a,b in pairs:
        s = s.replace(a,b)
    p.write_text(s, encoding="utf-8")

# User-visible branding. Internal Amnezia targets/services remain unchanged for compatibility.
replace("client/cmake/branding/common.cmake", [
    ('set(CLIENT_APPLICATION_NAME "AmneziaVPN"', 'set(CLIENT_APPLICATION_NAME "Flint"'),
    ('set(CLIENT_ORGANIZATION_NAME "AmneziaVPN.ORG"', 'set(CLIENT_ORGANIZATION_NAME "Flint"'),
    ('set(CLIENT_APP_INSTANCE_NAME "AmneziaVPNInstance"', 'set(CLIENT_APP_INSTANCE_NAME "FlintInstance"'),
])
replace("client/cmake/branding/android.cmake", [
    ('set(CLIENT_ANDROID_PACKAGE "org.amnezia.vpn"', 'set(CLIENT_ANDROID_PACKAGE "app.flint.vpn"'),
])
replace("client/android/build.gradle.kts", [
    ('applicationId = "org.amnezia.vpn"', 'applicationId = "app.flint.vpn"'),
])

# Protect Flint refresh token and cached subscription URL with the same secure settings layer.
secure = root / "client/secureQSettings.cpp"
s = secure.read_text(encoding="utf-8")
s = s.replace(
    'encryptedKeys({ "Servers/serversList" })',
    'encryptedKeys({ "Servers/serversList", "Conf/flintRefreshToken", "Conf/flintSubscriptionUrl", "Conf/flintImportedSubscriptionUrl" })'
)
secure.write_text(s, encoding="utf-8")

# RU Direct defaults.
domains = [
"tbank.ru","alfabank.ru","gazprombank.ru","vtb.ru","psbank.ru","sberbank.ru","mironline.ru",
"vk.com","ok.ru","mail.ru","max.ru","gosuslugi.ru","esia.gosuslugi.ru","kremlin.ru","government.ru",
"duma.gov.ru","epp.genproc.gov.ru","council.gov.ru","dom.gosuslugi.ru","roskazna.gov.ru","nalog.gov.ru",
"digital.gov.ru","mchs.gov.ru","yandex.ru","ya.ru","yandex.net","go.yandex","2gis.ru","rzd.ru","tutu.ru",
"gismeteo.ru","aeroflot.ru","flypobeda.ru","citydrive.ru","dellin.ru","delimobil.ru","avito.ru","ozon.ru",
"wildberries.ru","wb.ru","cdek.ru","kuper.ru","samokat.ru","tensor.ru","kontur.ru","megamarket.ru",
"hh.ru","1c.ru","moex.com","detmir.ru","evotor.ru","lenta.com","okmarket.ru","dostavista.ru","dodopizza.ru",
"music.yandex.ru","kinopoisk.ru","dzen.ru","ivi.ru","rutube.ru","premier.one","rbc.ru","ria.ru","gazeta.ru",
"lenta.ru","rambler.ru","kp.ru","aif.ru","rg.ru","vedomosti.ru","1tv.ru","360tv.ru","mts.ru","beeline.ru",
"megafon.ru","t2.ru","rostelecom.ru","sbermobile.ru","vk.ru","zakupki.gov.ru"
]
repo_cpp = root / "client/core/repositories/secureAppSettingsRepository.cpp"
s = repo_cpp.read_text(encoding="utf-8")
if "FLINT_RU_DIRECT_DEFAULTS_BEGIN" not in s:
    anchor = "    m_gatewayEndpoint = storedEndpoint.isEmpty() ? gatewayEndpoint : storedEndpoint;\n"
    if anchor not in s:
        raise SystemExit("SecureAppSettingsRepository anchor changed")
    items = ",\n            ".join(f'QStringLiteral("{d}")' for d in domains)
    snippet = f'''    m_gatewayEndpoint = storedEndpoint.isEmpty() ? gatewayEndpoint : storedEndpoint;\n\n    // FLINT_RU_DIRECT_DEFAULTS_BEGIN\n    if (!value("Conf/flintRuDirectInitialized", false).toBool()) {{\n        const QStringList flintRuDirectSites = {{\n            {items}\n        }};\n        QVariantMap directSites;\n        for (const QString &site : flintRuDirectSites) directSites.insert(site, QStringList{{}});\n        setValue("Conf/ExceptSites", directSites);\n        setValue("Conf/routeMode", static_cast<int>(RouteMode::VpnAllExceptSites));\n        setValue("Conf/sitesSplitTunnelingEnabled", true);\n        setValue("Conf/flintRuDirectInitialized", true);\n    }}\n    // FLINT_RU_DIRECT_DEFAULTS_END\n'''
    s = s.replace(anchor, snippet, 1)
    repo_cpp.write_text(s, encoding="utf-8")

# Resolve the direct-list hostnames immediately.
ctl = root / "client/core/controllers/ipSplitTunnelingController.cpp"
s = ctl.read_text(encoding="utf-8")
if "FLINT_RU_DIRECT_RESOLVE_BEGIN" not in s:
    anchor = "    fillSites();\n}\n"
    if anchor not in s:
        raise SystemExit("IpSplitTunnelingController anchor changed")
    snippet = '''    fillSites();\n\n    // FLINT_RU_DIRECT_RESOLVE_BEGIN\n    for (const auto &site : m_sites) {\n        if (site.second.isEmpty() && !NetworkUtilities::ipAddressWithSubnetRegExp().exactMatch(site.first))\n            QHostInfo::lookupHost(site.first, this, SLOT(onHostResolved(QHostInfo)));\n    }\n    // FLINT_RU_DIRECT_RESOLVE_END\n}\n'''
    s = s.replace(anchor, snippet, 1)
    ctl.write_text(s, encoding="utf-8")

# Flint controller + Home UI.
(root / "client/ui/controllers/flintController.h").write_text((overlay / "flintController.h").read_text(encoding="utf-8"), encoding="utf-8")
(root / "client/ui/controllers/flintController.cpp").write_text((overlay / "flintController.cpp").read_text(encoding="utf-8"), encoding="utf-8")
(root / "client/ui/qml/Pages2/PageHome.qml").write_text((overlay / "PageHome.qml").read_text(encoding="utf-8"), encoding="utf-8")

core_h = root / "client/core/controllers/coreController.h"
s = core_h.read_text(encoding="utf-8")
if '#include "ui/controllers/flintController.h"' not in s:
    s = s.replace('#include "ui/controllers/networkReachabilityController.h"\n',
                  '#include "ui/controllers/networkReachabilityController.h"\n#include "ui/controllers/flintController.h"\n')
if 'FlintController* m_flintController;' not in s:
    s = s.replace('    UpdateUiController* m_updateUiController;\n',
                  '    UpdateUiController* m_updateUiController;\n    FlintController* m_flintController;\n')
core_h.write_text(s, encoding="utf-8")

core_cpp = root / "client/core/controllers/coreController.cpp"
s = core_cpp.read_text(encoding="utf-8")
anchor = '    m_networkReachabilityController = new NetworkReachabilityController(this);\n    setQmlContextProperty("NetworkReachabilityController", m_networkReachabilityController);\n    setQmlContextProperty("NetworkReachability", m_networkReachabilityController);\n'
if 'setQmlContextProperty("FlintController"' not in s:
    if anchor not in s:
        raise SystemExit("CoreController anchor changed")
    block = anchor + '''
    m_flintController = new FlintController(m_settings, this);
    setQmlContextProperty("FlintController", m_flintController);
    connect(m_flintController, &FlintController::profileReady, this, [this](const QString &uri) {
        if (!m_importCoreController) return;
        auto result = m_importCoreController->extractConfigFromData(uri);
        if (result.errorCode == ErrorCode::NoError && !result.config.isEmpty())
            m_importCoreController->importConfig(result.config);
    });
'''
    s = s.replace(anchor, block, 1)
core_cpp.write_text(s, encoding="utf-8")

print("Flint Android overlay applied")
