"""Build A4/Letter image compression-state controls with body and page-band logos."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import re
import struct
import zlib

R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
REL = 'http://schemas.openxmlformats.org/package/2006/relationships'

def png():

    def chunk(name, data):
        return struct.pack('>I', len(data)) + name + data + struct.pack('>I', zlib.crc32(name + data) & 4294967295)

    width, height = (72, 24)
    pixels = b''.join((b'\x00' + b''.join((bytes((0, 139, 144) if (x // 12 + y // 12) % 2 else (35, 54, 85)) for x in range(width))) for y in range(height)))
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')

def image(drawing, index, width, height, state):
    s = re.sub('cx="[0-9]+" cy="[0-9]+"', f'cx="{int(width * 12700)}" cy="{int(height * 12700)}"', drawing)
    s = re.sub('<wp:docPr id="[0-9]+"', f'<wp:docPr id="{index}"', s)
    if state is not None:
        s = s.replace('<a:blip ', f'<a:blip cstate="{state}" ', 1)
    return s

def paragraph(text, body='', line=320):
    content = '<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="22"/></w:rPr><w:t xml:space="preserve">' + text + '</w:t></w:r>' if text else ''
    if body:
        content += '<w:r>' + body + '</w:r>'
    return '<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="' + str(line) + '" w:lineRule="exact"/></w:pPr>' + content + '</w:p>'

def build(base, output):
    with ZipFile(base) as z:
        original = {n: z.read(n) for n in z.namelist()}
    xml = original['word/document.xml'].decode()
    prefix = xml.split('<w:body>')[0]
    picture = re.search('<w:drawing>.*?</w:drawing>', xml, re.S).group()
    picture = re.sub(' wp14:(anchorId|editId)="[^"]*"', '', picture)
    for variant in ['control', 'compressed']:
        for paper in ['a4', 'letter']:
            parts = original.copy()
            states = ['none', 'email', 'screen', 'print', 'hqprint']
            body = ''.join((paragraph(f'R0{i + 1} {state}: ') + paragraph('', image(picture, i + 1, 72, 24, None if variant == 'control' else state), 720) for i, state in enumerate(states)))
            w, h = (11906, 16838) if paper == 'a4' else (12240, 15840)
            section = f'<w:sectPr><w:headerReference w:type="default" r:id="header262"/><w:footerReference w:type="default" r:id="footer262"/><w:pgSz w:w="{w}" w:h="{h}"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="400" w:footer="400" w:gutter="0"/></w:sectPr>'
            parts['word/document.xml'] = (prefix + '<w:body>' + body + section + '</w:body></w:document>').encode()
            for band, index in [('header', 6), ('footer', 7)]:
                root = 'hdr' if band == 'header' else 'ftr'
                content = paragraph('', image(picture, index, 36, 12, None if variant == 'control' else 'print' if band == 'header' else 'screen'), 400)
                head = prefix.replace('<w:document', '<w:' + root)
                parts[f'word/{band}1.xml'] = (head + content + f'</w:{root}>').encode()
                parts[f'word/_rels/{band}1.xml.rels'] = f'<Relationships xmlns="{REL}"><Relationship Id="rId4" Type="{R}/image" Target="media/image1.png"/></Relationships>'.encode()
            parts['word/media/image1.png'] = png()
            relationships = parts['word/_rels/document.xml.rels'].decode().replace('</Relationships>', f'<Relationship Id="header262" Type="{R}/header" Target="header1.xml"/><Relationship Id="footer262" Type="{R}/footer" Target="footer1.xml"/></Relationships>')
            parts['word/_rels/document.xml.rels'] = relationships.encode()
            types = parts['[Content_Types].xml'].decode().replace('</Types>', '<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/><Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/></Types>')
            parts['[Content_Types].xml'] = types.encode()
            folder = output / variant / paper
            folder.mkdir(parents=True, exist_ok=False)
            with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as z:
                for n, data in parts.items():
                    item = ZipInfo(n, (2025, 1, 1, 0, 0, 0))
                    item.create_system = 0
                    item.compress_type = ZIP_DEFLATED
                    z.writestr(item, data)
if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.base, args.output)
