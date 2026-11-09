"""Build contained signed nested-table controls without replacing Word references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import xml.etree.ElementTree as ET

WORD = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
ET.register_namespace('w', WORD)


def q(name):
    return '{' + WORD + '}' + name


def paragraph(text):
    return ET.fromstring(f'<w:p xmlns:w="{WORD}"><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr><w:t>{text}</w:t></w:r></w:p>')


def build(resources, output):
    for mode in ['12', '15']:
        for paper in ['a4', 'letter']:
            with ZipFile(resources / 'signed-table-indent' / (mode + '-truncate') / paper / 'source.docx') as archive:
                original = {name: archive.read(name) for name in archive.namelist()}
            for kind, indent in [('negative', '-100'), ('zero', '0'), ('positive', '100')]:
                parts = original.copy()
                document = ET.fromstring(parts['word/document.xml'])
                body = document.find(q('body'))
                table = body.find(q('tbl'))
                table.find(q('tblPr') + '/' + q('tblInd')).set(q('w'), indent)
                position = list(body).index(table)
                body.remove(table)
                margins = ''.join(f'<w:{side} w:w="120" w:type="dxa"/>' for side in ['top', 'left', 'bottom', 'right'])
                outer = ET.fromstring(f'<w:tbl xmlns:w="{WORD}"><w:tblPr><w:tblW w:w="8000" w:type="dxa"/><w:tblInd w:w="0" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblCellMar>' + margins + '</w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="8000"/></w:tblGrid><w:tr><w:trPr><w:cantSplit/></w:trPr><w:tc><w:tcPr><w:tcW w:w="8000" w:type="dxa"/></w:tcPr></w:tc></w:tr></w:tbl>')
                cell = outer.find(q('tr') + '/' + q('tc'))
                cell.append(paragraph('OUTER_START'))
                cell.append(table)
                cell.append(paragraph('OUTER_END'))
                body.insert(position, outer)
                parts['word/document.xml'] = ET.tostring(document, encoding='utf-8')
                folder = output / (mode + '-' + kind) / paper
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
