"""Audit source table markers independently of the renderer and Word pagination."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
from zipfile import ZipFile
from xml.etree import ElementTree
import pdfplumber

MARKER = re.compile(r'\b(?:M\d{2}|N\d{2}-\d|[ASK]\d{2}-\d{3})\b')


def audit(source, reference, candidate):
    with ZipFile(source) as archive:
        xml = ElementTree.fromstring(archive.read('word/document.xml'))
    expected = Counter(MARKER.findall(' '.join(node.text or '' for node in xml.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}t'))))
    assert expected and all(count == 1 for count in expected.values()), 'Fixture markers must be unique'
    result = dict(sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(), expectedMarkerCount=len(expected))
    for role, path in [('reference', reference), ('candidate', candidate)]:
        pages, found = [], Counter()
        with pdfplumber.open(path) as pdf:
            for index, page in enumerate(pdf.pages, 1):
                markers = MARKER.findall(page.extract_text(x_tolerance=1, y_tolerance=1))
                found.update(markers)
                pages.append(dict(page=index, markers=markers,
                    glyphsOutsidePhysicalPage=sum(char['top'] < 0 or char['bottom'] > page.height or char['x0'] < 0 or char['x1'] > page.width for char in page.chars if char['text'].strip())))
        result[role] = dict(sha256=hashlib.sha256(path.read_bytes()).hexdigest(), pages=pages,
            missingMarkers=sorted((expected - found).elements()), unexpectedOrDuplicateMarkers=sorted((found - expected).elements()))
    candidate_record = result['candidate']
    assert not candidate_record['missingMarkers'] and not candidate_record['unexpectedOrDuplicateMarkers'], 'Candidate lost or duplicated source content'
    assert all(page['glyphsOutsidePhysicalPage'] == 0 for page in candidate_record['pages']), 'Candidate paints text outside its page'
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('reference', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    report = audit(args.source, args.reference, args.candidate)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps({role: report[role]['missingMarkers'] for role in ['reference', 'candidate']}))
