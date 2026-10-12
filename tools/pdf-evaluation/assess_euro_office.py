"""Report independent frame/pagination/raster deviations, including missing frames.

Measurements are evidence, never an implicit tolerance or a qualification pass.
"""
import argparse
import hashlib
import json
import pathlib
import re
import zipfile

import pdfplumber
from PIL import Image
from prepare_copies import FIXTURES


def frames(pdf, width, height):
    with pdfplumber.open(pdf) as document:
        found = {tuple(round(r[k], 5) for k in ('x0', 'top', 'x1', 'bottom'))
                 for r in document.pages[0].rects + document.pages[0].curves
                 if r['stroke'] and abs(r['width'] - width) < 0.05 and abs(r['height'] - height) < 0.05}
    return [dict(zip(('x0', 'top', 'x1', 'bottom'), f)) for f in sorted(found)]


def pixels(path):
    with Image.open(path) as image:
        return image.size, hashlib.sha256(image.convert('RGB').tobytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('repository', type=pathlib.Path)
    parser.add_argument('run', type=pathlib.Path)
    parser.add_argument('word', type=pathlib.Path)
    args = parser.parse_args()
    results = []
    for entry in json.loads((args.run / 'results.json').read_text()):
        name = entry['fixture']
        source = args.repository / 'nuaforma-docx/build' / (FIXTURES[name] + '.docx')
        sha = hashlib.sha256(source.read_bytes()).hexdigest()
        assert sha == entry.get('originalTemplateSha256', entry['sourceSha256']), name
        with zipfile.ZipFile(source) as package:
            xml = package.read('word/document.xml').decode()
        style = re.search(r'<v:shape\b[^>]*id="MarginBox"[^>]*style="([^"]+)"', xml)[1]
        css = dict(s.split(':', 1) for s in style.split(';') if ':' in s)
        profiles = [('margin', float(css['height'][:-2]), float(css['width'][:-2]))]
        if 'id="TextBox1"' in xml:
            profiles += [('ordinary', 260, 35), ('modern', 360, 36)]
        reference = args.word / name / 'reference.pdf'
        converted = args.run / 'eurooffice' / name / (name + '.pdf')
        measurements = []
        for label, width, height in profiles:
            before, after = frames(reference, width, height), frames(converted, width, height)
            delta = {k: after[0][k] - before[0][k] for k in before[0]} if len(before) == len(after) == 1 else None
            measurements.append(dict(object=label, reference=before, converted=after, delta=delta))
        with pdfplumber.open(reference) as left, pdfplumber.open(converted) as right:
            geometry = [(p.width, p.height) for p in left.pages] == [(p.width, p.height) for p in right.pages]
        results.append(dict(fixture=name, sourceUnchanged=True, samePageGeometry=geometry, frames=measurements))
    pairs = []
    for a, b in [('roundtrip-before', 'roundtrip-after'), ('complex-expected', 'complex-generated'),
                 ('barcode-expected', 'barcode-generated'), ('watermark-plain', 'watermark-transparent'),
                 ('watermark-plain', 'watermark-opaque')]:
        left = sorted((args.run / 'eurooffice' / a).glob('page-*.png'))
        right = sorted((args.run / 'eurooffice' / b).glob('page-*.png'))
        pairs.append(dict(first=a, second=b, samePixels=len(left) == len(right) > 0 and
                          all(pixels(x) == pixels(y) for x, y in zip(left, right))))
    report = dict(documents=results, pairs=pairs)
    (args.run / 'comparison.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
