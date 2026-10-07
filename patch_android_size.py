"""Reduce deployed dependencies without removing VPN protocols or QR features."""
from pathlib import Path
import shutil

def apply(root: Path):
    here=Path(__file__).resolve().parent
    # These two imports only supplied StandardPaths. QtCore provides it without
    # pulling in the desktop Widgets library on Android.
    for name in ('PageProtocolXraySnapshots.qml','PageSettingsApiSubscriptionKey.qml'):
        p=root/'client/ui/qml/Pages2'/name
        text=p.read_text(encoding='utf-8')
        assert 'import Qt.labs.platform 1.1' in text
        text=text.replace('import Qt.labs.platform 1.1', '' if 'import QtCore' in text else 'import QtCore')
        p.write_text(text,encoding='utf-8')
    qml=root/'client/ui/qml'
    forbidden=('Qt.labs.platform','QtTest','QtQuick.Controls.Material','QtQuick.Controls.Fusion',
               'QtQuick.Controls.Imagine','QtQuick.Controls.Universal','QtQuick.Controls.FluentWinUI3')
    for p in qml.rglob('*.qml'):
        assert not any('import '+m in p.read_text(encoding='utf-8') for m in forbidden),p
    assert 'QQuickStyle::setStyle("Basic")' in (root/'client/amneziaApplication.cpp').read_text(encoding='utf-8')
    android=root/'client/android'
    shutil.copy2(here/'tools/optimize_android_deployment.py',android/'flint_optimize_deployment.py')
    shutil.copy2(here/'flint/flint-release.pro',android/'flint-release.pro')
    gradle=android/'build.gradle.kts'
    text=gradle.read_text(encoding='utf-8')
    anchor='        release {\n'
    assert text.count(anchor)==1
    text=text.replace(anchor,anchor+'''            isMinifyEnabled = true
            // Qt accesses named resources dynamically; retain those resources.
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "flint-release.pro")
''',1)
    text+='''
// androiddeployqt has generated libs.xml before Gradle starts. Prune the native
// files and their loader entries together, before resource/JNI merging.
tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        val optimization = providers.exec {
            commandLine("python3", file("flint_optimize_deployment.py").absolutePath, projectDir.absolutePath)
            isIgnoreExitValue = true
        }
        logger.lifecycle(optimization.standardOutput.asText.get())
        logger.lifecycle(optimization.standardError.asText.get())
        optimization.result.get().assertNormalExitValue()
    }
}
'''
    gradle.write_text(text,encoding='utf-8')
