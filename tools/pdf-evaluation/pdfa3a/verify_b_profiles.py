"""Measure B-profile page parity and barcode scanability against the qualified standard output."""
import argparse
import json
import pathlib
import subprocess
import sys
from PIL import Image, ImageChops
from pypdf import PdfReader


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("matrix", type=pathlib.Path)
    parser.add_argument("destination", type=pathlib.Path)
    parser.add_argument("--poppler", type=pathlib.Path, required=True)
    parser.add_argument("--decoder", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[3]
    args.destination.mkdir(parents=True, exist_ok=False)
    sys.path.insert(0, str(args.decoder.resolve()))
    import zxingcpp
    records = []
    for fixture in ("simple", "letter", "complex", "barcode", "watermark"):
        baseline = root / "build/pdfa-feasibility/review-final" / fixture / "standard"
        for profile in ("1b", "2b", "3b"):
            source = args.matrix / fixture / ("pdfa" + profile) / "output/input.pdf"
            pages = args.destination / fixture / profile
            pages.mkdir(parents=True)
            subprocess.run([str(args.poppler / "pdftoppm.exe"), "-r", "100", "-png", str(source), str(pages / "page")],
                           check=True, capture_output=True, timeout=60)
            reader = PdfReader(source)
            assert reader.pdf_header == ("%PDF-1.4" if profile == "1b" else "%PDF-1.7")
            assert not reader.is_encrypted and not reader.attachments and "/AcroForm" not in reader.trailer["/Root"]
            actual = sorted(pages.glob("page-*.png"))
            expected = sorted(baseline.glob("page-*.png"))
            assert len(actual) == len(expected)
            differences = []
            scans = []
            for left, right in zip(actual, expected):
                with Image.open(left) as image, Image.open(right) as reference:
                    assert image.size == reference.size
                    difference = ImageChops.difference(image.convert("RGB"), reference.convert("RGB"))
                    differences.append(difference.getbbox())
                    scans += [(item.text, item.orientation) for item in zxingcpp.read_barcodes(image)]
            if fixture == "barcode":
                assert scans == [("NF001", -90)], scans
                subprocess.run([str(args.poppler / "pdftoppm.exe"), "-f", "1", "-singlefile", "-r", "300", "-png",
                                str(source), str(pages / "barcode-300")], check=True, capture_output=True, timeout=60)
                with Image.open(pages / "barcode-300.png") as image:
                    assert [(item.text, item.orientation) for item in zxingcpp.read_barcodes(image)] == [("NF001", -90)]
            records.append({"fixture": fixture, "profile": profile, "pages": len(reader.pages), "pixelDifferences": differences, "scans": scans})
            (args.destination / "verification.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
            print(fixture, profile, differences, flush=True)


if __name__ == "__main__":
    main()
