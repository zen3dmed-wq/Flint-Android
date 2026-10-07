from pathlib import Path
import shutil


def apply(root: Path, assets: Path):
    android = root / "client/android"
    for name in ["FlintWidgetModel.kt", "FlintWidgetProvider.kt", "FlintWidgetActivity.kt"]:
        shutil.copy2(assets / "widget" / name, android / "src/org/amnezia/vpn" / name)
    for name, folder in [("flint_widget.xml", "layout"), ("flint_widget_info.xml", "xml"),
                         ("flint_widget_background.xml", "drawable"), ("flint_widget_connected.xml", "drawable"),
                         ("flint_widget_waiting.xml", "drawable"), ("flint_widget_power.xml", "drawable"),
                         ("flint_widget_button.xml", "drawable")]:
        (android / "res" / folder).mkdir(exist_ok=True)
        shutil.copy2(assets / "widget" / name, android / "res" / folder / name)
    manifest = android / "AndroidManifest.xml"
    text = manifest.read_text(encoding="utf-8")
    assert text.count("</application>") == 1
    text = text.replace("</application>", '''
        <receiver android:name="org.amnezia.vpn.FlintWidgetProvider" android:exported="false" android:label="Flint VPN">
            <intent-filter><action android:name="android.appwidget.action.APPWIDGET_UPDATE" /></intent-filter>
            <meta-data android:name="android.appwidget.provider" android:resource="@xml/flint_widget_info" />
        </receiver>
        <activity android:name="org.amnezia.vpn.FlintWidgetActivity" android:exported="false"
            android:excludeFromRecents="true" android:launchMode="singleTop"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
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
            val manager = android.appwidget.AppWidgetManager.getInstance(this)
            if (manager.isRequestPinAppWidgetSupported) {
                manager.requestPinAppWidget(ComponentName(this, FlintWidgetProvider::class.java), null, null)
            } else {
                Toast.makeText(this, "На домашнем экране удерживайте пустое место → Виджеты → Flint VPN", Toast.LENGTH_LONG).show()
            }
        }
    }

''' + anchor)
    activity.write_text(text, encoding="utf-8")
