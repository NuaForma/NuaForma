"""Create an inert synthetic Office metadata variant without altering the base story parts."""
import argparse
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import zipfile
import zlib

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
A = 'http://schemas.openxmlformats.org/drawingml/2006/main'
REL = 'http://schemas.openxmlformats.org/package/2006/relationships'
CUSTOM = 'http://schemas.openxmlformats.org/officeDocument/2006/customXml'
ROOTS = [
    ('http://schemas.microsoft.com/office/2006/metadata/contentType', 'contentTypeSchema',
     '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:nuaforma:example"><xs:element name="Example" type="xs:string"/></xs:schema>'),
    ('http://schemas.microsoft.com/office/2006/metadata/properties', 'properties',
     '<documentManagement><Example>METADATA {not a template</Example></documentManagement>'),
    ('http://schemas.microsoft.com/sharepoint/v3/contenttype/forms', 'FormTemplates', '<Display/><Edit/><New/>')]


def build(base, theme_source, font, output):
    if output.exists():
        raise ValueError('Refuse to replace an existing fixture')
    with zipfile.ZipFile(base) as archive:
        parts = {n: archive.read(n) for n in archive.namelist()}

    def append(part, closing, value):
        text = parts[part].decode('utf-8')
        if text.count(closing) != 1:
            raise ValueError('Unexpected synthetic base structure')
        parts[part] = text.replace(closing, value + closing).encode()

    def content_type(name, mime):
        append('[Content_Types].xml', '</Types>', f'<Override PartName="/{name}" ContentType="{mime}"/>')

    def relationship(identity, kind, target):
        append('word/_rels/document.xml.rels', '</Relationships>',
               f'<Relationship Id="{identity}" Type="{kind}" Target="{target}"/>')

    for index, (namespace, tag, body) in enumerate(ROOTS, 1):
        name = f'customXml/item{index}.xml'
        prefix = '<?mso-contentType ?>' if index == 1 else ''
        parts[name] = f'{prefix}<m:{tag} xmlns:m="{namespace}">{body}</m:{tag}>'.encode()
        props = f'customXml/itemProps{index}.xml'
        parts[props] = f'<ds:datastoreItem xmlns:ds="{CUSTOM}" ds:itemID="{{00000000-0000-0000-0000-{index:012d}}}"><ds:schemaRefs><ds:schemaRef ds:uri="{namespace}"/></ds:schemaRefs></ds:datastoreItem>'.encode()
        parts[f'customXml/_rels/item{index}.xml.rels'] = f'<Relationships xmlns="{REL}"><Relationship Id="props{index}" Type="{R}/customXmlProps" Target="itemProps{index}.xml"/></Relationships>'.encode()
        relationship(f'custom{index}', R + '/customXml', '../' + name)
        content_type(name, 'application/xml')
        content_type(props, 'application/vnd.openxmlformats-officedocument.customXmlProperties+xml')
    parts['word/stylesWithEffects.xml'] = parts['word/styles.xml']
    relationship('effects233', 'http://schemas.microsoft.com/office/2007/relationships/stylesWithEffects', 'stylesWithEffects.xml')
    content_type('word/stylesWithEffects.xml', 'application/vnd.ms-word.stylesWithEffects+xml')
    key = '11111111-2222-3333-4444-555555555555'
    embedded = bytearray(font.read_bytes())
    mask = bytes.fromhex(key.replace('-', ''))[::-1]
    for index in range(32):
        embedded[index] ^= mask[index % 16]
    parts['word/fonts/fixture.odttf'] = bytes(embedded)
    parts['word/fontTable.xml'] = f'<w:fonts xmlns:w="{W}" xmlns:r="{R}"><w:font w:name="Liberation Sans"><w:embedRegular r:id="font233" w:fontKey="{{{key}}}" w:subsetted="0"/></w:font></w:fonts>'.encode()
    parts['word/_rels/fontTable.xml.rels'] = f'<Relationships xmlns="{REL}"><Relationship Id="font233" Type="{R}/font" Target="fonts/fixture.odttf"/></Relationships>'.encode()
    relationship('fontTable233', R + '/fontTable', 'fontTable.xml')
    content_type('word/fontTable.xml', 'application/vnd.openxmlformats-officedocument.wordprocessingml.fontTable+xml')
    content_type('word/fonts/fixture.odttf', 'application/vnd.openxmlformats-officedocument.obfuscatedFont')
    with zipfile.ZipFile(theme_source) as archive:
        theme = ET.fromstring(archive.read('word/theme/theme1.xml'))
    fills = theme.find(f'{{{A}}}themeElements/{{{A}}}fmtScheme/{{{A}}}fillStyleLst')
    if fills is None:
        raise ValueError('Theme matrix missing')
    fills.remove(fills[0])
    picture = ET.fromstring(f'<a:blipFill xmlns:a="{A}" xmlns:r="{R}"><a:blip r:embed="theme233"/><a:stretch><a:fillRect/></a:stretch></a:blipFill>')
    fills.insert(0, picture)
    ET.register_namespace('a', A)
    ET.register_namespace('r', R)
    parts['word/theme/theme1.xml'] = ET.tostring(theme, encoding='utf-8')

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)

    parts['word/media/theme233.png'] = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', 1, 1, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\0\0\x8b\x90')) + chunk(b'IEND', b'')
    parts['word/theme/_rels/theme1.xml.rels'] = f'<Relationships xmlns="{REL}"><Relationship Id="theme233" Type="{R}/image" Target="../media/theme233.png"/></Relationships>'.encode()
    relationship('theme233', R + '/theme', 'theme/theme1.xml')
    content_type('word/theme/theme1.xml', 'application/vnd.openxmlformats-officedocument.theme+xml')
    content_type('word/media/theme233.png', 'image/png')
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as archive:
        for name, data in parts.items():
            entry = zipfile.ZipInfo(name, date_time=(2025, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ['base', 'theme-source', 'font', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    build(args.base, args.theme_source, args.font, args.output)
