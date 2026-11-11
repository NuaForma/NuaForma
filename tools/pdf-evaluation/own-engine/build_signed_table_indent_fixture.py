"""Build signed direct/inherited Word table-indent controls without overwriting references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def build(base, output):
    variants = [('fraction', '-100.8'), ('truncate', '-100'), ('floor', '-101'),
                ('negative-zero', '-0.8'), ('zero', '0'), ('style-fraction', '-100.8'), ('direct-zero', '0')]
    for mode in ['12', '15']:
        for paper in ['a4', 'letter']:
            with ZipFile(base / (mode + '-explicit') / paper / 'source.docx') as archive:
                original = {name: archive.read(name) for name in archive.namelist()}
            for variant, value in variants:
                parts = original.copy()
                document = parts['word/document.xml'].decode()
                old = '<w:tblInd w:w="0" w:type="dxa"/>'
                assert document.count(old) == 1
                if variant in ['style-fraction', 'direct-zero']:
                    if variant == 'style-fraction':
                        document = document.replace(old, '')
                    document = document.replace('<w:tblPr>', '<w:tblPr><w:tblStyle w:val="SignedIndent"/>', 1)
                    style = '<w:style w:type="table" w:styleId="SignedIndent"><w:name w:val="Synthetic signed indent"/><w:tblPr><w:tblInd w:w="-100.8" w:type="dxa"/></w:tblPr></w:style>'
                    for part in ['word/styles.xml', 'word/stylesWithEffects.xml']:
                        if part in parts:
                            parts[part] = parts[part].decode().replace('</w:styles>', style + '</w:styles>').encode()
                else:
                    document = document.replace(old, f'<w:tblInd w:w="{value}" w:type="dxa"/>')
                parts['word/document.xml'] = document.encode()
                folder = output / (mode + '-' + variant) / paper
                folder.mkdir(parents=True, exist_ok=False)
                with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
                    for name, data in parts.items():
                        entry = ZipInfo(name, (2025, 1, 1, 0, 0, 0))
                        entry.create_system = 0
                        entry.compress_type = ZIP_DEFLATED
                        archive.writestr(entry, data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.base, args.output)
