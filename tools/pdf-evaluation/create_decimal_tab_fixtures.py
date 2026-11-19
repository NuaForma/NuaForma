"""Build first-number decimal alignment controls with explicit reference locale metadata."""
import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_STORED

ROOT = Path(__file__).resolve().parents[2]
W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
CASES = {
    'amount-comma': '12,50', 'amount-grouping': '12.50', 'currency': 'USD 12,50',
    'grouped-comma': '1.234,50', 'mixed-separators': '1,234.50', 'unit-suffix': 'ABC 12X',
    'text-only': 'ABC', 'marker-prefix': 'L01_12,50_END', 'negative': '-12,50',
    'parentheses': '(12,50)', 'space-separated': '12 345,50', 'two-numbers': 'AB12CD34',
    'time': '12:50', 'prefix-grouping': 'A.B 12,5', 'fraction': ',50',
    'text-comma': 'ABC, DEF', 'currency-fraction': '$ ,50', 'prefix-comma': 'A,B 12,50',
    'trailing-grouping': '12.', 'repeated-grouping': '12..34', 'grouping-before-unit': '12. X',
    'separator-only': ',',
}


def build(output):
    output.mkdir(parents=True, exist_ok=False)
    for paper in ['a4', 'letter']:
        with ZipFile(ROOT / f'nuaforma-docx-pdf/src/test/resources/list-tabs/decimal/{paper}/source.docx') as archive:
            original = {name: archive.read(name) for name in archive.namelist()}
        for variant, value in CASES.items():
            parts = original.copy()
            document = ET.fromstring(parts['word/document.xml'])
            body = document.find(W + 'body')
            paragraphs = list(body.findall(W + 'p'))
            assert len(paragraphs) == 2
            body.remove(paragraphs[1])
            text = next(node for node in body.iter(W + 't') if node.text == 'L01_12,50_END')
            text.text = value
            parts['word/document.xml'] = ET.tostring(document, encoding='utf-8')
            target = output / variant / paper / 'source.docx'
            target.parent.mkdir(parents=True)
            with ZipFile(target, 'w', ZIP_STORED) as archive:
                for name, data in parts.items():
                    entry = ZipInfo(name, (2026, 11, 18, 0, 0, 0))
                    entry.create_system = 0
                    entry.compress_type = ZIP_STORED
                    archive.writestr(entry, data)
    (output / 'cases.json').write_text(json.dumps(CASES, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    build(parser.parse_args().output)
