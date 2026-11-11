"""Build synthetic mode-14 sources from qualified formatting fixtures; never overwrite output."""
from argparse import ArgumentParser
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import re

CASES = {
    'spacing-a4': 'paragraph-spacing/legacy-fixed-edges',
    'spacing-letter': 'paragraph-spacing/letter-fixed',
    'table-legacy': 'conditional-tables/word12-legacy',
    'table-override': 'conditional-tables/word12-override',
    'table-letter': 'conditional-tables/letter',
    'mirror-letter': 'mirror-indents/letter-flip',
    'font-open-type': 'font-features/legacy-enabled',
}


def build():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for name, base in CASES.items():
        with ZipFile(args.base / base / 'source.docx') as archive:
            parts = {part: archive.read(part) for part in archive.namelist()}
        settings, count = re.subn(
            r'(<w:compatSetting\b(?=[^>]*w:name="compatibilityMode")[^>]*w:val=")(12|15)(")',
            r'\g<1>14\3', parts['word/settings.xml'].decode())
        assert count == 1, f'{name}: expected exactly one qualified source compatibility mode'
        parts['word/settings.xml'] = settings.encode()
        directory = args.output / name
        directory.mkdir()
        with ZipFile(directory / 'source.docx', 'x', ZIP_DEFLATED) as archive:
            for part, data in parts.items():
                info = ZipInfo(part, (2025, 1, 1, 0, 0, 0))
                info.compress_type = ZIP_DEFLATED
                archive.writestr(info, data)


if __name__ == '__main__':
    build()
