"""Build distributable notices from pinned Cargo sources and resolved Maven artifacts.

Inputs may contain machine paths; output contains only coordinates, source URLs and license text.
No third-party source is modified. All Cargo.lock packages are included (a conservative superset).
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cargo-metadata', type=Path, required=True)
    parser.add_argument('--runtime-list', type=Path, action='append', required=True)
    parser.add_argument('--gradle-cache', type=Path, action='append', required=True)
    parser.add_argument('--font-directory', type=Path, required=True)
    parser.add_argument('--font-license-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.output / 'wordprism-notices'
    root.mkdir(parents=True, exist_ok=True)
    records = []
    errors = []
    packages = json.loads(args.cargo_metadata.read_text(encoding='utf-8-sig'))['packages']
    for package in sorted(packages, key=lambda p: (p['name'], p['version'])):
        if package['name'] == 'wordprism-memory':
            continue
        source = Path(package['manifest_path']).parent
        coordinate = f"{package['name']}-{package['version']}"
        expression = package['license']
        chosen = 'MPL-2.0' if package['name'] == 'priority-queue' else ('MIT AND Unicode-3.0' if package['name'] == 'unicode-ident' else 'MIT' if 'MIT' in expression else expression)
        files = [p for p in source.iterdir() if p.is_file() and re.match(r'(licen[cs]e|copying|copyright|notice|MPL-2|AUTHORS)', p.name, re.I) and not p.name.startswith('LGPL')]
        texts = []
        target = root / 'rust' / coordinate
        target.mkdir(parents=True, exist_ok=True)
        if not files and package['name'] == 'jni-sys-macros':
            # This crate omits workspace licenses; use its published VCS commit, never a moving branch.
            commit = json.loads((source / '.cargo_vcs_info.json').read_text())['git']['sha1']
            url = f'https://raw.githubusercontent.com/jni-rs/jni-sys/{commit}/LICENSE-MIT'
            destination = target / 'LICENSE-MIT'
            if not destination.is_file():
                with urllib.request.urlopen(url, timeout=30) as response:
                    destination.write_bytes(response.read(64_000))
            if b'Permission is hereby granted' not in destination.read_bytes():
                errors.append(f'Unexpected license text: {url}')
            texts.append(str(destination.relative_to(root)).replace('\\', '/'))
        elif not files:
            errors.append(f'No license file: {coordinate}')
        for path in files:
            shutil.copyfile(path, target / path.name)
            texts.append(str((target / path.name).relative_to(root)).replace('\\', '/'))
        record = dict(component=coordinate, ecosystem='cargo', declaredLicense=expression, selectedLicense=chosen,
                      source=f"https://crates.io/api/v1/crates/{package['name']}/{package['version']}/download", notices=texts)
        if package['name'] == 'priority-queue':
            # MPL 2.0 executable distribution includes the exact unmodified covered source.
            crate = source.parent.parent.parent / 'cache' / source.parent.name / f'{coordinate}.crate'
            if not crate.is_file():
                errors.append(f'No MPL source archive: {coordinate}')
            else:
                destination = target / f'{coordinate}.crate'
                shutil.copyfile(crate, destination)
                record['sourceArchive'] = str(destination.relative_to(root)).replace('\\', '/')
                record['sourceSha256'] = hashlib.sha256(crate.read_bytes()).hexdigest()
        records.append(record)

    def artifact_paths(group, module, version, extension):
        return [p for cache in args.gradle_cache for p in (cache / group / module / version).glob(f'*/*.{extension}')]

    def pom_licenses(group, module, version, seen=None):
        seen = set() if seen is None else seen
        coordinate = (group, module, version)
        if coordinate in seen:
            return []
        seen.add(coordinate)
        poms = artifact_paths(*coordinate, 'pom')
        if not poms:
            return []
        doc = ET.fromstring(poms[0].read_bytes())
        licenses = [dict(name=x.findtext('{*}name', ''), url=x.findtext('{*}url', '')) for x in doc.findall('{*}licenses/{*}license')]
        if licenses:
            return licenses
        parent = doc.find('{*}parent')
        if parent is not None:
            return pom_licenses(parent.findtext('{*}groupId'), parent.findtext('{*}artifactId'), parent.findtext('{*}version'), seen)
        return []

    modules = sorted(set(tuple(line.split('\t')) for path in args.runtime_list for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()))
    for group, module, version in modules:
        licenses = pom_licenses(group, module, version)
        if not licenses:
            errors.append(f'No POM license: {group}:{module}:{version}')
        coordinate = f'{group}-{module}-{version}'
        notices = []
        binaries = artifact_paths(group, module, version, 'jar') + artifact_paths(group, module, version, 'aar')
        for binary in binaries:
            if binary.name.endswith('-sources.jar'):
                continue
            with zipfile.ZipFile(binary) as archive:
                archives = [(binary.name, archive)]
                if 'classes.jar' in archive.namelist():
                    archives.append(('classes.jar', zipfile.ZipFile(io.BytesIO(archive.read('classes.jar')))))
                for _, content in archives:
                    for name in content.namelist():
                        if name.endswith('/') or not re.search(r'(^|/)(LICENSE|NOTICE|COPYRIGHT)([._-]|$)', name, re.I):
                            continue
                        data = content.read(name)
                        if len(data) > 200_000:
                            continue
                        target = root / 'maven' / coordinate / name.replace('/', '_')
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_bytes(data)
                        notices.append(str(target.relative_to(root)).replace('\\', '/'))
        records.append(dict(component=f'{group}:{module}:{version}', ecosystem='maven', licenses=licenses,
                            source=f"https://{'dl.google.com/dl/android/maven2' if group.startswith('androidx.') else 'repo.maven.apache.org/maven2'}/{group.replace('.', '/')}/{module}/{version}/",
                            notices=sorted(set(notices))))
    apache = next(Path(p['manifest_path']).parent / 'LICENSE-APACHE' for p in packages if (Path(p['manifest_path']).parent / 'LICENSE-APACHE').is_file())
    shutil.copyfile(apache, root / 'Apache-2.0.txt')
    for filename, family, repository, license_name in [
        ('atkinson_next.ttf', 'Atkinson Hyperlegible Next', 'atkinson-hyperlegible-next', 'AtkinsonHyperlegible-OFL.txt'),
        ('atkinson_mono.ttf', 'Atkinson Hyperlegible Mono', 'atkinson-hyperlegible-next-mono', 'AtkinsonHyperlegibleMono-OFL.txt'),
    ]:
        font = args.font_directory / filename
        license_file = args.font_license_directory / license_name
        # Both shipped binaries declare Version 2.001 and OFL 1.1 in their TrueType name table.
        if 'Version 2.001'.encode('utf-16-be') not in font.read_bytes():
            errors.append(f'Recheck updated font source/version: {filename}')
        destination = root / 'fonts' / license_name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(license_file, destination)
        records.append(dict(component=f'{family} 2.001', ecosystem='font', selectedLicense='OFL-1.1',
                            source=f'https://github.com/googlefonts/{repository}',
                            fontSha256=hashlib.sha256(font.read_bytes()).hexdigest(),
                            notices=[str(destination.relative_to(root)).replace('\\', '/')]))
    (root / 'manifest.json').write_text(json.dumps(records, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'Wrote {len(records)} component notices to {root}')


if __name__ == '__main__':
    main()
