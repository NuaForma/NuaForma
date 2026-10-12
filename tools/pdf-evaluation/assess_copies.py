"""Independent copy immutability and raster-difference checks for the experiment."""
import argparse
import hashlib
import json
import pathlib

from PIL import Image, ImageChops

parser = argparse.ArgumentParser()
parser.add_argument('repository', type=pathlib.Path)
parser.add_argument('copies', type=pathlib.Path)
parser.add_argument('baseline', type=pathlib.Path)
parser.add_argument('experiment', type=pathlib.Path)
args = parser.parse_args()
records = []
for copy in json.loads((args.copies / 'copies.json').read_text(encoding='utf-8')):
    source = args.repository / copy['source']
    assert hashlib.sha256(source.read_bytes()).hexdigest() == copy['sourceSha256'], source
    name = copy['fixture']
    before = sorted((args.baseline / 'libreoffice' / name).glob('page-*.png'))
    after = sorted((args.experiment / 'libreoffice' / name).glob('page-*.png'))
    assert len(before) == len(after) > 0, name
    pages = []
    for left, right in zip(before, after):
        with Image.open(left) as original, Image.open(right) as normalized:
            assert original.size == normalized.size, name
            bbox = ImageChops.difference(original.convert('RGB'), normalized.convert('RGB')).getbbox()
            pages.append(dict(page=right.name, changedBounds=bbox, image=str(right)))
    records.append(dict(fixture=name, sourceUnchanged=True, pages=pages))
for first, second in (('roundtrip-before', 'roundtrip-after'), ('complex-expected', 'complex-generated'),
                      ('barcode-expected', 'barcode-generated'), ('watermark-plain', 'watermark-transparent')):
    left = sorted((args.experiment / 'libreoffice' / first).glob('page-*.png'))
    right = sorted((args.experiment / 'libreoffice' / second).glob('page-*.png'))
    assert len(left) == len(right) > 0
    assert all(a.read_bytes() == b.read_bytes() for a, b in zip(left, right)), (first, second)
(args.experiment / 'differences.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
print(json.dumps(records))
