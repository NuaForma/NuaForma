"""Synthetic Noto Serif kerning, ligature and run-boundary Word oracle."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_fixture import build as base, paragraph, run, W, R

W14 = 'http://schemas.microsoft.com/office/word/2010/wordml'


def build(root, output, paper='a4', enabled=True, compatibility=15, family='Noto Serif'):
    entries = base(root, output, entries_only=True)
    font = f'<w:rFonts w:ascii="{family}" w:hAnsi="{family}"/><w:sz w:val="40"/>'
    ligatures = '<w14:ligatures w14:val="standard"/>'
    entries['word/styles.xml'] = (f'<w:styles xmlns:w="{W}" xmlns:w14="{W14}"><w:docDefaults><w:rPrDefault><w:rPr>{font}</w:rPr></w:rPrDefault>'
        '<w:pPrDefault><w:pPr><w:spacing w:before="0" w:after="120" w:line="800" w:lineRule="exact"/><w:widowControl w:val="0"/></w:pPr></w:pPrDefault></w:docDefaults>'
        '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>'
        '<w:style w:type="paragraph" w:styleId="Features"><w:name w:val="Features"/><w:basedOn w:val="Normal"/>'
        f'<w:rPr><w:kern w:val="40"/>{ligatures}</w:rPr></w:style></w:styles>')
    entries['word/settings.xml'] = (f'<w:settings xmlns:w="{W}"><w:defaultTabStop w:val="720"/><w:compat>'
        f'<w:compatSetting w:name="enableOpenTypeFeatures" w:uri="http://schemas.microsoft.com/office/word" w:val="{int(enabled)}"/>'
        f'<w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="{compatibility}"/>'
        '</w:compat></w:settings>')
    entries['word/_rels/document.xml.rels'] = entries['word/_rels/document.xml.rels'].replace('</Relationships>',
        f'<Relationship Id="featureSettings" Type="{R}/settings" Target="settings.xml"/></Relationships>')
    entries['[Content_Types].xml'] = entries['[Content_Types].xml'].replace('</Types>',
        '<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/></Types>')

    def p(label, text, props='', style='Normal'):
        return paragraph(run(label + ' ' + text, props), f'<w:pStyle w:val="{style}"/>')

    text = 'AVATAR To WA AVATAR To WA AVATAR To WA'
    liga = 'office fi fl ffi ffl office fi fl ffi ffl'
    content = [p('F01', text, '<w:kern w:val="0"/>'),
        p('F02', text, '<w:kern w:val="40"/>'), p('F03', text, '<w:kern w:val="42"/>'),
        p('F04', text, '<w:kern w:val="1"/>'), p('F05', liga, ligatures),
        p('F06', liga, '<w14:ligatures w14:val="none"/>'),
        p('F07', 'AVATAR office fi fl ffi ffl AVATAR office fi fl ffi ffl', '<w:kern w:val="40"/>' + ligatures),
        p('F08', text, '<w:sz w:val="20"/><w:kern w:val="22"/>'),
        p('F09', text, '<w:sz w:val="22"/><w:kern w:val="22"/>'),
        p('F10', 'AVATAR office fi fl ffi ffl', style='Features'),
        p('F11', 'AVATAR office fi fl ffi ffl', '<w:kern w:val="0"/><w14:ligatures w14:val="none"/>', 'Features'),
        paragraph(run('F12 AV') + run('ATAR of') + run('fice fi fl ffi ffl'), '<w:pStyle w:val="Features"/>'),
        p('F13', 'café façade Ελληνικά Кириллица AVATAR office', '<w:kern w:val="40"/>' + ligatures)]
    if family == 'Liberation Sans':
        content.append(p('F14', 'of office of office AVATAR To WA', '<w:kern w:val="1"/>'))
    width, height = (11906, 16838) if paper == 'a4' else (12240, 15840)
    entries['word/document.xml'] = (f'<w:document xmlns:w="{W}" xmlns:r="{R}" xmlns:w14="{W14}"><w:body>' + ''.join(content) +
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
    parser.add_argument('--paper', choices=('a4', 'letter'), default='a4')
    parser.add_argument('--disabled', action='store_true')
    parser.add_argument('--compatibility', type=int, choices=(12, 15), default=15)
    parser.add_argument('--family', choices=('noto-serif', 'liberation-sans'), default='noto-serif')
    args = parser.parse_args()
    build(Path(__file__).resolve().parents[3], args.output, args.paper, not args.disabled, args.compatibility,
        'Noto Serif' if args.family == 'noto-serif' else 'Liberation Sans')
