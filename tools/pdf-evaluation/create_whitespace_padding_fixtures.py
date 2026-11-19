"""Build inert Word controls for XML edge spaces and inline image padding."""
import argparse
import copy
from pathlib import Path
from xml.sax.saxutils import escape
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from create_tab_collision_fixtures import parts as base_parts, W, R
from create_paragraph_formatting_fixtures import paragraph
from create_text_background_fixtures import run


def text(value, mode, properties=''):
    if mode == 'trimmed':
        value = value.strip(' \t\r\n')
        mode = 'preserve'
    attr = '' if mode == 'missing' else f' xml:space="{mode}"'
    return f'<w:r><w:rPr>{properties}</w:rPr><w:t{attr}>{escape(value)}</w:t></w:r>'


def whitespace(mode):
    body = ''
    for i, value in enumerate([' leading', 'trailing ', ' both ', '  multiple  ', ' ', '   ', 'internal  multiple', ' a  b ']):
        body += paragraph(run(f'A{i:02}_START') + text(value, mode) + run(f'A{i:02}_END'))
    body += paragraph(run('F00_START') + text(' styled ', mode, '<w:b/><w:u w:val="single"/>') + run('F00_END'))
    body += paragraph(run('W00_BEGIN ') + text('word ' * 70, mode) + run('W00_END'))
    body += paragraph(run('E00_BEFORE') + text('   ', mode, '<w:sz w:val="60"/>') + run('E00_AFTER'), line=240, rule='auto')
    body += paragraph(run('E01_NEXT'), line=240, rule='auto')
    return body


def image_source():
    root = Path(__file__).resolve().parents[2]
    fixture = root/'nuaforma-docx-pdf/src/test/resources/passive-print-metadata/image-save-dpi/control/a4/source.docx'
    with ZipFile(fixture) as archive:
        source = ET.fromstring(archive.read('word/document.xml'))
        image = archive.read('word/media/image1.png')
        drawing = copy.deepcopy(next(e for e in source.iter() if e.tag == f'{{{W}}}drawing'))
        for element in drawing.iter():
            if element.tag.endswith('}blip'):
                element.attrib[f'{{{R}}}embed'] = 'image'
        return ET.tostring(drawing, encoding='unicode'), image


def picture(drawing, effect, automatic):
    node = ET.fromstring(drawing)
    for element in node.iter():
        if element.tag.endswith('}effectExtent'):
            element.attrib.update(effect)
    return paragraph('<w:r>' + ET.tostring(node, encoding='unicode') + '</w:r>',
                     line=240 if automatic else 900, rule='auto' if automatic else 'exact')


def parts(paper, variant, drawing, image):
    result = base_parts(paper, '')
    if variant.startswith('effect-'):
        effects = {'effect-zero': {}, 'effect-small': {'r': '7620', 'b': '9525'},
                   'effect-right-bottom': {'r': '254000', 'b': '254000'},
                   'effect-left-top': {'l': '254000', 't': '254000'},
                   'effect-all': {n: '254000' for n in ['l', 't', 'r', 'b']}}
        chosen = effects.get(variant, {n: '12700' for n in ['l', 't', 'r', 'b']})
        if variant in ['effect-table', 'effect-cell-exact']:
            if variant == 'effect-cell-exact':
                chosen = effects['effect-all']
            margins = '<w:tblCellMar>' + ''.join(f'<w:{side} w:w="80" w:type="dxa"/>' for side in ['top', 'left', 'bottom', 'right']) + '</w:tblCellMar>'
            body = paragraph(run('T00_BEFORE')) + '<w:tbl><w:tblPr><w:tblW w:w="7200" w:type="dxa"/><w:tblLayout w:type="fixed"/>' + margins + '</w:tblPr><w:tblGrid><w:gridCol w:w="7200"/></w:tblGrid>'
            if variant == 'effect-cell-exact':
                body += '<w:tr><w:tc><w:tcPr><w:tcW w:w="7200" w:type="dxa"/></w:tcPr>' + paragraph(run('T01_PRIOR')) + '</w:tc></w:tr>'
            body += '<w:tr><w:tc><w:tcPr><w:tcW w:w="7200" w:type="dxa"/></w:tcPr>' + picture(drawing, chosen, variant == 'effect-table') + paragraph(run('T02_CELL')) + '</w:tc></w:tr></w:tbl>' + paragraph(run('T03_END'))
        elif variant == 'effect-bands':
            body = paragraph(run('G00_BODY'))
        else:
            body = paragraph(run('I00_BEFORE')) + picture(drawing, chosen, True) + paragraph(run('I01_AFTER'))
            body += picture(drawing, chosen, False) + paragraph(run('I02_END'))
        result['word/media/image1.png'] = image
        result['[Content_Types].xml'] = result['[Content_Types].xml'].replace('</Types>', '<Default Extension="png" ContentType="image/png"/></Types>')
        result['word/_rels/document.xml.rels'] = result['word/_rels/document.xml.rels'].replace('</Relationships>', f'<Relationship Id="image" Type="{R}/image" Target="media/image1.png"/></Relationships>')
    else:
        body = whitespace(variant)
    section = result['word/document.xml'].split('<w:sectPr>')[1].split('</w:sectPr>')[0]
    if variant == 'effect-bands':
        for name, kind in [('header1.xml', 'hdr'), ('footer1.xml', 'ftr')]:
            result[f'word/{name}'] = f'<w:{kind} xmlns:w="{W}">{picture(drawing, {n: "12700" for n in ["l", "t", "r", "b"]}, True)}</w:{kind}>'
            result[f'word/_rels/{name}.rels'] = f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="image" Type="{R}/image" Target="media/image1.png"/></Relationships>'
            band = 'header' if kind == 'hdr' else 'footer'
            result['[Content_Types].xml'] = result['[Content_Types].xml'].replace('</Types>', f'<Override PartName="/word/{name}" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.{band}+xml"/></Types>')
            result['word/_rels/document.xml.rels'] = result['word/_rels/document.xml.rels'].replace('</Relationships>', f'<Relationship Id="{band}" Type="{R}/{band}" Target="{name}"/></Relationships>')
        section = '<w:headerReference w:type="default" r:id="header"/><w:footerReference w:type="default" r:id="footer"/>' + section
        section = section.replace('w:top="1134"', 'w:top="1440"').replace('w:bottom="1134"', 'w:bottom="1440"').replace('<w:pgMar ', '<w:pgMar w:header="360" w:footer="360" ')
    result['word/document.xml'] = f'<w:document xmlns:w="{W}"' + (f' xmlns:r="{R}"' if variant == 'effect-bands' else '') + f'><w:body>{body}<w:sectPr>{section}</w:sectPr></w:body></w:document>'
    return result


def build(output):
    output.mkdir(parents=True, exist_ok=False)
    drawing, image = image_source()
    for variant in ['missing', 'default', 'preserve', 'trimmed', 'effect-zero', 'effect-small', 'effect-right-bottom', 'effect-left-top', 'effect-all', 'effect-table', 'effect-cell-exact', 'effect-bands']:
        for paper in ['a4', 'letter']:
            folder = output/variant/paper
            folder.mkdir(parents=True)
            with ZipFile(folder/'source.docx', 'x') as archive:
                for name, value in sorted(parts(paper, variant, drawing, image).items()):
                    info = ZipInfo(name, (2026, 11, 18, 0, 0, 0))
                    info.create_system = 0
                    info.compress_type = ZIP_DEFLATED
                    archive.writestr(info, value if isinstance(value, bytes) else value.encode())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    build(parser.parse_args().output)
