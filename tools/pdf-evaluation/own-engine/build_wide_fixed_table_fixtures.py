"""Build wide fixed-table Word controls without replacing references or golden images."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from xml.etree import ElementTree as E

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
E.register_namespace('w', W)


def q(name):
    return '{' + W + '}' + name


def build(resources, output):
    for mode in ['12', '15']:
        for paper in ['a4', 'letter']:
            base = resources / 'signed-table-indent' / (mode + '-truncate') / paper / 'source.docx'
            with ZipFile(base) as archive:
                original = {name: archive.read(name) for name in archive.namelist()}
            for kind, indent in [('omitted', None), ('zero', 0), ('positive', 100), ('negative', -100)]:
                parts = original.copy()
                document = E.fromstring(parts['word/document.xml'])
                table = document.find('.//' + q('tbl'))
                properties = table.find(q('tblPr'))
                section = document.find('.//' + q('sectPr'))
                page_width = int(section.find(q('pgSz')).get(q('w')))
                margins = section.find(q('pgMar'))
                body_width = page_width - int(margins.get(q('left'))) - int(margins.get(q('right')))
                width = body_width + 466
                properties.find(q('tblW')).set(q('w'), str(width))
                previous_indent = properties.find(q('tblInd'))
                if indent is None:
                    properties.remove(previous_indent)
                else:
                    previous_indent.set(q('w'), str(indent))
                widths = [width // 2, width - width // 2]
                for column, value in zip(table.find(q('tblGrid')), widths):
                    column.set(q('w'), str(value))
                for row in table.findall(q('tr')):
                    for cell, value in zip(row.findall(q('tc')), widths):
                        cell.find(q('tcPr')).find(q('tcW')).set(q('w'), str(value))
                parts['word/document.xml'] = E.tostring(document, encoding='utf-8')
                folder = output / (mode + '-' + kind) / paper
                folder.mkdir(parents=True, exist_ok=False)
                with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
                    for name, data in parts.items():
                        item = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                        item.compress_type = ZIP_DEFLATED
                        item.create_system = 0
                        archive.writestr(item, data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.resources, args.output)
