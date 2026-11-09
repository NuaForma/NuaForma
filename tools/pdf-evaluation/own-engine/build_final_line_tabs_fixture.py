"""Build final-line tab controls with an explicit comma decimal separator."""
import argparse
from pathlib import Path
import re
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def run(content):
    return ('<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/>'
            '<w:sz w:val="20"/></w:rPr>' + content + '</w:r>')


def text(value):
    return run('<w:t>' + value + '</w:t>')


def paragraph(content, alignment='left', kind='left', leader='none'):
    stops = ''.join(f'<w:tab w:val="{kind}" w:leader="{leader}" w:pos="{position}"/>'
                    for position in [1440, 2880])
    return (f'<w:p><w:pPr><w:jc w:val="{alignment}"/><w:tabs>{stops}</w:tabs>'
            '<w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/>'
            '<w:widowControl w:val="0"/></w:pPr>' + content + '</w:p>')


def build(base, output):
    for paper in ['a4', 'letter']:
        with ZipFile(base / paper / 'source.docx') as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        document = original['word/document.xml'].decode()
        prefix = document.split('<w:body>')[0] + '<w:body>'
        suffix = '<w:sectPr>' + document.split('<w:sectPr>')[1]
        settings = original['word/settings.xml'].decode()
        declaration = '<w:decimalSymbol w:val=","/>'
        if '<w:decimalSymbol' in settings:
            settings, count = re.subn(r'<w:decimalSymbol\b[^>]*/>', declaration, settings)
            assert count == 1
        else:
            settings = settings.replace('</w:settings>', declaration + '</w:settings>')
        for variant, alignment in [('left-control', 'left'), ('justified', 'both')]:
            body = paragraph(text('FINAL LINE TABS ' + paper.upper()))
            index = 0
            for kind in ['left', 'right', 'center', 'decimal']:
                for leader in ['none', 'dot', 'hyphen', 'underscore']:
                    middle = f'12{index},45' if kind == 'decimal' else f'T{index:02}B'
                    end = f'67{index},90' if kind == 'decimal' else f'T{index:02}C'
                    body += paragraph(text(f'T{index:02}A') + run('<w:tab/>') + text(middle) +
                                      run('<w:tab/>') + text(end), alignment, kind, leader)
                    index += 1
            body += paragraph(text('END_FINAL_LINE_TABS'))
            parts = original.copy()
            parts['word/document.xml'] = (prefix + body + suffix).encode()
            parts['word/settings.xml'] = settings.encode()
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
