"""Create deterministic character-spacing controls without replacing any reference."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import xml.etree.ElementTree as ET

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'


def run(text, spacing=None, style=None, bold=False, decorated=False):
    properties = '<w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/>'
    if style:
        properties = '<w:rStyle w:val="'+style+'"/>'+properties
    if spacing is not None:
        properties += '<w:spacing w:val="'+str(spacing)+'"/>'
    if bold:
        properties += '<w:b/>'
    if decorated:
        properties += '<w:u w:val="single"/><w:strike/>'
    return '<w:r><w:rPr>'+properties+'</w:rPr><w:t xml:space="preserve">'+text+'</w:t></w:r>'


def paragraph(content, style=None, right_indent=None, left_to_right=False):
    properties = '<w:pStyle w:val="'+style+'"/>' if style else ''
    if left_to_right:
        properties += '<w:bidi w:val="0"/>'
    if right_indent is not None:
        properties += '<w:ind w:right="'+str(right_indent)+'"/>'
    properties += '<w:spacing w:before="0" w:after="0" w:line="280" w:lineRule="exact"/><w:widowControl w:val="0"/>'
    return '<w:p><w:pPr>'+properties+'</w:pPr>'+content+'</w:p>'


def build(base, output):
    values = [(0,'ABCDEF'),(2,'ABCDEF'),(-2,'ABCDEF'),(20,'ABCDEF'),(-20,'ABCDEF'),(57,'ABCDEF'),(-15,'ABCDEF'),(10,'АБВГДЕ'),(10,'ΑΒΓΔΕΖ'),(20,'A B C')]
    for paper in ['a4','letter']:
        with ZipFile(base/paper/'source.docx') as archive:
            parts = {name:archive.read(name) for name in archive.namelist()}
        original = parts['word/document.xml'].decode()
        prefix = original.split('<w:body>')[0]+'<w:body>'
        suffix = '<w:sectPr>'+original.split('<w:sectPr>')[1]
        section = ET.fromstring(parts['word/document.xml']).find('.//'+W+'sectPr')
        size = section.find(W+'pgSz')
        margins = section.find(W+'pgMar')
        body_width = int(size.get(W+'w'))-int(margins.get(W+'left'))-int(margins.get(W+'right'))
        body = paragraph(run('CHARACTER SPACING '+paper.upper()))
        for index,(spacing,text) in enumerate(values):
            body += paragraph(run('P'+str(index).zfill(2)+' ')+run(text,spacing)+run('TAIL'),left_to_right=index==9)
        body += paragraph(run('P10 ',0)+run('ABC')+run('DEF',0)+run('GHI')+run('TAIL',0),style='TrackedParagraph')
        body += paragraph(run('P11 ')+run('ABC',style='TrackedCharacter')+run('DEF',0,style='TrackedCharacter')+run('TAIL'))
        body += paragraph(run('P12 ')+run('ABCDEF',20,bold=True)+run('TAIL'))
        body += paragraph(run('P13 ')+run('ABC DEF ',10,decorated=True)+run('TAIL'))
        for group,spacing in enumerate([20,-10,40]):
            words = ' '.join('W'+str(group)+str(index).zfill(2) for index in range(12))
            body += paragraph(run(words,spacing),right_indent=body_width-1620)
        body += paragraph(run('END_CHARACTER_SPACING'))
        parts['word/document.xml'] = (prefix+body+suffix).encode()
        styles = parts['word/styles.xml'].decode()
        additions = '<w:style w:type="paragraph" w:styleId="TrackedParagraph"><w:name w:val="Tracked Paragraph"/><w:basedOn w:val="Normal"/><w:rPr><w:spacing w:val="10"/></w:rPr></w:style>'
        additions += '<w:style w:type="character" w:styleId="TrackedCharacter"><w:name w:val="Tracked Character"/><w:rPr><w:spacing w:val="20"/></w:rPr></w:style>'
        assert '</w:styles>' in styles
        for part in ['word/styles.xml','word/stylesWithEffects.xml']:
            if part in parts:
                original_styles = parts[part].decode()
                assert '</w:styles>' in original_styles
                parts[part] = original_styles.replace('</w:styles>',additions+'</w:styles>').encode()
        folder = output/paper
        folder.mkdir(parents=True,exist_ok=False)
        with ZipFile(folder/'source.docx','x',ZIP_DEFLATED) as archive:
            for name,data in parts.items():
                entry = ZipInfo(name,(2025,1,1,0,0,0))
                entry.create_system = 0
                entry.compress_type = ZIP_DEFLATED
                archive.writestr(entry,data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    build(args.base,args.output)
