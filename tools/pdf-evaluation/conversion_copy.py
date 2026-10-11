"""Experimental VML normalization for trusted fixtures, NOT a production converter.

Changes only a temporary package copy. Scanability is not a layout acceptance gate.
"""
import argparse
import copy
from decimal import Decimal
import hashlib
import json
import pathlib
import re
import zipfile


def normalize(xml):
    count = 0

    def shape(match):
        nonlocal count
        source = match.group()
        opening, content = source.split('>', 1)
        style_match = re.search(r'\bstyle="([^"]*)"', opening)
        if not style_match or '<w:txbxContent' not in content:
            return source
        style = dict(item.split(':', 1) for item in style_match[1].split(';') if ':' in item)
        if style.get('rotation') != '90' or 'mso-layout-flow-alt:bottom-to-top' not in content:
            return source
        if not all(re.fullmatch(r'[0-9]+(?:\.[0-9]+)?pt', style.get(k, '')) and
                   Decimal(style[k][:-2]) > 0 for k in ('width', 'height')):
            raise ValueError('Experiment requires explicit positive point dimensions')
        style['width'], style['height'] = style['height'], style['width']
        style['rotation'] = '0'
        replacement = ';'.join(f'{key}:{value}' for key, value in style.items())
        opening = opening[:style_match.start(1)] + replacement + opening[style_match.end(1):]
        count += 1
        return opening + '>' + content

    return re.sub(r'<v:shape\b.*?</v:shape>', shape, xml, flags=re.S), count


def conversion_copy(source, destination):
    before = hashlib.sha256(source.read_bytes()).hexdigest()
    changed = []
    shapes = 0
    with zipfile.ZipFile(source) as package, zipfile.ZipFile(destination, 'x') as result:
        for entry in package.infolist():
            data = package.read(entry)
            replacement = data
            if entry.filename.startswith('word/') and entry.filename.endswith('.xml'):
                patched, count = normalize(data.decode('utf-8'))
                shapes += count
                if count:
                    replacement = patched.encode('utf-8')
                    changed.append(entry.filename)
            result.writestr(copy.copy(entry), replacement)
    with zipfile.ZipFile(source) as package, zipfile.ZipFile(destination) as result:
        assert package.namelist() == result.namelist()
        for name in package.namelist():
            if name not in changed:
                assert package.read(name) == result.read(name), name
    assert hashlib.sha256(source.read_bytes()).hexdigest() == before
    return dict(source=str(source), sourceSha256=before, changedParts=changed, normalizedShapes=shapes)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=pathlib.Path)
    parser.add_argument('destination', type=pathlib.Path)
    args = parser.parse_args()
    print(json.dumps(conversion_copy(args.source, args.destination)))
