"""Inspect the final APK rather than inferring native/notice packaging from Gradle configuration."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path


def validate(path: Path, release: bool):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        libraries = [name for name in names if name.startswith('lib/') and name.endswith('.so')]
        abis = {name.split('/')[1] for name in libraries}
        assert abis == {'arm64-v8a', 'x86_64'}, f'Unexpected supported ABIs: {abis}'
        for abi in abis:
            assert f'lib/{abi}/libwordprism_memory.so' in names, f'Missing memory kernel for {abi}'
        for name in libraries:
            data = archive.read(name)
            assert data[:5] == b'\x7fELF\x02', f'Expected ELF64: {name}'
            offset = struct.unpack_from('<Q', data, 32)[0]
            size, count = struct.unpack_from('<HH', data, 54)
            alignments = [struct.unpack_from('<Q', data, offset + i * size + 48)[0] for i in range(count)
                          if struct.unpack_from('<I', data, offset + i * size)[0] == 1]
            assert alignments and all(align >= 16384 for align in alignments), f'16 KiB ELF alignment missing: {name}'
            entry = archive.getinfo(name)
            if entry.compress_type == zipfile.ZIP_STORED:
                # Uncompressed native libraries are mapped directly out of the APK.
                with path.open('rb') as apk:
                    apk.seek(entry.header_offset)
                    header = apk.read(30)
                filename_len, extra_len = struct.unpack_from('<HH', header, 26)
                assert (entry.header_offset + 30 + filename_len + extra_len) % 16384 == 0, f'16 KiB ZIP alignment missing: {name}'
        prefix = 'assets/wordprism-notices/'
        manifest = json.loads(archive.read(prefix + 'manifest.json'))
        assert 'assets/wordprism-notices/Apache-2.0.txt' in names
        for component in manifest:
            for notice in component['notices']:
                assert prefix + notice in names, f'Missing notice: {notice}'
            if 'sourceArchive' in component:
                source = archive.read(prefix + component['sourceArchive'])
                assert hashlib.sha256(source).hexdigest() == component['sourceSha256']
        fonts = [component for component in manifest if component['ecosystem'] == 'font']
        assert len(fonts) == 2 and all(component['selectedLicense'] == 'OFL-1.1' for component in fonts)
        # AAPT2 shortens release resource paths (e.g. res/iu.ttf) without changing the binaries.
        font_hashes = {hashlib.sha256(archive.read(name)).hexdigest() for name in names if name.startswith('res/') and name.endswith('.ttf')}
        assert all(component['fontSha256'] in font_hashes for component in fonts), 'Bundled font hashes do not match notices'
        if release:
            assert not any(b'Lapp/wordprism/mock/' in archive.read(name) for name in names if name.endswith('.dex')), 'Release contains the simulated backend'
        print(f'{path.name}: {len(libraries)} native libraries, both ABIs and 16 KiB alignment, {len(manifest)} notices, fonts/source archives verified' + ('; no mock in release' if release else ''))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--release', action='store_true')
    args = parser.parse_args()
    validate(args.apk, args.release)
