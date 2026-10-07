"""Prune unused Qt deployment modules before Gradle packaging.

Flint sets Basic explicitly. Keep every VPN engine, camera/QR component, file
dialog, route database and both ARM ABIs. Refuse to remove a dependency of any
retained ELF. Update Qt's Java loader lists in the same operation.
"""
from pathlib import Path
import json,re,sys,xml.etree.ElementTree as ET

STYLES='(?:Fusion|Imagine|Material|Universal|FluentWinUI3)'
PATTERNS=[
    re.compile(r'^libQt6QuickControls2'+STYLES+r'(?:StyleImpl)?_'),
    re.compile(r'^libqml_QtQuick_Controls_'+STYLES+r'_'),
    re.compile(r'^libQt6(?:Widgets|LabsPlatform|QuickTest|Test)_'),
    re.compile(r'^libqml_Qt_labs_platform_'),
    re.compile(r'^libqml_QtTest_'),
]

def excluded(name):
    return any(p.match(name) for p in PATTERNS)

def validate_dependencies(dependencies):
    removed={n for n in dependencies if excluded(n)}
    for name,needed in dependencies.items():
        if name not in removed and removed.intersection(needed):
            raise ValueError('Required dependency would be removed: '+name+' -> '+str(sorted(removed.intersection(needed))))
    return removed

def filter_loader(tree):
    removed=[]
    for arr in tree.getroot().findall('array'):
        if arr.get('name') not in ('qt_libs','bundled_libs','load_local_libs'):continue
        for item in list(arr.findall('item')):
            value=item.text or ''
            if ';' not in value:continue
            abi,names=value.split(';',1)
            retained=[]
            for token in names.split(':'):
                name=Path(token).name
                if not name.startswith('lib'):name='lib'+name
                if not name.endswith('.so'):name+='.so'
                if excluded(name):removed.append(value)
                else:retained.append(token)
            if retained:item.text=abi+';'+':'.join(retained)
            else:arr.remove(item)
    return removed

def optimize(root):
    from elftools.elf.elffile import ELFFile
    root=Path(root).resolve()
    manifest=root/'AndroidManifest.xml'
    assert manifest.is_file() and 'app.flint.distribution' in manifest.read_text(encoding='utf-8')
    libs=root/'libs'; xml=root/'res/values/libs.xml'
    assert libs.is_dir() and xml.is_file(), 'Qt deployment must run first'
    plans=[]
    for folder in sorted(libs.iterdir()):
        if not folder.is_dir():continue
        dependencies={}
        for p in folder.glob('*.so'):
            with p.open('rb') as f:
                dynamic=ELFFile(f).get_section_by_name('.dynamic')
                dependencies[p.name]=[t.needed for t in dynamic.iter_tags('DT_NEEDED')] if dynamic else []
        removed=validate_dependencies(dependencies)
        plans.extend(folder/name for name in sorted(removed))
    tree=ET.parse(xml)
    loader_removed=filter_loader(tree)
    records=[]
    for p in plans:
        assert p.resolve().is_relative_to(libs.resolve()) and p.is_file()
        records.append({'file':p.relative_to(root).as_posix(),'uncompressedBytes':p.stat().st_size})
    # All dependency checks happen before any mutation. Only exact known files
    # in the generated deployment directory can be removed, never an SDK tree.
    for p in plans:p.unlink()
    tree.write(xml,encoding='utf-8',xml_declaration=True)
    report=root/'flint-size-report.json'
    previous=json.loads(report.read_text()) if report.exists() else {'removed':[]}
    merged={r['file']:r for r in previous['removed']+records}
    data={'removed':list(merged.values()),'uncompressedBytesRemoved':sum(r['uncompressedBytes'] for r in merged.values()),
          'loaderEntriesRemovedThisRun':len(loader_removed),'dependencyClosureVerified':True,
          'preserved':['ARM32','ARM64','VPN protocols','QR camera','QR image import','Qt Basic','Qt Quick Dialogs','geo databases']}
    report.write_text(json.dumps(data,indent=2),encoding='utf-8')
    print('Flint size optimization:',len(records),'unused Qt libraries removed')

if __name__=='__main__':optimize(sys.argv[1])
