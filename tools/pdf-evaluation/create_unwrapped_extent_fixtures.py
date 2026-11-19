"""Build non-wrapping object extents and unrotated Office frame controls."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[2]
W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
WP = 'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'
WPS = 'http://schemas.microsoft.com/office/word/2010/wordprocessingShape'
A = 'http://schemas.openxmlformats.org/drawingml/2006/main'


def build(output):
    output.mkdir(parents=True, exist_ok=False)
    for paper in ['a4', 'letter']:
        for kind in ['frame', 'image', 'line']:
            source = ROOT / f'nuaforma-docx-pdf/src/test/resources/floating-metadata/{kind}-control/{paper}/source.docx'
            with ZipFile(source) as archive:
                original = {name: archive.read(name) for name in archive.namelist()}
            for mode, padding in [('control', 0), ('small', 9525), ('large', 127000), ('office', 9525)]:
                parts = original.copy()
                document = ET.fromstring(parts['word/document.xml'])
                anchor = next(n for n in document.iter() if n.tag == f'{{{WP}}}anchor')
                effect = anchor.find(f'{{{WP}}}effectExtent')
                effect.attrib.update({side: str(padding * (i + 1 if mode == 'large' else 1)) for i, side in enumerate(['l', 't', 'r', 'b'])})
                if kind == 'frame':
                    content = next(n for n in anchor.iter() if n.tag == f'{{{W}}}txbxContent')
                    text = next(content.iter(f'{{{W}}}t'), None)
                    if text is None:
                        paragraph = next(content.iter(f'{{{W}}}p'))
                        run = ET.SubElement(paragraph, f'{{{W}}}r')
                        text = ET.SubElement(run, f'{{{W}}}t')
                    text.text = 'B00_INSIDE'
                    if mode == 'office':
                        body = next(anchor.iter(f'{{{WPS}}}bodyPr'))
                        body.attrib.update(anchorCtr='0', upright='1')
                        line = next(anchor.iter(f'{{{A}}}ln'))
                        ET.SubElement(line, f'{{{A}}}miter', lim='800000')
                        for name in ['headEnd', 'tailEnd']:
                            ET.SubElement(line, f'{{{A}}}{name}', type='none', w='med', len='med')
                if mode == 'office' and kind != 'frame':
                    anchor.set('hidden', 'false')
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
