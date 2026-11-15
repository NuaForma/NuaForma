"""Build synthetic exact-leading controls; never replace references or golden images."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED, ZipInfo
import argparse
import xml.etree.ElementTree as E

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
E.register_namespace('w', W)


def paragraph(text, size, height):
    return (f'<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="{height * 20}" w:lineRule="exact"/></w:pPr>'
            f'<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="{size * 2}"/></w:rPr>'
            f'<w:t>{text}</w:t></w:r></w:p>')


def build(output):
    root = Path(__file__).resolve().parents[2]
    cases = [(size, height, paper) for size, height in [(10, 20), (12, 12), (18, 12), (18, 10), (18, 20)] for paper in ['a4', 'letter']]
    targets = [output / f'{size}pt-{height}line' / paper / 'source.docx' for size, height, paper in cases]
    if any(target.exists() for target in targets):
        raise FileExistsError('Refusing to replace existing source fixtures')
    for (size, height, paper), target in zip(cases, targets):
        with ZipFile(root / 'nuaforma-docx-pdf/src/test/resources/preferred-table-widths' / paper / 'source.docx') as archive:
            parts = {name: archive.read(name) for name in archive.namelist()}
        document = E.fromstring(parts['word/document.xml'])
        body = document.find(f'{{{W}}}body')
        section = body.find(f'{{{W}}}sectPr')
        for child in list(body):
            body.remove(child)
        content = E.fromstring(f'<w:body xmlns:w="{W}">' + paragraph('M00_BEFORE', 10, 20) +
                               paragraph('ÅÉHgjpq', size, height) + paragraph('M01_AFTER', 10, 20) + '</w:body>')
        body.extend(content)
        body.append(section)
        parts['word/document.xml'] = E.tostring(document, encoding='utf-8')
        target.parent.mkdir(parents=True, exist_ok=True)
        with ZipFile(target, 'x', ZIP_DEFLATED) as archive:
            for name, data in parts.items():
                archive.writestr(ZipInfo(name, (2025, 1, 1, 0, 0, 0)), data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    build(parser.parse_args().output)
