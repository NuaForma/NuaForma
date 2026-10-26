"""Read-only Word/engine field proof: exact content/style per page, excluding only TOC leader dots."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from zipfile import ZipFile
from xml.etree import ElementTree as ET
import pdfplumber


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inspect(path):
    result = []
    with pdfplumber.open(path) as pdf:
        for index, page in enumerate(pdf.pages):
            chars = [c for c in page.chars if not c['text'].isspace() and not (index == 0 and c['text'] == '.')]
            # Retain word boundaries and order; removing a leader replaces it with a space.
            text = page.extract_text(x_tolerance=1, y_tolerance=2)
            if index == 0:
                text = re.sub(r'(?:\.[ ]*){2,}', ' ', text)
            text = ' '.join(text.split())
            result.append((page.width, page.height, text, chars))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path); parser.add_argument('reference', type=Path)
    parser.add_argument('candidate', type=Path); parser.add_argument('output', type=Path)
    args = parser.parse_args()
    provenance = json.loads(Path(str(args.reference) + '.provenance.json').read_text(encoding='utf-8-sig'))
    assert digest(args.source) == provenance['sourceSha256']
    assert digest(args.reference) == provenance['pdfSha256'] and provenance['updatedFields']
    with ZipFile(args.source) as archive:
        source = ET.fromstring(archive.read('word/document.xml'))
    w = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
    assert all('..' not in node.text for node in source.iter(w + 't') if node.text), 'Literal dot runs would conflict with this narrowly scoped leader policy'
    before, after = inspect(args.reference), inspect(args.candidate)
    assert len(before) == len(after) == 3
    report = dict(sourceSha256=digest(args.source), wordSha256=digest(args.reference), candidateSha256=digest(args.candidate),
                  policy='Ignore generated dot leaders on the first (contents) page only; preserve all other text and punctuation.', pages=[])
    for index, (reference, candidate) in enumerate(zip(before, after), 1):
        assert abs(reference[0] - candidate[0]) < .1 and abs(reference[1] - candidate[1]) < .1
        assert reference[2] == candidate[2], (index, reference[2], candidate[2])
        a, b = reference[3], candidate[3]
        assert ''.join(c['text'] for c in a) == ''.join(c['text'] for c in b), f'Glyph content/order differs on page {index}'
        for left, right in zip(a, b):
            assert left['fontname'].split('+')[-1] == right['fontname'].split('+')[-1], (index, left['text'], left['fontname'], right['fontname'])
            # Word's fixed-format export rounds this source's 11 pt text to 11.04 pt.
            assert abs(left['size'] - right['size']) <= .05
        report['pages'].append(dict(page=index, sameContent=True, sameFontFaces=True, sizesWithinWordExportPrecision=True, glyphs=len(a),
            maxFontSizeDeltaPoints=max(abs(l['size'] - r['size']) for l, r in zip(a, b)),
            maxGlyphStartDeltaPoints=max(abs(l['x0'] - r['x0']) for l, r in zip(a, b)),
            maxGlyphEndDeltaPoints=max(abs(l['x1'] - r['x1']) for l, r in zip(a, b)),
            maxGlyphTopDeltaPoints=max(abs(l['top'] - r['top']) for l, r in zip(a, b))))
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
