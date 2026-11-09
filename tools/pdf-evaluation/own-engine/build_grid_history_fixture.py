"""Build closed table-grid revision controls without overwriting source or Word references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def build(base, output):
    for paper in ['a4', 'letter']:
        with ZipFile(base / paper / 'source.docx') as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        for variant, widths in [('fractional-history', ['819.2', '409.6']), ('different-column-count', ['2400'] * 3)]:
            parts = original.copy()
            document = parts['word/document.xml'].decode()
            history = '<w:tblGridChange w:id="7" w:author="Synthetic {inactive" w:date="2025-01-01T00:00:00Z"><w:tblGrid>'
            history += ''.join(f'<w:gridCol w:w="{width}"/>' for width in widths)
            history += '</w:tblGrid></w:tblGridChange>'
            assert '</w:tblGrid>' in document
            parts['word/document.xml'] = document.replace('</w:tblGrid>', history + '</w:tblGrid>', 1).encode()
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
