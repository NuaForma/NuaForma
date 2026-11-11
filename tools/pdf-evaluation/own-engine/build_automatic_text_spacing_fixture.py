"""Author deterministic inactive East Asian spacing cases without replacing references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def build():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise SystemExit('Refuse to overwrite a reference directory')
    with ZipFile(args.base) as archive:
        original = {name: archive.read(name) for name in archive.namelist()}
    for name in ['a4', 'letter']:
        parts = original.copy()
        styles = parts['word/styles.xml'].decode()
        defaults = '<w:pPrDefault><w:pPr><w:autoSpaceDE/><w:autoSpaceDN/></w:pPr></w:pPrDefault>'
        styles = styles.replace('</w:docDefaults>', defaults + '</w:docDefaults>')
        styles = styles.replace('<w:jc ', '<w:autoSpaceDE w:val="true"/><w:autoSpaceDN w:val="on"/><w:jc ')
        parts['word/styles.xml'] = styles.encode()
        document = parts['word/document.xml'].decode()
        count = 0
        while '<w:pPr>' in document:
            flags = '<w:autoSpaceDE w:val="0"/><w:autoSpaceDN w:val="false"/>' if count == 0 else '<w:autoSpaceDE w:val="on"/><w:autoSpaceDN w:val="true"/>'
            document = document.replace('<w:pPr>', '<w:pPr data-placeholder="1">' + flags, 1)
            count += 1
        document = document.replace(' data-placeholder="1"', '')
        document = document.replace('Short final paragraph.', 'Short final paragraph. Ελληνικά Кириллица 12345')
        if name == 'letter':
            document = document.replace('w:w="11906" w:h="16838"', 'w:w="12240" w:h="15840"')
        parts['word/document.xml'] = document.encode()
        directory = args.output / name
        directory.mkdir(parents=True)
        with ZipFile(directory / 'source.docx', 'x', ZIP_DEFLATED) as archive:
            for part, data in parts.items():
                info = ZipInfo(part, (2025, 1, 1, 0, 0, 0))
                info.compress_type = ZIP_DEFLATED
                archive.writestr(info, data)


if __name__ == '__main__':
    build()
