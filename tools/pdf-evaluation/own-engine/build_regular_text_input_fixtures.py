"""Reproduce regular text-form fields and controls, including known negative inputs; export exact-source Word PDFs separately."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import xml.etree.ElementTree as E
import argparse
import copy
W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
P = 'http://schemas.openxmlformats.org/package/2006/relationships'
C = 'http://schemas.openxmlformats.org/package/2006/content-types'
E.register_namespace('w', W)

def q(name):
    return '{' + W + '}' + name

def run(text, props=''):
    return '<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/>' + props + '</w:rPr><w:t xml:space="preserve">' + text + '</w:t></w:r>'

def build(resources, output):
    output.mkdir(parents=True, exist_ok=False)
    for paper in ['a4', 'letter']:
        with ZipFile(resources / 'preferred-table-widths' / paper / 'source.docx') as z:
            base = {n: z.read(n) for n in z.namelist()}
        for kind in ['plain-control', 'regular-cached', 'regular-no-default', 'regular-empty', 'regular-disabled', 'regular-exit', 'regular-short-max', 'regular-mergeformat']:
            parts = base.copy()
            tree = E.fromstring(parts['word/document.xml'])
            body = tree.find(q('body'))
            section = body.find(q('sectPr'))
            for c in list(body):
                body.remove(c)
            enabled = '0' if kind == 'regular-disabled' else '1'
            exit = '1' if kind == 'regular-exit' else '0'
            maximum = '2' if kind == 'regular-short-max' else '100'
            default = '' if kind == 'regular-no-default' else '<w:default w:val="Different default"/>'
            cached = '' if kind == 'regular-empty' else run('Alpha', '<w:b/><w:color w:val="AA0000"/>') + run('Beta', '<w:i/><w:color w:val="0000AA"/>')
            controls = '<w:r><w:fldChar w:fldCharType="begin"><w:ffData><w:name w:val="SyntheticText"/><w:enabled w:val="' + enabled + '"/><w:calcOnExit w:val="' + exit + '"/><w:textInput><w:type w:val="regular"/>' + default + '<w:maxLength w:val="' + maximum + '"/></w:textInput></w:ffData></w:fldChar></w:r><w:r><w:instrText xml:space="preserve"> FORMTEXT ' + ('\\* MERGEFORMAT ' if kind == 'regular-mergeformat' else '') + '</w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>' + cached + '<w:r><w:fldChar w:fldCharType="end"/></w:r>'
            p = E.fromstring('<w:p xmlns:w="' + W + '"><w:pPr><w:spacing w:line="400" w:lineRule="exact" w:before="0" w:after="0"/></w:pPr>' + run('F00_BEFORE ') + (cached if kind == 'plain-control' else controls) + run(' F01_AFTER') + '</w:p>')
            body.append(p)
            body.append(section)
            parts['word/document.xml'] = E.tostring(tree, encoding='utf-8')
            dest = output / 'body' / kind / paper
            dest.mkdir(parents=True, exist_ok=False)
            with ZipFile(dest / 'source.docx', 'x', ZIP_DEFLATED) as z:
                for name, data in parts.items():
                    e = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                    e.create_system = 0
                    e.compress_type = ZIP_DEFLATED
                    z.writestr(e, data)
    for paper in ['a4', 'letter']:
        with ZipFile(output / 'body/regular-cached' / paper / 'source.docx') as z:
            base = {n: z.read(n) for n in z.namelist()}
        for kind in ['uppercase-format', 'five-en-spaces', 'five-en-control', 'absent-type-empty-format']:
            parts = base.copy()
            tree = E.fromstring(parts['word/document.xml'])
            field = tree.find('.//' + q('textInput'))
            if kind == 'uppercase-format':
                E.SubElement(field, q('format'), {q('val'): 'Uppercase'})
            if kind == 'absent-type-empty-format':
                field.remove(field.find(q('type')))
                E.SubElement(field, q('format'), {q('val'): ''})
            if kind.startswith('five-en'):
                t = next((n for n in tree.iter(q('t')) if n.text == 'Alpha'))
                t.text = '\u2002' * 5
                for p in tree.iter(q('p')):
                    for n in list(p):
                        if n.tag == q('r') and ''.join((c.text or '' for c in n.iter(q('t')))) == 'Beta':
                            p.remove(n)
                        elif kind == 'five-en-control' and n.tag == q('r') and (n.find(q('fldChar')) is not None or n.find(q('instrText')) is not None):
                            p.remove(n)
            parts['word/document.xml'] = E.tostring(tree, encoding='utf-8')
            dest = output / 'additional' / kind / paper
            dest.mkdir(parents=True, exist_ok=False)
            with ZipFile(dest / 'source.docx', 'x', ZIP_DEFLATED) as z:
                for name, data in parts.items():
                    e = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                    e.create_system = 0
                    e.compress_type = ZIP_DEFLATED
                    z.writestr(e, data)
    for paper in ['a4', 'letter']:
        with ZipFile(output / 'body/regular-cached' / paper / 'source.docx') as archive:
            parts = {name: archive.read(name) for name in archive.namelist()}
        document = E.fromstring(parts['word/document.xml'])
        document.find('.//' + q('textInput') + '/' + q('default')).set(q('val'), 'Не выводить\u2002{missing')
        parts['word/document.xml'] = E.tostring(document, encoding='utf-8')
        target = output / 'metadata/metadata-only' / paper
        target.mkdir(parents=True, exist_ok=False)
        with ZipFile(target / 'source.docx', 'x', ZIP_DEFLATED) as archive:
            for name, content in parts.items():
                entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                entry.create_system = 0
                entry.compress_type = ZIP_DEFLATED
                archive.writestr(entry, content)
    for paper in ['a4', 'letter']:
        with ZipFile(output / 'body/regular-cached' / paper / 'source.docx') as archive:
            basis = {name: archive.read(name) for name in archive.namelist()}
        for kind in ['uppercase-spelling', 'empty-editing-name', 'both']:
            parts = dict(basis)
            document = E.fromstring(parts['word/document.xml'])
            if kind != 'empty-editing-name':
                E.SubElement(document.find('.//' + q('textInput')), q('format'), {q('val'): 'UPPERCASE'})
            if kind != 'uppercase-spelling':
                document.find('.//' + q('ffData') + '/' + q('name')).set(q('val'), '')
            parts['word/document.xml'] = E.tostring(document, encoding='utf-8')
            target = output / 'metadata-variants' / kind / paper
            target.mkdir(parents=True, exist_ok=False)
            with ZipFile(target / 'source.docx', 'x', ZIP_DEFLATED) as archive:
                for name, data in parts.items():
                    entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                    entry.create_system = 0
                    entry.compress_type = ZIP_DEFLATED
                    archive.writestr(entry, data)
    E.register_namespace('r', R)
    for paper in ['a4', 'letter']:
        for kind in ['field', 'control']:
            basis = 'regular-cached' if kind == 'field' else 'plain-control'
            with ZipFile(output / 'body' / basis / paper / 'source.docx') as z:
                parts = {n: z.read(n) for n in z.namelist()}
            doc = E.fromstring(parts['word/document.xml'])
            body = doc.find(q('body'))
            first = body.find(q('p'))
            second = copy.deepcopy(first)
            pr = second.find(q('pPr'))
            pr.insert(0, E.Element(q('pageBreakBefore')))
            for node in second.iter(q('t')):
                if node.text:
                    node.text = node.text.replace('F00', 'F02').replace('F01', 'F03')
            body.insert(1, second)
            section = body.find(q('sectPr'))
            section.find(q('pgMar')).set(q('header'), '720')
            section.find(q('pgMar')).set(q('footer'), '720')
            for label, part, prefix in [('header', 'header1.xml', 'H'), ('footer', 'footer1.xml', 'G')]:
                band = E.Element(q('hdr' if label == 'header' else 'ftr'))
                paragraph = copy.deepcopy(first)
                for node in paragraph.iter(q('t')):
                    if node.text:
                        node.text = node.text.replace('F00', prefix + '00').replace('F01', prefix + '01')
                band.append(paragraph)
                parts['word/' + part] = E.tostring(band, encoding='utf-8')
                if paper == 'a4':
                    E.SubElement(section, q(label + 'Reference'), {q('type'): 'default', '{' + R + '}id': label})
            if paper == 'a4':
                refs = [node for node in section if node.tag in [q('headerReference'), q('footerReference')]]
                for node in refs:
                    section.remove(node)
                refs.sort(key=lambda node: 0 if node.tag == q('headerReference') else 1)
                for index, node in enumerate(refs):
                    section.insert(index, node)
                rels = E.fromstring(parts['word/_rels/document.xml.rels'])
                types = E.fromstring(parts['[Content_Types].xml'])
                for label in ['header', 'footer']:
                    E.SubElement(rels, '{' + P + '}Relationship', {'Id': label, 'Type': R + '/' + label, 'Target': label + '1.xml'})
                    E.SubElement(types, '{' + C + '}Override', {'PartName': '/word/' + label + '1.xml', 'ContentType': 'application/vnd.openxmlformats-officedocument.wordprocessingml.' + label + '+xml'})
                parts['word/_rels/document.xml.rels'] = E.tostring(rels, encoding='utf-8')
                parts['[Content_Types].xml'] = E.tostring(types, encoding='utf-8')
            parts['word/document.xml'] = E.tostring(doc, encoding='utf-8')
            dest = output / 'bands' / kind / paper
            dest.mkdir(parents=True, exist_ok=False)
            with ZipFile(dest / 'source.docx', 'x', ZIP_DEFLATED) as z:
                for name, data in parts.items():
                    e = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                    e.create_system = 0
                    e.compress_type = ZIP_DEFLATED
                    z.writestr(e, data)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.base, args.output)
if __name__ == '__main__':
    main()
