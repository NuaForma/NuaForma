"""Build fresh synthetic tab templates and independently expanded Word controls."""
import argparse
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
REL = 'http://schemas.openxmlformats.org/package/2006/relationships'


def run(value='', tab=False):
    return ('<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/>'
            '<w:sz w:val="20"/></w:rPr>' + ('<w:tab/>' if tab else
            '<w:t xml:space="preserve">' + escape(value) + '</w:t>') + '</w:r>')


def paragraph(content, stops='', indent=0):
    indentation = f'<w:ind w:hanging="{-indent}"/>' if indent < 0 else f'<w:ind w:firstLine="{indent}"/>'
    return ('<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="400" w:lineRule="exact"/>'
            '<w:widowControl w:val="0"/>' + indentation +
            ('<w:tabs>' + stops + '</w:tabs>' if stops else '') + '</w:pPr>' + content + '</w:p>')


def parts(paper, value):
    content = paragraph(run('TAB COLLISIONS ' + paper.upper()))
    index = 0
    for kind in ['left', 'right', 'center', 'decimal']:
        for leader in ['none', 'dot', 'hyphen', 'underscore']:
            stops = ''.join(f'<w:tab w:val="{kind}" w:leader="{leader}" w:pos="{pos}"/>' for pos in [1600, 4320])
            content += paragraph(run(f'P{index:02}' + value) + run(tab=True) + run(f'123456,T{index:02}'), stops)
            index += 1
    for kind in ['left', 'right', 'center', 'decimal']:
        stops = f'<w:tab w:val="{kind}" w:pos="{1600}"/>'
        content += paragraph(run(f'P{index:02}' + value) + run(tab=True) + run(f'123456,T{index:02}'), stops)
        index += 1
    for kind, label in [('right', 'H00_VALUE'), ('center', 'H01_VALUEVALUE'), ('decimal', '12345678901234,H02')]:
        content += paragraph(run(tab=True) + run(label), f'<w:tab w:val="{kind}" w:pos="0"/>', -720)
    size = (11906, 16838) if paper == 'a4' else (12240, 15840)
    content += f'<w:sectPr><w:pgSz w:w="{size[0]}" w:h="{size[1]}"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr>'
    return {
        '[Content_Types].xml': '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>',
        '_rels/.rels': f'<Relationships xmlns="{REL}"><Relationship Id="document" Type="{R}/officeDocument" Target="word/document.xml"/></Relationships>',
        'word/document.xml': f'<w:document xmlns:w="{W}" xmlns:r="{R}"><w:body>{content}</w:body></w:document>',
        'word/styles.xml': f'<w:styles xmlns:w="{W}"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style></w:styles>',
        'word/settings.xml': f'<w:settings xmlns:w="{W}"><w:defaultTabStop w:val="720"/><w:decimalSymbol w:val=","/></w:settings>',
        'word/_rels/document.xml.rels': f'<Relationships xmlns="{REL}"><Relationship Id="styles" Type="{R}/styles" Target="styles.xml"/><Relationship Id="settings" Type="{R}/settings" Target="settings.xml"/></Relationships>',
    }


def build(output):
    output.mkdir(parents=True, exist_ok=False)
    for paper in ['a4', 'letter']:
        for variant, value in [('template', '{prefix}'), ('short', ''), ('collision', 'WWWWW'), ('past', 'WWWWWWWW')]:
            folder = output / variant / paper
            folder.mkdir(parents=True)
            with ZipFile(folder / 'source.docx', 'x') as archive:
                for name, data in sorted(parts(paper, value).items()):
                    info = ZipInfo(name, (2026, 11, 18, 0, 0, 0))
                    info.create_system = 0
                    info.compress_type = ZIP_DEFLATED
                    archive.writestr(info, data.encode())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    build(parser.parse_args().output)
