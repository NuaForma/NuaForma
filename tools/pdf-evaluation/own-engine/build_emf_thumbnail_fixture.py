"""Build an opaque package-preview control using a public synthetic GDI logo."""
import argparse
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
import struct


def record(kind, *values):
    return struct.pack('<' + 'I' * (2 + len(values)), kind, 8 + len(values) * 4, *values)


def logo():
    records = [record(39, 1, 0, 0x00b07010, 0), record(37, 1), record(37, 0x80000008),
               record(43, 10, 10, 190, 90), record(37, 0x80000000), record(42, 25, 25, 75, 75)]
    end = record(14, 0, 0, 20)
    size = 88 + sum(map(len, records)) + len(end)
    header = struct.pack('<14I2H7I', 1, 88, 0, 0, 200, 100, 0, 0, 20000, 10000, 0x464d4520, 0x10000,
                         size, len(records) + 2, 16, 0, 0, 0, 0, 1000, 1000, 1000, 1000)
    assert len(header) == 88
    return header + b''.join(records) + end


def build(base, output):
    for paper in ['a4', 'letter']:
        with ZipFile(base / paper / 'source.docx') as archive:
            parts = {name: archive.read(name) for name in archive.namelist()}
        assert 'metadata/thumbnail' not in parts['_rels/.rels'].decode()
        assert 'Extension="emf"' not in parts['[Content_Types].xml'].decode()
        parts['docProps/thumbnail.emf'] = logo()
        relation = '<Relationship Id="previewEmf" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail" Target="docProps/thumbnail.emf"/>'
        parts['_rels/.rels'] = parts['_rels/.rels'].decode().replace('</Relationships>', relation + '</Relationships>').encode()
        declaration = '<Default Extension="emf" ContentType="image/x-emf"/>'
        parts['[Content_Types].xml'] = parts['[Content_Types].xml'].decode().replace('</Types>', declaration + '</Types>').encode()
        folder = output / paper
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
