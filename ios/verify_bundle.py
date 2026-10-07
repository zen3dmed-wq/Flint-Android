"""Check the actual unsigned app and embedded packet tunnel before archiving it."""
import json, plistlib, subprocess, sys
from pathlib import Path

build = Path(sys.argv[1])
apps = list(build.rglob('Flint.app'))
assert len(apps) == 1, f'Expected one Flint.app, found {apps}'
app = apps[0]
def read(path):
    return plistlib.loads(path.read_bytes())
info = read(app/'Info.plist')
assert info['CFBundleIdentifier'] == 'app.flint.vpn'
assert info['CFBundleDisplayName'] == info['CFBundleName'] == 'Flint'
assert info['CFBundleShortVersionString'] == '8.10.25'
assert info['CFBundleVersion'] == '2195'
extensions = list((app/'PlugIns').glob('*.appex'))
assert len(extensions) == 1
ext = extensions[0]
ei = read(ext/'Info.plist')
assert ei['CFBundleDisplayName'] == 'Flint VPN'
assert ei['CFBundleIdentifier'] == info['CFBundleIdentifier'] + '.network-extension'
assert ei['CFBundleVersion'] == info['CFBundleVersion']
assert ei['NSExtension']['NSExtensionPointIdentifier'] == 'com.apple.networkextension.packet-tunnel'
assert ei['com.wireguard.ios.app_group_id'] == info['com.wireguard.ios.app_group_id'] == 'group.app.flint.vpn'
for bundle, meta in [(app,info),(ext,ei)]:
    binary = bundle/meta['CFBundleExecutable']
    arches = subprocess.check_output(['lipo','-archs',str(binary)],text=True).strip().split()
    assert 'arm64' in arches, arches
assert (app/'Assets.car').is_file()
print(json.dumps({'app':str(app),'extension':str(ext),'version':info['CFBundleShortVersionString'],'build':info['CFBundleVersion'],'signed':False},indent=2))
