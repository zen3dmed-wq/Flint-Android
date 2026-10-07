"""Guard pruning against lost native dependencies and mixed Qt loader entries."""
from pathlib import Path
import sys,unittest,xml.etree.ElementTree as ET
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from optimize_android_deployment import excluded,validate_dependencies,filter_loader

class SizeTests(unittest.TestCase):
    def test_keeps_runtime_features(self):
        for name in ('libgojni.so','libwg-go.so','libck-ovpn-plugin.so','libbarhopper_v3.so',
                     'libQt6QuickControls2Basic_arm64-v8a.so','libQt6QuickDialogs2_armeabi-v7a.so',
                     'libQt6QuickTemplates2_arm64-v8a.so','libQt6Core_arm64-v8a.so'):
            self.assertFalse(excluded(name),name)
    def test_refuses_removing_a_required_library(self):
        with self.assertRaises(ValueError):
            validate_dependencies({'libApp.so':['libQt6Widgets_arm64-v8a.so'],'libQt6Widgets_arm64-v8a.so':[]})
    def test_prunes_only_unreferenced_set(self):
        graph={'libApp.so':['libQt6Core_arm64-v8a.so'],'libQt6Core_arm64-v8a.so':[],
               'libQt6Widgets_arm64-v8a.so':[],'libQt6LabsPlatform_arm64-v8a.so':['libQt6Widgets_arm64-v8a.so']}
        self.assertEqual(validate_dependencies(graph),{'libQt6Widgets_arm64-v8a.so','libQt6LabsPlatform_arm64-v8a.so'})
    def test_mixed_loader_list_preserves_platform_plugin(self):
        tree=ET.ElementTree(ET.fromstring('''<resources><array name="load_local_libs"><item>arm64-v8a;libQt6QuickControls2Fusion_arm64-v8a.so:libplugins_platforms_qtforandroid_arm64-v8a.so</item></array><array name="qt_libs"><item>armeabi-v7a;Qt6Widgets_armeabi-v7a</item><item>armeabi-v7a;Qt6Core_armeabi-v7a</item></array><array name="unrelated"><item>arm64-v8a;Qt6Widgets_arm64-v8a</item></array></resources>'''))
        self.assertEqual(len(filter_loader(tree)),2)
        self.assertEqual(tree.find("array[@name='load_local_libs']/item").text,'arm64-v8a;libplugins_platforms_qtforandroid_arm64-v8a.so')
        self.assertEqual([i.text for i in tree.findall("array[@name='qt_libs']/item")],['armeabi-v7a;Qt6Core_armeabi-v7a'])
        self.assertEqual(len(tree.findall("array[@name='unrelated']/item")),1)
        self.assertEqual(filter_loader(tree),[])

if __name__=='__main__':unittest.main()
