"""Compare the same synthetic line input exported by Word and the production native reader.

Qualification only: requires pdfplumber and Pillow/PDFium for optional raster review,
never a product or build-time dependency. Does not write any reference resource.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path
import pdfplumber


def audit(word, native):
    result = {'wordSha256': hashlib.sha256(word.read_bytes()).hexdigest(),
              'nativeSha256': hashlib.sha256(native.read_bytes()).hexdigest(), 'pages': []}
    with pdfplumber.open(word) as reference, pdfplumber.open(native) as candidate:
        assert len(reference.pages) == len(candidate.pages), 'Page count changed'
        for index, (a, b) in enumerate(zip(reference.pages, candidate.pages), 1):
            def controls(page):
                values = [w for w in page.extract_words() if re.fullmatch('[BIGDTAP][0-9]{2}', w['text'])]
                assert len(values) == len({w['text'] for w in values}), 'Duplicate control identifier'
                return {w['text']: (w['x0'], w['top']) for w in values}
            aa, bb = controls(a), controls(b)
            assert aa.keys() == bb.keys(), f'Page {index} control placement changed'
            assert [w['text'] for w in a.extract_words()] == [w['text'] for w in b.extract_words()], f'Page {index} text changed'
            dx = max((abs(aa[k][0] - bb[k][0]) for k in aa), default=0)
            dy = max((abs(aa[k][1] - bb[k][1]) for k in aa), default=0)
            assert dx <= .2 and dy <= .2, f'Page {index} text displacement exceeds qualification tolerance'
            size_delta = max(abs(a.width - b.width), abs(a.height - b.height))
            assert size_delta <= .05, 'Page dimensions changed'
            def vector(line):
                points = sorted(line['pts'])
                return (*points[0], *points[-1]), line['linewidth'], (line.get('dash') or ([], 0))[0]
            native_strokes = [vector(line) for line in b.lines]
            word_strokes = [vector(line) for line in a.lines]
            # Word paints the explicit 1.5-pt compound drawing as two filled 0.5-pt rectangles.
            for rect in a.rects:
                if abs(rect['height'] - .5) < .001 and rect['width'] > 8:
                    y = (rect['top'] + rect['bottom']) / 2
                    word_strokes.append(((rect['x0'], y, rect['x1'], y), .5, []))
            for points, width, dash in word_strokes:
                assert any(max(abs(x - y) for x, y in zip(points, pp)) <= .2 and abs(width - ww) <= .05 and
                           len(dash) == len(dd) and all(abs(x - y) <= .2 for x, y in zip(dash, dd))
                           for pp, ww, dd in native_strokes), f'Page {index} line geometry, width or dash changed: {points}'
            result['pages'].append({'page': index, 'controls': len(aa), 'maxTextXPoints': round(dx, 4), 'maxTextYPoints': round(dy, 4),
                                    'textMatches': True, 'maxPageSizeDeltaPoints': round(size_delta, 4), 'wordVectorStrokesMatched': len(word_strokes)})
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('word', type=Path); parser.add_argument('native', type=Path); parser.add_argument('report', type=Path)
    args = parser.parse_args()
    args.report.write_text(json.dumps(audit(args.word, args.native), indent=2) + '\n', encoding='utf-8')
