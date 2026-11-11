"""Build synthetic right-indent adjustment sources; never replace references."""
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
        for part in ['word/styles.xml', 'word/document.xml']:
            text = parts[part].decode().replace('<w:pPr>', '<w:pPr><w:adjustRightInd/>')
            if name == 'letter' and part == 'word/document.xml':
                text = text.replace('<w:sectPr>', '<w:sectPr><w:docGrid w:type="default" w:linePitch="360"/>')
            parts[part] = text.encode()
        directory = args.output / name
        directory.mkdir()
        with ZipFile(directory / 'source.docx', 'x', ZIP_DEFLATED) as archive:
            for part, data in parts.items():
                info = ZipInfo(part, (2025, 1, 1, 0, 0, 0))
                info.compress_type = ZIP_DEFLATED
                archive.writestr(info, data)


if __name__ == '__main__':
    build()
