"""Deterministic passive paragraph-border and VML/DrawingML line qualification input (#172)."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_report_fixture import build as report_entries
from build_fixture import W, R, paragraph, run


def border(edges='bottom', style='single', space=4, width=8, color='008B90'):
    return '<w:pBdr>' + ''.join(f'<w:{s} w:val="{style}" w:sz="{width}" w:space="{space}" w:color="{color}"/>' for s in edges.split()) + '</w:pBdr>'


def vml(y=28, end=350, dash='', fixed=False, identity='line1'):
    position = ';mso-position-horizontal-relative:page;mso-position-vertical-relative:page' if fixed else ';mso-position-horizontal-relative:text;mso-position-vertical-relative:text'
    stroke = f'<v:stroke dashstyle="{dash}"/>' if dash else ''
    return f'<w:r><w:pict><v:line id="{identity}" style="position:absolute;z-index:-1{position}" from="24pt,{y}pt" to="{end}pt,{y}pt" strokecolor="#008b90" strokeweight="1.5pt">{stroke}</v:line></w:pict></w:r>'


def drawing(y=32, dash='solid', rotation=0, flip=False, compound='sng', identity=172):
    return f'''<w:r><w:drawing><wp:anchor distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="0" behindDoc="1" locked="0" layoutInCell="1" allowOverlap="1">
      <wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="column"><wp:posOffset>304800</wp:posOffset></wp:positionH>
      <wp:positionV relativeFrom="paragraph"><wp:posOffset>{y * 12700}</wp:posOffset></wp:positionV>
      <wp:extent cx="2540000" cy="0"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:wrapNone/><wp:docPr id="{identity}" name="Passive line"/>
      <a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"><wps:wsp><wps:cNvSpPr/>
      <wps:spPr><a:xfrm rot="{rotation}" flipH="{str(flip).lower()}"><a:off x="0" y="0"/><a:ext cx="2540000" cy="0"/></a:xfrm><a:prstGeom prst="line"><a:avLst/></a:prstGeom>
      <a:ln w="19050" cmpd="{compound}"><a:solidFill><a:srgbClr val="008B90"/></a:solidFill><a:prstDash val="{dash}"/></a:ln></wps:spPr><wps:bodyPr/></wps:wsp></a:graphicData></a:graphic>
      </wp:anchor></w:drawing></w:r>'''


def build(root, output):
    entries = report_entries(root, output, entries_only=True)
    old = entries['word/document.xml']
    prefix = old.split('<w:body>')[0].replace('xmlns:w=', 'xmlns:v="urn:schemas-microsoft-com:vml" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" xmlns:w=', 1)
    section = '<w:sectPr>' + old.split('<w:sectPr>')[1].split('</w:sectPr>')[0] + '</w:sectPr>'
    entries['word/styles.xml'] = entries['word/styles.xml'].replace('</w:styles>', '<w:style w:type="paragraph" w:styleId="LineBase"><w:name w:val="Line Base"/><w:basedOn w:val="Normal"/><w:pPr>' + border() + '</w:pPr></w:style></w:styles>')
    body = [paragraph(run('Paragraph borders and automatic rules'), '<w:pStyle w:val="Title"/>')]
    for i, style in enumerate(('single', 'double', 'dashed', 'dotted')):
        body += [paragraph(run(f'B{i+1:02d} {style} rule'), border(style=style)), paragraph(run(f'A{i+1:02d} Text after the rule'))]
    body += [paragraph(run('I01 Inherited bottom border'), '<w:pStyle w:val="LineBase"/>'),
             paragraph(run('I02 Direct removal'), '<w:pStyle w:val="LineBase"/><w:pBdr><w:bottom w:val="nil"/></w:pBdr>')]
    for between in (False, True):
        edges = 'top right bottom left' + (' between' if between else '')
        body += [paragraph(run(f'G{int(between)}{n} Connected frame'), border(edges=edges)) for n in range(1, 3)]
        body += [paragraph(run('Separator outside frame'))]
    body += [paragraph(run('Positioned vector lines'), '<w:pStyle w:val="Title"/><w:pageBreakBefore/>')]
    for n, line in enumerate((vml(), vml(dash='dash', identity='line2'), vml(dash='shortDot', identity='line3'), drawing(identity=174), drawing(dash='dash', identity=175), drawing(dash='dot', identity=176), drawing(y=120, rotation=5400000, identity=177), drawing(compound='dbl', identity=178), drawing(y=64, rotation=1800000, flip=True, identity=179), vml(y=70, fixed=True, identity='line10')), 1):
        after = 4400 if n == 7 else 2400 if n == 9 else 800
        body += [paragraph(run(f'D{n:02d} Anchored line') + line, f'<w:spacing w:before="0" w:after="{after}" w:line="320" w:lineRule="exact"/>' + ('<w:pageBreakBefore/>' if n == 9 else ''))]
    cell = paragraph(run('T01 Border inside a table cell'), border(style='double') + '<w:widowControl w:val="0"/>')
    padding = ''.join(f'<w:{s} w:w="160" w:type="dxa"/>' for s in ('top', 'bottom', 'left', 'right'))
    body += [f'<w:tbl><w:tblPr><w:tblW w:w="6000" w:type="dxa"/><w:tblInd w:w="160" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders/><w:tblCellMar>{padding}</w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="6000"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w="6000" w:type="dxa"/></w:tcPr>{cell}</w:tc></w:tr></w:tbl>', paragraph(run('After the table'))]
    # A rule directly before and after natural pagination, without a forced section break.
    body += [paragraph(run(f'P{n:02d} Pagination remains stable'), border() if n in (1, 24, 25, 26) else '') for n in range(1, 27)]
    entries['word/document.xml'] = prefix + '<w:body>' + ''.join(body) + section + '</w:body></w:document>'
    for band, label, edge in [('header', 'HEADER', 'bottom'), ('footer', 'FOOTER', 'top')]:
        entries[f'word/{band}1.xml'] = f'<w:{"hdr" if band == "header" else "ftr"} xmlns:w="{W}" xmlns:r="{R}" xmlns:v="urn:schemas-microsoft-com:vml">' + paragraph(run(label) + vml(y=8, end=400, dash='dash').replace('24pt,8pt','230pt,8pt'), border(edges=edge, space=2)) + f'</w:{"hdr" if band == "header" else "ftr"}>'
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'w') as archive:
        for name, value in sorted(entries.items()):
            info = ZipInfo(name, date_time=(2026, 11, 1, 0, 0, 0)); info.create_system = 0; info.compress_type = ZIP_DEFLATED
            archive.writestr(info, value.encode() if isinstance(value, str) else value)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('output', type=Path)
    build(Path(__file__).resolve().parents[3], parser.parse_args().output)
