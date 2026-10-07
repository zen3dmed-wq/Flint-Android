"""Verify the packaged dependency closure and record actual footprint savings."""
from pathlib import Path
import io,json,sys,zipfile
from elftools.elf.elffile import ELFFile
from optimize_android_deployment import excluded

def verify(apk):
    apk=Path(apk)
    report={'file':apk.name,'apkBytes':apk.stat().st_size,'abis':{},'dexBytes':0}
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None
        assert set(n.split('/')[1] for n in z.namelist() if n.startswith('lib/') and n.endswith('.so'))=={'arm64-v8a','armeabi-v7a'}
        for abi in ('arm64-v8a','armeabi-v7a'):
            entries=[i for i in z.infolist() if i.filename.startswith('lib/'+abi+'/') and i.filename.endswith('.so')]
            names={Path(i.filename).name for i in entries}
            assert not any(excluded(n) for n in names),'unused Qt module remains'
            for required in ('libgojni.so','libwg-go.so','libck-ovpn-plugin.so','libovpn3.so','libbarhopper_v3.so',
                             f'libQt6QuickControls2Basic_{abi}.so',f'libQt6QuickDialogs2_{abi}.so'):
                assert required in names,required
            for entry in entries:
                d=ELFFile(io.BytesIO(z.read(entry.filename))).get_section_by_name('.dynamic')
                for t in d.iter_tags('DT_NEEDED') if d else []:
                    assert not excluded(t.needed),'removed library still needed: '+t.needed
                    if t.needed.startswith(('libQt6','libqml_','libGamepad')):assert t.needed in names,t.needed
            report['abis'][abi]={'libraries':len(entries),'packedBytes':sum(i.compress_size for i in entries),'installedNativeBytes':sum(i.file_size for i in entries)}
        report['dexBytes']=sum(i.file_size for i in z.infolist() if i.filename.endswith('.dex'))
        assert 'assets/geo/geoip.dat' in z.namelist() and 'assets/geo/geosite.dat' in z.namelist()
    assert report['apkBytes']<122000000,'No meaningful reduction relative to 134 MB baseline'
    report['baselineApkBytes']=134296433
    report['savedApkBytes']=report['baselineApkBytes']-report['apkBytes']
    report['savedPercent']=round(100*report['savedApkBytes']/report['baselineApkBytes'],1)
    report['nativeDependencyClosureVerified']=True
    return report

if __name__=='__main__':
    report=verify(sys.argv[1])
    if len(sys.argv)>2:Path(sys.argv[2]).write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report,indent=2))
