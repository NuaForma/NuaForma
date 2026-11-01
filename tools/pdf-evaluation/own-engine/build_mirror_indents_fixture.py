"""Synthetic Word oracle for paragraph mirror indents, hanging indents and section numbering."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_fixture import build as base, paragraph, run, W, R


def build(root, output, paper='a4', flip=True, compatibility=15):
    entries = base(root, output, entries_only=True)
    font = '<w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="22"/>'
    entries['word/styles.xml'] = (f'<w:styles xmlns:w="{W}"><w:docDefaults><w:rPrDefault><w:rPr>{font}</w:rPr></w:rPrDefault>'
        '<w:pPrDefault><w:pPr><w:spacing w:before="0" w:after="0" w:line="320" w:lineRule="exact"/></w:pPr></w:pPrDefault></w:docDefaults>'
        '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>'
        '<w:style w:type="paragraph" w:styleId="Mirror"><w:name w:val="Mirror"/><w:basedOn w:val="Normal"/>'
        '<w:pPr><w:mirrorIndents/><w:ind w:left="1440" w:right="720" w:hanging="360"/></w:pPr></w:style></w:styles>')
    entries['word/settings.xml'] = (f'<w:settings xmlns:w="{W}"><w:defaultTabStop w:val="720"/><w:compat>'
        f'<w:compatSetting w:name="doNotFlipMirrorIndents" w:uri="http://schemas.microsoft.com/office/word" w:val="{0 if flip else 1}"/>'
        f'<w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="{compatibility}"/>'
        '</w:compat></w:settings>')
    entries['word/_rels/document.xml.rels'] = entries['word/_rels/document.xml.rels'].replace('</Relationships>',
        f'<Relationship Id="mirrorSettings" Type="{R}/settings" Target="settings.xml"/></Relationships>')
    entries['[Content_Types].xml'] = entries['[Content_Types].xml'].replace('</Types>',
        '<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>')
    width, height = (11906, 16838) if paper == 'a4' else (12240, 15840)

    def section(start=None):
        number = '' if start is None else f'<w:pgNumType w:fmt="decimal" w:start="{start}"/>'
        return (f'<w:sectPr><w:type w:val="nextPage"/><w:pgSz w:w="{width}" w:h="{height}"/>'
            '<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="0" w:footer="0" w:gutter="0"/>'
            + number + '</w:sectPr>')

    content = []
    for n in range(1, 5):
        props = '<w:pStyle w:val="Mirror"/>' + ('<w:pageBreakBefore/>' if n == 2 or n == 4 else '')
        text = f'M{n}0 Mirrored hanging paragraph: ' + 'alpha beta gamma delta epsilon zeta eta theta iota kappa lambda. ' * 3
        content.append(paragraph(run(text), props))
        content.append(paragraph(run(f'N{n}0 Directly disabled mirror'), '<w:pStyle w:val="Mirror"/><w:mirrorIndents w:val="0"/>'))
        content.append(paragraph(run(f'R{n}0 Right aligned mirror'), '<w:pStyle w:val="Mirror"/><w:jc w:val="right"/>'))
        content.append(paragraph(run(f'F{n}0 Positive first-line mirror'), '<w:pStyle w:val="Mirror"/><w:ind w:firstLine="360"/>'))
        if n == 2:
            content.append(paragraph(run('Section boundary'), section()))
    entries['word/document.xml'] = f'<w:document xmlns:w="{W}" xmlns:r="{R}"><w:body>' + ''.join(content) + section(2) + '</w:body></w:document>'
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
    parser.add_argument('--no-flip', action='store_true')
    parser.add_argument('--compatibility', type=int, choices=(12, 15), default=15)
    args = parser.parse_args()
    build(Path(__file__).resolve().parents[3], args.output, args.paper, not args.no_flip, args.compatibility)
