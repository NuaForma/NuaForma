"""Independent PDF/A matrix rendering, font/attachment checks and barcode scans."""
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
    args.destination.mkdir(parents=True, exist_ok=False)
    sys.path.insert(0, str(args.decoder.resolve()))
    import zxingcpp
    records = []
    for fixture in ("simple", "letter", "complex", "barcode", "watermark"):
        sources = {mode: args.matrix / fixture / mode / "output/input.pdf" for mode in ("standard", "pdfa3b", "pdfa3b-ua")}
        sources.update({mode + "-a": args.matrix / fixture / mode / "metadata-only/output/input.pdf" for mode in ("pdfa3b", "pdfa3b-ua")})
        reference = None
        for mode, source in sources.items():
            pages = args.destination / fixture / mode
            pages.mkdir(parents=True)
            subprocess.run([str(args.poppler / "pdftoppm.exe"), "-r", "100", "-png", str(source), str(pages / "page")], check=True, capture_output=True, timeout=60)
            reader = PdfReader(source)
            assert reader.pdf_header == "%PDF-1.7" and not reader.is_encrypted
            catalog = reader.trailer["/Root"]
            assert "/AF" not in catalog and not reader.attachments and "/AcroForm" not in catalog
            fonts = set()
            for page in reader.pages:
                for font in page.get("/Resources", {}).get_object().get("/Font", {}).get_object().values():
                    font = font.get_object()
                    descendant = font.get("/DescendantFonts", [font])[0].get_object()
                    assert any(key in descendant["/FontDescriptor"].get_object() for key in ("/FontFile", "/FontFile2", "/FontFile3"))
                    fonts.add(str(font.get("/BaseFont")))
            rendered = sorted(pages.glob("page-*.png"))
            scans = []
            for page in rendered:
                with Image.open(page) as image:
                    scans += [(item.text, item.orientation) for item in zxingcpp.read_barcodes(image)]
            if fixture == "barcode":
                assert scans == [("NF001", -90)], scans
                subprocess.run([str(args.poppler / "pdftoppm.exe"), "-f", "1", "-singlefile", "-r", "300", "-png", str(source), str(pages / "barcode-300")], check=True, capture_output=True, timeout=60)
                with Image.open(pages / "barcode-300.png") as image:
                    assert [(item.text, item.orientation) for item in zxingcpp.read_barcodes(image)] == [("NF001", -90)]
            differences = []
            if reference is None:
                reference = rendered
            else:
                assert len(reference) == len(rendered)
                for actual, expected in zip(rendered, reference):
                    with Image.open(actual) as left, Image.open(expected) as right:
                        assert left.size == right.size
                        differences.append(ImageChops.difference(left.convert("RGB"), right.convert("RGB")).getbbox())
                assert not any(differences), (fixture, mode, differences)
            records.append({"fixture": fixture, "mode": mode, "pages": len(reader.pages), "fonts": sorted(fonts), "scans": scans, "pixelDifferences": differences})
    (args.destination / "verification.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
    print(json.dumps(records))


if __name__ == "__main__":
    main()
