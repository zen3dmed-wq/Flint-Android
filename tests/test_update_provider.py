"""Validate Android component identity, not just presence of the update authority."""
from pathlib import Path
import importlib.util
import tempfile
import unittest
import xml.etree.ElementTree as ET

SOURCE = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'


def check_manifest(text):
    tree = ET.fromstring(text)
    providers = tree.findall('application/provider')
    names = [p.get(ANDROID + 'name') for p in providers]
    if len(names) != len(set(names)):
        raise ValueError('Android reuses providers by component class: duplicate provider identity')
    update = next(p for p in providers if p.get(ANDROID + 'authorities', '').endswith('.updates'))
    assert update.get(ANDROID + 'name') == 'org.amnezia.vpn.FlintUpdateProvider'
    assert update.get(ANDROID + 'exported') == 'false'
    assert update.get(ANDROID + 'grantUriPermissions') == 'true'
    assert update.find('meta-data').get(ANDROID + 'resource') == '@xml/flint_update_paths'


class UpdateProviderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        files = {
            'client/core/coreController.cpp': '    setQmlContextProperty("FlintController", m_flintController);',
            'client/cmake/branding/common.cmake': 'set(CLIENT_ENABLE_APP_UPDATES 1)',
            'client/android/build.gradle.kts': 'isUniversalApk = false',
            'client/android/AndroidManifest.xml': '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>
<provider android:name="androidx.core.content.FileProvider" android:authorities="org.amnezia.vpn.qtprovider" android:exported="false" android:grantUriPermissions="true">
<meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/qtprovider_paths" />
</provider></application></manifest>''',
            'client/android/res/xml/qtprovider_paths.xml': '<paths><files-path name="files_path" path="/" /></paths>',
        }
        for name, text in files.items():
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding='utf-8')
        for name in ('client/ui/controllers', 'client/android/src/org/amnezia/vpn'):
            (self.root / name).mkdir(parents=True)
        spec = importlib.util.spec_from_file_location('updates_patch', SOURCE / 'patch_app_updates.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.apply(self.root)
        self.manifest = (self.root / 'client/android/AndroidManifest.xml').read_text(encoding='utf-8')

    def tearDown(self):
        self.temp.cleanup()

    def test_existing_and_update_providers_have_distinct_components(self):
        check_manifest(self.manifest)

    def test_detects_exact_819_and_820_duplicate_component_regression(self):
        broken = self.manifest.replace('org.amnezia.vpn.FlintUpdateProvider', 'androidx.core.content.FileProvider')
        with self.assertRaisesRegex(ValueError, 'duplicate provider identity'):
            check_manifest(broken)

    def test_update_uri_only_exposes_private_update_cache(self):
        tree = ET.parse(self.root / 'client/android/res/xml/flint_update_paths.xml')
        self.assertEqual([(p.tag, p.get('name'), p.get('path')) for p in tree.getroot()],
                         [('cache-path', 'updates', 'flint-updates/')])

    def test_qt_provider_keeps_original_files_roots(self):
        tree = ET.fromstring(self.manifest)
        qt = next(p for p in tree.findall('application/provider') if p.get(ANDROID+'authorities') == 'org.amnezia.vpn.qtprovider')
        self.assertEqual(qt.find('meta-data').get(ANDROID+'resource'), '@xml/qtprovider_paths')
        self.assertEqual((self.root/'client/android/res/xml/qtprovider_paths.xml').read_text(),
                         '<paths><files-path name="files_path" path="/" /></paths>')

    def test_provider_implementation_is_in_android_source_set(self):
        copied = self.root/'client/android/src/org/amnezia/vpn/FlintUpdateProvider.kt'
        self.assertEqual(copied.read_bytes(), (SOURCE/'flint/FlintUpdateProvider.kt').read_bytes())


if __name__ == '__main__':
    unittest.main()
