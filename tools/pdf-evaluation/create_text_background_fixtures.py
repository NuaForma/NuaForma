"""Build fresh, inert DOCX controls for highlighting and solid text shading."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from xml.sax.saxutils import escape
import random
from create_tab_collision_fixtures import parts as base_parts, W, R

PALETTE = ['black', 'blue', 'cyan', 'green', 'magenta', 'red', 'yellow', 'white',
           'darkBlue', 'darkCyan', 'darkGreen', 'darkMagenta', 'darkRed', 'darkYellow', 'darkGray', 'lightGray']


def contrast_colors():
    colors = [(v,v,v) for v in [0,59,60,61,62,128,255]] + [(v,0,0) for v in [199,200,201,202,203,255]]
    colors += [(0,v,0) for v in [100,101,102,103,104,128,255]]
    colors += [(0,0,255),(128,0,128),(255,0,255),(10,47,65),(0,139,144)]
    generator = random.Random(1842)
    thresholds = [(1,78,120), (4,99,4), (2,94,35), (3,63,192), (74,36,149), (75,57,34), (75,57,35), (12,95,3)]
    return colors + thresholds + [tuple(generator.randrange(256) for _ in range(3)) for _ in range(10)]


def contrast():
    body = []
    for index, rgb in enumerate(contrast_colors()):
        fill = ''.join(f'{component:02X}' for component in rgb)
        content = ''.join(run(f'A{index:02}_{mode} ', properties + shading(fill)) for mode, properties in [
            ('DEFAULT',''), ('AUTO','<w:color w:val="auto"/>'), ('BLACK','<w:color w:val="000000"/>')])
        body.append(paragraph(content, line=280))
    return ''.join(body)


def highlight_contrast():
    cases = [f'<w:highlight w:val="{color}"/>' for color in PALETTE]
    cases += [shading('000000') + '<w:highlight w:val="yellow"/>',
              shading('FFFFFF') + '<w:highlight w:val="blue"/>',
              shading('0000FF') + '<w:highlight w:val="none"/>']
    return ''.join(paragraph(''.join(run(f'A{index:02}_{mode} ', prop + color)
        for mode, color in [('DEFAULT', ''), ('AUTO', '<w:color w:val="auto"/>'),
                            ('BLACK', '<w:color w:val="000000"/>')]), line=280)
        for index, prop in enumerate(cases))


def run(text='', properties='', size=20, control=''):
    return (f'<w:r><w:rPr><w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="{size}"/>' +
            properties + '</w:rPr>' + (f'<w:t xml:space="preserve">{escape(text)}</w:t>' if text else '') + control + '</w:r>')


def paragraph(content, extra='', line=400, automatic=False):
    return ('<w:p><w:pPr><w:spacing w:before="0" w:after="0" w:line="' + str(line) +
            '" w:lineRule="' + ('auto' if automatic else 'exact') + '"/><w:widowControl w:val="0"/>' + extra + '</w:pPr>' + content + '</w:p>')


def shading(fill):
    return f'<w:shd w:val="clear" w:color="auto" w:fill="{fill}"/>'


def metrics():
    cases = [('highlight10', '<w:highlight w:val="yellow"/>', 20), ('shading10', shading('FFFF00'), 20),
             ('highlight20', '<w:highlight w:val="yellow"/>', 40), ('shading20', shading('FFFF00'), 40),
             ('super', '<w:highlight w:val="cyan"/><w:vertAlign w:val="superscript"/>', 24),
             ('bold', '<w:b/><w:highlight w:val="green"/>', 24),
             ('precedence', shading('FF0000') + '<w:highlight w:val="cyan"/>', 24)]
    body = [paragraph(run(name + ' ') + run(f'M{i:02}_TEXT', prop, size) + run(' END'), line=800)
            for i, (name, prop, size) in enumerate(cases)]
    body += [paragraph(run('leading ') + run(' SPACE SPACE ', prop) + run('end'), line=800)
             for prop in ['<w:highlight w:val="yellow"/>', shading('FFFF00')]]
    return ''.join(body)


def highlight():
    body = []
    for i, color in enumerate(PALETTE):
        ink = '<w:color w:val="FFFFFF"/>' if color == 'black' or color.startswith('dark') else ''
        body.append(paragraph(run('Color ') + run(f'H{i:02}_COLOR', ink + f'<w:highlight w:val="{color}"/>') + run(' ' + color)))
    body.append(paragraph(run('H16_INHERITED'), '<w:pStyle w:val="Marked"/>'))
    body.append(paragraph(run('H17_RESET', '<w:highlight w:val="none"/>'), '<w:pStyle w:val="Marked"/>'))
    body.append(paragraph(run('H18_CHARACTER', '<w:rStyle w:val="RedFill"/>'), '<w:pStyle w:val="Marked"/>'))
    body.append(paragraph(run('H19_REVEAL', '<w:rStyle w:val="RedFill"/><w:highlight w:val="none"/>'), '<w:pStyle w:val="Marked"/>'))
    prop = '<w:highlight w:val="yellow"/>'
    body.append(paragraph(run('H20_TAB', prop) + run(properties=prop, control='<w:tab/>') + run('H21_END', prop),
                          '<w:tabs><w:tab w:val="left" w:pos="3600"/></w:tabs>'))
    body.append(paragraph(run('H22_BREAK', prop, control='<w:br/>') + run('H23_AFTER', prop)))
    body.append(paragraph(run('H24_WRAP ' + 'wrapping text ' * 13, prop)))
    body.append(paragraph(run('H25_TRAILING   ', prop)))
    body.append(paragraph(run('H26_UNICODE café Ελληνικά Кириллица', prop)))
    body.append(paragraph(run('H27_JUSTIFY ' + 'justify text ' * 12, prop), '<w:jc w:val="both"/>'))
    body.append(paragraph(run('   ', prop)))
    body.append(paragraph(run('H28_AFTER', prop)))
    return ''.join(body)


def cell(content):
    return '<w:tc><w:tcPr><w:tcW w:w="3600" w:type="dxa"/>' + shading('008B90') + '</w:tcPr>' + paragraph(content) + '</w:tc>'


def shade():
    body = []
    cases = [shading('FFFFFF'), shading('FFE4B5'), shading('008B90'), shading('auto'), '<w:shd w:val="nil"/>',
             '<w:shd w:val="clear" w:themeFill="accent1" w:themeFillTint="80" w:fill="000000"/>',
             '<w:shd w:val="clear" w:themeFill="accent1" w:themeFillShade="80" w:fill="000000"/>']
    for i, prop in enumerate(cases):body.append(paragraph(run('Shade ') + run(f'S{i:02}_TEXT', prop)))
    body.append(paragraph(run('S07_INHERITED', '<w:rStyle w:val="RedFill"/>')))
    body.append(paragraph(run('S08_NIL', '<w:rStyle w:val="RedFill"/><w:shd w:val="nil"/>')))
    body.append(paragraph(run('S17_CLEAR', '<w:rStyle w:val="RedFill"/>' + shading('auto'))))
    body.append(paragraph(run('S09_PRECEDENCE', shading('FF0000') + '<w:highlight w:val="yellow"/>')))
    body.append(paragraph(run('S10_SPACE ' + ' wrapping shading ' * 11, shading('FFE4B5'))))
    body.append(paragraph(run('S11_TRAILING   ', shading('FFE4B5'))))
    table = ('<w:tbl><w:tblPr><w:tblW w:w="7200" w:type="dxa"/><w:tblLayout w:type="fixed"/>'
             '<w:tblCellMar>' + ''.join(f'<w:{edge} w:w="80" w:type="dxa"/>' for edge in ['top','left','bottom','right']) +
             '</w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="3600"/><w:gridCol w:w="3600"/></w:tblGrid><w:tr>' +
             cell(run('S12_WHITE', shading('FFFFFF'))) + cell(run('S13_CLEAR', shading('auto'))) + '</w:tr></w:tbl>')
    body.append(table)
    body.append(paragraph(run('S14_MARK'), '<w:rPr>' + shading('FF0000') + '</w:rPr>'))
    body.append(paragraph(run('S15_SMALL', shading('FFE4B5'), 16) + run(' S16_LARGE', shading('FFE4B5'), 32), line=240, automatic=True))
    return ''.join(body)


def build(output):
    output.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parents[2]
    with ZipFile(root/'nuaforma-docx-pdf/src/test/resources/theme-colors/a4/source.docx') as archive:
        theme = archive.read('word/theme/theme1.xml').decode()
    for variant, make in [('metrics', metrics), ('highlight', highlight), ('shading', shade), ('contrast', contrast),
                          ('highlight-contrast', highlight_contrast)]:
        for paper in ['a4', 'letter']:
            source = base_parts(paper, '')
            styles = ('<w:style w:type="paragraph" w:styleId="Marked"><w:name w:val="Marked"/><w:basedOn w:val="Normal"/>'
                      '<w:rPr><w:highlight w:val="green"/></w:rPr></w:style><w:style w:type="character" w:styleId="RedFill">'
                      '<w:name w:val="RedFill"/><w:rPr>' + shading('FF0000') + '</w:rPr></w:style>')
            source['word/styles.xml'] = source['word/styles.xml'].replace('</w:styles>', styles + '</w:styles>')
            section = source['word/document.xml'].split('<w:sectPr>')[1].split('</w:sectPr>')[0]
            if variant != 'metrics':
                section = section.replace('<w:pgSz', '<w:headerReference w:type="default" r:id="header"/><w:footerReference w:type="default" r:id="footer"/><w:pgSz')
                section = section.replace('w:top="1134"', 'w:top="1800"').replace('w:bottom="1134"', 'w:bottom="1200" w:header="600" w:footer="600"')
                for band, tag, marker in [('header', 'hdr', 'H90_HEADER'), ('footer', 'ftr', 'H91_FOOTER')]:
                    source[f'word/{band}1.xml'] = f'<w:{tag} xmlns:w="{W}">' + paragraph(run(marker, '<w:highlight w:val="cyan"/>'), line=280) + f'</w:{tag}>'
                    source['word/_rels/document.xml.rels'] = source['word/_rels/document.xml.rels'].replace('</Relationships>', f'<Relationship Id="{band}" Type="{R}/{band}" Target="{band}1.xml"/></Relationships>')
                    source['[Content_Types].xml'] = source['[Content_Types].xml'].replace('</Types>', f'<Override PartName="/word/{band}1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.{band}+xml"/></Types>')
            source['word/theme/theme1.xml'] = theme
            source['word/_rels/document.xml.rels'] = source['word/_rels/document.xml.rels'].replace('</Relationships>', f'<Relationship Id="theme" Type="{R}/theme" Target="theme/theme1.xml"/></Relationships>')
            source['[Content_Types].xml'] = source['[Content_Types].xml'].replace('</Types>', '<Override PartName="/word/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/></Types>')
            source['word/document.xml'] = f'<w:document xmlns:w="{W}" xmlns:r="{R}"><w:body>' + make() + '<w:sectPr>' + section + '</w:sectPr></w:body></w:document>'
            folder = output/variant/paper
            folder.mkdir(parents=True)
            with ZipFile(folder/'source.docx', 'x') as archive:
                for name, data in sorted(source.items()):
                    info = ZipInfo(name, (2026, 11, 18, 0, 0, 0))
                    info.create_system = 0
                    info.compress_type = ZIP_DEFLATED
                    archive.writestr(info, data.encode())


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    build(parser.parse_args().output)
