"""Inspect final service PDF/A identifiers, embedded fonts, page parity and barcode scans."""
import argparse
import json
import pathlib
import subprocess
import sys
from PIL import Image, ImageChops
from pypdf import PdfReader


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", type=pathlib.Path)
    parser.add_argument("destination", type=pathlib.Path)
    parser.add_argument("--poppler", type=pathlib.Path, required=True)
    parser.add_argument("--decoder", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.destination.mkdir(parents=True, exist_ok=False)
    sys.path.insert(0, str(args.decoder.resolve()))
    import zxingcpp
    records = []
    for source in sorted(args.service.glob("*.pdf")):
        reader = PdfReader(source)
        expected = 1 if source.stem.endswith("pdfa-1b") else 2 if source.stem == "pdfa-2b" else None if source.stem.endswith("standard") else 3
        assert reader.pdf_header == ("%PDF-1.4" if expected == 1 else "%PDF-1.7")
        assert not reader.is_encrypted and not reader.attachments and "/AcroForm" not in reader.trailer["/Root"]
        metadata = reader.xmp_metadata
        assert str(metadata.pdfaid_part if metadata else None) == str(expected), source
        if expected:
            assert metadata.pdfaid_conformance == "B"
        for page in reader.pages:
            for font in page.get("/Resources", {}).get_object().get("/Font", {}).get_object().values():
                font = font.get_object()
                base = font.get("/DescendantFonts", [font])[0].get_object()
                assert any(name in base["/FontDescriptor"].get_object() for name in ("/FontFile", "/FontFile2", "/FontFile3"))
        target = args.destination / source.stem
        target.mkdir()
        subprocess.run([str(args.poppler / "pdftoppm.exe"), "-r", "100", "-png", str(source), str(target / "page")],
                       check=True, capture_output=True, timeout=60)
        if source.stem in ("tenant-a", "tenant-b"):
            subprocess.run([str(args.poppler / "pdftoppm.exe"), "-f", "1", "-singlefile", "-r", "300", "-png",
                            str(source), str(target / "barcode-300")], check=True, capture_output=True, timeout=60)
            with Image.open(target / "barcode-300.png") as image:
                expected_text = "NF001" if source.stem == "tenant-a" else "NF002"
                assert [(item.text, item.orientation) for item in zxingcpp.read_barcodes(image)] == [(expected_text, -90)]
        records.append({"file": source.name, "part": expected, "pages": len(reader.pages)})
    left = args.destination / "watermark-opaque-pdfa-1b"
    right = args.destination / "watermark-opaque-standard"
    actual = sorted(left.glob("page-*.png"))
    expected = sorted(right.glob("page-*.png"))
    assert len(actual) == len(expected) == 5
    for left, right in zip(actual, expected):
        with Image.open(left) as image, Image.open(right) as reference:
            assert image.size == reference.size and ImageChops.difference(image.convert("RGB"), reference.convert("RGB")).getbbox() is None
    (args.destination / "inspection.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
    print(json.dumps(records))


if __name__ == "__main__":
    main()
