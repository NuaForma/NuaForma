"""Qualify the split JVM image with existing native HTTP/mount tools; no binary publication."""
import argparse
import concurrent.futures
import io
import json
import pathlib
import re
import zipfile
from qualify_service import Runtime, generate, http, multipart, template
from verify_native_platform import verify


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--architecture", required=True, choices=("amd64", "arm64"))
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--seccomp", type=pathlib.Path, default=pathlib.Path("deployment/pdf-runtime/seccomp.json"))
    parser.add_argument("--fonts", required=True, type=pathlib.Path)
    parser.add_argument("--extensions", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--apparmor", choices=("nuaforma-runtime",))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    runtime = Runtime(args, "--mount", f"type=bind,source={args.fonts.resolve()},target=/opt/nuaforma/custom-fonts,readonly",
                      "--mount", f"type=bind,source={args.extensions.resolve()},target=/opt/nuaforma/extensions,readonly")
    checks = []
    try:
        image = json.loads(runtime.command("image", "inspect", args.image))[0]
        engine = json.loads(runtime.command("info", "--format", "{{json .}}"))
        verify(image, engine, args.architecture)
        for role, path, version in (("server", "server", "25.0.4.1"), ("validator", "openjdk", "17.0.20.1")):
            result = runtime.command("exec", runtime.name, f"/opt/java/{path}/bin/java", "-version")
            assert re.search(r'openjdk version "' + re.escape(version) + r'"', result), (role, result)
            checks.append(role + "-java-" + version)
        base = runtime.connect()
        status, _, body = http(base, "/api/v1/capabilities")
        assert status == 200
        providers = {provider["id"]: provider for provider in json.loads(body)["providers"]}
        assert "pdf" in providers["nuaforma-docx-pdf"]["available"]["writeFormats"]
        assert "mounted-example" in providers
        font = next(font for font in providers["nuaforma-docx-pdf"]["fonts"]["entries"]
                    if font["family"] == "Libre Barcode 39" and font["face"] == "regular")
        assert font["source"] == "mounted"
        declaration = {"family": font["family"], "face": font["face"], "resource": font["resource"]}
        source = template()
        output = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(source)) as incoming, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as outgoing:
            for name in incoming.namelist():
                data = incoming.read(name)
                if name == "word/styles.xml":
                    data = data.replace(b"Liberation Serif", b"Libre Barcode 39")
                if name == "word/document.xml":
                    data = data.replace(b"Hello ", b"")
                    data = data.replace(b"<w:r>", b'<w:r><w:rPr><w:rFonts w:ascii="Libre Barcode 39" w:hAnsi="Libre Barcode 39"/></w:rPr>')
                outgoing.writestr(name, data)
        document = output.getvalue()
        def request(reference):
            envelope = {"input": {"format": "docx"}, "output": {"format": "mount-example"},
                        "providerId": "mounted-example", "data": {"reference": reference}}
            boundary, data = multipart(envelope, source)
            response, _, result = http(base, "/api/v1/documents", data, "multipart/form-data; boundary=" + boundary)
            assert response == 200 and result == reference.encode(), (response, result[:1024])
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            list(executor.map(request, ["NATIVE-TENANT-A", "NATIVE-TENANT-B"]))
        checks.append("Java17-extension/concurrent-tenant-isolation")
        status, _, docx = generate(base, "docx", document, {"name": "*NF001*"}, {"fontMode": "embed", "fonts": [declaration]})
        assert status == 200, (status, docx[:1024])
        with zipfile.ZipFile(io.BytesIO(docx)) as archive:
            assert any(name.startswith("word/fonts/") and name.endswith(".odttf") for name in archive.namelist())
        (args.output / "mounted-font.docx").write_bytes(docx)
        status, _, pdf = generate(base, "pdf", docx, {})
        assert status == 200 and pdf.startswith(b"%PDF-1.7"), (status, pdf[:1024])
        (args.output / "mounted-font.pdf").write_bytes(pdf)
        runtime.clean()
        checks.append("mounted-open-font/catalog/DOCX-embedding/PDF-A3b-validation/private-cleanup")
    finally:
        runtime.close()
    (args.output / "qualification.json").write_text(json.dumps({"architecture": args.architecture, "checks": checks}, indent=2))
    print(json.dumps(checks))


if __name__ == "__main__":
    main()
