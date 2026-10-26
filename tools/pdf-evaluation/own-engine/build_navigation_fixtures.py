"""Deterministic inert hyperlinks and bookmark targets, including wrapped and cross-page links."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_fixture import build as base_entries, paragraph, run

KINDS = ['a4', 'letter', 'table', 'multipage']

def build(root, output, kind):
    entries = base_entries(root, output, entries_only=True)
    original = entries['word/document.xml']
    props = '<w:spacing w:before="0" w:after="120" w:line="320" w:lineRule="exact"/><w:widowControl/>'
    blue = '<w:color w:val="0050A0"/><w:u w:val="single"/>'
    external = '<w:hyperlink r:id="website" w:history="1">' + run('Lees de documentatie van NuaForma op de website. ' * 4, blue) + '</w:hyperlink>'
    internal = '<w:hyperlink w:anchor="Conclusion" w:history="1">' + run('Ga naar de conclusie', blue) + '</w:hyperlink>'
    mail = '<w:hyperlink r:id="mail">' + run('Stuur een bericht', blue) + '</w:hyperlink>'
    blocks = paragraph(run('Links en bladwijzers'), '<w:pStyle w:val="Title"/>')
    blocks += paragraph(run('N001 ') + external + run(' Einde van de verwijzing.'), props)
    blocks += paragraph(run('N002 ') + internal + run(' en lees verder.'), props)
    blocks += paragraph(run('N003 ') + mail, props)
    if kind == 'table':
        cell = '<w:tc><w:tcPr><w:tcW w:w="4500" w:type="dxa"/></w:tcPr>' + paragraph(internal, props.replace('<w:widowControl/>', '<w:widowControl w:val="0"/>')) + '</w:tc>'
        blocks += '<w:tbl><w:tblPr><w:tblW w:w="4500" w:type="dxa"/><w:tblLayout w:type="fixed"/><w:tblInd w:w="0" w:type="dxa"/><w:tblBorders/><w:tblCellMar><w:top w:w="80" w:type="dxa"/><w:left w:w="120" w:type="dxa"/><w:bottom w:w="80" w:type="dxa"/><w:right w:w="120" w:type="dxa"/></w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w="4500"/></w:tblGrid><w:tr>' + cell + '</w:tr></w:tbl>'
    if kind == 'multipage':
        blocks += ''.join(paragraph(run(f'N{i:03d} ') + external, props) for i in range(4, 20))
    bookmark = '<w:bookmarkStart w:id="1" w:name="Conclusion"/>' + run('Conclusie met een interne bestemming', '<w:b/>') + '<w:bookmarkEnd w:id="1"/>'
    blocks += paragraph(bookmark, props + '<w:pageBreakBefore/>')
    blocks += paragraph(run('N099 Alle tekst blijft behouden; de links openen uitsluitend na een bewuste klik.'), props)
    blocks += paragraph(internal, props)
    section = '<w:sectPr>' + original.split('<w:sectPr>', 1)[1].split('</w:sectPr>', 1)[0] + '</w:sectPr>'
    if kind == 'letter': section = section.replace('w:w="11906" w:h="16838"', 'w:w="12240" w:h="15840"')
    entries['word/document.xml'] = original.split('<w:body>', 1)[0] + '<w:body>' + blocks + section + '</w:body></w:document>'
    relations = '<Relationship Id="website" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://nuaforma.dev/image-comparison/" TargetMode="External"/><Relationship Id="mail" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="mailto:example@example.test" TargetMode="External"/>'
    entries['word/_rels/document.xml.rels'] = entries['word/_rels/document.xml.rels'].replace('</Relationships>', relations + '</Relationships>')
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'w') as archive:
        for name, value in sorted(entries.items()):
            info = ZipInfo(name, date_time=(2026, 10, 26, 0, 0, 0)); info.create_system = 0; info.compress_type = ZIP_DEFLATED
            archive.writestr(info, value.encode('utf-8') if isinstance(value, str) else value)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('output', type=Path)
    args = parser.parse_args(); root = Path(__file__).resolve().parents[3]
    for kind in KINDS: build(root, args.output / kind / 'source.docx', kind)
