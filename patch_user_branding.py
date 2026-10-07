"""Brand display strings without changing protocol, JNI or storage identities."""
from pathlib import Path
import re


def apply(root: Path):
    def edit(relative, old, new):
        path = root / relative
        text = path.read_text(encoding='utf-8')
        assert text.count(old) == 1, f'Branding anchor changed: {relative}: {old}'
        path.write_text(text.replace(old, new, 1), encoding='utf-8')

    # Android's own "VPN active" notification reads Builder.setSession, not
    # the application label or our foreground-service notification title.
    edit('client/android/protocolApi/src/main/kotlin/Protocol.kt',
         'const val VPN_SESSION_NAME = "AmneziaVPN"',
         'const val VPN_SESSION_NAME = "Flint"')
    edit('client/android/src/org/amnezia/vpn/AmneziaTileService.kt',
         'private const val DEFAULT_TILE_LABEL = "AmneziaVPN"',
         'private const val DEFAULT_TILE_LABEL = "Flint"')
    # Re-registering the same channel updates its name and keeps user settings.
    edit('client/android/src/org/amnezia/vpn/ServiceNotification.kt',
         '.setName("AmneziaVPN")', '.setName("Flint")')
    edit('client/android/src/org/amnezia/vpn/AuthActivity.kt',
         '.setTitle("AmneziaVPN")', '.setTitle("Flint")')

    def display(text):
        if re.search(r'copyright|©|licen[sc]e|лиценз|авторск', text, re.I):
            return text
        return text.replace('AmneziaVPN', 'Flint').replace('Amnezia VPN', 'Flint')

    for relative in ['client/ui/utils/notificationHandler.cpp',
                     'client/core/utils/containers/containerUtils.cpp']:
        path = root / relative
        text = path.read_text(encoding='utf-8')
        changed = re.sub(r'"(?:[^"\\]|\\.)*"', lambda m: display(m[0]), text)
        assert changed != text, f'Common UI branding anchor changed: {relative}'
        path.write_text(changed, encoding='utf-8')

    # Cover all Android languages, including locale-specific notification text.
    for path in (root / 'client/android/res').glob('values*/strings.xml'):
        text = path.read_text(encoding='utf-8')
        path.write_text(re.sub(r'(<string\b[^>]*>)(.*?)(</string>)',
                               lambda m: m[1] + display(m[2]) + m[3], text, flags=re.S), encoding='utf-8')
    # Built-in import/file dialogs can still be reached from the Flint UI.
    # Only string literals change; QML types, protocol names and license files do not.
    for path in (root / 'client/ui/qml').rglob('*.qml'):
        text = path.read_text(encoding='utf-8')
        changed = re.sub(r'"(?:[^"\\]|\\.)*"', lambda m: display(m[0]), text)
        if changed != text:
            path.write_text(changed, encoding='utf-8')
    # Keep translated sources in sync so a Russian system does not restore an
    # old brand through a compiled Qt translation. Context names remain intact.
    for path in (root / 'client').rglob('*.ts'):
        text = path.read_text(encoding='utf-8')
        changed = re.sub(r'(<(?:source|translation)\b[^>]*>)(.*?)(</(?:source|translation)>)',
                         lambda m: m[1] + display(m[2]) + m[3], text, flags=re.S)
        if changed != text:
            path.write_text(changed, encoding='utf-8')
