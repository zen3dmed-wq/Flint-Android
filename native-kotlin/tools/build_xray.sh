#!/usr/bin/env bash
# Build the pinned gomobile JNI bindings and core. Does not fetch mutable geo data.
# Requirements: Linux/macOS shell, Python 3, Go 1.26.0, JDK 17, Android NDK+SDK.
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
output_dir="${1:-$project_dir/engine/libs}"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT
export GOTOOLCHAIN=local
test "$(go env GOVERSION)" = "go1.26.0" || { echo 'Go 1.26.0 is required for the pinned engine build.' >&2; exit 1; }
curl --fail --location --retry 3 --output "$work_dir/source.zip" \
  https://github.com/amnezia-vpn/amnezia-libxray/archive/refs/tags/v1.0.3.zip
python3 - "$work_dir" <<'PY'
import hashlib, pathlib, sys, zipfile
root = pathlib.Path(sys.argv[1])
archive = root / 'source.zip'
expected = '3b1194c2a76e73913fdae49983c40a219c45a164ebdae72ef1297469348de730'
assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected, 'Engine source checksum mismatch'
with zipfile.ZipFile(archive) as z:
    for entry in z.infolist():
        target = (root / entry.filename).resolve()
        assert target.is_relative_to(root.resolve())
    z.extractall(root)
PY
cd "$work_dir/amnezia-libxray-1.0.3"
go mod download
go mod verify
mobile_version="$(go list -m -f '{{.Version}}' golang.org/x/mobile)"
export GOBIN="$work_dir/bin"
mkdir -p "$GOBIN"
go install "golang.org/x/mobile/cmd/gomobile@$mobile_version"
go install "golang.org/x/mobile/cmd/gobind@$mobile_version"
export PATH="$GOBIN:$PATH"
mkdir -p "$(go env GOPATH)/pkg/gomobile"
# Russian routing uses expanded bundled JSON. Future geo references require the
# app's pinned assets/geo files; no download of changing databases occurs here.
gomobile bind -target android/arm,android/arm64,android/amd64 -androidapi 30 \
  -javapkg=org.amnezia.vpn.protocol.xray -o libxray.aar \
  -ldflags="-w -s -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384" -trimpath
mkdir -p "$output_dir"
cp libxray.aar "$output_dir/libxray.aar"
python3 - "$output_dir/libxray.aar" <<'PY'
import hashlib, json, pathlib, sys, zipfile
path = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(path) as z:
    names = set(z.namelist())
    assert 'classes.jar' in names
    for abi in ['armeabi-v7a', 'arm64-v8a', 'x86_64']:
        assert f'jni/{abi}/libgojni.so' in names
info = {'sourceVersion': 'amnezia-libxray v1.0.3', 'go': '1.26.0',
        'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
        'abi': ['armeabi-v7a', 'arm64-v8a', 'x86_64'], 'pageSize': 16384,
        'assets': 'No mutable geo database downloads; app bundles its own routing catalog.'}
path.with_suffix('.provenance.json').write_text(json.dumps(info, indent=2) + '\n')
print('Verified AAR SHA256:', info['sha256'])
PY
