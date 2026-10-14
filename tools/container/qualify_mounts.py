"""Qualify real font/extension mounts with the shipped service and synthetic requests."""
import argparse
import concurrent.futures
import json
import pathlib
from qualify_service import Runtime, http, generate, multipart, template


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--seccomp", type=pathlib.Path, default=pathlib.Path("deployment/pdf-runtime/seccomp.json"))
    parser.add_argument("--fonts", type=pathlib.Path, required=True)
    parser.add_argument("--extensions", type=pathlib.Path, required=True)
    parser.add_argument("--fixtures", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--apparmor", choices=("nuaforma-runtime",))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    runtime = Runtime(args, "--mount", "type=bind,source=" + str(args.fonts.resolve()) + ",target=/opt/nuaforma/custom-fonts,readonly",
                      "--mount", "type=bind,source=" + str(args.extensions.resolve()) + ",target=/opt/nuaforma/extensions,readonly")
    try:
        base = runtime.connect()
        def capabilities():
            status, _, body = http(base, "/api/v1/capabilities")
            assert status == 200
            return json.loads(body)
        caps = capabilities()
        providers = {provider["id"]: provider for provider in caps["providers"]}
        assert "mounted-example" in providers, caps
        assert providers["mounted-example"]["fonts"]["status"] == "UNSUPPORTED"
        assert providers["nuaforma-docx-pdf"]["fonts"]["status"] == "AVAILABLE", providers["nuaforma-docx-pdf"]["fonts"]
        entries = providers["nuaforma-docx-pdf"]["fonts"]["entries"]
        assert any(font["source"] == "bundled" for font in entries)
        assert any(font["family"] == "Arial" and font["face"] == "bold" and font["source"] == "mounted" for font in entries)
        barcode = next(font for font in entries if font["family"] == "Libre Barcode 39" and font["face"] == "regular")
        assert barcode["source"] == "mounted" and barcode["resource"].startswith("nf-mounted-")
        assert "/opt/nuaforma" not in json.dumps(caps)
        def extension(reference):
            envelope = {"input": {"format": "docx"}, "output": {"format": "mount-example"}, "providerId": "mounted-example", "data": {"reference": reference}}
            boundary, body = multipart(envelope, template())
            status, _, result = http(base, "/api/v1/documents", body, "multipart/form-data; boundary=" + boundary)
            assert status == 200 and result == reference.encode(), (status, result)
        extension("EXTENSION-INITIAL")
        declarations = [{"family": font["family"], "face": font["face"], "resource": font["resource"]}
                        for font in entries if font["source"] == "mounted" and font["family"] in ("Arial", "Libre Barcode 39")]
        status, _, body = generate(base, "docx", (args.fixtures / "font-reference/template.docx").read_bytes(),
                                   {"customer": {"name": "Ada"}, "reference": "NF001"}, {"fontMode": "embed", "fonts": declarations})
        assert status == 200, (status, body[:1024])
        (args.output / "mounted-embedded.docx").write_bytes(body)
        status, _, embedded_pdf = generate(base, "pdf", body, {}, {"paragraphDefaults": {"afterTwips": 160, "line240ths": 278}})
        assert status == 200, (status, embedded_pdf[:1024])
        (args.output / "template-embedded.pdf").write_bytes(embedded_pdf)
        def tenant(name, reference, uploaded=False):
            options = {"paragraphDefaults": {"afterTwips": 160, "line240ths": 278}}
            resources = None
            if uploaded:
                options.update(fontMode="validate", fonts=[{"family": "Libre Barcode 39", "face": "regular", "resource": "request-private"}])
                resources = {"request-private": (args.fonts / "LibreBarcode39-Regular.ttf").read_bytes()}
            status, _, body = generate(base, "pdf", (args.fixtures / "font-reference/template.docx").read_bytes(),
                                       {"customer": {"name": "Ada"}, "reference": reference}, options, resources)
            assert status == 200, (status, body[:1024])
            (args.output / (name + ".pdf")).write_bytes(body)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            list(executor.map(lambda item: tenant(*item), [("tenant-a", "NF001", False), ("tenant-b", "NF002", True)]))
        envelope = {"input": {"format": "docx"}, "output": {"format": "pdf", "options": {"paragraphDefaults": {"afterTwips": 160, "line240ths": 278}}},
                    "data": {"customer": {"name": "Ada"}, "reference": "NF-001"}, "options": {"watermark": {"text": "{#if true}CONCEPT{#else}FINAL{/if} — {customer.name}"}}}
        boundary, body = multipart(envelope, (args.fixtures / "watermark-reference/plain.docx").read_bytes())
        status, _, body = http(base, "/api/v1/documents", body, "multipart/form-data; boundary=" + boundary)
        assert status == 200, (status, body[:1024])
        (args.output / "watermark-dynamic.pdf").write_bytes(body)
        assert capabilities() == caps, "Private upload changed deployment catalog"
        # Mutate only these explicitly supplied generated QA copies, restoring them in all cases.
        font_path = args.fonts / "LibreBarcode39-Regular.ttf"
        provider_path = args.extensions / "mounted-provider.jar"
        original_font, original_provider = font_path.read_bytes(), provider_path.read_bytes()
        try:
            font_path.write_bytes(b"changed-after-startup")
            provider_path.write_bytes(b"changed-after-startup")
            assert capabilities() == caps
            extension("EXTENSION-SNAPSHOT")
            tenant("snapshot-font", "NF001")
        finally:
            font_path.write_bytes(original_font)
            provider_path.write_bytes(original_provider)
        runtime.clean()
        (args.output / "capabilities.json").write_text(json.dumps(caps, indent=2), encoding="utf-8")
    finally:
        runtime.close()
    print("Qualified independent mounted provider, bundled/mounted font catalog, private upload isolation and concurrent PDF generation")


if __name__ == "__main__":
    main()
