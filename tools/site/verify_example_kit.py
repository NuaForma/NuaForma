"""Generate only the website examples through the released, isolated service."""
import argparse
import importlib.util
import io
import json
import pathlib
import subprocess
import sys
import zipfile
from pypdf import PdfReader

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/container"))
spec = importlib.util.spec_from_file_location("qualification", ROOT / "tools/container/qualify_service.py")
qualification = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qualification)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--image", default="ghcr.io/nuaforma/nuaforma:0.1.0-alpha.1")
    parser.add_argument("--poppler", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    args.seccomp = ROOT / "deployment/pdf-runtime/seccomp.json"
    args.output.mkdir(parents=True, exist_ok=False)
    runtime = qualification.Runtime(args)
    try:
        base = runtime.connect()
        template = (ROOT / "site/downloads/nuaforma-example-template.docx").read_bytes()
        trial = json.loads((ROOT / "site/downloads/01-final-pdfa3b.json").read_text())
        trial.update(providerId="nuaforma-docx", output={"format": "docx"})
        boundary, body = qualification.multipart(trial, template)
        status, _, result = qualification.http(base, "/api/v1/documents", body, "multipart/form-data; boundary=" + boundary)
        assert status == 200, ("DOCX preflight", status, result.decode(errors="replace"))
        print("DOCX preflight PASS", flush=True)
        for file in sorted((ROOT / "site/downloads").glob("[0-9][0-9]-*.json")):
            envelope = json.loads(file.read_text())
            boundary, body = qualification.multipart(envelope, template)
            status, _, output = qualification.http(base, "/api/v1/documents", body, "multipart/form-data; boundary=" + boundary)
            assert status == 200, (file.name, status, output.decode(errors="replace"))
            extension = envelope["output"]["format"]
            target = args.output / (file.stem + "." + extension)
            target.write_bytes(output)
            if envelope.get("options", {}).get("watermark"):
                watermark_document = output
                if extension != "docx":
                    word_request = dict(envelope, providerId="nuaforma-docx", output={"format": "docx"})
                    boundary, body = qualification.multipart(word_request, template)
                    word_status, _, watermark_document = qualification.http(base, "/api/v1/documents", body, "multipart/form-data; boundary=" + boundary)
                    assert word_status == 200
                with zipfile.ZipFile(io.BytesIO(watermark_document)) as package:
                    headers = b"".join(package.read(n) for n in package.namelist() if n.startswith("word/") and "header" in n)
                    assert ("DRAFT " + envelope["data"]["reference"]).encode() in headers
                    assert b"{reference}" not in headers
            if extension == "docx":
                with zipfile.ZipFile(target) as package:
                    assert len([n for n in package.namelist() if n.startswith("word/media/")]) == 2
                    xml = package.read("word/document.xml").decode()
                    assert "{#" not in xml and "{/" not in xml
                preview = dict(envelope, output={"format": "pdf", "options": {"profile": "pdfa-3b"}})
                preview.pop("providerId", None)
                preview.pop("options", None)
                boundary, body = qualification.multipart(preview, output)
                status, _, output = qualification.http(base, "/api/v1/documents", body, "multipart/form-data; boundary=" + boundary)
                assert status == 200, (file.name, status, output.decode(errors="replace"))
                target = target.with_suffix(".pdf")
                target.write_bytes(output)
            reader = PdfReader(target)
            text = " ".join(page.extract_text() for page in reader.pages)
            assert envelope["data"]["reference"] in text
            assert "{#" not in text and "{/" not in text
            compact = "".join(text.split())
            required = {
                "01": ["Blocked", "Review requested", "project is paused", "875.50"],
                "02": ["Urgent handling requested", "No email supplied", "In progress"],
                "03": ["City not supplied", "No additional message", "Project details are not included"],
                "04": ["Template design"],
                "05": ["Editable document delivery requested"],
                "06": ["Literal markers stay literal: {customer.name}"],
            }[file.name[:2]]
            for value in required:
                assert "".join(value.split()) in compact, (file.name, value)
            if file.name[:2] in ("01", "03", "06"):
                assert "DRAFT" not in text
            if file.name[:2] in ("03", "06"):
                assert "Customer onboarding" not in text and "Future rollout" not in text
            if file.name[:2] == "03":
                assert "Template design" not in text
            profile = envelope["output"].get("options", {}).get("profile")
            if profile and profile.startswith("pdfa-"):
                assert str(reader.xmp_metadata.pdfaid_part) == profile[5]
                assert reader.xmp_metadata.pdfaid_conformance.lower() == "b"
            subprocess.run([args.poppler, "-r", "100", "-png", str(target), str(args.output / file.stem)], check=True)
            (args.output / (file.stem + ".txt")).write_text(text, encoding="utf-8")
            print(file.name, "PASS", len(reader.pages), "pages", flush=True)
        runtime.clean()
    except Exception:
        print(runtime.command("logs", runtime.name), flush=True)
        raise
    finally:
        runtime.close()


if __name__ == "__main__":
    main()
