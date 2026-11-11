"""Build bounded initial-section and form-protection sources from reviewed color fixtures."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def archive(output, name, parts):
    directory = output / name
    directory.mkdir(parents=True, exist_ok=False)
    with ZipFile(directory / 'source.docx', 'x', ZIP_DEFLATED) as target:
        for part, data in parts.items():
            item = ZipInfo(part, (2025, 1, 1, 0, 0, 0))
            item.create_system = 0
            item.compress_type = ZIP_DEFLATED
            target.writestr(item, data)


def source(base, paper):
    with ZipFile(base / 'theme-colors' / paper / 'source.docx') as package:
        return {name: package.read(name) for name in package.namelist()}


def paragraph(text, section=''):
    return '<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="400" w:lineRule="exact"/>' + section + '</w:pPr><w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="24"/></w:rPr><w:t>' + text + '</w:t></w:r></w:p>'


def build(base, output):
    for paper in ['a4', 'letter']:
        parts = source(base, paper)
        document = parts['word/document.xml'].decode()
        assert document.count('<w:sectPr>') == 1
        document = document.replace('<w:sectPr>', '<w:sectPr><w:type w:val="continuous"/>' + ('<w:formProt/>' if paper == 'letter' else ''))
        parts['word/document.xml'] = document.encode()
        if paper == 'letter':
            parts['word/settings.xml'] = parts['word/settings.xml'].decode().replace('</w:settings>', '<w:documentProtection w:edit="forms" w:enforcement="1"/></w:settings>').encode()
        archive(output, 'single-' + paper, parts)

    parts = source(base, 'a4')
    document = parts['word/document.xml'].decode()
    prefix = document.split('<w:body>')[0]
    original = '<w:sectPr>' + document.split('<w:sectPr>')[1].split('</w:sectPr>')[0] + '</w:sectPr>'
    landscape = original.replace('w:w="11906" w:h="16838"', 'w:w="15840" w:h="12240" w:orient="landscape"')
    assert landscape != original
    first = original.replace('<w:sectPr>', '<w:sectPr><w:type w:val="continuous"/><w:formProt w:val="false"/>')
    second = landscape.replace('<w:sectPr>', '<w:sectPr><w:type w:val="nextPage"/><w:formProt w:val="0"/>')
    body = paragraph('Q01 First A4 section begins here.') + paragraph('Q02 First section ends here.', first)
    body += paragraph('Q03 Second Letter landscape section begins here.') + paragraph('Q04 Second section ends here.')
    parts['word/document.xml'] = (prefix + '<w:body>' + body + second + '</w:body></w:document>').encode()
    parts['word/settings.xml'] = parts['word/settings.xml'].decode().replace('</w:settings>', '<w:documentProtection w:edit="forms" w:enforcement="1"/></w:settings>').encode()
    archive(output, 'multi-initial', parts)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    options = parser.parse_args()
    build(options.base, options.output)
