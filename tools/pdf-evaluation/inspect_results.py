"""Independent metadata, text, font and barcode checks of research PDFs and PNGs."""
import argparse
import hashlib
import json
import pathlib
import re
import sys

from PIL import Image
from pypdf import PdfReader

parser = argparse.ArgumentParser()
parser.add_argument('directory', type=pathlib.Path)
parser.add_argument('--decoder-directory', type=pathlib.Path, required=True)
parser.add_argument('--named-images', action='store_true', help='Inspect diagnostic <PDF-stem>-<page>.png files')
args = parser.parse_args()
sys.path.insert(0, str(args.decoder_directory.resolve()))
import zxingcpp

results = []
for filename in sorted(args.directory.glob('*/*/*.pdf')):
    reader = PdfReader(filename)
    fonts = set()
    for page in reader.pages:
        resources = page.get('/Resources', {}).get_object()
        for font in resources.get('/Font', {}).get_object().values():
            obj = font.get_object()
            descendants = obj.get('/DescendantFonts', [])
            base = descendants[0].get_object() if descendants else obj
            descriptor = base.get('/FontDescriptor', {}).get_object()
            fonts.add((str(obj.get('/BaseFont')), any(k in descriptor for k in ('/FontFile', '/FontFile2', '/FontFile3'))))
    barcodes = []
    images = sorted(filename.parent.glob('page-*.png'))
    if args.named_images:
        images = sorted(p for p in filename.parent.glob(f'{filename.stem}-*.png')
                        if re.fullmatch(re.escape(filename.stem) + r'-\d+\.png', p.name))
    for image in images:
        with Image.open(image) as pixels:
            barcodes += [{'page': image.name, 'text': item.text, 'format': str(item.format),
                          'orientation': item.orientation, 'position': str(item.position)}
                         for item in zxingcpp.read_barcodes(pixels)]
    text = '\n'.join(page.extract_text() or '' for page in reader.pages)
    filename.with_suffix('.txt').write_text(text, encoding='utf-8')
    result = dict(pdf=str(filename), backend=filename.parent.parent.name, fixture=filename.parent.name,
                  pages=len(reader.pages), sizes=[list(page.mediabox) for page in reader.pages],
                  version=reader.pdf_header, fonts=sorted(fonts), barcodes=barcodes,
                  rasterPages=len(images), sha256=hashlib.sha256(filename.read_bytes()).hexdigest())
    results.append(result)
    print(json.dumps(result))
(args.directory / 'inspection.json').write_text(json.dumps(results, indent=2), encoding='utf-8')
