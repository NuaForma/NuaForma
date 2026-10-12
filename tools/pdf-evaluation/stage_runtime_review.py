"""Stage and rasterize trusted runtime qualification output for existing QA checks."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('run', type=pathlib.Path)
    parser.add_argument('destination', type=pathlib.Path)
    parser.add_argument('--poppler-directory', type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.destination.mkdir(parents=True, exist_ok=False)
    records = json.loads((args.run / 'results.json').read_text(encoding='utf-8-sig'))
    assert len(records) == 14
    for record in records:
        name = record['fixture']
        source = args.run / name / 'output/input.pdf'
        target = args.destination / 'libreoffice' / name
        target.mkdir(parents=True, exist_ok=False)
        pdf = target / f'{name}.pdf'
        shutil.copy2(source, pdf)
        assert hashlib.sha256(source.read_bytes()).digest() == hashlib.sha256(pdf.read_bytes()).digest()
        subprocess.run([str(args.poppler_directory / 'pdftoppm.exe'), '-r', '100', '-png', str(pdf), str(target / 'page')],
                       check=True, timeout=120, capture_output=True)
        record['pdfSha256'] = hashlib.sha256(pdf.read_bytes()).hexdigest()
    (args.destination / 'results.json').write_text(json.dumps(records, indent=2), encoding='utf-8')
    print(json.dumps({'documents': len(records), 'pages': len(list(args.destination.glob('libreoffice/*/page-*.png')))}))


if __name__ == '__main__':
    main()
