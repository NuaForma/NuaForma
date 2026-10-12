"""Geometry controls on private copies of a trusted synthetic fixture."""
import argparse
import copy
import hashlib
import json
import pathlib
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('source', type=pathlib.Path)
parser.add_argument('directory', type=pathlib.Path)
args = parser.parse_args()
before = hashlib.sha256(args.source.read_bytes()).hexdigest()
args.directory.mkdir(parents=True, exist_ok=False)
variants = {
    'baseline': {},
    'wide': {'width:180pt': 'width:220pt'},
    'tall': {'height:42pt': 'height:60pt'},
    'no-rotation': {'rotation:90': 'rotation:0'},
    'left': {'mso-position-horizontal:right': 'mso-position-horizontal:left'},
    'center': {'mso-position-horizontal:right': 'mso-position-horizontal:center'},
    'margin': {'mso-position-horizontal-relative:page': 'mso-position-horizontal-relative:margin'},
    'larger-page': {'w:w="12240"': 'w:w="14400"'},
    'larger-margin': {'w:right="1440"': 'w:right="2000"'},
    'offsets': {'margin-left:-18pt': 'margin-left:20pt', 'margin-top:-115pt': 'margin-top:30pt'},
    'absolute': {'mso-position-horizontal:right': 'mso-position-horizontal:absolute', 'mso-position-vertical:bottom': 'mso-position-vertical:absolute'},
    'wrap': {'</v:shape>': '<w10:wrap anchorx="page" anchory="margin"/></v:shape>'},
    'wrap-none': {'</v:shape>': '<w10:wrap type="none" anchorx="page" anchory="margin"/></v:shape>'},
    'thick': {'height:42pt': 'height:100pt'},
    'small-margin': {'w:right="1440"': 'w:right="480"'},
}
with zipfile.ZipFile(args.source) as source:
    original = source.read('word/document.xml').decode()
    for name, changes in variants.items():
        text = original
        for old, new in changes.items():
            assert old in text, old
            text = text.replace(old, new)
        with zipfile.ZipFile(args.directory / f'{name}.docx', 'x') as result:
            for entry in source.infolist():
                result.writestr(copy.copy(entry), text.encode() if entry.filename == 'word/document.xml' else source.read(entry))
assert hashlib.sha256(args.source.read_bytes()).hexdigest() == before
print(json.dumps(dict(sourceSha256=before, variants=list(variants))))
