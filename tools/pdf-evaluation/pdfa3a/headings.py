"""Controlled semantic-heading copy of the synthetic layout fixture; retain its visible style."""
import argparse
import copy
import pathlib
import shutil
import xml.etree.ElementTree as ET
import zipfile
from run import execute
from matrix import report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image", required=True)
    parser.add_argument("--matrix", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[3]
    work = args.output.resolve()
    work.mkdir(parents=True, exist_ok=False)
    for folder in ("output", "fonts", "home", "cache", "profile", "tmp"):
        (work / folder).mkdir()
    source = args.matrix / "letter/pdfa3b"
    shutil.copyfile(source / "fonts.conf", work / "fonts.conf")
    for font in (source / "fonts").iterdir():
        shutil.copyfile(font, work / "fonts" / font.name)
    w = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    with zipfile.ZipFile(source / "input.docx") as original, zipfile.ZipFile(work / "input.docx", "w", zipfile.ZIP_DEFLATED) as target:
        for name in original.namelist():
            data = original.read(name)
            if name == "word/styles.xml":
                tree = ET.fromstring(data)
                title = next(style for style in tree.findall(w + "style") if style.get(w + "styleId") == "Title")
                heading = copy.deepcopy(title)
                heading.set(w + "styleId", "Heading1")
                heading.find(w + "name").set(w + "val", "heading 1")
                paragraph = heading.find(w + "pPr")
                if paragraph is None:
                    paragraph = ET.SubElement(heading, w + "pPr")
                ET.SubElement(paragraph, w + "outlineLvl", {w + "val": "0"})
                tree.append(heading)
                data = ET.tostring(tree, encoding="utf-8", xml_declaration=True)
            elif name == "word/document.xml":
                tree = ET.fromstring(data)
                for style in tree.iter(w + "pStyle"):
                    if style.get(w + "val") == "Title":
                        style.set(w + "val", "Heading1")
                data = ET.tostring(tree, encoding="utf-8", xml_declaration=True)
            target.writestr(name, data)
    for mode in ("pdfa3b", "relabel"):
        assert execute(root, work, mode, args.docker, args.image)["exitCode"] == 0
    shutil.copyfile(work / "output/relabelled.pdf", work / "output/input.pdf")
    assert execute(root, work, "validate-3a", args.docker, args.image)["exitCode"] == 0
    assert report(work / "result.txt")["compliant"]
    assert execute(root, work, "semantic", args.docker, args.image)["exitCode"] == 0
    text = (work / "result.txt").read_text(encoding="utf-8")
    assert "-> H1" in text, text
    print("Explicit semantic Heading1 retained as H1; independently validated as 3a")


if __name__ == "__main__":
    main()
