"""Build synthetic preferred-width probes, without modifying existing reference files."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from xml.sax.saxutils import escape


def paragraph(text):
    return '<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr><w:t>'+escape(text)+'</w:t></w:r></w:p>'


def table(label, kind, implicit=False, merged=False):
    percent = kind == 'pct'
    width = '4000' if percent else '6400'
    cell_widths = ['1250', '3750'] if percent else ['1600', '4800']
    properties = f'<w:tblW w:w="{width}" w:type="{kind}"/>'
    if not implicit:
        properties += '<w:tblLayout w:type="autofit"/>'
    properties += '<w:tblInd w:w="0" w:type="dxa"/><w:tblBorders>'
    properties += ''.join(f'<w:{side} w:val="single" w:sz="4" w:color="222222"/>' for side in ['top', 'left', 'bottom', 'right', 'insideH', 'insideV'])
    properties += '</w:tblBorders><w:tblCellMar>'+''.join(f'<w:{side} w:w="{0 if side in ("top", "bottom") else 108}" w:type="dxa"/>' for side in ['top', 'left', 'bottom', 'right'])+'</w:tblCellMar>'
    rows = ''
    for index in range(3):
        cells = ''
        for column in range(2):
            value = f'{label}_{index+1}_{column+1}'
            if column == 1 and index == 1:
                value += ' alpha beta gamma delta epsilon zeta eta theta'
            cells += f'<w:tc><w:tcPr><w:tcW w:w="{cell_widths[column]}" w:type="{kind}"/></w:tcPr>'+paragraph(value)+'</w:tc>'
        rows += '<w:tr><w:trPr><w:cantSplit/></w:trPr>'+cells+'</w:tr>'
    if merged:
        rows += '<w:tr><w:trPr><w:cantSplit/></w:trPr><w:tc><w:tcPr><w:tcW w:w="6400" w:type="dxa"/><w:gridSpan w:val="2"/></w:tcPr>'+paragraph('MERGED_SHARED_COLUMNS')+'</w:tc></w:tr>'
    return '<w:tbl><w:tblPr>'+properties+'</w:tblPr><w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="8000"/></w:tblGrid>'+rows+'</w:tbl>'


def build(base, output):
    for paper in ['a4', 'letter']:
        with ZipFile(base/'passive-office-package'/paper/'source.docx') as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        document = original['word/document.xml'].decode()
        prefix = document[:document.index('<w:body>')+len('<w:body>')]
        suffix = document[document.index('<w:sectPr'):]
        body = paragraph('PREFERRED TABLE WIDTHS '+paper.upper())
        for label, kind, implicit, merged in [('PHYSICAL', 'dxa', False, False), ('PERCENT', 'pct', False, False), ('IMPLICIT', 'dxa', True, False), ('MERGED', 'dxa', True, True)]:
            body += paragraph(label)+table(label, kind, implicit, merged)
        body += paragraph('END_PREFERRED_TABLES')
        parts = dict(original)
        parts['word/document.xml'] = (prefix+body+suffix).encode()
        target = output/paper
        target.mkdir(parents=True, exist_ok=False)
        with ZipFile(target/'source.docx', 'x', ZIP_DEFLATED) as archive:
            for name, data in parts.items():
                info = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                info.create_system = 0
                info.compress_type = ZIP_DEFLATED
                archive.writestr(info, data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.base, args.output)
