"""Build zero-gutter positional controls without overwriting references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import xml.etree.ElementTree as E

WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"


def build(resources, output):
    word = WORD
    for layout in ['ordinary', 'mirrored']:
        for paper in ['a4', 'letter']:
            base = resources / ('preferred-table-widths/' + paper if layout == 'ordinary' else 'mirror-indents/' + paper + '-flip') / 'source.docx'
            with ZipFile(base) as archive:
                original = {name: archive.read(name) for name in archive.namelist()}
            document = E.fromstring(original['word/document.xml'])
            assert all(node.get('{' + word + '}gutter') in [None, '0'] for node in document.iter('{' + word + '}pgMar'))
            variants = ['control', 'rtl-enabled', 'rtl-disabled'] + ([] if layout == 'mirrored' else ['top-enabled', 'top-disabled'])
            for variant in variants:
                parts = original.copy()
                if variant.startswith('rtl-'):
                    text = parts['word/document.xml'].decode()
                    assert '<w:rtlGutter' not in text
                    node = '<w:rtlGutter w:val="' + ('1' if variant.endswith('enabled') else '0') + '"/>'
                    parts['word/document.xml'] = text.replace('</w:sectPr>', node + '</w:sectPr>').encode()
                if variant.startswith('top-'):
                    text = parts['word/settings.xml'].decode()
                    assert '<w:gutterAtTop' not in text
                    node = '<w:gutterAtTop w:val="' + ('1' if variant.endswith('enabled') else '0') + '"/>'
                    parts['word/settings.xml'] = text.replace('</w:settings>', node + '</w:settings>').encode()
                folder = output / layout / variant / paper
                folder.mkdir(parents=True, exist_ok=False)
                with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
                    for name, data in parts.items():
                        entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                        entry.create_system = 0
                        entry.compress_type = ZIP_DEFLATED
                        archive.writestr(entry, data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resources", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    build(args.resources, args.output)
