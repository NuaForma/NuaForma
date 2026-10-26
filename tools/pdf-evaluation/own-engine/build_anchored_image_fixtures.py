"""Paragraph-bound images, rectangular wrapping, crop and page movement; deterministic Word QA inputs."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from build_fixture import build as letter_entries, paragraph, run

KINDS = ['left', 'right', 'largest', 'top-bottom', 'page', 'boundary', 'letter', 'crop', 'both-sides', 'multipage']

def build(root, output, kind):
    entries = letter_entries(root, output, entries_only=True)
    original = entries['word/document.xml']
    inline = original.split('<wp:inline', 1)[1].split('</wp:inline>', 1)[0]
    inline = '<wp:inline' + inline + '</wp:inline>'
    inline = inline.replace('685800', '1219200')
    inner = inline.split('>', 1)[1].rsplit('</wp:inline>', 1)[0]
    if kind == 'crop':
        inner = inner.replace('<a:stretch>', '<a:srcRect l="20000" t="10000" r="10000" b="20000"/><a:stretch>')
    horizontal = '<wp:align>right</wp:align>' if kind in ['right', 'letter'] else '<wp:posOffset>0</wp:posOffset>'
    if kind in ['largest', 'both-sides', 'multipage']: horizontal = '<wp:posOffset>1524000</wp:posOffset>'
    wrap = '<wp:wrapTopAndBottom/>' if kind in ['top-bottom', 'boundary'] else '<wp:wrapSquare wrapText="largest"/>'
    if kind == 'page': wrap = '<wp:wrapNone/>'
    if kind in ['both-sides', 'multipage']: wrap = '<wp:wrapSquare wrapText="bothSides"/>'
    vertical = 'page' if kind == 'page' else 'paragraph'
    offset = '2540000' if kind == 'page' else '0'
    anchor = ('<wp:anchor distT="0" distB="76200" distL="76200" distR="76200" simplePos="0" relativeHeight="0" '
              'behindDoc="0" locked="0" layoutInCell="1" allowOverlap="1"><wp:simplePos x="0" y="0"/>'
              f'<wp:positionH relativeFrom="margin">{horizontal}</wp:positionH>'
              f'<wp:positionV relativeFrom="{vertical}"><wp:posOffset>{offset}</wp:posOffset></wp:positionV>'
              + inner.replace('<wp:docPr', wrap + '<wp:docPr', 1) + '</wp:anchor>')
    drawing = '<w:r><w:drawing>' + anchor + '</w:drawing></w:r>'
    props = '<w:spacing w:before="0" w:after="120" w:line="320" w:lineRule="exact"/><w:widowControl/>'
    text = 'De afbeelding blijft gekoppeld aan deze alinea. De tekst volgt de beschikbare ruimte naast het beeld en gebruikt daarna weer de volledige breedte. '
    blocks = paragraph(run('Afbeeldingen en tekstomloop'), '<w:pStyle w:val="Title"/>')
    if kind == 'boundary':
        blocks += ''.join(paragraph(run(f'Voorafgaande regel B{i:02d}'), props) for i in range(1, 30))
    blocks += paragraph(drawing + run('A001 ' + text * (90 if kind == 'multipage' else 5)), props)
    blocks += ''.join(paragraph(run(f'A{i:03d} ' + text), props) for i in range(2, 8))
    section = '<w:sectPr>' + original.split('<w:sectPr>', 1)[1].split('</w:sectPr>', 1)[0] + '</w:sectPr>'
    if kind == 'letter': section = section.replace('w:w="11906" w:h="16838"', 'w:w="12240" w:h="15840"')
    entries['word/document.xml'] = original.split('<w:body>', 1)[0] + '<w:body>' + blocks + section + '</w:body></w:document>'
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, 'w') as archive:
        for name, value in sorted(entries.items()):
            info = ZipInfo(name, date_time=(2026, 10, 26, 0, 0, 0)); info.create_system = 0; info.compress_type = ZIP_DEFLATED
            archive.writestr(info, value.encode('utf-8') if isinstance(value, str) else value)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('output', type=Path)
    args = parser.parse_args(); root = Path(__file__).resolve().parents[3]
    for kind in KINDS: build(root, args.output / kind / 'source.docx', kind)
