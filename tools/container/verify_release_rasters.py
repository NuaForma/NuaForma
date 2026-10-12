"""Independently render candidate PDFs, compare all pages and decode rotated reference barcodes."""
import argparse
import json
import pathlib
import subprocess
import sys

from PIL import Image, ImageChops
from pypdf import PdfReader


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("candidate", "baseline", "output", "poppler", "decoder"):
        parser.add_argument("--" + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    sys.path.insert(0, str(args.decoder.resolve()))
    import zxingcpp
    records = []
    for file in sorted(args.candidate.glob("*.pdf")):
        reference = args.baseline / file.name
        assert reference.is_file(), "Missing qualified reference: " + file.name
        reader = PdfReader(file)
        assert not reader.is_encrypted and not reader.attachments
        for page in reader.pages:
            fonts = page.get("/Resources", {}).get_object().get("/Font", {})
            for font in fonts.get_object().values() if hasattr(fonts, "get_object") else fonts.values():
                font = font.get_object()
                font = font.get("/DescendantFonts", [font])[0].get_object()
                descriptor = font.get("/FontDescriptor")
                assert descriptor is not None and any(key in descriptor.get_object() for key in ("/FontFile", "/FontFile2", "/FontFile3"))
        directory = args.output / file.stem
        for label, source in (("candidate", file), ("baseline", reference)):
            destination = directory / label
            destination.mkdir(parents=True)
            subprocess.run([str(args.poppler / "pdftoppm.exe"), "-r", "100", "-png", str(source), str(destination / "page")],
                           check=True, capture_output=True, timeout=60)
        pages = sorted((directory / "candidate").glob("*.png"))
        references = sorted((directory / "baseline").glob("*.png"))
        assert len(pages) == len(references) == len(reader.pages)
        for actual, expected in zip(pages, references):
            with Image.open(actual) as image, Image.open(expected) as baseline:
                assert image.size == baseline.size
                assert ImageChops.difference(image.convert("RGB"), baseline.convert("RGB")).getbbox() is None, actual
        scans = []
        if file.stem in ("tenant-a", "tenant-b"):
            prefix = directory / "barcode-300"
            subprocess.run([str(args.poppler / "pdftoppm.exe"), "-f", "1", "-singlefile", "-r", "300", "-png", str(file), str(prefix)],
                           check=True, capture_output=True, timeout=60)
            with Image.open(prefix.with_suffix(".png")) as image:
                scans = [(barcode.text, barcode.orientation) for barcode in zxingcpp.read_barcodes(image)]
            assert scans == [("NF001" if file.stem == "tenant-a" else "NF002", -90)], scans
        records.append(dict(file=file.name, pages=len(pages), pixelEqualToQualifiedReference=True, embeddedFonts=True, scans=scans))
        print(file.name, len(pages), scans, flush=True)
    assert records
    (args.output / "verification.json").write_text(json.dumps(records, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
