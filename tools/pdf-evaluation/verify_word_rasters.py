"""Check fresh Word oracle pages against previously reviewed Word rasters."""
import argparse
import json
import pathlib
import subprocess

from PIL import Image, ImageChops
from pypdf import PdfReader

parser = argparse.ArgumentParser()
parser.add_argument('copies', type=pathlib.Path)
parser.add_argument('word', type=pathlib.Path)
parser.add_argument('--poppler', type=pathlib.Path, required=True)
args = parser.parse_args()
records = []
for copy in json.loads((args.copies / 'copies.json').read_text()):
    source = pathlib.Path(copy['source'])
    original = sorted(source.parent.glob(source.stem + '-[0-9]*.png'))
    assert original, str(source)
    pdf = args.word / copy['fixture'] / 'reference.pdf'
    reader = PdfReader(pdf)
    assert len(reader.pages) == len(original), str(pdf)
    with Image.open(original[0]) as page:
        dpi = round(page.width * 72 / float(reader.pages[0].mediabox.width))
    prefix = pdf.parent / 'page'
    assert not list(pdf.parent.glob('page-*.png')), str(prefix)
    subprocess.run([str(args.poppler / 'pdftoppm.exe'), '-r', str(dpi), '-png', str(pdf), str(prefix)], check=True, timeout=60)
    fresh = sorted(pdf.parent.glob('page-*.png'))
    assert len(fresh) == len(original)
    for before, after in zip(original, fresh):
        with Image.open(before) as left, Image.open(after) as right:
            assert left.size == right.size, copy['fixture']
            difference = ImageChops.difference(left.convert('RGB'), right.convert('RGB')).getbbox()
        records.append(dict(fixture=copy['fixture'], page=after.name, dpi=dpi, identical=difference is None, changedBounds=difference, image=str(after)))
(args.word / 'raster-check.json').write_text(json.dumps(records, indent=2))
print(json.dumps(records))
