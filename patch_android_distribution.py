"""Produce phone/TV launchers without changing the installed account/package identity."""
from pathlib import Path
import os
import xml.etree.ElementTree as ET

def apply(root: Path):
    variant=os.environ.get('FLINT_VARIANT','universal')
    assert variant in ('phone','tv','universal')
    android=root/'client/android';a='{http://schemas.android.com/apk/res/android}'
    ET.register_namespace('android',a[1:-1])
    manifest=android/'AndroidManifest.xml';tree=ET.parse(manifest);app=tree.getroot().find('application')
    ET.SubElement(app,'meta-data',{a+'name':'app.flint.distribution',a+'value':variant})
    launcher=next(n for n in app.findall('activity') if n.get(a+'name').endswith('.AmneziaActivity'))
    for f in launcher.findall('intent-filter'):
        for c in list(f.findall('category')):
            value=c.get(a+'name')
            if (variant=='phone' and value=='android.intent.category.LEANBACK_LAUNCHER') or (variant=='tv' and value=='android.intent.category.LAUNCHER'):
                f.remove(c)
        if not f.findall('category'):launcher.remove(f)
    if variant=='tv':
        for node in app.findall('receiver'):
            if node.get(a+'name','').endswith('.FlintWidgetProvider'):node.set(a+'enabled','false')
        for node in launcher.findall('meta-data'):
            if node.get(a+'name')=='android.app.shortcuts':launcher.remove(node)
    tree.write(manifest,encoding='utf-8',xml_declaration=True)
    activity=android/'src/org/amnezia/vpn/AmneziaActivity.kt'
    original=activity.read_text(encoding='utf-8')
    if variant!='universal':
        original=original.replace('fun isOnTv(): Boolean = FlintRemoteKeys.isTelevision(',
            'fun isOnTv(): Boolean = '+str(variant=='tv').lower()+' || '+('false && ' if variant=='phone' else '')+'FlintRemoteKeys.isTelevision(')
    activity.write_text(original,encoding='utf-8')
    (android/'src/org/amnezia/vpn/FlintBuild.kt').write_text('''package org.amnezia.vpn
object FlintBuild {
    const val DISTRIBUTION = "%s"
    @JvmStatic fun updateTarget(): String = "&variant=" + DISTRIBUTION + "&abi=" +
        (if (android.os.Process.is64Bit()) "arm64-v8a" else "armeabi-v7a")
}
'''%variant,encoding='utf-8')
