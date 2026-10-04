"""Route Yandex navigation apps through Android's underlying network in RF auto mode."""
from pathlib import Path
import shutil


def apply(root: Path, assets: Path):
    native = root / 'client/android/protocolApi/src/main/kotlin'
    shutil.copy2(assets / 'FlintRussianApps.kt', native / 'FlintRussianApps.kt')
    path = native / 'Protocol.kt'
    text = path.read_text(encoding='utf-8')
    old = '''        if (splitTunnelType == SPLIT_TUNNEL_DISABLE) return
        val splitTunnelApps = config.getJSONArray("splitTunnelApps")'''
    new = '''        if (splitTunnelType != SPLIT_TUNNEL_DISABLE) {
        val splitTunnelApps = config.getJSONArray("splitTunnelApps")'''
    assert text.count(old) == 1
    text = text.replace(old, new, 1)
    old = '''            appHandlerFunc(splitTunnelApps.getString(i))
        }
    }'''
    new = '''            appHandlerFunc(splitTunnelApps.getString(i))
        }
        }
        FlintRussianApps.apply(config.optBoolean("flintRussianAppsDirect", false),
            includedApplications, excludedApplications, context.packageName) { app ->
            try {
                context.packageManager.getApplicationInfo(app, 0)
                true
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                false
            }
        }
    }'''
    assert text.count(old) == 1
    text = text.replace(old, new, 1)
    start = text.index('        if (splitTunnelType != SPLIT_TUNNEL_DISABLE) {')
    end = text.index('        FlintRussianApps.apply', start)
    lines = text[start:end].splitlines(keepends=True)
    text = text[:start] + lines[0] + ''.join('    ' + line if line.strip() else line for line in lines[1:-1]) + lines[-1] + text[end:]
    # A navigation app can be uninstalled between config preparation and establish().
    old = '            vpnBuilder.addDisallowedApplication(app)'
    new = '''            try {
                vpnBuilder.addDisallowedApplication(app)
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                if (app !in FlintRussianApps.packages) throw e
            }'''
    assert text.count(old) == 1
    path.write_text(text.replace(old, new, 1), encoding='utf-8')

    path = root / 'client/vpnConnection.cpp'
    text = path.read_text(encoding='utf-8')
    anchor = '    m_vpnConfiguration.insert(configKey::appSplitTunnelType, appsRouteMode);'
    assert text.count(anchor) == 1
    text = text.replace(anchor, '''    // Recomputed on every connection; disabling either switch restores normal
    // VPN app routing. The Android service saves this with the profile for restart.
    m_vpnConfiguration.insert("flintRussianAppsDirect",
        m_appSettingsRepository->isSitesSplitTunnelingEnabled() &&
        m_appSettingsRepository->routeMode() == amnezia::RouteMode::VpnAllExceptSites &&
        m_appSettingsRepository->flintAutomaticRouting());
''' + anchor, 1)
    path.write_text(text, encoding='utf-8')

    path = root / 'client/android/AndroidManifest.xml'
    text = path.read_text(encoding='utf-8')
    assert '<queries>' not in text
    text = text.replace('    <application', '''    <queries>
        <package android:name="ru.yandex.yandexmaps" />
        <package android:name="ru.yandex.yandexnavi" />
    </queries>

    <application''', 1)
    path.write_text(text, encoding='utf-8')
