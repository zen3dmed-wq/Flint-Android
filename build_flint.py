#!/usr/bin/env python3
from pathlib import Path
import shutil
import sys
from patch_android_identity import apply as apply_android_identity
from patch_android_widget import apply as apply_android_widget
from patch_direct_sites import apply as apply_direct_sites
from patch_qr_images import apply as apply_qr_images
from patch_vpn_permission import apply as apply_vpn_permission
from patch_routing import apply as apply_routing
from patch_flint_qr_subscription import apply as apply_flint_qr_subscription

root = Path(sys.argv[1]).resolve()
apply_flint_qr_subscription(root)
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
    'set(AMNEZIAVPN_VERSION 8.10.11 CACHE STRING "Client app version")'
)
replace(
    "CMakeLists.txt",
    'set(APP_ANDROID_VERSION_CODE 2163)',
    'set(APP_ANDROID_VERSION_CODE 2181)'
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
# Keep the Java/JNI namespace intact while matching the installed Flint package ID.

gradle = root / "client/android/build.gradle.kts"
gs = gradle.read_text(encoding="utf-8")
# Components below are fully qualified under the engine's Java namespace.
# CI builds an unsigned release APK; it is signed with Flint's private key
# only after the artifact is downloaded into the private build environment.
gs = gs.replace('signingConfig = signingConfigs["release"]', 'signingConfig = null')
gs = gs.replace('applicationId = "org.amnezia.vpn"', 'applicationId = "app.flint.vpn"')
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

apply_direct_sites(root)
apply_qr_images(root)

# Keep Flint tokens/subscription in the app's protected settings.
secure = root / "client/secureQSettings.cpp"
s = secure.read_text(encoding="utf-8")
old = 'encryptedKeys({ "Servers/serversList" })'
new = ('encryptedKeys({ "Servers/serversList", "Conf/flintAccessToken", '
       '"Conf/flintRefreshToken", "Conf/flintSubscriptionUrl", "Conf/flintDraft/purchase", "Conf/flintDraft/support", '
       '"Conf/flintTelegramVerifier", "Conf/flintTelegramLoginId", "Conf/flintManualImports", "Conf/flintCachedProfiles", "Conf/flintLastProfile", "Conf/flintInstalledProfile", "Conf/flintWorkingProfile" })')
if old in s:
    s = s.replace(old, new, 1)
# Persist the installation identity before any login request can use it. The
# secure wrapper has no public sync(); flush its underlying QSettings while
# setValue still holds its existing mutex.
identity_flush = '''    m_cache.insert(key, value);
    if (key == QStringLiteral("Conf/flintDeviceId")) {
        m_settings.sync();
    }'''
if identity_flush not in s:
    anchor = '    m_cache.insert(key, value);'
    if s.count(anchor) != 1:
        raise RuntimeError("SecureQSettings identity persistence anchor changed")
    s = s.replace(anchor, identity_flush, 1)
assert identity_flush in s
secure.write_text(s, encoding="utf-8")

# Install Flint API controller and the phone UI.
shutil.copy2(flint / "flintSubscriptionFetch.h", root / "client/ui/controllers/flintSubscriptionFetch.h")
shutil.copy2(flint / "flintHealth.cpp", root / "client/ui/controllers/flintHealth.cpp")
shutil.copy2(flint / "FlintProbe.kt", root / "client/android/xray/src/main/kotlin/FlintProbe.kt")
shutil.copy2(flint / "flintController.h",
             root / "client/ui/controllers/flintController.h")
shutil.copy2(flint / "flintController.cpp",
             root / "client/ui/controllers/flintController.cpp")
shutil.copy2(flint / "PageHome.qml",
             root / "client/ui/qml/Pages2/PageHome.qml")
shutil.copy2(flint / "PageStart.qml",
             root / "client/ui/qml/Pages2/PageStart.qml")

for name in ("FlintAccount.qml", "FlintIdentity.qml", "FlintButton.qml", "FlintField.qml", "FlintChoice.qml", "FlintDevices.qml", "FlintSites.qml", "DeviceRows.js", "FlintFocus.js", "FlintUsage.js", "FlintPlans.js", "FlintSubscriptions.qml", "FlintTrafficBar.qml"):
    shutil.copy2(flint / name, root / "client/ui/qml/Pages2" / name)
# Flint's user-visible assets. The Amnezia engine remains internal only.
qml_assets = root / "client/ui/qml/Assets"
qml_assets.mkdir(parents=True, exist_ok=True)
for asset in ["flint-dog.svg", "flint-background.svg", "flint-main.png", "flint-background.jpg", "flint-logo.svg", "flint-logo.png"]:
    shutil.copy2(flint / asset, qml_assets / asset)

qml_qrc = root / "client/ui/qml/qml.qrc"
qrc = qml_qrc.read_text(encoding="utf-8")
for name in ("FlintAccount.qml", "FlintIdentity.qml", "FlintButton.qml", "FlintField.qml", "FlintChoice.qml", "FlintDevices.qml", "FlintSites.qml", "DeviceRows.js", "FlintFocus.js", "FlintUsage.js", "FlintPlans.js", "FlintSubscriptions.qml", "FlintTrafficBar.qml"):
    qrc = qrc.replace("    </qresource>", f"        <file>Pages2/{name}</file>\n    </qresource>", 1)
for asset in ["flint-dog.svg", "flint-background.svg", "flint-main.png", "flint-background.jpg", "flint-logo.svg", "flint-logo.png"]:
    entry = f"        <file>Assets/{asset}</file>\n"
    if entry.strip() not in qrc:
        qrc = qrc.replace("    </qresource>", entry + "    </qresource>", 1)
qml_qrc.write_text(qrc, encoding="utf-8")

# Android launcher icon: use the same Flint dog artwork as the Windows design.
launcher_png = root / "client/android/res/drawable/flint_launcher.png"
shutil.copy2(flint / "flint-main.png", launcher_png)

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
apply_android_identity(root, flint)
apply_android_widget(root, flint)

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
        '    connect(m_vpnConnection.data(), &VpnConnection::bytesChanged, m_flintController, &FlintController::updateTraffic);\n' 
        '    connect(m_connectionUiController, &ConnectionUiController::connectionStateChanged,\n'
        '            m_flintController, [this]() {\n'
        '        m_flintController->setVpnActive(m_connectionUiController->isConnected() || m_connectionUiController->isConnectionInProgress());\n'
        '        if (m_connectionUiController->isConnected()) m_flintController->markProfileConnected();\n'
        '    });\n'
        '    connect(m_serversUiController, &ServersUiController::defaultServerIdChanged,\n'
        '            m_flintController, [this](const QString &defaultId) {\n'
        '        QVariantList servers;\n'
        '        for (int i = 0; i < m_serversUiController->getServersCount(); ++i) {\n'
        '            const QString id = m_serversUiController->getServerId(i);\n'
        '            servers.append(QVariantMap{{"id", id}, {"name", m_serversUiController->serverName(id)}});\n'
        '        }\n'
        '        m_flintController->syncSavedServers(servers, defaultId);\n'
        '    });\n'
        '    connect(m_flintController, &FlintController::profileReady,\n'
        '            this, [this](const QString &uri) {\n'
        '        if (!m_importController || !m_serversUiController) { m_flintController->profileInstallResult(false); return; }\n'
        '        if (m_connectionUiController &&\n'
        '            (m_connectionUiController->isConnected() || m_connectionUiController->isConnectionInProgress())) {\n'
        '            emit m_pageController->showNotificationMessage(QStringLiteral("Отключите Flint перед сменой локации."));\n'
        '            m_flintController->profileInstallResult(false);\n'
        '            return;\n'
        '        }\n'
        '        const QString oldFlintId = m_settings->value("Conf/flintProfileServerId").toString();\n'
        '        if (!oldFlintId.isEmpty() && m_serversUiController->getServerIndexById(oldFlintId) >= 0 &&\n'
        '            m_settings->value("Conf/flintInstalledProfile").toString() == uri) {\n'
        '            m_serversUiController->setDefaultServer(oldFlintId);\n'
        '            m_serversUiController->setProcessedServerId(oldFlintId);\n'
        '            m_flintController->profileInstallResult(true);\n'
        '            return;\n'
        '        }\n'
        '        QStringList previousIds;\n'
        '        for (int i = 0; i < m_serversUiController->getServersCount(); ++i)\n'
        '            previousIds.append(m_serversUiController->getServerId(i));\n'
        '        if (!m_importController->extractConfigFromData(uri)) { m_flintController->profileInstallResult(false); return; }\n'
        '        m_importController->importConfig();\n'
        '        m_serversUiController->updateModel();\n'
        '        QString newFlintId;\n'
        '        for (int i = 0; i < m_serversUiController->getServersCount(); ++i) {\n'
        '            const QString id = m_serversUiController->getServerId(i);\n'
        '            if (!previousIds.contains(id)) { newFlintId = id; break; }\n'
        '        }\n'
        '        if (newFlintId.isEmpty()) { m_flintController->profileInstallResult(false); return; }\n'
        '        m_flintController->setManagedProfileServerId(newFlintId);\n'
        '        if (!oldFlintId.isEmpty() && oldFlintId != newFlintId &&\n'
        '            m_serversUiController->getServerIndexById(oldFlintId) >= 0) {\n'
        '            m_serversUiController->removeServer(oldFlintId);\n'
        '        }\n'
        '        m_serversUiController->updateModel();\n'
        '        m_serversUiController->setDefaultServer(newFlintId);\n'
        '        m_serversUiController->setProcessedServerId(newFlintId);\n'
        '        m_flintController->profileInstallResult(true);\n'
        '    });\n'
    )
    block += '    connect(m_flintController, &FlintController::manualProfilesReady, this, [this](const QStringList &profiles) {\n        int imported=0,existing=0,failed=0;\n        QVariantMap seen=m_settings->value("Conf/flintManualImports").toMap();\n        for(const auto &uri:profiles) {\n            const QString id=seen.value(uri).toString();\n            if(!id.isEmpty() && m_serversUiController->getServerIndexById(id)>=0){++existing;continue;}\n            QStringList before;for(int i=0;i<m_serversUiController->getServersCount();++i)before<<m_serversUiController->getServerId(i);\n            if(!m_importController->extractConfigFromData(uri)){++failed;continue;}\n            m_importController->importConfig();m_serversUiController->updateModel();\n            QString added;for(int i=0;i<m_serversUiController->getServersCount();++i){auto serverId=m_serversUiController->getServerId(i);if(!before.contains(serverId)){added=serverId;break;}}\n            if(!added.isEmpty()){seen[uri]=added;++imported;}else ++failed;\n        }\n        m_settings->setValue("Conf/flintManualImports",seen);\n        emit m_flintController->manualImportFinished(imported,failed?QStringLiteral("Не удалось добавить %1 серверов. Добавлено: %2.").arg(failed).arg(imported):(existing&&!imported?QStringLiteral("Эти серверы уже есть в списке."):QString()));\n    });\n'
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
assert 'applicationId = "app.flint.vpn"' in gradle.read_text(encoding="utf-8")
assert 'PageSetupWizardStart' not in (root / "client/ui/qml/Pages2/PageStart.qml").read_text(encoding="utf-8")
assert 'source: "PageHome.qml"' in (root / "client/ui/qml/Pages2/PageStart.qml").read_text(encoding="utf-8")
assert 'Loader {' in (root / "client/ui/qml/Pages2/PageStart.qml").read_text(encoding="utf-8")
assert 'Flickable' in (root / "client/ui/qml/Pages2/PageHome.qml").read_text(encoding="utf-8")
assert 'zakupki.gov.ru' in repo.read_text(encoding="utf-8")
assert '/auth/telegram/bot/start' in (root / "client/ui/controllers/flintController.cpp").read_text(encoding="utf-8")
assert 'parseSubscriptionProfiles' in (root / "client/ui/controllers/flintController.cpp").read_text(encoding="utf-8")
assert 'flintProfileServerId' in (root / "client/core/controllers/coreController.cpp").read_text(encoding="utf-8")
assert 'clearQtCaches();' in (root / "client/amneziaApplication.cpp").read_text(encoding="utf-8")

print("Flint Android 8.10.11 startup-safe patch applied and statically verified")

apply_vpn_permission(root)

apply_routing(root, flint)
