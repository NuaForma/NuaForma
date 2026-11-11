"""Reproduce separator variants from the immutable synthetic control sources; export Word references separately."""
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import argparse
import xml.etree.ElementTree as E

W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
E.register_namespace('w', W)

def q(name):
    return '{' + W + '}' + name

def build(base, output):
    output.mkdir(parents=True, exist_ok=False)
    for paper in ['a4', 'letter']:
        with ZipFile(base / 'control' / paper / 'source.docx') as archive:
            basis = {name: archive.read(name) for name in archive.namelist()}
        for kind in ['control', 'empty-properties', 'size24', 'size36', 'leading-reference', 'styled-leading-reference']:
            parts = dict(basis)
            notes = E.fromstring(parts['word/footnotes.xml'])
            for note in notes:
                if note.get(q('type')) not in ['separator', 'continuationSeparator']:
                    continue
                paragraph = note.find(q('p'))
                if kind in ['empty-properties', 'size24', 'size36']:
                    run = paragraph.find(q('r'))
                    props = E.Element(q('rPr'))
                    run.insert(0, props)
                    if kind != 'empty-properties':
                        E.SubElement(props, q('sz'), {q('val'): kind.removeprefix('size')})
                if kind in ['leading-reference', 'styled-leading-reference']:
                    run = E.Element(q('r'))
                    if kind == 'styled-leading-reference':
                        E.SubElement(E.SubElement(run, q('rPr')), q('rStyle'), {q('val'): 'Reference'})
                    E.SubElement(run, q('footnoteRef'))
                    paragraph.insert(1, run)
            parts['word/footnotes.xml'] = E.tostring(notes, encoding='utf-8')
            target = output / kind / paper
            target.mkdir(parents=True, exist_ok=False)
            with ZipFile(target / 'source.docx', 'x', ZIP_DEFLATED) as archive:
                for name, data in parts.items():
                    entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                    entry.create_system = 0
                    entry.compress_type = ZIP_DEFLATED
                    archive.writestr(entry, data)

    for story, name in [('continuation', 'footnote'), ('endnotes', 'endnote')]:
        part = 'word/' + name + 's.xml'
        for paper in ['a4', 'letter']:
            with ZipFile(base / story / 'control' / paper / 'source.docx') as archive:
                basis = {entry: archive.read(entry) for entry in archive.namelist()}
            for kind in ['control', 'combined']:
                parts = dict(basis)
                notes = E.fromstring(parts[part])
                if kind == 'combined':
                    for note in notes:
                        if note.get(q('type')) not in ['separator', 'continuationSeparator']:
                            continue
                        paragraph = note.find(q('p'))
                        run = paragraph.find(q('r'))
                        props = E.Element(q('rPr'))
                        E.SubElement(props, q('sz'), {q('val'): '24'})
                        run.insert(0, props)
                        prefix = E.Element(q('r'))
                        style = 'FootnoteReference' if story == 'continuation' else 'Reference'
                        E.SubElement(E.SubElement(prefix, q('rPr')), q('rStyle'), {q('val'): style})
                        E.SubElement(prefix, q(name + 'Ref'))
                        paragraph.insert(1, prefix)
                parts[part] = E.tostring(notes, encoding='utf-8')
                target = output / story / kind / paper
                target.mkdir(parents=True, exist_ok=False)
                with ZipFile(target / 'source.docx', 'x', ZIP_DEFLATED) as archive:
                    for entry_name, data in parts.items():
                        entry = ZipInfo(entry_name, (2025, 1, 1, 0, 0, 0))
                        entry.create_system = 0
                        entry.compress_type = ZIP_DEFLATED
                        archive.writestr(entry, data)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.base, args.output)

if __name__ == '__main__':
    main()
