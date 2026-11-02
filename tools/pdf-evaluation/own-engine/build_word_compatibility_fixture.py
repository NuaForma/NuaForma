"""Synthetic Word authoring metadata, signed indents, EMF page-band logos and real notes.

The fixture is public and generated from scratch; no customer document or logo is used.
"""
import struct
import sys
import re
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_fixture import build as letter, paragraph, run, W, R
from build_notes_fixtures import build as notes


def record(kind, *values):
    return struct.pack('<' + 'I' * (2 + len(values)), kind, 8 + len(values) * 4, *values)


def logo():
    records = [record(39, 1, 0, 0x00B07010, 0), record(37, 1), record(37, 0x80000008),
               record(43, 10, 10, 190, 90), record(37, 0x80000000), record(42, 25, 25, 75, 75)]
    end = record(14, 0, 0, 20)
    size = 88 + sum(map(len, records)) + len(end)
    header = struct.pack('<14I2H7I', 1, 88, 0, 0, 200, 100, 0, 0, 20000, 10000,
                         0x464D4520, 0x10000, size, len(records) + 2, 16, 0,
                         0, 0, 0, 1000, 1000, 1000, 1000)
    return header + b''.join(records) + end


def build(root, output, kind='a4'):
    entries = notes(root, output, 'multipage', entries_only=True)
    base = letter(root, output, entries_only=True)['word/document.xml']
    inline = base[base.index('<wp:inline'):base.index('</wp:inline>') + len('</wp:inline>')]

    def band(name, width, height):
        drawing = re.sub(r'cx="\d+"', f'cx="{width * 12700}"', inline)
        drawing = re.sub(r'cy="\d+"', f'cy="{height * 12700}"', drawing)
        drawing = re.sub(r'r:embed="[^"]+"', 'r:embed="emfLogo"', drawing)
        namespaces = (f'xmlns:w="{W}" xmlns:r="{R}" '
                      'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
                      'xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" '
                      'xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"')
        content = paragraph('<w:r><w:drawing>' + drawing + '</w:drawing></w:r>',
                            '<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/>')
        content += paragraph(run(name.upper() + ' logo retained'),
                             '<w:spacing w:before="0" w:after="0" w:line="320" w:lineRule="exact"/>')
        # WordprocessingML uses hdr/ftr, unlike the relationship names.
        root_name = 'hdr' if name == 'header' else 'ftr'
        entries[f'word/{name}1.xml'] = f'<w:{root_name} {namespaces}>' + content + f'</w:{root_name}>'
        entries[f'word/_rels/{name}1.xml.rels'] = ('<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            f'<Relationship Id="emfLogo" Type="{R}/image" Target="media/{name}.emf"/></Relationships>')
        entries[f'word/media/{name}.emf'] = logo()
        entries['[Content_Types].xml'] = entries['[Content_Types].xml'].replace('</Types>',
            f'<Override PartName="/word/{name}1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.{name}+xml"/></Types>')
        entries['word/_rels/document.xml.rels'] = entries['word/_rels/document.xml.rels'].replace('</Relationships>',
            f'<Relationship Id="{name}" Type="{R}/{name}" Target="{name}1.xml"/></Relationships>')

    band('header', 100, 50)
    band('footer', 40, 20)
    entries['[Content_Types].xml'] = entries['[Content_Types].xml'].replace('</Types>', '<Default Extension="emf" ContentType="image/x-emf"/></Types>')
    document = entries['word/document.xml'].replace('<w:sectPr>',
        '<w:sectPr><w:headerReference w:type="default" r:id="header"/><w:footerReference w:type="default" r:id="footer"/>')
    document = re.sub(r'w:bottom="\d+"', 'w:bottom="400"', document)
    document = re.sub(r'w:footer="\d+"', 'w:footer="480"', document)
    document = document.replace('<w:body>', '<w:body>' + paragraph(run('SIGNED INDENTS'),
        '<w:ind w:left="-120" w:right="-80" w:hanging="60"/><w:rPr><w:sz w:val="28"/></w:rPr>'))
    if kind == 'letter':
        document = document.replace('w:w="11906" w:h="16838"', 'w:w="12240" w:h="15840"')
    entries['word/document.xml'] = document
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'w') as archive:
        for name, value in sorted(entries.items()):
            info = ZipInfo(name, date_time=(2026, 11, 2, 0, 0, 0))
            info.create_system = 0
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, value.encode('utf-8') if isinstance(value, str) else value)


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[3]
    for kind in ('a4', 'letter'):
        build(root, Path(sys.argv[1]) / kind / 'source.docx', kind)
