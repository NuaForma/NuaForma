"""Reproduce exact synthetic unused Word editing-selection/control sources; export Word PDFs separately."""
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
start = '<w:bookmarkStart w:id="701" w:name="_GoBack"/>'
end = '<w:bookmarkEnd w:id="701"/>'

def run(text):
    return '<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr><w:t xml:space="preserve">' + text + '</w:t></w:r>'

def para(content):
    return '<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="400" w:lineRule="exact"/></w:pPr>' + content + '</w:p>'

def build(resources, output):
    output.mkdir(parents=True, exist_ok=False)
    for paper in ['a4', 'letter']:
        with ZipFile(resources / 'preferred-table-widths' / paper / 'source.docx') as z:
            base = {n: z.read(n) for n in z.namelist()}
        for kind in ['paragraph', 'cross-paragraph', 'body-selection', 'field-instruction', 'field-result', 'table-selection']:
            ordinary = para(run('B00_BEFORE ') + start + run('B01_SELECTED') + end + run(' B02_AFTER'))
            if kind == 'cross-paragraph':
                ordinary = para(run('B00_BEFORE ') + start + run('B01_SELECTED')) + para(run('B02_CONTINUED') + end + run(' B03_AFTER'))
            if kind == 'body-selection':
                ordinary = start + para(run('B00_SELECTED')) + para(run('B01_SELECTED')) + end
            if kind == 'table-selection':
                ordinary = start + '<w:tbl><w:tblPr><w:tblW w:type="dxa" w:w="6000"/><w:tblLayout w:type="fixed"/><w:tblCellMar><w:top w:w="80" w:type="dxa"/><w:left w:w="100" w:type="dxa"/><w:bottom w:w="80" w:type="dxa"/><w:right w:w="100" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="6000"/></w:tblGrid><w:tr><w:trPr><w:cantSplit/></w:trPr><w:tc><w:tcPr><w:tcW w:type="dxa" w:w="6000"/></w:tcPr>' + para(run('B00_SELECTED')) + '</w:tc></w:tr></w:tbl>' + end + para(run('B01_AFTER'))
            if kind.startswith('field-'):
                instruction = '<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr><w:instrText xml:space="preserve"> SEQ Figure </w:instrText></w:r>'
                result = run('1')
                if kind == 'field-instruction':
                    instruction = start + instruction + end
                else:
                    result = start + result + end
                field = '<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr><w:fldChar w:fldCharType="begin"/></w:r>' + instruction + '<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr><w:fldChar w:fldCharType="separate"/></w:r>' + result + '<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr><w:fldChar w:fldCharType="end"/></w:r>'
                ordinary = para(run('B00_BEFORE ') + field + run(' B01_AFTER'))
            for decorated in [False, True]:
                parts = base.copy()
                tree = E.fromstring(parts['word/document.xml'])
                body = tree.find(q('body'))
                section = body.find(q('sectPr'))
                for node in list(body):
                    body.remove(node)
                content = ordinary if decorated else ordinary.replace(start, '').replace(end, '')
                wrapper = E.fromstring('<w:body xmlns:w="' + W + '">' + content + '</w:body>')
                for node in wrapper:
                    body.append(node)
                body.append(section)
                parts['word/document.xml'] = E.tostring(tree, encoding='utf-8')
                dest = output / (kind + ('-selected' if decorated else '-control')) / paper
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
