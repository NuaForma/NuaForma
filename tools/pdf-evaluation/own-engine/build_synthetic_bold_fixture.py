"""Create an inert same-family synthetic-bold source; never replace a golden fixture."""
from pathlib import Path
import argparse
import xml.etree.ElementTree as ET
import zipfile

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'


def build(base, output):
    if output.exists():
        raise ValueError('Refuse to replace an existing fixture')
    with zipfile.ZipFile(base) as archive:
        parts = {name: archive.read(name) for name in archive.namelist()}
    ET.register_namespace('w', W[1:-1])
    document = ET.fromstring(parts['word/document.xml'])
    body = document.find(W + 'body')
    section = body.find(W + 'sectPr')
    for child in list(body):
        body.remove(child)

    def paragraph():
        node = ET.SubElement(body, W + 'p')
        properties = ET.SubElement(node, W + 'pPr')
        ET.SubElement(properties, W + 'spacing', {W + 'before': '0', W + 'after': '0', W + 'line': '720', W + 'lineRule': 'exact'})
        return node

    def run(parent, family, face, text, color=None, underline=False):
        node = ET.SubElement(parent, W + 'r')
        properties = ET.SubElement(node, W + 'rPr')
        ET.SubElement(properties, W + 'rFonts', {W + 'ascii': family, W + 'hAnsi': family})
        ET.SubElement(properties, W + 'sz', {W + 'val': '24'})
        if face in ('bold', 'boldItalic'):
            ET.SubElement(properties, W + 'b')
        if face in ('italic', 'boldItalic'):
            ET.SubElement(properties, W + 'i')
        if color:
            ET.SubElement(properties, W + 'color', {W + 'val': color})
        if underline:
            ET.SubElement(properties, W + 'u', {W + 'val': 'single'})
        ET.SubElement(node, W + 't', {'{http://www.w3.org/XML/1998/namespace}space': 'preserve'}).text = text

    for family in ('Noto Serif Light', 'Liberation Sans'):
        for face in ('regular', 'bold', 'italic', 'boldItalic'):
            run(paragraph(), family, face, f'{family} {face}: HAMBURGEFONTS abcdef 012345')
    mixed = paragraph()
    run(mixed, 'Noto Serif Light', 'bold', 'COLORED BOLD ', '1C7E82', True)
    run(mixed, 'Noto Serif Light', 'regular', 'plain after bold', '000000')
    body.append(section)
    parts['word/document.xml'] = ET.tostring(document, encoding='utf-8', xml_declaration=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as archive:
        for name, data in parts.items():
            entry = zipfile.ZipInfo(name, date_time=(2025, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.base, args.output)
