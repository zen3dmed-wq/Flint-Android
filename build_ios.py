#!/usr/bin/env python3
"""Apply the shared Flint client, then configure the real iOS tunnel targets."""
import argparse
import json
import plistlib
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

def apply(root, bundle_id, team, render_icons=True):
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9-]*(?:\.[A-Za-z][A-Za-z0-9-]*)+', bundle_id):
        raise ValueError('Invalid Apple bundle identifier')
    if team and not re.fullmatch(r'[A-Z0-9]{10}', team):
        raise ValueError('Apple Team ID must contain 10 uppercase letters/digits')
    # Includes the shared account, API, subscription importer and Flint QML UI.
    subprocess.run([sys.executable, str(HERE / 'build_flint.py'), str(root)], check=True)
    def edit(rel, old, new):
        p = root / rel
        s = p.read_text(encoding='utf-8')
        if old not in s:
            raise RuntimeError(f'iOS upstream anchor changed: {rel}: {old!r}')
        p.write_text(s.replace(old, new), encoding='utf-8')

    group = 'group.' + bundle_id
    edit('CMakeLists.txt', 'set(AMNEZIAVPN_VERSION 8.10.6 CACHE', 'set(AMNEZIAVPN_VERSION 8.10.6.2176 CACHE')
    edit('cmake/platform_settings.cmake', 'set(CONAN_INSTALL_BUILD_CONFIGURATIONS Release Debug MinSizeRel RelWithDebInfo)',
         'set(CONAN_INSTALL_BUILD_CONFIGURATIONS ${CMAKE_CONFIGURATION_TYPES})')
    apple = 'client/cmake/branding/apple.cmake'
    edit(apple, 'org.amnezia.AmneziaVPN', bundle_id)
    edit(apple, 'X7UJ388FXK', f'"{team}"')
    ios = 'client/cmake/branding/ios.cmake'
    edit(ios, 'org.amnezia.AmneziaVPN', bundle_id)
    edit(ios, 'itms-apps://itunes.apple.com/app/id1600529900', 'https://flintmain.ru')
    # Do not refer to another developer's provisioning profiles.
    p = root / ios
    s = re.sub(r'"(?:distr|dev) ios\.[^"]+"', '""', p.read_text(encoding='utf-8'))
    p.write_text(s, encoding='utf-8')

    for rel in ['client/ios/app/main.entitlements', 'client/ios/networkextension/AmneziaVPNNetworkExtension.entitlements']:
        p = root / rel
        values = plistlib.loads(p.read_bytes())
        values['com.apple.security.application-groups'] = [group]
        values['keychain-access-groups'] = ['$(AppIdentifierPrefix)' + group]
        values.pop('com.apple.security.files.user-selected.read-write', None)
        p.write_bytes(plistlib.dumps(values, sort_keys=False))

    p = root / 'client/ios/app/Info.plist.in'
    values = plistlib.loads(p.read_bytes())
    values['NSCameraUsageDescription'] = 'Flint использует камеру только для сканирования QR-кода подключения.'
    values['LSApplicationQueriesSchemes'] = ['tg']
    values['UIUserInterfaceStyle'] = 'Dark'
    p.write_bytes(plistlib.dumps(values, sort_keys=False))
    # Each platform identifies itself correctly to the configurable account API.
    edit('client/ui/controllers/flintController.cpp', '"android"', '"ios"')
    edit('client/ui/controllers/flintController.cpp', '"android/"', '"ios/"')
    edit('client/ui/qml/Pages2/PageHome.qml', 'Flint Android 8.10.6', 'Flint iOS 8.10.6')
    edit('client/ui/qml/Pages2/PageHome.qml', 'import Style 1.0', 'import Style 1.0\nimport PageEnum 1.0')
    edit('client/ui/qml/Pages2/PageHome.qml', '        ImportController.startDecodingQr()',
         '        ImportController.startDecodingQr()\n        PageController.goToPage(PageEnum.PageSetupWizardQrReader)')
    edit('client/ui/qml/Pages2/PageHome.qml', '            if (!root.qrScanning) return',
         '            if (!root.qrScanning) return\n            PageController.closePage()')
    edit('client/ui/qml/Pages2/PageHome.qml', 'text: "Добавить виджет на экран"',
         'visible: false\n                text: "Добавить виджет на экран"')

    # Native asset conversion keeps the supplied square composition centered.
    assets = root / 'client/ios/app/Media.xcassets'
    appicons = assets / 'AppIcon.appiconset'
    contents = json.loads((appicons / 'Contents.json').read_text())
    contents['images'] = [i for i in contents['images'] if i.get('filename') and i['idiom'] in ('iphone', 'ipad', 'ios-marketing')]
    (appicons / 'Contents.json').write_text(json.dumps(contents, indent=2) + '\n')
    if render_icons:
        rendered = set()
        for entry in contents['images']:
            size = int(float(entry['size'].split('x')[0]) * int(entry['scale'][:-1]))
            target = appicons / entry['filename']
            if target in rendered:
                continue
            subprocess.run(['sips', '-s', 'format', 'png', '-z', str(size), str(size), str(HERE/'flint/flint-emblem.jpg'), '--out', str(target)], check=True, stdout=subprocess.DEVNULL)
            rendered.add(target)
        launch = assets / 'FlintLaunch.imageset'
        launch.mkdir(exist_ok=True)
        (launch/'Contents.json').write_text(json.dumps({'images':[{'idiom':'universal','filename':'flint-emblem.jpg'}], 'info':{'author':'xcode','version':1}}))
        (launch/'flint-emblem.jpg').write_bytes((HERE/'flint/flint-emblem.jpg').read_bytes())
    edit('client/ios/app/AmneziaVPNLaunchScreen.storyboard', 'launch.png', 'FlintLaunch')
    print(f'Flint iOS configured: {bundle_id}, {group}; signing {"team " + team if team else "not configured"}')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--bundle-id', default='app.flint.vpn')
    parser.add_argument('--team', default='')
    parser.add_argument('--skip-icon-render', action='store_true', help='Static validation only; not a distributable build')
    args = parser.parse_args()
    apply(args.source.resolve(), args.bundle_id, args.team, not args.skip_icon_render)
