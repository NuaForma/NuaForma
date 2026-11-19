"""Build inactive Word anchor metadata controls without changing printed objects."""
import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/pdf-evaluation/own-engine'))
from build_line_fixture import drawing as drawing_line

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
WP = 'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'
WP14 = 'http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing'
A = 'http://schemas.openxmlformats.org/drawingml/2006/main'
WPS = 'http://schemas.microsoft.com/office/word/2010/wordprocessingShape'


def build(output):
    output.mkdir(parents=True, exist_ok=False)
    for paper in ['a4', 'letter']:
        for kind in ['frame', 'image', 'line']:
            fixture = 'image-body-behind' if kind == 'image' else 'control'
            source = ROOT / f'nuaforma-docx-pdf/src/test/resources/floating-drawings/{fixture}/{paper}/source.docx'
            with ZipFile(source) as archive:
                original = {name: archive.read(name) for name in archive.namelist()}
            for mode in ['control', 'hidden-zero', 'hidden-false', 'relative-size-zero', 'inactive-simple-position', 'combined']:
                parts = original.copy()
                document = ET.fromstring(parts['word/document.xml'])
                body = document.find(f'{{{W}}}body')
                paragraph = body.find(f'{{{W}}}p')
                if kind == 'line':
                    previous = paragraph.find(f'{{{W}}}r')
                    wrapper = ET.fromstring(f'<root xmlns:w="{W}" xmlns:wp="{WP}" xmlns:a="{A}" xmlns:wps="{WPS}">' + drawing_line() + '</root>')
                    paragraph.remove(previous)
                    paragraph.insert(1, wrapper[0])
                anchor = next(n for n in paragraph.iter() if n.tag == f'{{{WP}}}anchor')
                for position, offset in [('positionH', 100), ('positionV', 130)]:
                    element = anchor.find(f'{{{WP}}}{position}')
                    element.set('relativeFrom', 'page')
                    element[0].text = str(offset * 12700)
                if mode in ['hidden-zero', 'hidden-false', 'combined']:
                    anchor.set('hidden', '0' if mode == 'hidden-zero' else 'false')
                if mode in ['relative-size-zero', 'combined']:
                    for suffix, child in [('H', 'pctWidth'), ('V', 'pctHeight')]:
                        parent = ET.SubElement(anchor, f'{{{WP14}}}sizeRel{suffix}', relativeFrom='margin')
                        ET.SubElement(parent, f'{{{WP14}}}{child}').text = '0'
                if mode in ['inactive-simple-position', 'combined']:
                    anchor.find(f'{{{WP}}}simplePos').attrib.update(x='914400', y='10106025')
                if mode == 'combined':
                    anchor.set(f'{{{WP14}}}anchorId', '0A1B2C3D')
                    anchor.set(f'{{{WP14}}}editId', '00000000')
                parts['word/document.xml'] = ET.tostring(document, encoding='utf-8')
                folder = output / f'{kind}-{mode}' / paper
                folder.mkdir(parents=True)
                with ZipFile(folder / 'source.docx', 'w', ZIP_DEFLATED) as archive:
                    for name, data in parts.items():
                        entry = ZipInfo(name, (2026, 11, 18, 0, 0, 0))
                        entry.create_system = 0
                        entry.compress_type = ZIP_DEFLATED
                        archive.writestr(entry, data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    build(parser.parse_args().output)
