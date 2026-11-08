"""Build synthetic mixed-size physical-line references; never replace an existing fixture."""
from argparse import ArgumentParser
from html import escape
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def run(text, size):
    return ('<w:r><w:rPr><w:sz w:val="' + str(size * 2) + '"/></w:rPr>'
            '<w:t xml:space="preserve">' + escape(text) + '</w:t></w:r>')


def build():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    for name in ['a4', 'letter']:
        with ZipFile(args.base / name / 'source.docx') as archive:
            parts = {part: archive.read(part) for part in archive.namelist()}
        original = parts['word/document.xml'].decode()
        section = original[original.index('<w:sectPr>'):original.index('</w:sectPr>') + len('</w:sectPr>')]
        paragraphs = []
        for index in range(6):
            spacing = ('<w:spacing w:before="0" w:after="120" w:line="280" w:lineRule="atLeast"/>' if index % 3 == 2
                       else '<w:spacing w:before="0" w:after="120" w:line="' + ('360' if index % 3 else '240') + '" w:lineRule="auto"/>')
            properties = '<w:pPr><w:textAlignment w:val="baseline"/>' + spacing + '<w:ind w:left="360" w:right="4000"/>'
            if index == 3:
                properties += '<w:pageBreakBefore/>'
            properties += '</w:pPr>'
            content = (run('M' + str(index + 1) + ' Small words fill the first line. ', 10)
                       + run('LARGE ', 20) + run('tiny ', 8)
                       + run('More small words wrap after the large word and retain their own natural height. ', 10)
                       + run('MEDIUM ', 16) + run('Final small words close the paragraph.', 10))
            paragraphs.append('<w:p>' + properties + content + '</w:p>')
        prefix = original[:original.index('<w:body>')]
        parts['word/document.xml'] = (prefix + '<w:body>' + ''.join(paragraphs) + section + '</w:body></w:document>').encode()
        directory = args.output / name
        directory.mkdir()
        with ZipFile(directory / 'source.docx', 'x', ZIP_DEFLATED) as archive:
            for part, data in parts.items():
                info = ZipInfo(part, (2025, 1, 1, 0, 0, 0))
                info.compress_type = ZIP_DEFLATED
                archive.writestr(info, data)


if __name__ == '__main__':
    build()
