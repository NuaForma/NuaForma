"""Qualify the split JVM image with existing native HTTP/mount tools; no binary publication."""
import argparse
import concurrent.futures
import io
import json
import pathlib
import re
import shutil
import xml.etree.ElementTree as ET
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
    fonts = args.output / "mixed-fonts"
    shutil.copytree(args.fonts, fonts)
    original = (fonts / "LibreBarcode39-Regular.ttf").read_bytes()
    (fonts / "duplicate.ttf").write_bytes(original)
    (fonts / "corrupt.ttf").write_bytes(b"not-a-font")
    (fonts / "unsupported.woff").write_bytes(b"wOFF" + bytes(32))
    (fonts / "unreadable.ttf").write_bytes(original)
    (fonts / "unreadable.ttf").chmod(0)
    (fonts / "symlink.ttf").symlink_to("LibreBarcode39-Regular.ttf")
    runtime = Runtime(args, "--mount", f"type=bind,source={fonts.resolve()},target=/opt/nuaforma/custom-fonts,readonly",
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
        assert providers["nuaforma-docx"]["fonts"]["entries"] == [font]
        logs = runtime.command("logs", runtime.name)
        for rejected in ("corrupt.ttf", "unsupported.woff", "unreadable.ttf", "symlink.ttf"):
            assert re.search(r'MOUNTED_FONT_REJECTED[^\n]*' + re.escape(rejected), logs), rejected
        assert "MOUNTED_FONT_REJECTION_DETAIL" not in logs
        checks.append("mixed-mount/per-file-production-diagnostics/provider-and-catalog-survival")
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
            word = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
            table = ET.fromstring(archive.read("word/fontTable.xml"))
            key = next(item.attrib[word + "fontKey"] for entry in table for item in entry if item.tag == word + "embedRegular")
            mask = bytes.fromhex(key.replace("{", "").replace("}", "").replace("-", ""))[::-1]
            embedded = bytearray(archive.read(next(name for name in archive.namelist() if name.endswith(".odttf"))))
            for index in range(32):
                embedded[index] ^= mask[index % 16]
            assert embedded == original
        (args.output / "mounted-font.docx").write_bytes(docx)
        status, _, pdf = generate(base, "pdf", docx, {})
        assert status == 200 and pdf.startswith(b"%PDF-1.7"), (status, pdf[:1024])
        (args.output / "mounted-font.pdf").write_bytes(pdf)
        # Generate from the unembedded template too, so mounted fallback must actually run.
        status, _, pdf = generate(base, "pdf", document, {"name": "*NF001*"})
        assert status == 200, (status, pdf[:1024])
        assert re.search(rb"/BaseFont\s*/[^\s/]*LibreBarcode39", pdf) and b"/FontFile2" in pdf
        (args.output / "mounted-fallback.pdf").write_bytes(pdf)
        # Existing instances retain bytes; a fresh instance must observe the changed mount.
        (fonts / "LibreBarcode39-Regular.ttf").write_bytes(b"invalid after startup")
        (fonts / "duplicate.ttf").write_bytes(b"invalid after startup")
        assert generate(base, "pdf", document, {"name": "*NF001*"})[0] == 200
        runtime.clean()
        checks.append("mounted-open-font/catalog/DOCX-embedding/PDF-A3b-validation/private-cleanup")
    finally:
        runtime.close()
    restarted = Runtime(args, "--mount", f"type=bind,source={fonts.resolve()},target=/opt/nuaforma/custom-fonts,readonly")
    try:
        base = restarted.connect()
        status, _, body = http(base, "/api/v1/capabilities")
        providers = {provider["id"]: provider for provider in json.loads(body)["providers"]}
        assert status == 200 and providers["nuaforma-docx"]["fonts"]["entries"] == []
        assert generate(base, "docx", document, {"name": "Preserve"})[0] == 200
        status, _, failure = generate(base, "pdf", document, {"name": "*NF001*"})
        assert status == 422 and b"INVALID_REQUEST" in failure, (status, failure[:1024])
        checks.append("immutable-snapshot/restart/all-invalid-provider-survival/missing-font-no-substitution")
    finally:
        restarted.close()
    (args.output / "qualification.json").write_text(json.dumps({"architecture": args.architecture, "checks": checks}, indent=2))
    print(json.dumps(checks))


if __name__ == "__main__":
    main()
