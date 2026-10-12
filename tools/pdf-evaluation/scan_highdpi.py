"""Independent full-page high-DPI barcode follow-up, without cropping images."""
import argparse
import json
import pathlib
import subprocess
import sys

from PIL import Image

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('run', type=pathlib.Path)
parser.add_argument('--poppler', type=pathlib.Path, required=True)
parser.add_argument('--decoder-directory', type=pathlib.Path, required=True)
args = parser.parse_args()
sys.path.insert(0, str(args.decoder_directory.resolve()))
import zxingcpp

records = []
for name in ('barcode-expected', 'barcode-generated', 'barcode-embedded', 'barcode-isolated'):
    directory = args.run / 'eurooffice' / name
    output = directory / 'scan-300'
    output.mkdir(exist_ok=False)
    executable = args.poppler / 'pdftoppm.exe'
    if not executable.is_file():
        executable = args.poppler / 'pdftoppm'
    subprocess.run([str(executable), '-r', '300', '-png', str(directory / (name + '.pdf')), str(output / 'page')],
                   capture_output=True, check=True, timeout=60)
    found = []
    for png in sorted(output.glob('page-*.png')):
        with Image.open(png) as image:
            found.extend(dict(page=png.name, text=b.text, orientation=b.orientation, position=str(b.position))
                         for b in zxingcpp.read_barcodes(image))
    records.append(dict(fixture=name, dpi=300, scans=found))
(args.run / 'scans-300.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
print(json.dumps(records))
