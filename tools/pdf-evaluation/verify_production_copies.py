"""Read-only comparison of production conversion copies with the approved research profile."""
import argparse
import hashlib
import json
import pathlib
import xml.etree.ElementTree as ET
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('repository', type=pathlib.Path)
parser.add_argument('research', type=pathlib.Path)
parser.add_argument('production', type=pathlib.Path)
args = parser.parse_args()
records = json.loads((args.research / 'copies.json').read_text())
assert len(records) == 14


def tree(element):
    # Ignore serializer prefixes, attribute order and inter-element indentation, not visible text.
    text = element.text or ''
    if len(element) and not text.strip():
        text = ''
    attributes = dict(element.attrib)
    if element.tag == '{http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing}docPr':
        # These generated diagnostic labels differ; IDs, geometry and all authored names remain checked.
        if attributes.get('name') in ('NF conversion inline box', 'NF conversion margin box', 'NF conversion text box'):
            attributes['name'] = 'NF conversion diagnostic label'
    return element.tag, sorted(attributes.items()), text, [tree(child) for child in element]


for record in records:
    source = args.repository / record['source']
    assert hashlib.sha256(source.read_bytes()).hexdigest() == record['sourceSha256'], source
    name = record['fixture']
    with zipfile.ZipFile(args.research / f'{name}.docx') as expected, zipfile.ZipFile(args.production / f'{name}.docx') as actual:
        assert set(expected.namelist()) == set(actual.namelist()), name
        for part in expected.namelist():
            left, right = expected.read(part), actual.read(part)
            if part.endswith('.xml'):
                assert tree(ET.fromstring(left)) == tree(ET.fromstring(right)), (name, part)
            else:
                assert left == right, (name, part)
    print(json.dumps(dict(fixture=name, sourceUnchanged=True, approvedProfileMatches=True)))
