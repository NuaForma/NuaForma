"""Inert controls for floating drawing layers, ordering and editing metadata."""
import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/pdf-evaluation'))
sys.path.insert(0, str(ROOT / 'tools/pdf-evaluation/own-engine'))
from create_tab_collision_fixtures import parts as base_parts, W, R
from create_paragraph_formatting_fixtures import paragraph
from create_text_background_fixtures import run
from create_whitespace_padding_fixtures import image_source
from build_frame_fixtures import drawing_frame

WP = 'http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing'
A = 'http://schemas.openxmlformats.org/drawingml/2006/main'


def frame(ident, color, order=0, behind=0, x=100, y=130, metadata=False, locked=0, layout=1, distance=0):
    value = drawing_frame('text90', x, y, 180, 40, paragraph(run('')), fill=color, padding=(0, 0, 0, 0))
    value = value.replace('id="12"', f'id="{ident}"').replace('name="text90"', f'name="frame{ident}"')
    for key, before, after in [('relativeHeight', 0, order), ('behindDoc', 0, behind), ('locked', 0, locked), ('layoutInCell', 1, layout), ('distL', 0, distance), ('distR', 0, distance)]:
        value = value.replace(f'{key}="{before}"', f'{key}="{after}"')
    if metadata:
        value = value.replace('<wp:anchor ', '<wp:anchor wp14:anchorId="0A1B2C3D" wp14:editId="DEADBEEF" ')
    return value


def picture(drawing, ident=2, order=5, behind=0, x=100, y=130):
    node = ET.fromstring(drawing)
    anchor = node[0]
    anchor.tag = f'{{{WP}}}anchor'
    anchor.attrib = dict(distT='0', distB='0', distL='0', distR='0', simplePos='0', relativeHeight=str(order), behindDoc=str(behind), locked='0', layoutInCell='1', allowOverlap='1')
    prefix = [ET.Element(f'{{{WP}}}simplePos', x='0', y='0')]
    for name, value in [('positionH', x), ('positionV', y)]:
        position = ET.Element(f'{{{WP}}}{name}', relativeFrom='page')
        ET.SubElement(position, f'{{{WP}}}posOffset').text = str(round(value * 12700))
        prefix.append(position)
    for i, value in enumerate(prefix):
        anchor.insert(i, value)
    anchor.insert(5, ET.Element(f'{{{WP}}}wrapNone'))
    for element in anchor.iter():
        if element.tag == f'{{{WP}}}docPr':
            element.attrib.update(id=str(ident), name=f'picture{ident}')
        if element.tag in [f'{{{WP}}}extent', f'{{{A}}}ext']:
            element.attrib.update(cx=str(180 * 12700), cy=str(40 * 12700))
    return '<w:r>' + ET.tostring(node, encoding='unicode') + '</w:r>'


def build(output):
    output.mkdir(parents=True, exist_ok=False)
    drawing, image = image_source()
    cases = {
        'control': frame(1, 'FF0000'),
        'z-forward': frame(1, 'FF0000', 5) + frame(2, '0000FF', 10),
        'z-reverse': frame(2, '0000FF', 10) + frame(1, 'FF0000', 5),
        'z-red-front': frame(1, 'FF0000', 10) + frame(2, '0000FF', 5),
        'z-equal-forward': frame(1, 'FF0000', 5) + frame(2, '0000FF', 5),
        'z-equal-reverse': frame(2, '0000FF', 5) + frame(1, 'FF0000', 5),
        'tie-ids-swapped': frame(2, 'FF0000', 5) + frame(1, '0000FF', 5),
        'tie-ids-renumbered': frame(20, 'FF0000', 5) + frame(10, '0000FF', 5),
        'tie-maximum': frame(1, 'FF0000', 4294967295) + frame(2, '0000FF', 4294967295),
        'body-behind': frame(1, 'FF0000', 5, behind=1, x=56.7, y=56.7),
        'body-above': frame(1, 'FF0000', 5, x=56.7, y=56.7),
        'metadata-ids': frame(1, 'FF0000', metadata=True),
        'locked': frame(1, 'FF0000', locked=1),
        'layout-outside-cell': frame(1, 'FF0000', layout=0),
        'distance-unwrapped': frame(1, 'FF0000', distance=114300),
        'image-body-behind': picture(drawing, behind=1, x=56.7, y=56.7),
        'image-body-above': picture(drawing, x=56.7, y=56.7),
        'image-over-frame': frame(1, 'FF0000', 5) + picture(drawing, order=10),
        'frame-over-image': frame(1, 'FF0000', 10) + picture(drawing, order=5),
        'image-tie-after': frame(1, 'FF0000', 5) + picture(drawing, ident=2, order=5),
        'frame-tie-after': frame(2, 'FF0000', 5) + picture(drawing, ident=1, order=5),
    }
    for paper in ['a4', 'letter']:
        for variant, objects in cases.items():
            parts = base_parts(paper, '')
            section = parts['word/document.xml'].split('<w:sectPr>')[1].split('</w:sectPr>')[0]
            body = paragraph(objects + run('F00_BODY')) + paragraph(run('F01_NEXT'))
            parts['word/document.xml'] = f'<w:document xmlns:w="{W}" xmlns:wp="{WP}" xmlns:a="{A}" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" xmlns:wp14="http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing"><w:body>{body}<w:sectPr>{section}</w:sectPr></w:body></w:document>'
            if 'image' in variant or variant.startswith('frame-tie'):
                parts['word/media/image1.png'] = image
                parts['[Content_Types].xml'] = parts['[Content_Types].xml'].replace('</Types>', '<Default Extension="png" ContentType="image/png"/></Types>')
                parts['word/_rels/document.xml.rels'] = parts['word/_rels/document.xml.rels'].replace('</Relationships>', f'<Relationship Id="image" Type="{R}/image" Target="media/image1.png"/></Relationships>')
            folder = output / variant / paper
            folder.mkdir(parents=True)
            with ZipFile(folder / 'source.docx', 'w', ZIP_DEFLATED) as archive:
                for name, value in parts.items():
                    info = ZipInfo(name, (2026, 11, 18, 0, 0, 0))
                    info.create_system = 0
                    info.compress_type = ZIP_DEFLATED
                    archive.writestr(info, value.encode() if isinstance(value, str) else value)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    build(parser.parse_args().output)
