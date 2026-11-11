"""Reproduce synthetic SEQ style cases and independent literal controls; export exact-source Word references separately."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import argparse
import xml.etree.ElementTree as E

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
E.register_namespace('w', W)
KINDS = ['complex-default', 'complex-merge', 'complex-charformat', 'complex-split', 'complex-merge-split',
         'complex-auto', 'complex-merge-auto', 'simple', 'simple-merge']
CONTROLS = [('complex-default', 14, '00AA00'), ('complex-merge', 10, '0000AA'), ('complex-auto', 14, '00AA00'),
            ('complex-merge-auto', 10, '0000AA'), ('simple', 11, '000000')]

def q(name):
    return '{' + W + '}' + name

def properties(size, color):
    return '<w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:color w:val="' + color + '"/><w:sz w:val="' + str(size * 2) + '"/></w:rPr>'

def run(text, size=10, color='000000'):
    return '<w:r>' + properties(size, color) + '<w:t xml:space="preserve">' + text + '</w:t></w:r>'

def write(parts, target):
    target.mkdir(parents=True, exist_ok=False)
    with ZipFile(target / 'source.docx', 'x', ZIP_DEFLATED) as archive:
        for name, data in parts.items():
            entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
            entry.create_system = 0
            entry.compress_type = ZIP_DEFLATED
            archive.writestr(entry, data)

def build(base, output):
    output.mkdir(parents=True, exist_ok=False)
    for paper in ['a4', 'letter']:
        with ZipFile(base / 'preferred-table-widths' / paper / 'source.docx') as archive:
            basis = {name: archive.read(name) for name in archive.namelist()}
        for kind in KINDS:
            parts = dict(basis)
            document = E.fromstring(parts['word/document.xml'])
            body = document.find(q('body'))
            section = body.find(q('sectPr'))
            for node in list(body):
                body.remove(node)
            instruction = ' SEQ StyleProbe ' + ('\\* MERGEFORMAT ' if 'merge' in kind else '\\* CHARFORMAT ' if kind == 'complex-charformat' else '')
            if kind.startswith('simple'):
                field = '<w:fldSimple w:instr="' + instruction + '">' + run('999', 10, '0000AA') + '</w:fldSimple>'
            else:
                field = '<w:r>' + properties(18, 'AA0000') + '<w:fldChar w:fldCharType="begin"/></w:r><w:r>' + properties(14, '00AA00') + '<w:instrText xml:space="preserve">' + instruction + '</w:instrText></w:r><w:r>' + properties(16, 'AA00AA') + '<w:fldChar w:fldCharType="separate"/></w:r>' + run('999', 10, '0000AA') + '<w:r>' + properties(12, '000000') + '<w:fldChar w:fldCharType="end"/></w:r>'
            if 'split' in kind:
                field = field.replace('<w:r>' + properties(14, '00AA00') + '<w:instrText', '<w:r>' + properties(18, 'AA0000') + '<w:instrText xml:space="preserve"> </w:instrText></w:r><w:r>' + properties(14, '00AA00') + '<w:instrText')
            spacing = 'w:line="240" w:lineRule="auto"' if 'auto' in kind else 'w:line="400" w:lineRule="exact"'
            paragraph = '<w:p xmlns:w="' + W + '"><w:pPr><w:spacing w:before="0" w:after="0" ' + spacing + '/>' + properties(10, '000000') + '</w:pPr>' + run('F00_BEFORE ') + field + run(' F01_AFTER') + '</w:p>'
            body.append(E.fromstring(paragraph))
            body.append(section)
            parts['word/document.xml'] = E.tostring(document, encoding='utf-8')
            write(parts, output / 'fields' / kind / paper)
        for kind, size, color in CONTROLS:
            with ZipFile(output / 'fields' / kind / paper / 'source.docx') as archive:
                parts = {name: archive.read(name) for name in archive.namelist()}
            document = E.fromstring(parts['word/document.xml'])
            paragraph = document.find('.//' + q('body') + '/' + q('p'))
            for node in list(paragraph)[2:-1]:
                paragraph.remove(node)
            literal = E.Element(q('r'))
            props = E.SubElement(literal, q('rPr'))
            E.SubElement(props, q('rFonts'), {q('ascii'): 'Liberation Sans', q('hAnsi'): 'Liberation Sans'})
            E.SubElement(props, q('color'), {q('val'): color})
            E.SubElement(props, q('sz'), {q('val'): str(size * 2)})
            E.SubElement(literal, q('t')).text = '1'
            paragraph.insert(2, literal)
            parts['word/document.xml'] = E.tostring(document, encoding='utf-8')
            write(parts, output / 'controls' / kind / paper)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.base, args.output)

if __name__ == '__main__':
    main()
