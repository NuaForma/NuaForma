"""Render synthetic string-function output and literal equivalents in the qualified isolated renderer."""
import argparse
import json
import pathlib
import shutil
import subprocess
import sys

from PIL import Image
from pypdf import PdfReader

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent / "pdfa3a"))
from run import execute


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", required=True)
    parser.add_argument("--image", required=True)
    parser.add_argument("--poppler", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[2]
    args.output.mkdir(parents=True, exist_ok=False)
    records = []
    for fixture, sources in {
        "document": ("string-functions-reference/generated.docx", "string-functions-reference/expected.docx"),
        "watermark": ("watermark-reference/string-functions.docx", "watermark-reference/dynamic.docx"),
    }.items():
        pages = []
        for variant, source in zip(("generated", "expected"), sources):
            work = (args.output / fixture / variant).resolve()
            work.mkdir(parents=True)
            for folder in ("output", "fonts", "home", "cache", "profile", "tmp"):
                (work / folder).mkdir()
            shutil.copyfile(root / "nuaforma-docx/build" / source, work / "input.docx")
            (work / "fonts.conf").write_text(
                '<fontconfig><dir>/usr/share/fonts</dir><dir>/opt/libreoffice26.8/share/fonts</dir>'
                '<cachedir>/work/cache/fontconfig</cachedir></fontconfig>', encoding="utf-8")
            result = execute(root, work, "standard", args.docker, args.image)
            assert result["exitCode"] == 0, result
            pdf = work / "output/input.pdf"
            reader = PdfReader(pdf)
            assert not reader.is_encrypted and not reader.attachments
            subprocess.run([str(args.poppler / "pdftoppm.exe"), "-r", "100", "-png", str(pdf), str(work / "page")],
                           check=True, capture_output=True, timeout=60)
            pages.append(sorted(work.glob("page-*.png")))
        assert pages[0] and len(pages[0]) == len(pages[1])
        for actual, expected in zip(*pages):
            with Image.open(actual) as left, Image.open(expected) as right:
                assert left.size == right.size and left.convert("RGB").tobytes() == right.convert("RGB").tobytes(), actual
        records.append({"fixture": fixture, "pages": len(pages[0]), "pixelIdentical": True})
    (args.output / "verification.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
    print(json.dumps(records))


if __name__ == "__main__":
    main()
