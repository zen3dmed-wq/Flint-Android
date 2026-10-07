"""Check installation/discovery contracts in the generated Android project."""
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(sys.argv.pop(1)).resolve() / 'client/android'
A = '{http://schemas.android.com/apk/res/android}'


class WidgetInstallationTests(unittest.TestCase):
    def test_widgets_cannot_be_installed_on_external_storage(self):
        self.assertEqual(ET.parse(ROOT/'AndroidManifest.xml').getroot().get(A+'installLocation'), 'internalOnly')

    def test_widget_discovery_and_both_preview_formats(self):
        app = ET.parse(ROOT/'AndroidManifest.xml').getroot().find('application')
        receiver = next(r for r in app.findall('receiver') if r.get(A+'name').endswith('.FlintWidgetProvider'))
        self.assertIn(receiver.get(A+'enabled'), ('true','false'))
        self.assertEqual(receiver.find('intent-filter/action').get(A+'name'), 'android.appwidget.action.APPWIDGET_UPDATE')
        self.assertEqual(receiver.find('meta-data').get(A+'resource'), '@xml/flint_widget_info')
        info = ET.parse(ROOT/'res/xml/flint_widget_info.xml').getroot()
        self.assertEqual(info.get(A+'widgetCategory'), 'home_screen')
        self.assertEqual(info.get(A+'targetCellWidth'), '1')
        self.assertEqual(info.get(A+'targetCellHeight'), '1')
        self.assertEqual(info.get(A+'previewLayout'), '@layout/flint_widget')
        self.assertEqual(info.get(A+'previewImage'), '@drawable/flint_emblem')
        self.assertTrue((ROOT/'res/drawable-nodpi/flint_emblem.jpg').is_file())

    def test_launcher_shortcut_reuses_private_toggle_activity(self):
        app = ET.parse(ROOT/'AndroidManifest.xml').getroot().find('application')
        launcher = next(a for a in app.findall('activity') if a.get(A+'name').endswith('.AmneziaActivity'))
        if any(m.get(A+'name')=='app.flint.distribution' and m.get(A+'value')=='tv' for m in app.findall('meta-data')):
            self.assertFalse(any(m.get(A+'name')=='android.app.shortcuts' for m in launcher.findall('meta-data')))
            return
        shortcuts = next(m for m in launcher.findall('meta-data') if m.get(A+'name') == 'android.app.shortcuts')
        self.assertEqual(shortcuts.get(A+'resource'), '@xml/flint_shortcuts')
        shortcut = ET.parse(ROOT/'res/xml/flint_shortcuts.xml').getroot().find('shortcut')
        self.assertEqual(shortcut.get(A+'shortcutId'), 'flint-vpn-toggle')
        target = shortcut.find('intent').get(A+'targetClass')
        self.assertEqual(shortcut.find('intent').get(A+'targetPackage'), 'app.flint.vpn')
        activity = next(a for a in app.findall('activity') if a.get(A+'name') == target)
        self.assertEqual(activity.get(A+'exported'), 'false')
        self.assertEqual(activity.get(A+'taskAffinity'), '')
        self.assertEqual(activity.get(A+'theme'), '@style/FlintToggleTheme')
        receiver = next(r for r in app.findall('receiver') if r.get(A+'name').endswith('.FlintHomePinReceiver'))
        self.assertEqual(receiver.get(A+'exported'), 'false')
        setup = ROOT/'src/org/amnezia/vpn/FlintHomeSetupActivity.kt'
        self.assertTrue(setup.is_file())
        strings = ET.parse(ROOT/'res/values/flint_home_strings.xml').getroot()
        self.assertTrue(any(s.get('name') == 'FlintHomeSetupTheme' for s in strings.findall('style')))


if __name__ == '__main__':
    unittest.main()
