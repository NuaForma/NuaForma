"""Build a synthetic DOCX for the first own-engine/Word comparison using only stdlib."""
import argparse
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'


def run(text, properties=''):
    return f'<w:r><w:rPr>{properties}</w:rPr><w:t xml:space="preserve">{escape(text)}</w:t></w:r>'


def paragraph(content, properties=''):
    return f'<w:p><w:pPr>{properties}</w:pPr>{content}</w:p>'


def build(root, output, entries_only=False):
    logo = '''<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0">
      <wp:extent cx="685800" cy="685800"/><wp:docPr id="1" name="NuaForma logo" descr="NuaForma"/>
      <wp:cNvGraphicFramePr/><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
      <pic:pic><pic:nvPicPr><pic:cNvPr id="1" name="NuaForma logo"/><pic:cNvPicPr/></pic:nvPicPr>
      <pic:blipFill><a:blip r:embed="logo"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
      <pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="685800" cy="685800"/></a:xfrm>
      <a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>
      </wp:inline></w:drawing></w:r>'''
    content = [
        paragraph(logo, '<w:spacing w:before="0" w:after="240" w:line="1440" w:lineRule="exact"/>'),
        paragraph(run('Bevestiging van uw aanvraag'), '<w:pStyle w:val="Title"/>'),
        paragraph(run('24 oktober 2026', '<w:sz w:val="18"/><w:color w:val="666666"/>'), '<w:jc w:val="right"/>'),
        paragraph(run('Geachte lezer,')),
        paragraph(run('Wij bevestigen de ontvangst van uw aanvraag. Deze brief bevat ons logo en verschillende tekststijlen. De inhoud loopt over meerdere regels, zodat ook de regelafbreking zichtbaar wordt.')),
        paragraph(run('Uw referentie: ') + run('NF-2026-0057', '<w:b/>') + run('. Het bedrag is ') + run('€ 1.234,56', '<w:color w:val="008B90"/>') + run('.')),
        paragraph(run('Toelichting in cursief', '<w:rStyle w:val="Emphasis"/>') + run(': café, beëindiging en coördinatie blijven leesbaar.')),
        paragraph(run('Wij houden u op de hoogte', '<w:b/>'), '<w:jc w:val="center"/><w:spacing w:before="160" w:after="240"/>'),
        paragraph(run('Deze ingesprongen alinea heeft een afwijkende eerste regel. Ook de tweede regel behoudt zijn eigen linkermarge en de rechterzijde blijft binnen het tekstgebied van de brief.'), '<w:ind w:left="480" w:right="240" w:firstLine="240"/>'),
        paragraph(run('Met vriendelijke groet,'), '<w:spacing w:before="240" w:after="120"/>'),
        paragraph(run('Team NuaForma', '<w:b/>')),
    ]
    document = f'''<w:document xmlns:w="{W}" xmlns:r="{R}"
       xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
       xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">
       <w:body>{''.join(content)}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>
       <w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="0" w:footer="0" w:gutter="0"/>
       </w:sectPr></w:body></w:document>'''
    styles = f'''<w:styles xmlns:w="{W}"><w:docDefaults><w:rPrDefault><w:rPr>
      <w:rFonts w:ascii="Liberation Sans" w:hAnsi="Liberation Sans"/><w:sz w:val="22"/>
      </w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:before="0" w:after="160" w:line="320" w:lineRule="exact"/>
      </w:pPr></w:pPrDefault></w:docDefaults>
      <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
      <w:style w:type="paragraph" w:styleId="HeadingBase"><w:name w:val="Heading Base"/><w:basedOn w:val="Normal"/>
      <w:rPr><w:b/><w:sz w:val="36"/></w:rPr></w:style>
      <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="HeadingBase"/>
      <w:pPr><w:spacing w:before="0" w:after="240" w:line="480" w:lineRule="exact"/></w:pPr></w:style>
      <w:style w:type="character" w:styleId="Emphasis"><w:name w:val="Emphasis"/><w:rPr><w:i/></w:rPr></w:style>
      </w:styles>'''
    entries = {
        '[Content_Types].xml': '''<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="png" ContentType="image/png"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
          <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
          </Types>''',
        '_rels/.rels': f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="document" Type="{R}/officeDocument" Target="word/document.xml"/></Relationships>',
        'word/document.xml': document,
        'word/styles.xml': styles,
        'word/_rels/document.xml.rels': f'<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="styles" Type="{R}/styles" Target="styles.xml"/><Relationship Id="logo" Type="{R}/image" Target="media/logo.png"/></Relationships>',
        'word/media/logo.png': (root / 'assets/branding/nuaforma-icon-transparent.png').read_bytes(),
    }
    if entries_only:
        return entries
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'w') as archive:
        for name, value in sorted(entries.items()):
            info = ZipInfo(name, date_time=(2026, 10, 24, 0, 0, 0))
            info.create_system = 0
            info.compress_type = ZIP_DEFLATED
            archive.writestr(info, value.encode('utf-8') if isinstance(value, str) else value)
    print('Synthetic DOCX written:', output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    build(Path(__file__).resolve().parents[3], args.output)
