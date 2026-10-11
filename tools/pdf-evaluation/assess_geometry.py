"""Independent PDF vector/text coordinates and full-page scans vs Word's oracle.

Reports measured deviations. Never upgrades a scan into layout acceptance, and
never changes the expected source or tolerances to conceal a failing comparison.
"""
import argparse
import json
import pathlib
import re
import sys
import zipfile

import pdfplumber
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('copies', type=pathlib.Path)
parser.add_argument('converted', type=pathlib.Path)
parser.add_argument('word', type=pathlib.Path)
parser.add_argument('--decoder-directory', type=pathlib.Path, required=True)
args = parser.parse_args()
sys.path.insert(0, str(args.decoder_directory.resolve()))
import zxingcpp


def geometry(path, width, height):
    with pdfplumber.open(path) as pdf:
        page = pdf.pages[0]
        frames = [r for r in page.rects + page.curves if r['stroke'] and
                  abs(r['width'] - width) <= 0.5 and abs(r['height'] - height) <= 0.5]
        assert len(frames) == 1, (str(path), len(frames))
        frame = frames[0]
        chars = [c for c in page.chars if c['text'].strip() and
                 frame['x0'] - 0.5 <= (c['x0'] + c['x1']) / 2 <= frame['x1'] + 0.5 and
                 frame['top'] - 0.5 <= (c['top'] + c['bottom']) / 2 <= frame['bottom'] + 0.5]
        return dict(frame={k: frame[k] for k in ('x0', 'top', 'x1', 'bottom', 'linewidth')},
                    glyphOrigins=[dict(text=c['text'], font=c['fontname'], x=c['matrix'][4], y=c['matrix'][5]) for c in chars],
                    pageCount=len(pdf.pages), pageSizes=[(p.width, p.height) for p in pdf.pages])


records = []
for copy in json.loads((args.copies / 'copies.json').read_text()):
    name = copy['fixture']
    source = pathlib.Path(copy['source'])
    with zipfile.ZipFile(source) as package:
        xml = package.read('word/document.xml').decode()
    style = re.search(r'<v:shape\b[^>]*id="MarginBox"[^>]*style="([^"]+)"', xml)[1]
    css = dict(s.split(':', 1) for s in style.split(';') if ':' in s)
    width, height = float(css['height'][:-2]), float(css['width'][:-2])
    before = geometry(args.word / name / 'reference.pdf', width, height)
    after = geometry(args.converted / 'libreoffice' / name / f'{name}.pdf', width, height)
    assert before['pageSizes'] == after['pageSizes'], name
    deltas = {k: after['frame'][k] - before['frame'][k] for k in before['frame']}
    glyphs = []
    assert len(before['glyphOrigins']) == len(after['glyphOrigins']), name
    for left, right in zip(before['glyphOrigins'], after['glyphOrigins']):
        assert left['text'] == right['text'], name
        glyphs.append(dict(text=left['text'], deltaX=right['x'] - left['x'], deltaY=right['y'] - left['y']))
    scans = []
    for png in sorted((args.converted / 'libreoffice' / name).glob('page-*.png')):
        with Image.open(png) as image:
            scans.extend(dict(page=png.name, text=b.text, orientation=b.orientation, position=str(b.position))
                         for b in zxingcpp.read_barcodes(image))
    if name.startswith('barcode-'):
        assert any(s['text'] == 'NF001' and s['orientation'] == -90 for s in scans), name
    records.append(dict(fixture=name, reference=before, converted=after, frameDeltaPoints=deltas,
                        glyphDeltasPoints=glyphs, scans=scans))
(args.converted / 'geometry.json').write_text(json.dumps(records, indent=2))
print(json.dumps([dict(fixture=r['fixture'], frameDelta=r['frameDeltaPoints'], glyphDeltas=r['glyphDeltasPoints'], scans=r['scans']) for r in records]))
