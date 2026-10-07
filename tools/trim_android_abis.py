"""Remove dependency-only ABIs from the unsigned universal APK before signing.

The Android engine uses compressed JNI libraries (useLegacyPackaging=true).
Reject a different packaging mode rather than silently losing mmap alignment.
"""
from pathlib import Path
import argparse,copy,struct,zipfile

def trim(source,target,abis=None):
    source,target=Path(source),Path(target)
    assert source.resolve()!=target.resolve(), 'Input and output must differ'
    keep=set(abis or ['arm64-v8a','armeabi-v7a'])
    assert keep and keep <= {'arm64-v8a','armeabi-v7a'}
    with zipfile.ZipFile(source) as archive:
        names=archive.namelist()
        for abi in keep:
            assert any(n.startswith('lib/'+abi+'/libAmneziaVPN') and n.endswith('.so') for n in names), 'Missing application library for '+abi
        assert all(n.compress_type!=zipfile.ZIP_STORED for n in archive.infolist() if n.filename.endswith('.so')), 'Use Android zipalign for uncompressed libraries'
        with zipfile.ZipFile(target,'w') as output:
            for info in archive.infolist():
                parts=info.filename.split('/')
                if len(parts)>2 and parts[0]=='lib' and parts[1] not in keep:continue
                copied=copy.copy(info)
                if copied.compress_type==zipfile.ZIP_STORED:
                    offset=output.fp.tell()+30+len(copied.filename.encode('utf-8'))+len(copied.extra)
                    padding=(-offset)%4
                    if padding:copied.extra+=struct.pack('<HH',0xffff,padding)+bytes(padding)
                output.writestr(copied,archive.read(info.filename))
    with zipfile.ZipFile(target) as result:
        assert result.testzip() is None
        assert {n.split('/')[1] for n in result.namelist() if n.startswith('lib/') and n.endswith('.so')}==keep

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('source');parser.add_argument('target');parser.add_argument('--abi',action='append',choices=['arm64-v8a','armeabi-v7a'])
    args=parser.parse_args();trim(args.source,args.target,args.abi)
