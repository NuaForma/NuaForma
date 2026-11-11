"""Build passive numbering/save/editor controls without replacing references."""
import argparse
import re
from pathlib import Path
from xml.sax.saxutils import quoteattr
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo
import xml.etree.ElementTree as ET

WORD = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
WORD14 = 'http://schemas.microsoft.com/office/word/2010/wordml'


def read(path):
    with ZipFile(path) as archive:
        return {name: archive.read(name) for name in archive.namelist()}


def write(parts, folder):
    folder.mkdir(parents=True, exist_ok=False)
    with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
        for name, data in parts.items():
            entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
            entry.create_system = 0
            entry.compress_type = ZIP_DEFLATED
            archive.writestr(entry, data)


def append_settings(parts, addition):
    text = parts['word/settings.xml'].decode()
    if 'w14:' in addition and 'xmlns:w14=' not in text:
        text = text.replace('<w:settings', '<w:settings xmlns:w14="' + WORD14 + '"', 1)
    assert '</w:settings>' in text
    # Preserve namespace declarations referenced by mc:Ignorable values.
    parts['word/settings.xml'] = text.replace('</w:settings>', addition + '</w:settings>', 1).encode()


def numbering(resources, output, paper):
    original = read(resources / 'word-numbering-style/control0' / paper / 'source.docx')
    for variant, name in [('control', None), ('ordinary', 'Synthetic List'), ('brace', 'Named {inactive'), ('unicode', 'Список قائمة')]:
        parts = original.copy()
        if name is not None:
            text, count = re.subn(r'(<w:abstractNum\b[^>]*>)', lambda match: match[0] + '<w:name w:val=' + quoteattr(name) + '/>', parts['word/numbering.xml'].decode(), count=1)
            assert count == 1
            parts['word/numbering.xml'] = text.encode()
        write(parts, output / 'numbering-name' / variant / paper)


def image_dpi(resources, output, paper):
    original = read(resources / 'image-compression-states' / paper / 'source.docx')
    for variant, dpi, compress in [('control', None, True), ('dpi96', 96, True), ('dpi300', 300, True), ('dpi600', 600, True),
                                    ('no-compress-control', None, False), ('no-compress-dpi300', 300, False)]:
        parts = original.copy()
        text = parts['word/settings.xml'].decode()
        assert ET.fromstring(text).find('{' + WORD14 + '}defaultImageDpi') is None
        parts['word/settings.xml'] = re.sub(r'<w:doNotAutoCompressPictures\b[^>]*/>', '', text).encode()
        addition = '<w:doNotAutoCompressPictures w:val="' + ('0' if compress else '1') + '"/>'
        if dpi is not None:
            addition += '<w14:defaultImageDpi w14:val="' + str(dpi) + '"/>'
        append_settings(parts, addition)
        write(parts, output / 'image-save-dpi' / variant / paper)


def theme_lock(resources, output, paper):
    original = read(resources / 'theme-colors' / paper / 'source.docx')
    for variant, value in [('control', None), ('enabled', '1'), ('disabled', '0')]:
        parts = original.copy()
        assert ET.fromstring(parts['word/settings.xml']).find('{' + WORD + '}styleLockTheme') is None
        if value is not None:
            append_settings(parts, '<w:styleLockTheme w:val="' + value + '"/>')
        write(parts, output / 'theme-lock' / variant / paper)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    for paper in ['a4', 'letter']:
        numbering(args.resources, args.output, paper)
        image_dpi(args.resources, args.output, paper)
        theme_lock(args.resources, args.output, paper)
