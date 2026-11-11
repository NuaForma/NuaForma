"""Build bounded Word cell-pagination probes without changing reference output."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import xml.etree.ElementTree as E

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
E.register_namespace('w', W)


def paragraph(text, rule='', after=0):
    words = ' '.join(part+'_abc' for part in text.split('|'))
    return '<w:p><w:pPr><w:widowControl w:val="0"/>'+rule+'<w:spacing w:before="0" w:after="'+str(after)+'" w:line="400" w:lineRule="exact"/></w:pPr><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr><w:t>'+words+'</w:t></w:r></w:p>'


def build(base, output):
    q = lambda name: '{'+W+'}'+name
    rules = {'plain': '', 'widows': '<w:widowControl/>', 'keep-lines': '<w:keepLines/>', 'keep-next': '<w:keepNext/>'}
    for paper in ['a4', 'letter']:
        with ZipFile(base/'preferred-table-widths'/paper/'source.docx') as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        for kind, rule in rules.items():
            for remaining in [30, 110]:
                parts = dict(original)
                document = E.fromstring(parts['word/document.xml'])
                body = document.find(q('body'))
                section = body.find(q('sectPr'))
                size = section.find(q('pgSz'))
                margins = section.find(q('pgMar'))
                usable = int(size.get(q('h'))) - int(margins.get(q('top'))) - int(margins.get(q('bottom')))
                for node in list(body):
                    body.remove(node)
                first = paragraph('|'.join('T'+str(index) for index in range(6)), rule)
                if kind == 'widows':
                    first = first.replace('<w:widowControl w:val="0"/>', '')
                first += paragraph('T6|T7')
                table = '<w:tbl><w:tblPr><w:tblW w:w="1000" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="0" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="0" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="1000"/></w:tblGrid><w:tr><w:trPr/><w:tc><w:tcPr><w:tcW w:w="1000" w:type="dxa"/></w:tcPr>'+first+'</w:tc></w:tr></w:tbl>'
                content = E.fromstring('<w:body xmlns:w="'+W+'">'+paragraph('BEFORE', after=usable-400-remaining*20)+table+paragraph('AFTER')+'</w:body>')
                for node in content:
                    body.append(node)
                body.append(section)
                parts['word/document.xml'] = E.tostring(document, encoding='utf-8')
                target = output/(kind+'-'+str(remaining))/paper
                target.mkdir(parents=True, exist_ok=False)
                with ZipFile(target/'source.docx', 'x', ZIP_DEFLATED) as archive:
                    for name, data in parts.items():
                        archive.writestr(name, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.base, args.output)


if __name__ == '__main__':
    main()
