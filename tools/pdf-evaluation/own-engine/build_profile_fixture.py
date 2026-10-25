"""Build an explicitly opaque, white-backed *test source* for PDF/A-1b qualification.

Never used by the converter: original sources and their Word references remain intact.
Requires Pillow in the manual evaluation environment, not in the product build/runtime.
"""
import argparse
import io
import zipfile
from pathlib import Path
from PIL import Image


def build(source: Path, output: Path):
    if output.exists():
        raise FileExistsError("Preserve qualification sources; choose a new output")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as target:
        for item in original.infolist():
            data = original.read(item.filename)
            if item.filename.startswith("word/media/") and item.filename.endswith(".png"):
                with Image.open(io.BytesIO(data)) as image:
                    rgba = image.convert("RGBA")
                    white = Image.new("RGBA", image.size, "white")
                    white.alpha_composite(rgba)
                    buffer = io.BytesIO()
                    white.convert("RGB").save(buffer, format="PNG")
                    data = buffer.getvalue()
            entry = zipfile.ZipInfo(item.filename, (2026, 10, 25, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(entry, data)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()
    build(arguments.source, arguments.output)
