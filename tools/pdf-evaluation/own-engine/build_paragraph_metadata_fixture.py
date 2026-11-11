"""Build synthetic paragraph/language/style metadata references without replacement."""
from argparse import ArgumentParser
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def build():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for name in ['a4', 'letter']:
        with ZipFile(args.base / name / 'source.docx') as archive:
            parts = {part: archive.read(part) for part in archive.namelist()}
        styles = parts['word/styles.xml'].decode().replace('<w:rPr>', '<w:rPr><w:lang w:val="en-GB"/>', 1)
        styles = styles.replace('<w:name w:val="Normal"/>', '<w:name w:val="Normal"/><w:aliases w:val="Business body,Document body"/>')
        styles = styles.replace('<w:pPr>', '<w:pPr><w:textAlignment w:val="auto"/>')
        parts['word/styles.xml'] = styles.encode()
        document = parts['word/document.xml'].decode().replace('<w:pPr>', '<w:pPr><w:textAlignment w:val="baseline"/>')
        original = '<w:p><w:r><w:t>Short final paragraph. Ελληνικά Кириллица 12345</w:t></w:r></w:p>'
        replacement = ('<w:p><w:pPr><w:textAlignment w:val="auto"/><w:spacing w:before="0" w:after="120" w:line="720" w:lineRule="exact"/></w:pPr>'
                       '<w:r><w:rPr><w:lang w:eastAsia="fr-FR"/></w:rPr><w:t>Short final paragraph. Ελληνικά Кириллица 12345</w:t></w:r>'
                       '<w:r><w:rPr><w:sz w:val="40"/><w:lang w:eastAsia="fr-FR"/></w:rPr><w:t xml:space="preserve"> Large</w:t></w:r>'
                       '<w:r><w:rPr><w:sz w:val="16"/><w:lang w:eastAsia="fr-FR"/></w:rPr><w:t xml:space="preserve"> small</w:t></w:r></w:p>')
        assert original in document
        parts['word/document.xml'] = document.replace(original, replacement).encode()
        directory = args.output / name
        directory.mkdir()
        with ZipFile(directory / 'source.docx', 'x', ZIP_DEFLATED) as archive:
            for part, data in parts.items():
                info = ZipInfo(part, (2025, 1, 1, 0, 0, 0))
                info.compress_type = ZIP_DEFLATED
                archive.writestr(info, data)


if __name__ == '__main__':
    build()
