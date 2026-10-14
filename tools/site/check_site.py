"""Check public static links, request examples, archive parity and reviewed profiles."""
import html
import json
import pathlib
import re
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
SITE = ROOT / "site"
source = (SITE / "index.html").read_text(encoding="utf-8")
for reference in re.findall(r'(?:href|src)="([^"]+)"', source):
    if reference.startswith(("https://", "#")):
        continue
    assert (SITE / reference).is_file(), reference
index = json.loads((SITE / "downloads/scenarios.json").read_text())
assert len(index) == 6
for scenario in index:
    request = json.loads((SITE / "downloads" / scenario["file"]).read_text())
    assert request["input"]["format"] == "docx"
    assert isinstance(request["data"], dict)
    if request["output"]["options"].get("profile") == "pdfa-1b":
        assert request["options"]["watermark"]["opacity"] == 1
compose = html.unescape(re.search(r'<code id="compose-code">(.*?)</code>', source, re.S)[1])
assert compose.strip() == (SITE / "downloads/docker/compose.yaml").read_text().strip()
assert 'href="downloads/docker/seccomp.json" download="seccomp.json"' in source
assert 'href="downloads/docker-kit.zip" download="docker-kit.zip" data-download-status="docker-kit-status"' in source
assert 'id="docker-kit-status"' in source
assert 'id="docker-kit-download"' in source
assert "curl --fail --location https://nuaforma.dev/downloads/docker-kit.zip --output docker-kit.zip" in source
for name in ("docker", "helm"):
    folder = SITE / "downloads" / name
    assert (folder / "seccomp.json").read_bytes() == (ROOT / "deployment/pdf-runtime/seccomp.json").read_bytes()
    with zipfile.ZipFile(SITE / "downloads" / (name + "-kit.zip")) as archive:
        expected = {p.relative_to(folder).as_posix() for p in folder.rglob("*") if p.is_file()}
        assert set(archive.namelist()) == expected
        for member in expected:
            assert archive.read(member) == (folder / member).read_bytes()
with zipfile.ZipFile(SITE / "downloads/example-kit.zip") as archive:
    for member in archive.namelist():
        assert archive.read(member) == (SITE / "downloads" / member).read_bytes()
with zipfile.ZipFile(SITE / "downloads/nuaforma-example-template.docx") as archive:
    assert len([n for n in archive.namelist() if n.startswith("word/media/")]) == 2
    assert b"asciiTheme" not in archive.read("word/styles.xml")
    assert b"{#each" in archive.read("word/document.xml")
assert not any(p.is_symlink() for p in SITE.rglob("*"))
print("Static links, six scenarios, Compose parity, archives and profiles: PASS")
