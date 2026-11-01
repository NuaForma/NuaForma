"""Synthetic Word oracle for automatic paragraph spacing and container edges."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_fixture import build as base, paragraph, run, W, R


def build(root, output, paper='a4', fixed=False, compatibility=15, physical_edges=False):
    entries = base(root, output, entries_only=True)
    spacing = '<w:spacing w:before="0" w:after="0" w:line="320" w:lineRule="exact"/>'
    font = '<w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="22"/>'
    styles = [f'<w:styles xmlns:w="{W}"><w:docDefaults><w:rPrDefault><w:rPr>{font}</w:rPr></w:rPrDefault>'
              f'<w:pPrDefault><w:pPr>{spacing}</w:pPr></w:pPrDefault></w:docDefaults>',
              '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>']
    for name, extra in [('Auto', ''), ('Context', '<w:contextualSpacing/>')]:
        styles.append(f'<w:style w:type="paragraph" w:styleId="{name}"><w:name w:val="{name}"/><w:basedOn w:val="Normal"/>'
                      f'<w:pPr><w:spacing w:beforeAutospacing="1" w:afterAutospacing="1"/>{extra}</w:pPr></w:style>')
    entries['word/styles.xml'] = ''.join(styles) + '</w:styles>'
    entries['word/settings.xml'] = (f'<w:settings xmlns:w="{W}"><w:defaultTabStop w:val="720"/><w:compat>'
        f'<w:doNotUseHTMLParagraphAutoSpacing w:val="{1 if fixed else 0}"/>'
        f'<w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="{compatibility}"/>'
        '</w:compat></w:settings>')
    entries['word/_rels/document.xml.rels'] = entries['word/_rels/document.xml.rels'].replace('</Relationships>',
        f'<Relationship Id="spacingSettings" Type="{R}/settings" Target="settings.xml"/></Relationships>')
    entries['[Content_Types].xml'] = entries['[Content_Types].xml'].replace('</Types>',
        '<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>')

    def p(label, props='', style='Normal', size=22):
        return paragraph(run(label, f'<w:sz w:val="{size}"/>'), f'<w:pStyle w:val="{style}"/>{props}')

    physical = ' w:before="100" w:after="200"' if physical_edges else ''
    both = f'<w:spacing w:beforeAutospacing="1" w:afterAutospacing="1"{physical}/>'
    content = [p('A00 first paragraph, automatic before and after', both),
        p('B00 explicit zero spacing'),
        p('A01 auto-before flag and forty physical points', '<w:spacing w:before="800" w:beforeAutospacing="1"/>'),
        p('B01 explicit zero spacing'),
        p('A02 auto-after flag and forty physical points', '<w:spacing w:after="800" w:afterAutospacing="1"/>'),
        p('B02 explicit zero spacing'),
        p('A03 automatic spacing with a larger font', '<w:spacing w:beforeAutospacing="1" w:afterAutospacing="1" w:line="640" w:lineRule="exact"/>', size=40),
        p('B03 explicit zero spacing'),
        p('S01 inherited automatic spacing', style='Auto'),
        p('S02 direct spacing retains inherited auto flags', '<w:spacing w:before="0" w:after="0"/>', 'Auto'),
        p('S03 explicit false restores physical spacing', '<w:spacing w:before="60" w:after="120" w:beforeAutospacing="0" w:afterAutospacing="0"/>', 'Auto'),
        p('C01 contextual automatic spacing', style='Context'), p('C02 contextual automatic spacing', style='Context'),
        p('D01 auto flags and physical five-before ten-after', '<w:spacing w:before="100" w:after="200" w:beforeAutospacing="1" w:afterAutospacing="1"/>'),
        p('D02 auto flags and physical five-before ten-after', '<w:spacing w:before="100" w:after="200" w:beforeAutospacing="1" w:afterAutospacing="1"/>'),
        p('M01 manual before six and after eight', '<w:spacing w:before="120" w:after="160"/>'),
        p('M02 manual before ten and after zero', '<w:spacing w:before="200" w:after="0"/>'),
        p('P00 automatic spacing after an explicit page break', '<w:pageBreakBefore/>' + both),
        p('P01 explicit zero spacing')]

    rows = []
    for row in range(2):
        cells = []
        for column in range(2):
            labels = [f'T{row}{column}{n} automatic cell paragraph' for n in range(3)]
            cells.append('<w:tc><w:tcPr><w:tcW w:w="4000" w:type="dxa"/></w:tcPr>' + ''.join(p(text, both + '<w:widowControl w:val="0"/>') for text in labels) + '</w:tc>')
        rows.append('<w:tr><w:trPr><w:cantSplit/></w:trPr>' + ''.join(cells) + '</w:tr>')
    borders = ''.join(f'<w:{side} w:val="single" w:sz="4" w:color="000000"/>' for side in ('top', 'left', 'bottom', 'right', 'insideH', 'insideV'))
    padding = ''.join(f'<w:{side} w:w="120" w:type="dxa"/>' for side in ('top', 'left', 'bottom', 'right'))
    content.append('<w:tbl><w:tblPr><w:tblW w:w="8000" w:type="dxa"/><w:tblInd w:w="0" w:type="dxa"/>'
        f'<w:tblLayout w:type="fixed"/><w:tblBorders>{borders}</w:tblBorders><w:tblCellMar>{padding}</w:tblCellMar></w:tblPr>'
        '<w:tblGrid><w:gridCol w:w="4000"/><w:gridCol w:w="4000"/></w:tblGrid>' + ''.join(rows) + '</w:tbl>')
    content.extend([p('P02 automatic paragraph following a table', both), p('P03 final explicit zero spacing')])
    width, height = (11906, 16838) if paper == 'a4' else (12240, 15840)
    entries['word/document.xml'] = (f'<w:document xmlns:w="{W}" xmlns:r="{R}"><w:body>' + ''.join(content) +
        f'<w:sectPr><w:pgSz w:w="{width}" w:h="{height}"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" '
        'w:left="1134" w:header="0" w:footer="0" w:gutter="0"/></w:sectPr></w:body></w:document>')
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
    parser.add_argument('--paper', choices=('a4', 'letter'), default='a4')
    parser.add_argument('--fixed', action='store_true')
    parser.add_argument('--compatibility', type=int, choices=(12, 15), default=15)
    parser.add_argument('--physical-edges', action='store_true')
    args = parser.parse_args()
    build(Path(__file__).resolve().parents[3], args.output, args.paper, args.fixed, args.compatibility, args.physical_edges)
