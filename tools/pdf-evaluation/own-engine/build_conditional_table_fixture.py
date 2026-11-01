"""Synthetic conditional-table Word oracle; colors expose precedence without a visual guess."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_fixture import build as base, paragraph, run, W, R


def build(root, output, paper='a4', differentiate=True, override=True, vertical=True, minimal_header=False, normal_defaults=False, compatibility=15, default_bands=False):
    entries = base(root, output, entries_only=True)
    font = '<w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="20"/>'
    styles = [f'<w:styles xmlns:w="{W}"><w:docDefaults><w:rPrDefault><w:rPr>{font}</w:rPr></w:rPrDefault>'
        '<w:pPrDefault><w:pPr><w:spacing w:before="0" w:after="0" w:line="400" w:lineRule="exact"/>'
        '<w:widowControl w:val="0"/></w:pPr></w:pPrDefault></w:docDefaults>',
        '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/>' +
        ('<w:pPr><w:jc w:val="left"/></w:pPr><w:rPr><w:sz w:val="24"/></w:rPr>' if normal_defaults else '') + '</w:style>',
        '<w:style w:type="paragraph" w:styleId="Explicit"><w:name w:val="Explicit"/><w:basedOn w:val="Normal"/>'
        '<w:pPr><w:jc w:val="left"/></w:pPr><w:rPr><w:color w:val="112233"/><w:sz w:val="20"/></w:rPr></w:style>',
        '<w:style w:type="table" w:styleId="Regions"><w:name w:val="Regions"/>' +
        ('<w:tblPr/>' if default_bands else '<w:tblPr><w:tblStyleRowBandSize w:val="2"/><w:tblStyleColBandSize w:val="1"/></w:tblPr>') +
        '<w:rPr><w:color w:val="333333"/></w:rPr>']
    regions = [('wholeTable', '333333', 'FFFFFF', 'left', ''),
        ('band1Horz', '116611', 'DDF0DD', 'left', ''), ('band2Horz', '661111', 'F0DDDD', 'left', ''),
        ('band1Vert', '111166', 'DDDDF0', 'left', ''), ('band2Vert', '666611', 'F0F0DD', 'left', ''),
        ('firstCol', '008888', 'DDFFFF', 'left', '<w:i/>'), ('lastCol', '880088', 'FFDDFF', 'right', ''),
        ('firstRow', '0000CC', 'CCCCFF', 'center', '<w:b/><w:sz w:val="22"/>'),
        ('lastRow', 'CC0000', 'FFCCCC', 'center', '<w:b/>'),
        ('nwCell', '004400', 'AAFFAA', 'left', '<w:b/>'), ('neCell', '440044', 'FFAADD', 'right', '<w:b/>'),
        ('swCell', '444400', 'FFFFAA', 'left', '<w:b/>'), ('seCell', '004444', 'AAFFFF', 'right', '<w:b/>')]
    for name, color, fill, alignment, extra in regions:
        if minimal_header and name == 'firstRow':
            styles.append('<w:tblStylePr w:type="firstRow"><w:rPr><w:b/></w:rPr></w:tblStylePr>')
            continue
        styles.append(f'<w:tblStylePr w:type="{name}"><w:pPr><w:jc w:val="{alignment}"/></w:pPr>'
            f'<w:rPr><w:color w:val="{color}"/>{extra}</w:rPr><w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="{fill}"/></w:tcPr></w:tblStylePr>')
    styles.append('</w:style></w:styles>')
    entries['word/styles.xml'] = ''.join(styles)
    switches = [('overrideTableStyleFontSizeAndJustification', override), ('differentiateMultirowTableHeaders', differentiate)]
    entries['word/settings.xml'] = (f'<w:settings xmlns:w="{W}"><w:defaultTabStop w:val="720"/><w:compat>' +
        ''.join(f'<w:compatSetting w:name="{name}" w:uri="http://schemas.microsoft.com/office/word" w:val="{int(value)}"/>' for name, value in switches) +
        f'<w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="{compatibility}"/></w:compat></w:settings>')
    entries['word/_rels/document.xml.rels'] = entries['word/_rels/document.xml.rels'].replace('</Relationships>',
        f'<Relationship Id="tableSettings" Type="{R}/settings" Target="settings.xml"/></Relationships>')
    entries['[Content_Types].xml'] = entries['[Content_Types].xml'].replace('</Types>',
        '<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>')
    rows = []
    for row in range(6):
        cells = []
        for col in range(4):
            direct = row == 2 and col == 2
            props = '<w:pStyle w:val="Explicit"/>' if row == 3 and col == 2 else ''
            if direct:
                props += '<w:jc w:val="left"/>'
            text = run(f'C{row}{col} Region', '<w:color w:val="225588"/>' if direct else '')
            shading = '<w:shd w:val="clear" w:color="auto" w:fill="FFEE88"/>' if direct else ''
            cells.append('<w:tc><w:tcPr><w:tcW w:w="2400" w:type="dxa"/>' + shading + '</w:tcPr>' + paragraph(text, props) + '</w:tc>')
        header = '<w:tblHeader/>' if row < 2 else ''
        rows.append('<w:tr><w:trPr><w:cantSplit/>' + header + '</w:trPr>' + ''.join(cells) + '</w:tr>')
    borders = ''.join(f'<w:{side} w:val="single" w:sz="4" w:color="000000"/>' for side in ('top','left','bottom','right','insideH','insideV'))
    padding = ''.join(f'<w:{side} w:w="120" w:type="dxa"/>' for side in ('top','left','bottom','right'))
    table = ('<w:tbl><w:tblPr><w:tblStyle w:val="Regions"/><w:tblW w:w="9600" w:type="dxa"/><w:tblInd w:w="0" w:type="dxa"/>'
        f'<w:tblLayout w:type="fixed"/><w:tblLook w:val="{(0x01E0 if vertical else 0x05E0):04X}" w:firstRow="1" w:lastRow="1" w:firstColumn="1" w:lastColumn="1" w:noHBand="0" w:noVBand="{0 if vertical else 1}"/>'
        f'<w:tblBorders>{borders}</w:tblBorders><w:tblCellMar>{padding}</w:tblCellMar></w:tblPr>'
        '<w:tblGrid>' + '<w:gridCol w:w="2400"/>' * 4 + '</w:tblGrid>' + ''.join(rows) + '</w:tbl>')
    width, height = (11906, 16838) if paper == 'a4' else (12240, 15840)
    entries['word/document.xml'] = (f'<w:document xmlns:w="{W}" xmlns:r="{R}"><w:body>' + table + paragraph(run('End of conditional table')) +
        f'<w:sectPr><w:pgSz w:w="{width}" w:h="{height}"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="0" w:footer="0" w:gutter="0"/></w:sectPr></w:body></w:document>')
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'w') as archive:
        for name, value in sorted(entries.items()):
            info = ZipInfo(name, date_time=(2026, 11, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.create_system = 0
            archive.writestr(info, value.encode('utf-8') if isinstance(value, str) else value)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--paper', choices=('a4','letter'), default='a4')
    parser.add_argument('--no-differentiate', action='store_true')
    parser.add_argument('--no-override', action='store_true')
    parser.add_argument('--horizontal-only', action='store_true')
    parser.add_argument('--minimal-header', action='store_true')
    parser.add_argument('--normal-defaults', action='store_true')
    parser.add_argument('--compatibility', type=int, choices=(12,15), default=15)
    parser.add_argument('--default-bands', action='store_true')
    args = parser.parse_args()
    build(Path(__file__).resolve().parents[3], args.output, args.paper, not args.no_differentiate, not args.no_override,
        not args.horizontal_only, args.minimal_header, args.normal_defaults, args.compatibility, args.default_bands)
