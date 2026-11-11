"""Build the synthetic paragraph-justification reference; never overwrite reviewed assets."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def build():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('Refuse to overwrite a reviewed reference')
    word = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
    office = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
    relations = 'http://schemas.openxmlformats.org/package/2006/relationships'
    text = ('Professional documents retain their paragraph geometry while words are spread across the available line. '
            'The final line remains aligned to the left. ' * 3).strip()
    paragraphs = []
    for properties in ['', '<w:ind w:left="360" w:right="240" w:firstLine="360"/>', '<w:ind w:left="360" w:right="240" w:hanging="180"/>']:
        paragraphs.append(f'<w:p><w:pPr>{properties}</w:pPr><w:r><w:t>{text}</w:t></w:r></w:p>')
    paragraphs.append('<w:p><w:r><w:t>Short final paragraph.</w:t></w:r></w:p>')
    parts = {
        '[Content_Types].xml': '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>',
        '_rels/.rels': f'<Relationships xmlns="{relations}"><Relationship Id="main" Type="{office}/officeDocument" Target="word/document.xml"/></Relationships>',
        'word/_rels/document.xml.rels': f'<Relationships xmlns="{relations}"><Relationship Id="styles" Type="{office}/styles" Target="styles.xml"/></Relationships>',
        'word/styles.xml': f'<w:styles xmlns:w="{word}"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="24"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:pPr><w:jc w:val="both"/><w:spacing w:before="0" w:after="120" w:line="360" w:lineRule="exact"/></w:pPr></w:style></w:styles>',
        'word/document.xml': f'<w:document xmlns:w="{word}"><w:body>{"".join(paragraphs)}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="720" w:right="1440" w:bottom="720" w:left="1440"/></w:sectPr></w:body></w:document>'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(args.output, 'x', ZIP_DEFLATED) as archive:
        for name, value in parts.items():
            info = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, value.encode())


if __name__ == '__main__':
    build()
