"""Build fixed-cell long-word controls without replacing references or golden images."""
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
            with ZipFile(resources/'wide-fixed-tables'/(mode+'-zero')/paper/'source.docx') as archive:
                original = {name: archive.read(name) for name in archive.namelist()}
            for face, flag in [('regular', None), ('bold', 'b'), ('italic', 'i')]:
                parts = original.copy()
                document = E.fromstring(parts['word/document.xml'])
                table = document.find('.//' + q('tbl'))
                grid = table.find(q('tblGrid'))
                width = sum(int(column.get(q('w'))) for column in grid)
                widths = [700, width - 700]
                for column, value in zip(grid, widths):
                    column.set(q('w'), str(value))
                for index, row in enumerate(table.findall(q('tr'))):
                    for column, (cell, value) in enumerate(zip(row.findall(q('tc')), widths)):
                        cell.find(q('tcPr')).find(q('tcW')).set(q('w'), str(value))
                        text = cell.find(q('p')).find('.//' + q('t'))
                        text.set('{http://www.w3.org/XML/1998/namespace}space', 'preserve')
                        text.text = ['ABCDEFGHIJKLMN', 'pre ABCDEFGHIJKLMN', 'one two WWWW'][index] if column == 0 else 'b0'+str(index+1)
                if flag:
                    for properties in table.iter(q('rPr')):
                        properties.insert(1, E.Element(q(flag)))
                parts['word/document.xml'] = E.tostring(document, encoding='utf-8')
                folder = output/(mode+'-'+face)/paper
                folder.mkdir(parents=True, exist_ok=False)
                with ZipFile(folder/'source.docx', 'x', ZIP_DEFLATED) as archive:
                    for name, data in parts.items():
                        entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                        entry.compress_type = ZIP_DEFLATED
                        entry.create_system = 0
                        archive.writestr(entry, data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.resources, args.output)
