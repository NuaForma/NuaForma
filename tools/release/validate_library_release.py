"""Bind new library publication to signed source, complete artifact review and both image reviews."""
import argparse
import json
import pathlib
import re
import subprocess
import sys
from validate_multiarch_release import check as check_image
from prepare_central_bundle import release_version
from verify_libraries import MODULES


def check(record, companion, version, commit):
    if record.get('application') != 'server' or companion.get('application') != 'playground':
        raise ValueError('Separate server and Playground reviews are required')
    check_image(record, version, commit)
    check_image(companion, version, commit)
    if record.get('approved', {}).get('libraryAudit') is not True:
        raise ValueError('Library audit is not approved')
    expected = {module + '-' + version + suffix for module in MODULES
                for suffix in ('.pom', '.jar', '-sources.jar', '-javadoc.jar')}
    hashes = record.get('libraryHashes', {})
    if set(hashes) != expected or any(not re.fullmatch(r'[a-f0-9]{64}', value) for value in hashes.values()):
        raise ValueError('Every candidate POM, binary, source and documentation artifact must be reviewed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--review', type=pathlib.Path, required=True)
    parser.add_argument('--companion-review', type=pathlib.Path, required=True)
    args = parser.parse_args()
    release_version(args.version)
    record = json.loads(args.review.read_text())
    companion = json.loads(args.companion_review.read_text())
    check(record, companion, args.version, record['sourceCommit'])
    subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name('validate_multiarch_release.py')),
                    '--version', args.version, '--application', 'server', '--review', str(args.review),
                    '--companion-review', str(args.companion_review)], check=True)
    print('Exact library artifacts, signed source and both application/platform reviews: PASS')


if __name__ == '__main__':
    main()
