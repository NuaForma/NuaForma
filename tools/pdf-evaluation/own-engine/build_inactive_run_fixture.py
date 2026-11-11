"""Create explicit inactive run controls without replacing immutable references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def build(base, output):
    for paper in ['a4', 'letter']:
        with ZipFile(base / paper / 'source.docx') as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        for variant, flag in [('zero', '0'), ('false', 'false'), ('off', 'off')]:
            parts = original.copy()
            document = parts['word/document.xml'].decode()
            assert '<w:rPr>' in document
            document = document.replace('<w:rPr>', '<w:rPr><w:dstrike w:val="' + flag + '"/><w:w w:val="100"/>', 1)
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
