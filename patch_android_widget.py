from pathlib import Path
import shutil


def apply(root: Path, assets: Path):
    android = root / "client/android"
    for name in ["FlintWidgetModel.kt", "FlintWidgetProvider.kt", "FlintWidgetActivity.kt",
                 "FlintPinRequest.kt", "FlintHomeSetupActivity.kt", "FlintHomePinReceiver.kt"]:
        shutil.copy2(assets / "widget" / name, android / "src/org/amnezia/vpn" / name)
    for name, folder in [("flint_widget.xml", "layout"), ("flint_widget_info.xml", "xml"),
                         ("flint_widget_background.xml", "drawable"), ("flint_widget_connected.xml", "drawable"),
                         ("flint_widget_waiting.xml", "drawable"), ("flint_widget_power.xml", "drawable"),
                         ("flint_widget_button.xml", "drawable"),
                         ("flint_shortcuts.xml", "xml"), ("flint_home_strings.xml", "values")]:
        (android / "res" / folder).mkdir(exist_ok=True)
        shutil.copy2(assets / "widget" / name, android / "res" / folder / name)
    manifest = android / "AndroidManifest.xml"
    text = manifest.read_text(encoding="utf-8")
    # Widgets/services must remain available when removable storage is unmounted.
    assert 'android:installLocation="auto"' in text
    text = text.replace('android:installLocation="auto"', 'android:installLocation="internalOnly"')
    launcher_anchor = '                android:name="android.app.lib_name"'
    assert launcher_anchor in text
    text = text.replace('            <meta-data\n' + launcher_anchor,
                        '            <meta-data android:name="android.app.shortcuts" android:resource="@xml/flint_shortcuts" />\n\n'
                        '            <meta-data\n' + launcher_anchor)
    assert text.count("</application>") == 1
    text = text.replace("</application>", '''
        <receiver android:name="org.amnezia.vpn.FlintWidgetProvider" android:exported="false"
            android:enabled="true" android:label="Flint VPN" android:icon="@mipmap/flint_icon">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/flint_widget_info" />
        </receiver>
        <activity android:name="org.amnezia.vpn.FlintWidgetActivity" android:exported="false"
            android:excludeFromRecents="true" android:launchMode="singleTop"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
        <activity android:name="org.amnezia.vpn.FlintHomeSetupActivity" android:exported="false"
            android:excludeFromRecents="true" android:theme="@style/FlintHomeSetupTheme" />
        <receiver android:name="org.amnezia.vpn.FlintHomePinReceiver" android:exported="false" />
    </application>''')
    manifest.write_text(text, encoding="utf-8")
    state = android / "src/org/amnezia/vpn/VpnState.kt"
    text = state.read_text(encoding="utf-8")
    assert "dataStore.updateData(f)" in text
    text = text.replace("dataStore.updateData(f)", '''val updated = dataStore.updateData(f)
            // Widget rendering must never enter the storage recovery/delete path.
            runCatching { FlintWidgetProvider.updateAll(app, updated) }
                .onFailure { Log.w(TAG, "Widget update unavailable: ${it.javaClass.simpleName}") }''')
    state.write_text(text, encoding="utf-8")
    activity = android / "src/org/amnezia/vpn/AmneziaActivity.kt"
    text = activity.read_text(encoding="utf-8")
    anchor = '    @Suppress("unused")\n    fun qtAndroidControllerInitialized()'
    assert anchor in text
    text = text.replace(anchor, '''    @Suppress("unused")
    fun requestFlintWidget() {
        runOnUiThread {
            try {
                startActivity(Intent(this, FlintHomeSetupActivity::class.java))
            } catch (_: RuntimeException) {
                Toast.makeText(this, "Удерживайте значок Flint на рабочем столе → Вкл./выкл. VPN", Toast.LENGTH_LONG).show()
            }
        }
    }

''' + anchor)
    activity.write_text(text, encoding="utf-8")
