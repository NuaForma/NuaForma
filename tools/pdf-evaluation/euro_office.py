"""Isolated, direct x2t research on trusted synthetic fixtures, not production.

Uses an immutable official image, no editor/server startup, no network, no host
repository mounts and no privileged container. Input copies are read-only.
"""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

from prepare_copies import FIXTURES

CONVERTER = '/var/www/euro-office/documentserver/server/FileConverter/bin/x2t'
PACKAGE_SHA256 = '2c14ce8eabcf58bd4d6e56ca7304d565de48e6867f4bbd31ef41a9bacfcbaa13'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def owned_run(docker, image, arguments, mounts, deadline=120):
    identity = str(uuid.uuid4())
    name = 'nuaforma-euro-qa-' + identity
    command = [str(docker), 'run', '--rm', '--pull', 'never', '--name', name,
               '--label', 'dev.nuaforma.qa=' + identity, '--network', 'none',
               '--read-only', '--user', '1000:1000', '--cap-drop', 'ALL',
               '--security-opt', 'no-new-privileges', '--pids-limit', '128',
               '--memory', '2g', '--cpus', '2',
               '--tmpfs', '/tmp:rw,nosuid,nodev,size=512m',
               '--env', 'HOME=/tmp', '--workdir', str(pathlib.PurePosixPath(CONVERTER).parent)]
    for source, destination, readonly in mounts:
        if isinstance(source, str):
            command += ['--mount', f'type=volume,source={source},target={destination},volume-subpath=var/www/euro-office,readonly']
        else:
            command += ['--mount', f'type=bind,source={source.resolve()},target={destination}' + (',readonly' if readonly else '')]
    command += ['--entrypoint', CONVERTER, image, *arguments]
    try:
        return subprocess.run(command, capture_output=True, text=True, timeout=deadline)
    except subprocess.TimeoutExpired:
        label = subprocess.run([str(docker), 'inspect', '--format', '{{index .Config.Labels "dev.nuaforma.qa"}}', name],
                               capture_output=True, text=True, timeout=10)
        if label.returncode == 0 and label.stdout.strip() == identity:
            subprocess.run([str(docker), 'rm', '--force', name], capture_output=True, timeout=10, check=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('repository', type=pathlib.Path)
    parser.add_argument('destination', type=pathlib.Path)
    parser.add_argument('--docker', type=pathlib.Path, required=True)
    parser.add_argument('--poppler', type=pathlib.Path, required=True)
    parser.add_argument('--image', required=True)
    parser.add_argument('--font-directory', type=pathlib.Path, required=True)
    parser.add_argument('--conversion-copies', type=pathlib.Path, help='Compare the already measured native-geometry research copies')
    parser.add_argument('--package-volume', help='Read-only Docker volume containing a checksum-verified extracted official package')
    parser.add_argument('--fixtures', nargs='+', choices=tuple(FIXTURES), default=list(FIXTURES))
    args = parser.parse_args()
    if not re.fullmatch(r'ghcr\.io/euro-office/documentserver@sha256:[0-9a-f]{64}', args.image):
        parser.error('Use an immutable official image digest')
    if args.package_volume:
        label = subprocess.run([str(args.docker), 'volume', 'inspect', '--format',
                                '{{index .Labels "dev.nuaforma.package.sha256"}}', args.package_volume],
                               capture_output=True, text=True, check=True, timeout=10)
        if label.stdout.strip() != PACKAGE_SHA256:
            parser.error('Package volume must belong to the checksum-verified 9.3.5 research extraction')
    args.destination.mkdir(parents=True, exist_ok=False)
    cache = args.destination / 'font-cache'
    cache.mkdir()
    package_mounts = []
    if args.package_volume:
        package_mounts = [(args.package_volume, '/var/www/euro-office', True)]
    setup = owned_run(args.docker, args.image, ['-create-allfonts', '/cache', '/fonts'],
                      [(cache, '/cache', False), (args.font_directory, '/fonts', True)] + package_mounts)
    (args.destination / 'font-cache.log').write_text(setup.stdout + setup.stderr, encoding='utf-8')
    if setup.returncode:
        raise RuntimeError(f'Font cache initialization failed: {setup.returncode}')
    records = []
    for name in args.fixtures:
        original = args.repository / 'nuaforma-docx/build' / (FIXTURES[name] + '.docx')
        original_hash = digest(original)
        source = args.conversion_copies / (name + '.docx') if args.conversion_copies else original
        before = digest(source)
        inputs = args.destination / 'inputs' / name
        inputs.mkdir(parents=True)
        copied = inputs / 'input.docx'
        shutil.copy2(source, copied)
        output = args.destination / 'eurooffice' / name
        output.mkdir(parents=True)
        fields = dict(m_sFileFrom='/input/input.docx', m_sFileTo=f'/work/{name}.pdf',
                      m_nFormatTo='513', m_sFontDir='/cache', m_sAllFontsPath='/cache/AllFonts.js',
                      m_bIsNoBase64='true', m_bDontSaveAdditional='true')
        root = ET.Element('TaskQueueDataConvert')
        for key, value in fields.items():
            ET.SubElement(root, key).text = value
        ET.ElementTree(root).write(output / 'params.xml', encoding='utf-8', xml_declaration=True)
        started = time.monotonic()
        result = owned_run(args.docker, args.image, ['/work/params.xml'],
                           [(inputs, '/input', True), (output, '/work', False),
                            (cache, '/cache', True), (args.font_directory, '/fonts', True)] + package_mounts)
        elapsed = time.monotonic() - started
        (output / 'conversion.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        assert digest(source) == before and digest(copied) == before and digest(original) == original_hash, name
        pdf = output / f'{name}.pdf'
        record = dict(fixture=name, source=str(source), sourceSha256=before, image=args.image,
                      originalTemplate=str(original), originalTemplateSha256=original_hash,
                      packageVolume=args.package_volume,
                      packageSha256=PACKAGE_SHA256 if args.package_volume else None,
                      exitCode=result.returncode, pdf=pdf.is_file(), seconds=round(elapsed, 3))
        records.append(record)
        (args.destination / 'results.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
        print(json.dumps(record), flush=True)
        if result.returncode or not pdf.is_file():
            raise RuntimeError(f'Conversion failed for {name}; retain the diagnostic log')
        renderer = args.poppler / 'pdftoppm.exe'
        if not renderer.is_file():
            renderer = args.poppler / 'pdftoppm'
        subprocess.run([str(renderer), '-r', '100', '-png', str(pdf), str(output / 'page')],
                       capture_output=True, check=True, timeout=60)


if __name__ == '__main__':
    main()
