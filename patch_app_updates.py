"""Add the Flint updater without using the upstream vendor's update gateway."""
from pathlib import Path
import shutil

def apply(root):
    root = Path(root)
    assets = Path(__file__).resolve().parent / 'flint'
    for name in ('flintUpdates.h', 'flintUpdates.cpp', 'flintUpdatePolicy.h'):
        shutil.copy2(assets / name, root / 'client/ui/controllers' / name)
    cpp = root / 'client/core/coreController.cpp'
    if not cpp.exists():
        matches = list(root.glob('client/**/coreController.cpp'))
        assert len(matches) == 1
        cpp = matches[0]
    text = cpp.read_text(encoding='utf-8')
    anchor = '    setQmlContextProperty("FlintController", m_flintController);'
    assert anchor in text
    text = '#include "ui/controllers/flintUpdates.h"\n' + text.replace(anchor, anchor + '\n    setQmlContextProperty("FlintUpdateController", new FlintUpdates(m_flintController, this));', 1)
    cpp.write_text(text, encoding='utf-8')
    branding = root / 'client/cmake/branding/common.cmake'
    text = branding.read_text(encoding='utf-8')
    assert 'set(CLIENT_ENABLE_APP_UPDATES 1)' in text
    branding.write_text(text.replace('set(CLIENT_ENABLE_APP_UPDATES 1)', 'set(CLIENT_ENABLE_APP_UPDATES 0)'), encoding='utf-8')
    manifest = root / 'client/android/AndroidManifest.xml'
    text = manifest.read_text(encoding='utf-8')
    text = text.replace('<application', '<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n    <application', 1)
    provider = '''<provider android:name="org.amnezia.vpn.FlintUpdateProvider" android:authorities="${applicationId}.updates" android:exported="false" android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/flint_update_paths" />
        </provider>
    '''
    text = text.replace('</application>', provider + '</application>', 1)
    manifest.write_text(text, encoding='utf-8')
    (root / 'client/android/res/xml/flint_update_paths.xml').write_text('<?xml version="1.0" encoding="utf-8"?><paths xmlns:android="http://schemas.android.com/apk/res/android"><cache-path name="updates" path="flint-updates/" /></paths>\n', encoding='utf-8')
    shutil.copy2(assets / 'FlintUpdateInstaller.kt', root / 'client/android/src/org/amnezia/vpn/FlintUpdateInstaller.kt')
    shutil.copy2(assets / 'FlintUpdateProvider.kt', root / 'client/android/src/org/amnezia/vpn/FlintUpdateProvider.kt')
    gradle = root / 'client/android/build.gradle.kts'
    text = gradle.read_text(encoding='utf-8')
    assert 'isUniversalApk = false' in text
    gradle.write_text(text.replace('isUniversalApk = false', 'isUniversalApk = true'), encoding='utf-8')
