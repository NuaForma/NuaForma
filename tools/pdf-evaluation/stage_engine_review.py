"""Rasterize and independently inspect synthetic full-engine container qualification."""
import argparse
import json
import pathlib
import subprocess
import sys

from PIL import Image
from pypdf import PdfReader

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('engine', type=pathlib.Path)
parser.add_argument('destination', type=pathlib.Path)
parser.add_argument('--poppler-directory', type=pathlib.Path, required=True)
parser.add_argument('--decoder-directory', type=pathlib.Path, required=True)
parser.add_argument('--reference-run', type=pathlib.Path, required=True)
parser.add_argument('--fixtures', nargs='+', choices=('tenant-a', 'tenant-b', 'print-preview', 'template-embedded', 'watermark-dynamic'),
                    default=('tenant-a', 'tenant-b', 'print-preview', 'template-embedded', 'watermark-dynamic'))
parser.add_argument('--compare-all-pages', action='store_true')
args = parser.parse_args()
sys.path.insert(0, str(args.decoder_directory.resolve()))
import zxingcpp

args.destination.mkdir(parents=True, exist_ok=False)
records = []
for name in args.fixtures:
    source = args.engine / f'{name}.pdf'
    target = args.destination / 'libreoffice' / name
    target.mkdir(parents=True, exist_ok=False)
    subprocess.run([str(args.poppler_directory / 'pdftoppm.exe'), '-r', '100', '-png', str(source), str(target / 'page')],
                   check=True, capture_output=True, timeout=60)
    reader = PdfReader(source)
    assert reader.pdf_header == '%PDF-1.7' and not reader.is_encrypted
    text = '\n'.join(page.extract_text() or '' for page in reader.pages)
    if name in ('tenant-a', 'tenant-b'):
        own, other = ('NF001', 'NF002') if name == 'tenant-a' else ('NF002', 'NF001')
        assert own in text and other not in text
    if name == 'watermark-dynamic':
        assert len(reader.pages) == 5
        for page in reader.pages:
            assert '{customer.name}' not in (page.extract_text() or '')
        # LibreOffice exports watermark text paths as vector outlines, not extractable PDF text.
        # Compare every pixel to the independently generated, visually reviewed single-watermark reference.
        for page in sorted(target.glob('page-*.png')):
            reference = args.reference_run / 'libreoffice/watermark-dynamic' / page.name
            with Image.open(page) as actual, Image.open(reference) as expected:
                assert actual.size == expected.size and actual.tobytes() == expected.tobytes(), page
    fonts = set()
    for page in reader.pages:
        for font in page.get('/Resources', {}).get_object().get('/Font', {}).get_object().values():
            obj = font.get_object()
            base = obj.get('/DescendantFonts', [obj])[0].get_object()
            descriptor = base.get('/FontDescriptor', {}).get_object()
            assert any(key in descriptor for key in ('/FontFile', '/FontFile2', '/FontFile3'))
            fonts.add(str(obj.get('/BaseFont')))
    scans = []
    for page in sorted(target.glob('page-*.png')):
        with Image.open(page) as image:
            scans += [dict(text=barcode.text, rotation=barcode.orientation) for barcode in zxingcpp.read_barcodes(image)]
    if name != 'watermark-dynamic':
        assert scans == [dict(text='NF002' if name == 'tenant-b' else 'NF001', rotation=-90)]
    if args.compare_all_pages:
        assert len(list(target.glob('page-*.png'))) == len(list((args.reference_run / 'libreoffice' / name).glob('page-*.png')))
        for page in sorted(target.glob('page-*.png')):
            reference = args.reference_run / 'libreoffice' / name / page.name
            with Image.open(page) as actual, Image.open(reference) as expected:
                assert actual.size == expected.size and actual.tobytes() == expected.tobytes(), page
    records.append(dict(fixture=name, pages=len(reader.pages), fonts=sorted(fonts), scans=scans))
(args.destination / 'inspection.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
print(json.dumps(records))
