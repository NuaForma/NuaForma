"""Audit genuine Word references and exact native watermark/body output for the five trusted proofs."""
import argparse
import hashlib
import json
import pathlib
import re
import xml.etree.ElementTree as ET
import zipfile
import pdfplumber
from pypdf import PdfReader


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def audit(root):
    rows = []
    for name in ("a4-transparent", "letter-landscape-opaque", "multipage", "cyrillic-greek", "hebrew"):
        reference = root / (name + ".word.pdf")
        provenance = json.loads(reference.with_suffix(reference.suffix + ".provenance.json").read_text(encoding="utf-8-sig"))
        assert provenance["exporter"] == "Microsoft Word ExportAsFixedFormat"
        assert provenance["sourceSha256"] == sha256(root / (name + ".docx"))
        assert provenance["pdfSha256"] == sha256(reference)
        values = set()
        with zipfile.ZipFile(root / (name + ".docx")) as package:
            for part in package.namelist():
                if part.startswith("word/") and part.endswith(".xml"):
                    tree = ET.fromstring(package.read(part))
                    if tree.tag != "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}hdr":
                        continue
                    values.update(node.attrib["string"] for node in tree.iter("{urn:schemas-microsoft-com:vml}textpath") if "string" in node.attrib)
        assert len(values) == 1
        expected = values.pop()
        reader = PdfReader(root / (name + ".pdf"))
        with pdfplumber.open(reference) as word, pdfplumber.open(root / (name + ".pdf")) as native, pdfplumber.open(root / (name + ".plain.pdf")) as plain:
            assert len(word.pages) == len(native.pages) == len(plain.pages)
            for index, (w, n, p) in enumerate(zip(word.pages, native.pages, plain.pages)):
                source = [c for c in w.chars if not c["text"].isspace()]
                body = [c for c in n.chars if c.get("tag") != "Artifact" and not c["text"].isspace()]
                control = [c for c in p.chars if not c["text"].isspace()]
                assert "".join(c["text"] for c in source) == "".join(c["text"] for c in body)
                assert [(c["text"], c["x0"], c["top"], c["size"], c["non_stroking_color"]) for c in body] == [
                    (c["text"], c["x0"], c["top"], c["size"], c["non_stroking_color"]) for c in control]
                font = lambda c: re.sub(r"^[A-Z]{6}\+", "", c["fontname"])
                assert all(font(a) == font(b) for a, b in zip(source, body))
                size = max(abs(a["size"] - b["size"]) for a, b in zip(source, body))
                # Word emits 11-point source text at 11.04 points; allow one OOXML twip of exporter rounding.
                assert size <= 0.05, (name, index + 1, "Word font-size rounding", size)
                x = max(abs(a["x0"] - b["x0"]) for a, b in zip(source, body))
                y = max(abs(a["top"] - b["top"]) for a, b in zip(source, body))
                assert x <= 0.5 and y <= 0.5, (name, index + 1, x, y)
                logical = [str(operands[1]["/ActualText"]) for operands, op in reader.pages[index].get_contents().operations
                           if op == b"BDC" and len(operands) == 2 and operands[0] == "/Artifact" and "/ActualText" in operands[1]]
                assert logical == [expected]
                rows.append(dict(case=name, page=index + 1, bodyCharacters=len(body), exactNativeBodyGeometry=True,
                                 wordBodyTextAndFacesMatch=True, maxWordXDeltaPt=x, maxWordTopDeltaPt=y, maxWordFontSizeDeltaPt=size,
                                 logicalWatermark=expected, nativeSha256=sha256(root / (name + ".pdf")), wordSha256=sha256(reference)))
    return rows


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=pathlib.Path)
    args = parser.parse_args()
    result = audit(args.directory)
    (args.directory / "audited-pages.json").write_text(json.dumps(result, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    print(json.dumps(dict(pages=len(result), bodyCharacters=sum(row["bodyCharacters"] for row in result),
                         maxWordXDeltaPt=max(row["maxWordXDeltaPt"] for row in result),
                         maxWordTopDeltaPt=max(row["maxWordTopDeltaPt"] for row in result))))
