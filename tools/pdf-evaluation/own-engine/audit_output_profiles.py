"""Independently inspect exact qualified bytes and compare every profile pixel with standard PDF.

Run after qualifyOwnEngineProfiles. Requires the manual QA environment's pypdf,
pypdfium2 and numpy. No image masks, alignment or tolerated pixel differences.
"""
import argparse
import csv
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np
import pypdfium2 as pdfium
from pypdf import PdfReader


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def audit(directory: Path, baseline_root: Path | None = None):
    with (directory / "validation.tsv").open(encoding="utf-8") as file:
        rows = list(csv.DictReader(file, delimiter="\t"))
    results = []
    for row in rows:
        path = directory / row["case"] / (row["profile"] + ".pdf")
        if row["status"] == "rejected-transparency":
            assert row["profile"] == "pdfa-1b" and not path.exists()
            continue
        assert row["status"] == "passed"
        assert digest(path) == row["sha256"] and path.stat().st_size == int(row["bytes"])
        reader = PdfReader(path)
        standard = directory / row["case"] / "standard.pdf"
        if baseline_root is not None and row["profile"] == "standard" and row["case"].startswith("own-engine-"):
            standard = baseline_root / row["case"] / "own-engine.pdf"
        expected = PdfReader(standard)
        assert not reader.is_encrypted and len(reader.pages) == len(expected.pages)
        root = reader.trailer["/Root"]
        # PDFBox compressed saves use a 1.6 header with the effective version in the catalog.
        effective_version = max(reader.pdf_header[5:], str(root.get("/Version", "/1.0"))[1:])
        assert effective_version == ("1.4" if row["profile"] == "pdfa-1b" else "1.7")
        if row["profile"] == "pdfa-1b":
            assert reader.pdf_header == "%PDF-1.4"
        assert "/AcroForm" not in root and "/EmbeddedFiles" not in root.get("/Names", {})
        if row["profile"] != "standard":
            metadata = ET.fromstring(root["/Metadata"].get_data())
            ns = "{http://www.aiim.org/pdfa/ns/id/}"
            assert metadata.find(".//" + ns + "part").text == row["profile"][5]
            assert metadata.find(".//" + ns + "conformance").text == "B"
            intents = root["/OutputIntents"]
            assert len(intents) == 1
            icc = intents[0].get_object()["/DestOutputProfile"]
            assert icc["/N"] == 3 and len(icc.get_data()) > 128
        for page, baseline in zip(reader.pages, expected.pages):
            assert tuple(page.mediabox) == tuple(baseline.mediabox)
            assert page.extract_text() == baseline.extract_text()
            for ref in page["/Resources"].get("/Font", {}).values():
                font = ref.get_object()
                assert "/ToUnicode" in font
                descriptor = font["/DescendantFonts"][0].get_object()["/FontDescriptor"]
                assert len(descriptor["/FontFile2"].get_data()) > 0
        pixels = 0
        with pdfium.PdfDocument(path) as candidate, pdfium.PdfDocument(standard) as baseline:
            for number in range(len(candidate)):
                a_page, b_page = candidate[number], baseline[number]
                try:
                    a_bitmap, b_bitmap = a_page.render(scale=2), b_page.render(scale=2)
                    try:
                        a, b = np.asarray(a_bitmap.to_pil()), np.asarray(b_bitmap.to_pil())
                        assert a.shape == b.shape and np.array_equal(a, b), f"Profile pixels changed: {path}, page {number + 1}"
                        pixels += a.shape[0] * a.shape[1]
                    finally:
                        a_bitmap.close(); b_bitmap.close()
                finally:
                    a_page.close(); b_page.close()
        item = {"case": row["case"], "profile": row["profile"], "sha256": row["sha256"],
                "pages": len(reader.pages), "identicalPixels": pixels}
        results.append(item)
        print(json.dumps(item), flush=True)
    return {"outputs": len(results), "pages": sum(r["pages"] for r in results),
            "rejectedTransparent1b": sum(r["status"] == "rejected-transparency" for r in rows), "results": results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("report", type=Path)
    parser.add_argument("--baseline-root", type=Path, help="Also require the original corpus's standard pages to match its previous engine output")
    args = parser.parse_args()
    result = audit(args.directory, args.baseline_root)
    args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
