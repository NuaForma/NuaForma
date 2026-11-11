"""Build width spelling variants of the retained preferred-width controls."""
import argparse
from decimal import Decimal
from pathlib import Path
import re
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def build(base, output):
    for paper in ['a4', 'letter']:
        with ZipFile(base / paper / 'source.docx') as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        for variant in ['percent', 'decimal', 'fraction']:
            parts = original.copy()
            document = parts['word/document.xml'].decode()
            if variant == 'percent':
                document, count = re.subn(r'w:w="([0-9]+)" w:type="pct"',
                    lambda match: 'w:w="' + str(Decimal(match[1]) / 50) + '%" w:type="pct"', document)
                assert count > 0
            else:
                value = '6400.0' if variant == 'decimal' else '6400.8'
                before = '<w:tblW w:w="6400" w:type="dxa"/>'
                assert document.count(before) >= 1
                document = document.replace(before, '<w:tblW w:w="' + value + '" w:type="dxa"/>', 1)
            parts['word/document.xml'] = document.encode()
            folder = output / variant / paper
            folder.mkdir(parents=True, exist_ok=False)
            with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
                for name, data in parts.items():
                    entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                    entry.create_system = 0
                    entry.compress_type = ZIP_DEFLATED
                    archive.writestr(entry, data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.base, args.output)
