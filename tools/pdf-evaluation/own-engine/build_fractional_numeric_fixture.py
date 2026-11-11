"""Build fractional Word layout controls without overwriting any reference."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()


def paragraph(text, line='280', rule='exact'):
    return ('<w:p><w:pPr><w:spacing w:before="0" w:after="0" '
            f'w:line="{line}" w:lineRule="{rule}"/><w:widowControl w:val="0"/></w:pPr>'
            '<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/>'
            '<w:sz w:val="20"/></w:rPr><w:t>' + text + '</w:t></w:r></w:p>')


def table(grid, padding, rows):
    borders = ''.join(f'<w:{side} w:val="single" w:sz="4" w:color="222222"/>'
                      for side in ['top', 'left', 'bottom', 'right', 'insideH', 'insideV'])
    margins = ''.join(f'<w:{side} w:w="{padding}" w:type="dxa"/>' for side in ['top', 'left', 'bottom', 'right'])
    body = (f'<w:tbl><w:tblPr><w:tblW w:w="{int(float(grid))*8}" w:type="dxa"/><w:tblLayout w:type="fixed"/>'
            '<w:tblBorders>' + borders + '</w:tblBorders><w:tblCellMar>' + margins +
            '</w:tblCellMar></w:tblPr><w:tblGrid>' + f'<w:gridCol w:w="{grid}"/>' * 8 + '</w:tblGrid>')
    for row in range(rows):
        body += '<w:tr><w:trPr><w:cantSplit/></w:trPr>'
        for column in range(8):
            body += f'<w:tc><w:tcPr><w:tcW w:w="{int(float(grid))}" w:type="dxa"/></w:tcPr>' + paragraph(f'G{row:02}{column}') + '</w:tc>'
        body += '</w:tr>'
    return body + '</w:tbl>'


for paper in ['a4', 'letter']:
    with ZipFile(args.base / paper / 'source.docx') as archive:
        original = {name: archive.read(name) for name in archive.namelist()}
    document = original['word/document.xml'].decode()
    prefix = document.split('<w:body>')[0] + '<w:body>'
    suffix = '<w:sectPr>' + document.split('<w:sectPr>')[1]
    for feature in ['grid', 'padding', 'automatic-line']:
        for variant in ['raw', 'floor', 'ceil']:
            value = {'grid': ['1000.8', '1000', '1001'], 'padding': ['100.8', '100', '101'],
                     'automatic-line': ['252.8', '252', '253']}[feature][['raw', 'floor', 'ceil'].index(variant)]
            body = paragraph('NUMERIC ' + feature.upper() + ' ' + paper.upper())
            if feature == 'automatic-line':
                body += ''.join(paragraph(f'L{index:02} line spacing control', value, 'auto') for index in range(30))
            else:
                body += table(value if feature == 'grid' else '1000', value if feature == 'padding' else '80',
                              15 if feature == 'padding' else 2)
            body += paragraph('END_NUMERIC_CONTROL')
            parts = original.copy()
            parts['word/document.xml'] = (prefix + body + suffix).encode()
            folder = args.output / feature / variant / paper
            folder.mkdir(parents=True, exist_ok=False)
            with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
                for name, data in parts.items():
                    entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                    entry.create_system = 0
                    entry.compress_type = ZIP_DEFLATED
                    archive.writestr(entry, data)
print('Eighteen fractional Word layout sources created.')
