from pathlib import Path
import shutil

def apply(root: Path, assets: Path):
    shutil.copy2(assets/'flintRouting.h', root/'client/ui/controllers/flintRouting.h')
    for name in ['flint-routing-catalog.json', 'flint-routing-LICENSES.txt', 'flint-import-proxies.json']:
        shutil.copy2(assets/name, root/'client/ui/qml/Assets'/name)
    p=root/'client/ui/qml/qml.qrc'; s=p.read_text(encoding='utf-8')
    for name in ['flint-routing-catalog.json', 'flint-routing-LICENSES.txt', 'flint-import-proxies.json']:
        s=s.replace('    </qresource>', f'        <file>Assets/{name}</file>\n    </qresource>',1)
    p.write_text(s,encoding='utf-8')
    p=root/'client/core/repositories/secureAppSettingsRepository.h'; s=p.read_text(encoding='utf-8')
    assert 'public:' in s
    s=s.replace('public:', 'public:\n    QByteArray flintRoutingPolicy() const { return value("Conf/flintRouting").toByteArray(); }',1)
    p.write_text(s,encoding='utf-8')
    p=root/'client/vpnConnection.cpp'; s=p.read_text(encoding='utf-8')
    s='#include "ui/controllers/flintRouting.h"\n'+s
    anchor='    m_vpnConfiguration.insert(configKey::splitTunnelType, routeMode);'
    assert anchor in s
    s=s.replace(anchor, '''    // Match domains in Xray itself; resolving a list once at startup misses
    // subdomains and changes in CDN addresses. Geosite/geoip groups are expanded
    // from the bundled catalog before the native engine receives its config.
    if ((protocolName == "xray" || protocolName == "ssxray") &&
        m_appSettingsRepository->isSitesSplitTunnelingEnabled() &&
        m_appSettingsRepository->routeMode() == amnezia::RouteMode::VpnAllExceptSites) {
        auto data = m_vpnConfiguration.value(protocolName + "_config_data").toObject();
        auto native = QJsonDocument::fromJson(data.value(configKey::config).toString().toUtf8()).object();
        auto policy = QJsonDocument::fromJson(m_appSettingsRepository->flintRoutingPolicy()).object();
        if (policy.isEmpty()) policy = FlintRouting::defaults();
        if (!native.isEmpty() && FlintRouting::valid(policy)) {
            native = FlintRouting::apply(native, policy, m_appSettingsRepository->vpnSites(amnezia::RouteMode::VpnAllExceptSites).keys());
            data[configKey::config] = QString::fromUtf8(QJsonDocument(native).toJson(QJsonDocument::Compact));
            m_vpnConfiguration[protocolName + "_config_data"] = data;
            routeMode = amnezia::RouteMode::VpnAllSites;
            sitesJsonArray = QJsonArray{};
        }
    }
''' +anchor,1)
    p.write_text(s,encoding='utf-8')
