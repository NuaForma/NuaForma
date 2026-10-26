"""Audit genuine Word note exports, retaining per-page differences rather than replacing references.

Requires the existing evaluation-only pdfplumber installation, not a product dependency.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

import pdfplumber

KINDS = ['a4', 'letter', 'multipage', 'split-note', 'split-endnote', 'automatic', 'table']


def normalized(value):
    return ' '.join(value.split())


def footnote_stream(document):
    chunks = []
    for page in document.pages:
        rules = [r for r in page.rects if r['height'] < 1 and r['width'] > 140]
        assert len(rules) == 1, 'The split-footnote fixture has one separator per page'
        chunks.append(page.crop((0, rules[0]['bottom'], page.width, page.height)).extract_text())
    return normalized(' '.join(chunks))


def audit(reference_root, engine_root, comparisons, output):
    assert not output.exists(), 'Refuse to overwrite reviewed evidence'
    results = []
    for kind in KINDS:
        source = reference_root / kind / 'source.docx'
        assert source.read_bytes() == (engine_root / kind / 'source.docx').read_bytes(), 'Word and engine require identical synthetic inputs'
        report = json.loads((comparisons / f'notes-compare-{kind}' / 'comparison.json').read_text(encoding='utf-8'))
        word_pdf = reference_root / kind / 'word.pdf'
        engine_pdf = engine_root / kind / 'own-engine.pdf'
        assert report['reference']['sha256'] == hashlib.sha256(word_pdf.read_bytes()).hexdigest(), 'Stale Word comparison'
        assert report['candidate']['sha256'] == hashlib.sha256(engine_pdf.read_bytes()).hexdigest(), 'Stale engine comparison'
        with pdfplumber.open(word_pdf) as word, pdfplumber.open(engine_pdf) as engine:
            assert len(word.pages) == len(engine.pages)
            for a, b in zip(word.pages, engine.pages):
                assert abs(a.width - b.width) < .1 and abs(a.height - b.height) < .1
                assert all(-.1 <= c['x0'] <= c['x1'] <= b.width + .1 and -.1 <= c['top'] <= c['bottom'] <= b.height + .1 for c in b.chars)
            if kind == 'split-note':
                note_text = footnote_stream(word)
                assert note_text == footnote_stream(engine), 'The complete ordered footnote stream must match despite its page boundary'
                assert re.findall(r'N2\.\d{2}', note_text) == [f'N2.{i:02}' for i in range(1, 39)]
                for a, b in zip(word.pages, engine.pages):
                    def body(page):
                        rule = next(r for r in page.rects if r['height'] < 1 and r['width'] > 140)
                        return normalized(page.crop((0, 0, page.width, rule['top'])).extract_text())
                    assert body(a) == body(b), 'The owning body paragraphs must remain on the same pages'
            else:
                assert all(page['sameNormalizedText'] and page['wordBounds']['sameWords'] for page in report['comparison'])
            results.append({'case': kind, 'sourceSha256': hashlib.sha256(source.read_bytes()).hexdigest(),
                'wordSha256': hashlib.sha256(word_pdf.read_bytes()).hexdigest(),
                'engineSha256': hashlib.sha256(engine_pdf.read_bytes()).hexdigest(), 'pages': len(word.pages),
                'sameContentPerPage': all(p['sameNormalizedText'] for p in report['comparison']),
                'completeOrderedFootnoteStream': True if kind == 'split-note' else None,
                'knownDifference': 'One 12-point note line crosses the page boundary; body pagination and complete ordered note content match.' if kind == 'split-note' else None,
                'pageComparisons': report['comparison']})
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'Word note audit passed: {len(results)} sources / {sum(r["pages"] for r in results)} page pairs; one explicitly recorded continuation-boundary difference.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('reference_root', type=Path)
    parser.add_argument('engine_root', type=Path)
    parser.add_argument('comparisons', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    audit(args.reference_root, args.engine_root, args.comparisons, args.output)
