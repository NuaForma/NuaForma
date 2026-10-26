"""Deterministic synthetic footnote/endnote fixtures for the shared engine's DOCX adapter."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_fixture import build as base_entries, paragraph, run

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
R = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
KINDS = ['a4', 'letter', 'multipage', 'split-note', 'split-endnote', 'automatic', 'table']


def build(root, output, kind):
    entries = base_entries(root, output, entries_only=True)
    footnotes = []
    endnotes = []
    props = '<w:spacing w:before="0" w:after="120" w:line="320" w:lineRule="exact"/><w:widowControl/>'
    if kind == 'automatic': props = props.replace('w:line="320" w:lineRule="exact"', 'w:line="240" w:lineRule="auto"')

    def reference(identifier, end=False):
        name = 'endnote' if end else 'footnote'
        style = 'EndnoteReference' if end else 'FootnoteReference'
        return f'<w:r><w:rPr><w:rStyle w:val="{style}"/></w:rPr><w:{name}Reference w:id="{identifier}"/></w:r>'

    def note(identifier, text, end=False, extra=0):
        name = 'endnote' if end else 'footnote'
        style = 'Endnote' if end else 'Footnote'
        marker = f'<w:r><w:rPr><w:rStyle w:val="{style}Reference"/></w:rPr><w:{name}Ref/></w:r>'
        contents = paragraph(marker + run(' ' + text) + run(' Belangrijk.', '<w:b/>'), f'<w:pStyle w:val="{style}Text"/>')
        for index in range(extra):
            contents += paragraph(run(f'N{identifier}.{index+1:02d} ' + 'Deze toelichting bevat een aanvullende onderbouwing. ' * 3), f'<w:pStyle w:val="{style}Text"/>')
        return f'<w:{name} w:id="{identifier}">{contents}</w:{name}>'

    blocks = paragraph(run('Voetnoten en eindnoten'), '<w:pStyle w:val="Title"/>')
    blocks += paragraph(run('FN001 Een alinea met een korte voetnoot') + reference(1) + run(' en gewone tekst na de verwijzing.'), props)
    footnotes.append(note(1, 'Een korte toelichting bij de eerste verwijzing.'))
    if kind == 'split-note':
        blocks += paragraph(run('FN002 Deze voetnoot loopt door over meerdere pagina’s') + reference(2) + run('.'), props)
        footnotes.append(note(2, 'Begin van de lange voetnoot — Сведения και στοιχεία.', extra=38))
    else:
        if kind == 'multipage':
            for index in range(1, 18):
                blocks += paragraph(run(f'B{index:03d} De hoofdtekst behoudt zijn eigen regelafstand. ' * 3), props)
        owning = paragraph(run('FN002 Een tweede toelichting') + reference(2) + run(' hoort bij deze alinea.'), props)
        if kind == 'table':
            owning = owning.replace('<w:widowControl/>', '<w:widowControl w:val="0"/>')
            edges = ''.join(f'<w:{side} w:val="single" w:sz="4" w:color="B8C5CC"/>' for side in ['top', 'left', 'bottom', 'right', 'insideH', 'insideV'])
            padding = ''.join(f'<w:{side} w:w="{value}" w:type="dxa"/>' for side, value in [('top',80),('bottom',80),('left',120),('right',120)])
            owning = '<w:tbl><w:tblPr><w:tblW w:w="9600" w:type="dxa"/><w:tblInd w:w="120" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblBorders>' + edges + '</w:tblBorders><w:tblCellMar>' + padding + '</w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="9600"/></w:tblGrid><w:tr><w:trPr><w:cantSplit/></w:trPr><w:tc><w:tcPr><w:tcW w:w="9600" w:type="dxa"/></w:tcPr>' + owning + '</w:tc></w:tr></w:tbl>'
        blocks += owning
        footnotes.append(note(2, 'Een langere toelichting met café, coördinatie, Сведения και στοιχεία. ' * 4))
        blocks += paragraph(run('EN001 De eindnoot verwijst naar een bronvermelding') + reference(1, True) + run('.'), props)
        endnotes.append(note(1, 'Bronvermelding aan het einde van het document.', True, extra=65 if kind == 'split-endnote' else 0))
    blocks += paragraph(run('END De laatste alinea van de hoofdtekst.'), props)
    original = entries['word/document.xml']
    section = '<w:sectPr>' + original.split('<w:sectPr>', 1)[1].split('</w:sectPr>', 1)[0] + '</w:sectPr>'
    if kind == 'letter': section = section.replace('w:w="11906" w:h="16838"', 'w:w="12240" w:h="15840"')
    section = section.replace('<w:sectPr>', '<w:sectPr><w:footnotePr><w:pos w:val="pageBottom"/><w:numFmt w:val="decimal"/><w:numStart w:val="1"/><w:numRestart w:val="continuous"/></w:footnotePr><w:endnotePr><w:pos w:val="docEnd"/><w:numFmt w:val="decimal"/><w:numStart w:val="1"/><w:numRestart w:val="continuous"/></w:endnotePr>')
    entries['word/document.xml'] = original.split('<w:body>', 1)[0] + '<w:body>' + blocks + section + '</w:body></w:document>'
    styles = ''
    for name in ['Footnote', 'Endnote']:
        styles += f'<w:style w:type="character" w:styleId="{name}Reference"><w:name w:val="{name} Reference"/><w:rPr><w:vertAlign w:val="superscript"/></w:rPr></w:style>'
        styles += f'<w:style w:type="paragraph" w:styleId="{name}Text"><w:name w:val="{name} Text"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="exact"/><w:widowControl w:val="0"/></w:pPr><w:rPr><w:sz w:val="18"/></w:rPr></w:style>'
    entries['word/styles.xml'] = entries['word/styles.xml'].replace('</w:styles>', styles + '</w:styles>')
    for name, items in [('footnote', footnotes), ('endnote', endnotes)]:
        if not items: continue
        special = f'<w:{name} w:type="separator" w:id="-1">' + paragraph('<w:r><w:separator/></w:r>', '<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="exact"/>') + f'</w:{name}>'
        special += f'<w:{name} w:type="continuationSeparator" w:id="0">' + paragraph('<w:r><w:continuationSeparator/></w:r>', '<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="exact"/>') + f'</w:{name}>'
        entries[f'word/{name}s.xml'] = f'<w:{name}s xmlns:w="{W}">' + special + ''.join(items) + f'</w:{name}s>'
        entries['[Content_Types].xml'] = entries['[Content_Types].xml'].replace('</Types>', f'<Override PartName="/word/{name}s.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.{name}s+xml"/></Types>')
        entries['word/_rels/document.xml.rels'] = entries['word/_rels/document.xml.rels'].replace('</Relationships>', f'<Relationship Id="{name}s" Type="{R}/{name}s" Target="{name}s.xml"/></Relationships>')
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'w') as archive:
        for name, value in sorted(entries.items()):
            info = ZipInfo(name, date_time=(2026, 10, 26, 0, 0, 0)); info.create_system = 0; info.compress_type = ZIP_DEFLATED
            archive.writestr(info, value.encode('utf-8') if isinstance(value, str) else value)


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[3]; output = Path(sys.argv[1])
    for kind in KINDS: build(root, output/kind/'source.docx', kind)
