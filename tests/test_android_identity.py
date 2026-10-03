"""Check the cross-file contract used to rediscover an already running VPN."""
from pathlib import Path
import hashlib
import re
import sys
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(sys.argv.pop(1)).resolve()
ANDROID = ROOT / "client/android"
ASSETS = Path(__file__).resolve().parents[1] / "flint"
A = "{http://schemas.android.com/apk/res/android}"


class AndroidIdentityTest(unittest.TestCase):
    def setUp(self):
        self.manifest = ET.parse(ANDROID / "AndroidManifest.xml").getroot()
        gradle = (ANDROID / "build.gradle.kts").read_text(encoding="utf-8")
        self.package = re.search(r'applicationId = "([^"]+)"', gradle)[1]

    def test_every_protocol_can_find_its_running_service(self):
        services = {s.get(A + "name").rsplit(".", 1)[-1]: s.get(A + "process")
                    for s in self.manifest.findall("application/service")}
        kotlin = (ANDROID / "src/org/amnezia/vpn/VpnProto.kt").read_text(encoding="utf-8")
        entries = re.findall(r'([A-Z]+)\(\s*"[^"]+",\s*([^,]+),\s*(\w+)::class.java', kotlin)
        self.assertEqual(len(entries), 5)
        for protocol, expression, service in entries:
            with self.subTest(protocol=protocol):
                process = services[service]
                android_process = self.package + process if process.startswith(":") else process
                # Evaluate only the string expression used by VpnProto; no code execution.
                detected_process = expression.strip().replace("BuildConfig.APPLICATION_ID", '"' + self.package + '"')
                self.assertRegex(detected_process, r'^"[^"\n]+"(?:\s*\+\s*"[^"\n]+")?$')
                detected_process = "".join(re.findall(r'"([^"]+)"', detected_process))
                self.assertEqual(detected_process, android_process,
                                 "Reopening the activity would miss the live service")

    def test_launcher_and_round_icon_use_supplied_emblem(self):
        application = self.manifest.find("application")
        for name in ("icon", "roundIcon"):
            self.assertEqual(application.get(A + name), "@mipmap/flint_icon")
        icon = ET.parse(ANDROID / "res/mipmap-anydpi-v26/flint_icon.xml").getroot()
        self.assertEqual(icon.find("foreground/inset").get(A + "drawable"), "@drawable/flint_emblem")
        self.assertEqual((ASSETS / "flint-emblem.jpg").read_bytes(),
                         (ANDROID / "res/drawable-nodpi/flint_emblem.jpg").read_bytes())

    def test_notification_and_quick_settings_use_alpha_husky(self):
        kotlin = (ANDROID / "src/org/amnezia/vpn/ServiceNotification.kt").read_text(encoding="utf-8")
        self.assertIn('.setSmallIcon(R.drawable.ic_flint_notification)', kotlin)
        self.assertNotIn('R.drawable.ic_amnezia_round', kotlin)
        tile = next(s for s in self.manifest.findall("application/service")
                    if s.get(A + "name").endswith("AmneziaTileService"))
        self.assertEqual(tile.get(A + "icon"), "@drawable/ic_flint_notification")
        vector = ET.parse(ANDROID / "res/drawable/ic_flint_notification.xml").getroot()
        self.assertTrue(vector.findall("path"))
        for path in vector.findall("path"):
            self.assertEqual(path.get(A + "fillColor"), "#FFFFFFFF")

    def test_java_components_keep_their_real_namespace(self):
        application = self.manifest.find("application")
        self.assertEqual(application.get(A + "name"), "org.amnezia.vpn.AmneziaApplication")
        self.assertTrue(any(a.get(A + "name") == "org.amnezia.vpn.AmneziaActivity"
                            for a in application.findall("activity")))


if __name__ == "__main__":
    unittest.main()
