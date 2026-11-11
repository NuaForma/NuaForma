"""Build Word cell-margin controls without replacing source, Word or image references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import re


def paragraph(text):
    return '<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/><w:widowControl w:val="0"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr><w:t>' + text + '</w:t></w:r></w:p>'


def margin(edges):
    return '<w:tcMar>' + ''.join(('<w:' + n + ' w:w="' + str(v) + '" w:type="dxa"/>' for n, v in edges.items())) + '</w:tcMar>'


def regular(base, output):
    for paper in ['a4', 'letter']:
        source = base / paper / 'source.docx'
        with ZipFile(source) as z:
            original = {n: z.read(n) for n in z.namelist()}
        doc = original['word/document.xml'].decode()
        prefix = doc.split('<w:body>')[0] + '<w:body>'
        suffix = '<w:sectPr>' + doc.split('<w:sectPr>')[1]
        body = paragraph('CELL MARGINS ' + paper.upper())
        for tableIndex in range(2):
            style = '<w:tblStyle w:val="CellMarginStyle"/>' if tableIndex else ''
            borders = ''.join(('<w:' + side + ' w:val="single" w:sz="4" w:color="222222"/>' for side in ['top', 'left', 'bottom', 'right', 'insideH', 'insideV']))
            tableMargin = ''.join(('<w:' + n + ' w:w="' + str(v) + '" w:type="dxa"/>' for n, v in [('top', 80), ('left', 100), ('bottom', 80), ('right', 100)]))
            body += '<w:tbl><w:tblPr>' + style + '<w:tblW w:w="6000" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders>' + borders + '</w:tblBorders><w:tblCellMar>' + tableMargin + '</w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/></w:tblGrid>'
            settings = [({'bottom': 142}, None), ({'left': 240, 'right': 0}, {'top': 0, 'bottom': 0}), ({'top': 200, 'bottom': 40, 'left': 0}, {'left': 300, 'right': 300})]
            for row, pair in enumerate(settings):
                body += '<w:tr><w:trPr><w:cantSplit/></w:trPr>'
                for col, edges in enumerate(pair):
                    properties = '<w:tcW w:w="3000" w:type="dxa"/>' + (margin(edges) if edges else '')
                    text = 'M' + str(tableIndex) + str(row) + str(col) + 'A' + (' wrapped cell content requires several words' if row == 2 else '')
                    body += '<w:tc><w:tcPr>' + properties + '</w:tcPr>' + paragraph(text) + '</w:tc>'
                body += '</w:tr>'
            body += '<w:tr><w:trPr><w:cantSplit/></w:trPr><w:tc><w:tcPr><w:tcW w:w="6000" w:type="dxa"/><w:gridSpan w:val="2"/>' + margin({'left': 200, 'bottom': 142}) + '</w:tcPr>' + paragraph('M' + str(tableIndex) + '30A MERGED CELL') + '</w:tc></w:tr></w:tbl>' + paragraph('AFTER TABLE ' + str(tableIndex))
        body += paragraph('END_CELL_MARGINS')
        parts = original.copy()
        parts['word/document.xml'] = (prefix + body + suffix).encode()
        definition = '<w:style w:type="table" w:styleId="CellMarginStyle"><w:name w:val="Qualified Cell Margins"/><w:tcPr>' + margin({'top': 40, 'left': 200}) + '</w:tcPr></w:style>'
        for name in ['word/styles.xml', 'word/stylesWithEffects.xml']:
            if name in parts:
                parts[name] = parts[name].decode().replace('</w:styles>', definition + '</w:styles>').encode()
        folder = output / 'regular' / paper
        folder.mkdir(parents=True, exist_ok=False)
        with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as z:
            for n, v in parts.items():
                entry = ZipInfo(n, (2025, 1, 1, 0, 0, 0))
                entry.create_system = 0
                entry.compress_type = ZIP_DEFLATED
                z.writestr(entry, v)


def split(base, output):
    for paper in ['a4', 'letter']:
        with ZipFile(base / paper / 'source.docx') as z:
            parts = {n: z.read(n) for n in z.namelist()}
        s = parts['word/document.xml'].decode()
        prefix = s.split('<w:body>')[0] + '<w:body>'
        suffix = '<w:sectPr>' + s.split('<w:sectPr>')[1]
        borders = ''.join(('<w:' + side + ' w:val="single" w:sz="4" w:color="222222"/>' for side in ['top', 'left', 'bottom', 'right', 'insideH', 'insideV']))
        margins = ''.join(('<w:' + n + ' w:w="' + str(v) + '" w:type="dxa"/>' for n, v in [('top', 80), ('left', 100), ('bottom', 80), ('right', 100)]))
        body = paragraph('SPLIT CELL MARGINS ' + paper.upper()) + '<w:tbl><w:tblPr><w:tblW w:w="6000" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders>' + borders + '</w:tblBorders><w:tblCellMar>' + margins + '</w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/></w:tblGrid>'
        body += '<w:tr><w:trPr><w:tblHeader/><w:cantSplit/></w:trPr>'
        for label in ['H00A', 'H00B']:
            body += '<w:tc><w:tcPr><w:tcW w:w="3000" w:type="dxa"/></w:tcPr>' + paragraph(label) + '</w:tc>'
        body += '</w:tr><w:tr><w:trPr/>'
        for letter, edges in [('A', {'top': 160, 'bottom': 240, 'left': 240, 'right': 160}), ('B', {'top': 40, 'bottom': 360, 'left': 60, 'right': 280})]:
            body += '<w:tc><w:tcPr><w:tcW w:w="3000" w:type="dxa"/>' + margin(edges) + '</w:tcPr>'
            for index in range(70):
                body += paragraph('S' + str(index).zfill(2) + letter)
            body += '</w:tc>'
        body += '</w:tr></w:tbl>' + paragraph('END_SPLIT_MARGINS')
        parts['word/document.xml'] = (prefix + body + suffix).encode()
        folder = output / 'split' / paper
        folder.mkdir(parents=True, exist_ok=False)
        with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as z:
            for n, v in parts.items():
                entry = ZipInfo(n, (2025, 1, 1, 0, 0, 0))
                entry.create_system = 0
                entry.compress_type = ZIP_DEFLATED
                z.writestr(entry, v)


def minimum(output):
    for paper in ['a4', 'letter']:
        with ZipFile(output / 'regular' / paper / 'source.docx') as archive:
            parts = {name: archive.read(name) for name in archive.namelist()}
        document = parts['word/document.xml'].decode()
        document, count = re.subn('<w:trPr>', '<w:trPr><w:trHeight w:val="800" w:hRule="atLeast"/>', document, count=1)
        assert count == 1
        parts['word/document.xml'] = document.encode()
        folder = output / 'minimum' / paper
        folder.mkdir(parents=True, exist_ok=False)
        with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
            for name, data in parts.items():
                entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                entry.create_system = 0
                entry.compress_type = ZIP_DEFLATED
                archive.writestr(entry, data)


def fractional(output):
    for paper in ['a4', 'letter']:
        with ZipFile(output / 'regular' / paper / 'source.docx') as archive:
            parts = {name: archive.read(name) for name in archive.namelist()}
        document = parts['word/document.xml'].decode()
        assert 'w:w="142"' in document
        parts['word/document.xml'] = document.replace('w:w="142"', 'w:w="142.8"').encode()
        folder = output / 'fractional' / paper
        folder.mkdir(parents=True, exist_ok=False)
        with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
            for name, data in parts.items():
                entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                entry.create_system = 0
                entry.compress_type = ZIP_DEFLATED
                archive.writestr(entry, data)


def nil_margins(output):
    for paper in ['a4', 'letter']:
        with ZipFile(output / 'regular' / paper / 'source.docx') as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        document = original['word/document.xml'].decode()
        document = document.replace('w:w="0" w:type="dxa"', 'w:w="0" w:type="nil"')
        control, count = re.subn('<w:(top|right|bottom|left) w:w="0" w:type="nil"/>', lambda match: '<w:' + match[1] + ' w:w="' + ('80' if match[1] in ['top', 'bottom'] else '100') + '" w:type="dxa"/>', document)
        assert count == 8
        for variant, content in [('nil', document), ('nil-control', control)]:
            folder = output / variant / paper
            folder.mkdir(parents=True, exist_ok=False)
            parts = original.copy()
            parts['word/document.xml'] = content.encode()
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
    regular(args.base, args.output)
    split(args.base, args.output)
    minimum(args.output)
    fractional(args.output)
    nil_margins(args.output)
