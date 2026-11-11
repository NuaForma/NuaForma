"""Build legacy table-width controls without creating or replacing Word references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import xml.etree.ElementTree as ET

WORD = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
ET.register_namespace('w', WORD)


def q(name):
    return '{' + WORD + '}' + name


def build(resources, output):
    for paper in ['a4', 'letter']:
        with ZipFile(resources / 'signed-table-indent' / '12-truncate' / paper / 'source.docx') as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        for kind in ['autofit-physical', 'percent-50', 'percent-100', 'fit-contents']:
            for variant, indent in [('negative', '-100'), ('zero', '0')]:
                parts = original.copy()
                document = ET.fromstring(parts['word/document.xml'])
                table = document.find(q('body')).find(q('tbl'))
                properties = table.find(q('tblPr'))
                properties.find(q('tblInd')).set(q('w'), indent)
                properties.find(q('tblLayout')).set(q('type'), 'autofit')
                if kind in ['percent-50', 'percent-100', 'fit-contents']:
                    width = properties.find(q('tblW'))
                    width.set(q('type'), 'auto' if kind == 'fit-contents' else 'pct')
                    width.set(q('w'), '0' if kind == 'fit-contents' else '2500' if kind == 'percent-50' else '5000')
                    for cell_width in table.iter(q('tcW')):
                        cell_width.set(q('type'), 'auto')
                        cell_width.set(q('w'), '0')
                parts['word/document.xml'] = ET.tostring(document, encoding='utf-8')
                folder = output / ('12-' + kind + '-' + variant) / paper
                folder.mkdir(parents=True, exist_ok=False)
                with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
                    for name, data in parts.items():
                        entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                        entry.create_system = 0
                        entry.compress_type = ZIP_DEFLATED
                        archive.writestr(entry, data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.resources, args.output)
