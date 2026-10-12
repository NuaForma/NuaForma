"""Diagnostic only: materialize observed Word paragraph defaults in a private copy.

The oracle is workstation-specific evidence, never a production dependency or a
universal default policy. Existing source/output DOCX bytes are not changed.
"""
import argparse
import copy
import hashlib
import json
import pathlib
import re
import zipfile
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('source', type=pathlib.Path)
parser.add_argument('oracle', type=pathlib.Path)
parser.add_argument('destination', type=pathlib.Path)
args = parser.parse_args()
before = hashlib.sha256(args.source.read_bytes()).hexdigest()
with zipfile.ZipFile(args.oracle) as oracle:
    xml = oracle.read('word/styles.xml').decode()
    defaults = re.search(r'<w:pPrDefault>.*?</w:pPrDefault>', xml, re.S)[0]
with zipfile.ZipFile(args.source) as source:
    styles = source.read('word/styles.xml').decode()
    assert '<w:pPrDefault' not in styles, 'Do not override explicit template defaults'
    patched = styles.replace('</w:docDefaults>', defaults + '</w:docDefaults>')
    assert patched != styles
    ET.fromstring(patched)
    with zipfile.ZipFile(args.destination, 'x') as result:
        for entry in source.infolist():
            result.writestr(copy.copy(entry), patched.encode() if entry.filename == 'word/styles.xml' else source.read(entry))
    with zipfile.ZipFile(args.destination) as result:
        assert all(source.read(e) == result.read(e.filename) for e in source.infolist() if e.filename != 'word/styles.xml')
assert hashlib.sha256(args.source.read_bytes()).hexdigest() == before
print(json.dumps(dict(sourceSha256=before, changedParts=['word/styles.xml'], observedDefaults=defaults)))
