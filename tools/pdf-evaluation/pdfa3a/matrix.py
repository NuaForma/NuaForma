"""Synthetic PDF/A-3b/3a gap matrix; strict explicit reports, never product profile support."""
import argparse
import hashlib
import json
import pathlib
import shutil
import xml.etree.ElementTree as ET
import zipfile
from run import execute


def annotate(source, destination):
    word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    wp = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    drawing = "http://schemas.openxmlformats.org/drawingml/2006/main"
    ET.register_namespace("w", word)
    ET.register_namespace("wp", wp)
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as result:
        for name in original.namelist():
            data = original.read(name)
            if name == "word/styles.xml":
                tree = ET.fromstring(data)
                defaults = tree.find("{" + word + "}docDefaults")
                if defaults is None:
                    defaults = ET.SubElement(tree, "{" + word + "}docDefaults")
                run_default = defaults.find("{" + word + "}rPrDefault")
                if run_default is None:
                    run_default = ET.SubElement(defaults, "{" + word + "}rPrDefault")
                properties = run_default.find("{" + word + "}rPr")
                if properties is None:
                    properties = ET.SubElement(run_default, "{" + word + "}rPr")
                language = properties.find("{" + word + "}lang")
                if language is None:
                    language = ET.SubElement(properties, "{" + word + "}lang")
                language.set("{" + word + "}val", "en-US")
                data = ET.tostring(tree, encoding="utf-8", xml_declaration=True)
            elif name == "word/document.xml":
                tree = ET.fromstring(data)
                for container in tree.iter("{" + wp + "}inline"):
                    graphic = container.find(".//{" + drawing + "}graphicData")
                    if graphic is not None and graphic.get("uri", "").endswith("/picture"):
                        description = container.find("{" + wp + "}docPr")
                        if description is not None:
                            description.set("descr", "Teal rectangular synthetic test mark")
                data = ET.tostring(tree, encoding="utf-8", xml_declaration=True)
            result.writestr(name, data)


def report(path, flavour="3a"):
    tree = ET.parse(path)
    validation = next((item for item in tree.iter() if item.tag.split("}")[-1] == "validationReport"), None)
    if validation is None:
        raise AssertionError("Missing complete validation report")
    assert validation.get("jobEndStatus") == "normal", validation.attrib
    assert validation.get("profileName") == "PDF/A-" + flavour + " validation profile", validation.attrib
    summary = next(item for item in tree.iter() if item.tag.split("}")[-1] == "batchSummary")
    assert summary.get("totalJobs") == "1" and summary.get("failedToParse") == "0" and summary.get("encrypted") == "0", summary.attrib
    assert summary.get("outOfMemory") == "0" and summary.get("veraExceptions") == "0", summary.attrib
    rules = []
    for item in validation.iter():
        if item.tag.split("}")[-1] == "rule" and item.get("status") == "failed":
            rules.append({"clause": item.get("clause"), "testNumber": item.get("testNumber"),
                          "description": next((child.text for child in item if child.tag.split("}")[-1] == "description"), "")})
    details = next((item for item in validation if item.tag.split("}")[-1] == "details"), None)
    assert details is not None and int(details.get("passedRules", "0")) > 0 and int(details.get("passedChecks", "0")) > 0
    assert int(details.get("failedRules", "-1")) == len(rules)
    if validation.get("isCompliant") == "true":
        assert details.get("failedChecks") == "0" and not rules
    totals = next((item for item in summary if item.tag.split("}")[-1] == "validationReports"), None)
    assert totals is not None and totals.get("failedJobs") == "0" and totals.text == "1"
    assert totals.get("compliant") == ("1" if validation.get("isCompliant") == "true" else "0")
    assert totals.get("nonCompliant") == ("0" if validation.get("isCompliant") == "true" else "1")
    return {"compliant": validation.get("isCompliant") == "true", "profile": validation.get("profileName"), "failedRules": rules}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--fixtures", nargs="+", default=["simple", "letter", "complex", "barcode", "watermark"])
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[3]
    inputs = {
        "simple": root / "build/container-35/final-image-service/document.docx",
        "letter": root / "tools/pdf-evaluation/build/explicit-defaults-copies-v2/roundtrip-after.docx",
        "complex": root / "tools/pdf-evaluation/build/explicit-defaults-copies-v2/complex-generated.docx",
        "barcode": root / "tools/pdf-evaluation/build/explicit-defaults-copies-v2/barcode-generated.docx",
        "watermark": root / "tools/pdf-evaluation/build/explicit-defaults-copies-v2/watermark-dynamic.docx",
    }
    args.output.mkdir(parents=True, exist_ok=False)
    results = []
    for name in args.fixtures:
        source = inputs[name]
        digest = hashlib.sha256(source.read_bytes()).hexdigest()
        for export in ("standard", "pdfa3b", "pdfa3b-ua"):
            work = (args.output / name / export).resolve()
            work.mkdir(parents=True)
            for folder in ("output", "fonts", "home", "cache", "profile", "tmp"):
                (work / folder).mkdir()
            annotate(source, work / "input.docx")
            (work / "fonts.conf").write_text('<fontconfig><dir>/work/fonts</dir><dir>/usr/share/fonts</dir><dir>/opt/libreoffice26.8/share/fonts</dir><cachedir>/work/cache/fontconfig</cachedir><config><rescan><int>0</int></rescan></config></fontconfig>', encoding="utf-8")
            if name != "simple":
                for font in (root / "build/container-35/fonts").glob("*.ttf"):
                    shutil.copyfile(font, work / "fonts" / font.name)
            status = execute(root, work, export, args.docker, args.image)
            assert status["exitCode"] == 0, status
            record = {"fixture": name, "export": export, "sourceSha256": digest, "pdfSha256": hashlib.sha256((work / "output/input.pdf").read_bytes()).hexdigest()}
            for mode in ("inspect", "validate-3b", "validate-3a"):
                status = execute(root, work, mode, args.docker, args.image)
                shutil.copyfile(work / "result.txt", work / (mode + ".txt"))
                if mode.startswith("validate"):
                    record[mode] = report(work / (mode + ".txt"), mode.removeprefix("validate-"))
                else:
                    assert status["exitCode"] == 0, status
            if export != "standard":
                status = execute(root, work, "relabel", args.docker, args.image)
                assert status["exitCode"] == 0, status
                probe = work / "metadata-only"
                probe.mkdir()
                for folder in ("output", "fonts", "home", "cache", "profile", "tmp"):
                    (probe / folder).mkdir()
                shutil.copyfile(work / "output/relabelled.pdf", probe / "output/input.pdf")
                status = execute(root, probe, "validate-3a", args.docker, args.image)
                record["metadata-only-3a"] = report(probe / "result.txt")
            assert hashlib.sha256(source.read_bytes()).hexdigest() == digest
            results.append(record)
            (args.output / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
            print(json.dumps(record), flush=True)


if __name__ == "__main__":
    main()
