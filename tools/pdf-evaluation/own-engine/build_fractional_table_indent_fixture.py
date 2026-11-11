"""Build physical table-indent spelling controls without overwriting references."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED


def build(base, output):
    for mode in ['12', '15']:
        for paper in ['a4', 'letter']:
            with ZipFile(base / (mode + '-explicit') / paper / 'source.docx') as archive:
                original = {name: archive.read(name) for name in archive.namelist()}
            for name, value in [('fraction', '100.8'), ('floor', '100'), ('ceil', '101'), ('zero', '0.0'), ('style-zero', '0.0')]:
                parts = original.copy()
                document = parts['word/document.xml'].decode()
                old = '<w:tblInd w:w="0" w:type="dxa"/>'
                assert document.count(old) == 1
                if name == 'style-zero':
                    document = document.replace(old, '').replace('<w:tblPr>', '<w:tblPr><w:tblStyle w:val="FractionIndent"/>', 1)
                    style = '<w:style w:type="table" w:styleId="FractionIndent"><w:name w:val="Fraction indentation"/><w:tblPr><w:tblInd w:w="0.0" w:type="dxa"/></w:tblPr></w:style>'
                    for part in ['word/styles.xml', 'word/stylesWithEffects.xml']:
                        if part in parts:
                            parts[part] = parts[part].decode().replace('</w:styles>', style + '</w:styles>').encode()
                else:
                    document = document.replace(old, f'<w:tblInd w:w="{value}" w:type="dxa"/>')
                parts['word/document.xml'] = document.encode()
                folder = output / (mode + '-' + name) / paper
                folder.mkdir(parents=True, exist_ok=False)
                with ZipFile(folder / 'source.docx', 'x', ZIP_DEFLATED) as archive:
                    for part, data in parts.items():
                        entry = ZipInfo(part, (2025, 1, 1, 0, 0, 0))
                        entry.create_system = 0
                        entry.compress_type = ZIP_DEFLATED
                        archive.writestr(entry, data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    build(args.base, args.output)
