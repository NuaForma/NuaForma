"""Exercise the shipped container entry point; only synthetic test data is used."""
import argparse
import concurrent.futures
import io
import json
import pathlib
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zipfile


def template(paragraphs=1):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>')
        archive.writestr("_rels/.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        archive.writestr("word/_rels/document.xml.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>')
        archive.writestr("word/styles.xml", '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Liberation Serif" w:hAnsi="Liberation Serif"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr/></w:pPrDefault></w:docDefaults></w:styles>')
        archive.writestr("word/document.xml", '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>' +
                         '<w:p><w:r><w:t>Hello {name}</w:t></w:r></w:p>' * paragraphs + '<w:sectPr/></w:body></w:document>')
    return output.getvalue()


def multipart(envelope, document, resources=None):
    boundary = "NuaForma" + uuid.uuid4().hex
    output = io.BytesIO()
    parts = [("request", "application/json", json.dumps(envelope).encode()),
             ("template", "application/octet-stream", document)]
    parts += [("resource." + key, "application/octet-stream", value) for key, value in (resources or {}).items()]
    for name, media, value in parts:
        output.write(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="synthetic"\r\nContent-Type: {media}\r\n\r\n'.encode())
        output.write(value)
        output.write(b"\r\n")
    output.write(f"--{boundary}--\r\n".encode())
    return boundary, output.getvalue()


def http(base, path, body=None, media=None, timeout=180):
    request = urllib.request.Request(base + path, data=body, headers={"Content-Type": media} if media else {})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as response:
        return response.code, dict(response.headers), response.read()


def generate(base, format_id, document, data, options=None, resources=None):
    envelope = {"input": {"format": "docx"}, "output": {"format": format_id, "options": options or {}}, "data": data}
    if format_id == "docx":
        envelope["providerId"] = "nuaforma-docx"
    boundary, body = multipart(envelope, document, resources)
    return http(base, "/api/v1/documents", body, "multipart/form-data; boundary=" + boundary)


class Runtime:
    def __init__(self, args, *environment):
        self.args = args
        self.name = "nuaforma-qualification-" + uuid.uuid4().hex
        apparmor = getattr(args, "apparmor", None)
        policy = ["--security-opt", "apparmor=" + apparmor] if apparmor else []
        self.command("run", "-d", "--init", "--name", self.name, "--label", "dev.nuaforma.qualification=" + self.name,
                     "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                     "--security-opt", "seccomp=" + str(args.seccomp.resolve()), "--pids-limit", "128",
                     "--memory", "2g", "--cpus", "2", "--tmpfs", "/tmp:rw,size=512m,mode=1777",
                     "--publish", "127.0.0.1::8080", *policy, *environment, args.image)

    def command(self, *command, timeout=45):
        return subprocess.check_output([self.args.docker, *command], text=True, stderr=subprocess.STDOUT, timeout=timeout).strip()

    def connect(self):
        port = json.loads(self.command("inspect", self.name))[0]["NetworkSettings"]["Ports"]["8080/tcp"][0]["HostPort"]
        self.base = "http://127.0.0.1:" + port
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            try:
                status, _, _ = http(self.base, "/openapi.json", timeout=2)
                if status == 200:
                    return self.base
            except (OSError, TimeoutError):
                pass
            time.sleep(0.2)
        raise AssertionError("Shipped server did not start: " + self.command("logs", self.name))

    def clean(self):
        paths = self.command("exec", self.name, "/bin/sh", "-c", "find /tmp -maxdepth 1 -name 'nuaforma-*' -print")
        assert not paths, "Private request files remain"

    def close(self):
        # Only this randomly named, explicitly labelled test container is removed.
        label = self.command("inspect", self.name, "--format", '{{index .Config.Labels "dev.nuaforma.qualification"}}')
        assert label == self.name
        self.command("rm", "-f", self.name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--docker", default="docker")
    parser.add_argument("--seccomp", type=pathlib.Path, default=pathlib.Path("deployment/pdf-runtime/seccomp.json"))
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--fixtures", type=pathlib.Path)
    parser.add_argument("--fonts", type=pathlib.Path)
    parser.add_argument("--expected-version")
    parser.add_argument("--apparmor", choices=("nuaforma-runtime",))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    checks = []
    runtime = Runtime(args)
    try:
        base = runtime.connect()
        config = json.loads(runtime.command("inspect", runtime.name))[0]
        assert config["Config"]["User"] == "1000:1000" and config["HostConfig"]["ReadonlyRootfs"]
        assert config["HostConfig"]["Init"] and not config["HostConfig"]["Privileged"]
        assert config["HostConfig"]["CapDrop"] == ["ALL"]
        assert runtime.command("exec", runtime.name, "id", "-u") == "1000"
        assert runtime.command("exec", runtime.name, "/bin/sh", "-c", "test ! -w /opt/nuaforma/application && test ! -w /opt/nuaforma/renderer && echo immutable") == "immutable"
        checks.append("non-root/read-only/capabilities/init")
        if args.expected_version:
            status, headers, body = http(base, "/api/v1/version")
            identity = json.loads(body)
            assert status == 200 and identity["version"] == args.expected_version and identity["contractVersion"] == "0.1"
            assert set(identity) == {"version", "contractVersion", "commit"}
            assert headers.get("Cache-Control") == "no-store"
            (args.output / "version.json").write_text(json.dumps(identity, indent=2), encoding="utf-8")
            checks.append("packaged-release-version")
        status, _, body = http(base, "/api/v1/capabilities")
        assert status == 200
        caps = json.loads(body)
        assert "pdf" in next(item for item in caps["providers"] if item["id"] == "nuaforma-docx-pdf")["available"]["writeFormats"]
        status, headers, body = generate(base, "docx", template(), {"name": "SYNTHETIC-DOCX"})
        assert status == 200 and "wordprocessingml" in headers["Content-Type"]
        with zipfile.ZipFile(io.BytesIO(body)) as archive:
            xml = archive.read("word/document.xml")
            assert b"SYNTHETIC-DOCX" in xml and b"{name}" not in xml
        (args.output / "document.docx").write_bytes(body)
        status, headers, body = generate(base, "pdf", template(), {"name": "SYNTHETIC-PDF"})
        assert status == 200 and "application/pdf" in headers["Content-Type"] and body.startswith(b"%PDF-1.7"), (status, body[:2048])
        (args.output / "document.pdf").write_bytes(body)
        for profile, version in (("standard", "1.7"), ("pdfa-1b", "1.4"), ("pdfa-2b", "1.7"), ("pdfa-3b", "1.7")):
            status, headers, body = generate(base, "pdf", template(), {"name": "SYNTHETIC-PROFILE"}, {"profile": profile})
            assert status == 200 and body.startswith(("%PDF-" + version).encode()), (profile, status, body[:2048])
            (args.output / (profile + ".pdf")).write_bytes(body)
        assert generate(base, "pdf", template(), {}, {"profile": "pdfa-1b", "version": "1.7"})[0] == 422
        assert generate(base, "pdf", template(), {}, {"profile": "pdfa-3a"})[0] == 422
        runtime.clean()
        checks.append("default-PDF-A-3b/explicit-B-profiles/standard/conflict-rejection")
        checks.append("shipped-entrypoint/DOCX/PDF/private-cleanup")
        if args.fixtures or args.fonts:
            assert args.fixtures and args.fonts
            ids = ("arial", "arialbd", "ariali", "arialbi", "barcode")
            resources = {key: (args.fonts / ("LibreBarcode39-Regular.ttf" if key == "barcode" else key + ".ttf")).read_bytes() for key in ids}
            declarations = [{"family": "Arial" if key != "barcode" else "Libre Barcode 39", "face": face, "resource": key}
                            for key, face in zip(ids, ("regular", "bold", "italic", "boldItalic", "regular"))]
            options = {"fontMode": "validate", "fonts": declarations, "paragraphDefaults": {"afterTwips": 160, "line240ths": 278}}
            def tenant(name, reference):
                status, _, value = generate(base, "pdf", (args.fixtures / "font-reference/template.docx").read_bytes(),
                                            {"customer": {"name": "Ada"}, "reference": reference}, options, resources)
                assert status == 200, value.decode(errors="replace")
                (args.output / (name + ".pdf")).write_bytes(value)
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
                list(executor.map(lambda item: tenant(*item), [("tenant-a", "NF001"), ("tenant-b", "NF002")]))
            envelope = {"input": {"format": "docx"}, "output": {"format": "pdf", "options": options},
                        "data": {"customer": {"name": "Ada"}, "reference": "NF-001"},
                        "options": {"watermark": {"text": "{#if true}CONCEPT{#else}FINAL{/if} — {customer.name}"}}}
            boundary, body = multipart(envelope, (args.fixtures / "watermark-reference/plain.docx").read_bytes(), resources)
            status, _, body = http(base, "/api/v1/documents", body, "multipart/form-data; boundary=" + boundary)
            assert status == 200
            (args.output / "watermark-dynamic.pdf").write_bytes(body)
            # The 1b option restriction is observable before rendering, not a visual fallback.
            envelope["output"]["options"] = options | {"profile": "pdfa-1b"}
            boundary, body = multipart(envelope, (args.fixtures / "watermark-reference/plain.docx").read_bytes(), resources)
            assert http(base, "/api/v1/documents", body, "multipart/form-data; boundary=" + boundary)[0] == 422
            envelope["options"]["watermark"]["opacity"] = 1.0
            for profile in ("pdfa-1b", "standard"):
                envelope["output"]["options"] = options | {"profile": profile}
                boundary, body = multipart(envelope, (args.fixtures / "watermark-reference/plain.docx").read_bytes(), resources)
                status, _, body = http(base, "/api/v1/documents", body, "multipart/form-data; boundary=" + boundary)
                assert status == 200, (profile, status, body[:2048])
                (args.output / ("watermark-opaque-" + profile + ".pdf")).write_bytes(body)
            runtime.clean()
            checks.append("private-fonts/concurrent-tenants/dynamic-watermark/1b-transparency-rejection/opaque-watermark")
        # Stop only after seeing the actual converter, not merely a queued HTTP request.
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(generate, base, "pdf", template(2000), {"name": "SHUTDOWN"})
            deadline = time.monotonic() + 45
            running = False
            while time.monotonic() < deadline and not future.done():
                processes = runtime.command("top", runtime.name, "-eo", "pid,args")
                if any("soffice.bin" in line and "--convert-to" in line for line in processes.splitlines()):
                    running = True
                    break
                time.sleep(0.1)
            assert running, "Did not observe active LibreOffice conversion for shutdown test"
            started = time.monotonic()
            runtime.command("stop", "--time", "15", runtime.name, timeout=25)
            assert time.monotonic() - started < 15
            state = json.loads(runtime.command("inspect", runtime.name))[0]["State"]
            assert not state["Running"] and not state["OOMKilled"] and state["ExitCode"] != 137
            try:
                response = future.result(timeout=5)
                # A request may finish inside the documented shutdown grace period.
                if response[0] == 200:
                    assert response[2].startswith(b"%PDF-1.7") and "application/pdf" in response[1]["Content-Type"]
                else:
                    assert response[0] in (408, 500, 503)
            except (OSError, TimeoutError):
                pass
        checks.append("SIGTERM-during-real-conversion/no-forced-kill")
    finally:
        runtime.close()
    runtime = Runtime(args, "--env", "NUAFORMA_PDF_LAUNCHER=/missing-converter")
    try:
        base = runtime.connect()
        status, _, body = http(base, "/api/v1/capabilities")
        pdf = next(item for item in json.loads(body)["providers"] if item["id"] == "nuaforma-docx-pdf")
        assert status == 200 and pdf["available"]["writeFormats"] == ["docx"]
        assert generate(base, "docx", template(), {"name": "MISSING-CONVERTER"})[0] == 200
        assert generate(base, "pdf", template(), {"name": "MISSING-CONVERTER"})[0] == 503
        runtime.clean()
        checks.append("missing-converter/DOCX-usable/PDF-rejected")
    finally:
        runtime.close()
    runtime = Runtime(args, "--env", "JAVA_TOOL_OPTIONS=-Dnuaforma.server.timeoutMillis=1000")
    try:
        base = runtime.connect()
        status, _, _ = generate(base, "pdf", template(2000), {"name": "DEADLINE"})
        assert status == 408
        runtime.clean()
        checks.append("handler-deadline/cancellation/private-cleanup")
    finally:
        runtime.close()
    (args.output / "qualification.json").write_text(json.dumps({"image": args.image, "checks": checks}, indent=2), encoding="utf-8")
    print(json.dumps(checks))


if __name__ == "__main__":
    main()
