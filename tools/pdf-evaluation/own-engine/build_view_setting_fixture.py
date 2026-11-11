"""Build nonprinting Word view-setting controls without replacing any reference."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def build(base, output):
    for paper in ['a4', 'letter']:
        with ZipFile(base / paper / 'source.docx') as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        variants = [
            ('background', '<w:displayBackgroundShape/>'),
            ('form-indicator', '<w:doNotShadeFormData/>'),
            ('recommended', '<w:writeProtection w:recommended="1"/>'),
            ('disabled', '<w:displayBackgroundShape w:val="off"/><w:doNotShadeFormData w:val="false"/><w:writeProtection w:recommended="0"/>')]
        for name, addition in variants:
            parts = original.copy()
            settings = parts['word/settings.xml'].decode()
            assert all(tag not in settings for tag in ['displayBackgroundShape', 'doNotShadeFormData', 'writeProtection'])
            parts['word/settings.xml'] = settings.replace('</w:settings>', addition + '</w:settings>').encode()
            folder = output / name / paper
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
