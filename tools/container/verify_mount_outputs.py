"""Independently verify generated mount-qualification font bytes and high-resolution barcodes."""
import argparse
import hashlib
import json
import pathlib
import posixpath
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from PIL import Image
from pypdf import PdfReader


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--outputs", type=pathlib.Path, required=True)
    parser.add_argument("--fonts", type=pathlib.Path, required=True)
    parser.add_argument("--poppler-directory", type=pathlib.Path, required=True)
    parser.add_argument("--decoder-directory", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    sys.path.insert(0, str(args.decoder_directory.resolve()))
    import zxingcpp
    expected = {hashlib.sha256(path.read_bytes()).hexdigest() for path in args.fonts.glob("*.ttf")}
    namespace = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    relation = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
    embedded = set()
    with zipfile.ZipFile(args.outputs / "mounted-embedded.docx") as archive:
        relationships = {item.attrib["Id"]: posixpath.normpath("word/" + item.attrib["Target"])
                         for item in ET.fromstring(archive.read("word/_rels/fontTable.xml.rels"))}
        for font in ET.fromstring(archive.read("word/fontTable.xml")):
            for item in font:
                if not item.tag.startswith(namespace + "embed"):
                    continue
                key = item.attrib[namespace + "fontKey"].replace("{", "").replace("}", "").replace("-", "")
                mask = bytes.fromhex(key)[::-1]
                decoded = bytearray(archive.read(relationships[item.attrib[relation + "id"]]))
                for index in range(32):
                    decoded[index] ^= mask[index % 16]
                embedded.add(hashlib.sha256(decoded).hexdigest())
    assert embedded == expected and len(embedded) == 5, (embedded, expected)
    records = []
    for name, value in (("tenant-a", "NF001"), ("tenant-b", "NF002"), ("snapshot-font", "NF001"), ("template-embedded", "NF001")):
        source = args.outputs / (name + ".pdf")
        reader = PdfReader(source)
        assert len(reader.pages) == 2
        for page in reader.pages:
            for font in page["/Resources"].get_object()["/Font"].get_object().values():
                font = font.get_object()
                descendant = font.get("/DescendantFonts", [font])[0].get_object()
                assert any(key in descendant["/FontDescriptor"].get_object() for key in ("/FontFile", "/FontFile2", "/FontFile3"))
        prefix = args.output / name
        subprocess.run([str(args.poppler_directory / "pdftoppm.exe"), "-f", "1", "-singlefile", "-r", "300", "-png", str(source), str(prefix)], check=True, capture_output=True, timeout=60)
        with Image.open(prefix.with_suffix(".png")) as image:
            scans = [(item.text, item.orientation) for item in zxingcpp.read_barcodes(image)]
        assert scans == [(value, -90)], scans
        pages = args.output / (name + "-100")
        pages.mkdir()
        subprocess.run([str(args.poppler_directory / "pdftoppm.exe"), "-r", "100", "-png", str(source), str(pages / "page")], check=True, capture_output=True, timeout=60)
        if name in ("snapshot-font", "template-embedded"):
            for rendered in pages.glob("page-*.png"):
                with Image.open(rendered) as actual, Image.open(args.output / "tenant-a-100" / rendered.name) as reference:
                    assert actual.size == reference.size and actual.tobytes() == reference.tobytes(), rendered
        records.append({"fixture": name, "dpi": 300, "barcode": value, "rotation": -90})
    (args.output / "verification.json").write_text(json.dumps({"embeddedDocxFonts": len(embedded), "scans": records}, indent=2), encoding="utf-8")
    print(json.dumps({"embeddedDocxFonts": len(embedded), "scans": records}))


if __name__ == "__main__":
    main()
