from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from xml.sax.saxutils import unescape
import hashlib, json, re

import argparse

parser = argparse.ArgumentParser(description='Build labels spanning invisible review anchors without replacing references.')
parser.add_argument('--base', type=Path, required=True)
parser.add_argument('--control', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
for paper in ['a4', 'letter']:
    with ZipFile(args.base / 'classic' / paper / 'source.docx') as archive:
        parts = {name: archive.read(name) for name in archive.namelist()}
    document = parts['word/document.xml'].decode()
    document = document.replace('<w:commentRangeStart w:id="0"/>', '', 1)
    match = re.search(r'(<w:r>.*?<w:t(?: [^>]*)?>)(.*?)(</w:t>.*?</w:r>)', document, re.S)
    assert match is not None and '{' not in match[2]
    replacement = (match[1] + '{la' + match[3] + '<w:commentRangeStart w:id="0"/>' +
                   match[1] + 'bel}' + match[3])
    document = document[:match.start()] + replacement + document[match.end():]
    parts['word/document.xml'] = document.encode()
    folder = args.output / paper
    folder.mkdir(parents=True, exist_ok=False)
    with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
        for name, data in parts.items():
            entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
            entry.create_system = 0
            entry.compress_type = ZIP_DEFLATED
            archive.writestr(entry, data)
    (folder / 'data.json').write_text(json.dumps(dict(label=unescape(match[2])), ensure_ascii=False) + '\n')
    control = args.control / paper
    proof = dict(controlResource=f'preferred-table-widths/{paper}',
        note='The label resolves to the original synthetic control title. Word export and native golden are the unchanged control references.',
        sha256={name: hashlib.sha256((control / name).read_bytes()).hexdigest()
                for name in ['source.docx', 'word-reference.pdf', 'page-1.png']})
    (folder / 'expected-control.json').write_text(json.dumps(proof, indent=2) + '\n')
print('Two synthetic labels span invisible comment anchors; expected output reuses existing control goldens.')
