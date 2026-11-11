"""Build synthetic case-insensitive Word SEQ probes without updating references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import xml.etree.ElementTree as E

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
E.register_namespace('w', W)


def build(base, output):
    q = lambda name: '{'+W+'}'+name
    properties = '<w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr>'

    def run(text):
        return '<w:r>'+properties+'<w:t xml:space="preserve">'+text+'</w:t></w:r>'

    for paper in ['a4', 'letter']:
        with ZipFile(base/'preferred-table-widths'/paper/'source.docx') as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        for kind in ['mixed', 'canonical']:
            parts = dict(original)
            document = E.fromstring(parts['word/document.xml'])
            body = document.find(q('body'))
            section = body.find(q('sectPr'))
            for node in list(body):
                body.remove(node)
            for index, (name, switch) in enumerate([('Probe', ''), ('probe', ''), ('PROBE', '\\c'), ('probe', '\\r 7'), ('Probe', '\\n')]):
                if kind == 'canonical':
                    name = 'Probe'
                code = ' SEQ '+name+' '+switch+' '
                field = '<w:r>'+properties+'<w:fldChar w:fldCharType="begin"/></w:r><w:r>'+properties+'<w:instrText xml:space="preserve">'+code+'</w:instrText></w:r><w:r>'+properties+'<w:fldChar w:fldCharType="separate"/></w:r>'+run('999')+'<w:r>'+properties+'<w:fldChar w:fldCharType="end"/></w:r>'
                paragraph = '<w:p xmlns:w="'+W+'"><w:pPr><w:spacing w:before="0" w:after="0" w:line="400" w:lineRule="exact"/></w:pPr>'+run('C'+str(index)+'_BEFORE ')+field+run(' C'+str(index)+'_AFTER')+'</w:p>'
                body.append(E.fromstring(paragraph))
            body.append(section)
            parts['word/document.xml'] = E.tostring(document, encoding='utf-8')
            target = output/kind/paper
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
