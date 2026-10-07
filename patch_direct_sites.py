"""Apply editable direct sites and ignore late DNS answers for removed entries."""
from pathlib import Path
import shutil

def apply(root):
    here = Path(__file__).resolve().parent
    shutil.copy2(here/'flint/flintDirectSites.h', root/'client/ui/controllers/flintDirectSites.h')
    path = root/'client/core/controllers/ipSplitTunnelingController.cpp'
    text = path.read_text(encoding='utf-8')
    text = text.replace('#include "ipSplitTunnelingController.h"', '#include "ipSplitTunnelingController.h"\n#include "ui/controllers/flintDirectSites.h"')
    start = text.index('    QString normalized = hostname;', text.index('QString IpSplitTunnelingController::normalizeHostname'))
    end = text.index('\n}', start)
    text = text[:start] + '''    const QString value = hostname.trimmed();
    if (NetworkUtilities::ipAddressWithSubnetRegExp().exactMatch(value)) return value;
    return FlintDirectSites::normalize(value);''' + text[end:]
    marker = 'void IpSplitTunnelingController::processSiteAfterResolve(const QString &hostname, const QStringList &ips)\n{'
    assert marker in text
    text = text.replace(marker, marker + '''
    // A DNS reply may arrive after removal or after switching route lists.
    const auto sites = m_appSettingsRepository->vpnSites(m_currentRouteMode);
    if (!sites.contains(hostname)) return;
''')
    path.write_text(text, encoding='utf-8')
    path = root/'client/ui/controllers/ipSplitTunnelingUiController.cpp'
    text = path.read_text(encoding='utf-8')
    old = '        emit finished(tr("New site added: %1").arg(hostname));\n    }'
    assert old in text
    text = text.replace(old, old + ' else {\n        emit errorOccurred(QStringLiteral("Сайт уже есть в списке или адрес некорректен"));\n    }', 1)
    path.write_text(text, encoding='utf-8')
