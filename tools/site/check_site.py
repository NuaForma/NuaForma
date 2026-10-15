"""Check public static links, request examples, archive parity and reviewed profiles."""
import html
import argparse
import base64
import json
import pathlib
import re
import zipfile
import xml.etree.ElementTree as ET
from html.parser import HTMLParser

ROOT = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--site-directory', type=pathlib.Path, default=ROOT / 'site')
parser.add_argument('--allow-preparation', action='store_true', help='Validate draft structure, not readiness for publication')
arguments = parser.parse_args()
SITE = arguments.site_directory.resolve()
assert arguments.allow_preparation or not (SITE / 'RELEASE-PREPARATION.txt').exists(), 'Candidate site has outstanding publication requirements'
assert arguments.allow_preparation or 'REQUIRED_APPROVED_RELEASE_DIGEST' not in (SITE / 'downloads/helm/nuaforma/values.yaml').read_text(), 'Helm must pin the approved release digest'
source = (SITE / "index.html").read_text(encoding="utf-8")


class Tabs(HTMLParser):
    def __init__(self):
        super().__init__()
        self.tabs = {}
        self.panels = {}

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if attrs.get("role") == "tab":
            assert attrs["id"] not in self.tabs
            self.tabs[attrs["id"]] = attrs
        if attrs.get("role") == "tabpanel":
            assert attrs["id"] not in self.panels
            self.panels[attrs["id"]] = attrs


tabs = Tabs()
tabs.feed(source)
for guide in ('docs/README.md', 'docs/development.md', 'docs/extensions.md', 'CONTRIBUTING.md'):
    assert (ROOT / guide).is_file()
    assert 'href="https://github.com/NuaForma/NuaForma/blob/main/' + guide + '"' in source
assert len(tabs.tabs) == len(tabs.panels) == 8
for name, tab in tabs.tabs.items():
    panel = tabs.panels[tab["aria-controls"]]
    selected = tab["aria-selected"] == "true"
    assert panel["aria-labelledby"] == name
    assert ("hidden" not in panel) == selected
    assert tab["tabindex"] == ("0" if selected else "-1")
assert tabs.tabs["tab-compose"]["aria-selected"] == "true"
assert tabs.tabs["tab-compose-amd64"]["aria-selected"] == "true"
assert tabs.tabs["tab-compose-arm64"]["aria-selected"] == "false"
assert tabs.tabs["tab-client-bruno"]["aria-selected"] == "true"
assert tabs.tabs["tab-client-postman"]["aria-selected"] == "false"
assert tabs.tabs["tab-client-curl"]["aria-selected"] == "false"
for reference in re.findall(r'(?:href|src)="([^"]+)"', source):
    if reference.startswith(("https://", "#")):
        continue
    assert (SITE / reference).is_file(), reference
index = json.loads((SITE / "downloads/scenarios.json").read_text())
assert len(index) in (6, 7)
if len(index) == 7:
    assert index[-1]['file'] == '07-base64-image-pdfa3b.json'
    image_request = json.loads((SITE / 'downloads' / index[-1]['file']).read_text())
    picture = base64.b64decode(image_request['data']['image'], validate=True)
    assert picture.startswith(b'\x89PNG\r\n\x1a\n') and len(picture) < 1024 * 1024
    assert image_request['output']['options']['profile'] == 'pdfa-3b'
    assert all(json.loads((SITE / 'downloads' / item['file']).read_text())['data']['image'] is None for item in index[:-1])
for scenario in index:
    request = json.loads((SITE / "downloads" / scenario["file"]).read_text())
    assert request["input"]["format"] == "docx"
    assert isinstance(request["data"], dict)
    if request["output"]["options"].get("profile") == "pdfa-1b":
        assert request["options"]["watermark"]["opacity"] == 1
compose = html.unescape(re.search(r'<code id="compose-code">(.*?)</code>', source, re.S)[1])
assert compose.strip() == (SITE / "downloads/docker/compose.yaml").read_text().strip()
arm_compose = html.unescape(re.search(r'<code id="compose-arm64-code">(.*?)</code>', source, re.S)[1])
assert arm_compose.strip() == (SITE / "downloads/docker-arm64/compose.yaml").read_text().strip()
assert "platform: linux/arm64" in arm_compose
assert arm_compose.replace("platform: linux/arm64", "platform: linux/amd64").strip() == compose.strip()
assert 'href="downloads/docker-arm64-kit.zip"' in source
assert 'href="downloads/docker/seccomp.json" download="seccomp.json"' in source
assert 'href="downloads/docker-kit.zip" download="docker-kit.zip" data-download-status="docker-kit-status"' in source
assert 'id="docker-kit-status"' in source
assert 'id="docker-kit-download"' in source
assert "curl --fail --location https://nuaforma.dev/downloads/docker-kit.zip --output docker-kit.zip" in source
for name in ("docker", "docker-arm64", "helm"):
    folder = SITE / "downloads" / name
    assert (folder / "seccomp.json").read_bytes() == (ROOT / "deployment/pdf-runtime/seccomp.json").read_bytes()
    assert (folder / "nuaforma.apparmor").read_bytes() == (ROOT / "deployment/pdf-runtime/nuaforma.apparmor").read_bytes()
    if name.startswith("docker"):
        assert (folder / "compose.apparmor.yaml").read_bytes() == (ROOT / "deployment/pdf-runtime/compose.apparmor.yaml").read_bytes()
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
    if len(index) == 7:
        assert b'{image | image(60, 25, true)}' in archive.read('word/document.xml')
    namespace = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
    document = ET.fromstring(archive.read("word/document.xml"))
    table = document.find(".//w:tbl", namespace)
    width_attribute = "{" + namespace["w"] + "}w"
    grid = [int(column.attrib[width_attribute]) for column in table.findall("w:tblGrid/w:gridCol", namespace)]
    declared = int(table.find("w:tblPr/w:tblW", namespace).attrib[width_attribute])
    assert sum(grid) == declared == 8640
    for row in table.findall("w:tr", namespace):
        assert sum(int(cell.find("w:tcPr/w:tcW", namespace).attrib[width_attribute]) for cell in row.findall("w:tc", namespace)) == declared
    page = document.find(".//w:sectPr/w:pgSz", namespace)
    margins = document.find(".//w:sectPr/w:pgMar", namespace)
    assert declared <= int(page.attrib[width_attribute]) - int(margins.attrib["{" + namespace["w"] + "}left"]) - int(margins.attrib["{" + namespace["w"] + "}right"]) - 720
assert not any(p.is_symlink() for p in SITE.rglob("*"))

for client in ("bruno", "postman"):
    folder = SITE / "downloads" / client
    with zipfile.ZipFile(SITE / "downloads" / (client + "-kit.zip")) as archive:
        expected = {p.relative_to(folder).as_posix() for p in folder.rglob("*") if p.is_file()}
        assert set(archive.namelist()) == expected
        for member in expected:
            assert archive.read(member) == (folder / member).read_bytes()
    for file in ["nuaforma-example-template.docx", *[s["file"] for s in index]]:
        assert (folder / "assets" / file).read_bytes() == (SITE / "downloads" / file).read_bytes()
collection = json.loads((SITE / "downloads/postman/NuaForma.postman_collection.json").read_text(encoding="utf-8"))
assert collection["variable"] == [{"key": "baseUrl", "value": "http://localhost:8080", "type": "string"}]
assert len(collection["item"]) == 5 + len(index)
assert len(list((SITE / "downloads/bruno").glob("[0-9][0-9]-*.bru"))) == 5 + len(index)
generation_requests = [item["request"] for item in collection["item"] if item["request"]["url"].endswith("/documents")]
assert {r["body"]["formdata"][0]["src"] for r in generation_requests} == {"assets/" + s["file"] for s in index}
assessment = next(item["request"] for item in collection["item"] if item["request"]["url"].endswith("/assess"))
assert json.loads(assessment["body"]["raw"]) == json.loads((SITE / "downloads" / index[0]["file"]).read_text())
assert 'vars:pre-request {\n  baseUrl: http://localhost:8080\n}' in (SITE / "downloads/bruno/collection.bru").read_text()
for scenario in index:
    bru = next((SITE / "downloads/bruno").glob("??-" + pathlib.Path(scenario["file"]).stem + ".bru")).read_text()
    assert "body: multipartForm" in bru
    assert f'request: @file(assets/{scenario["file"]}) @contentType(application/json)' in bru
    assert 'template: @file(assets/nuaforma-example-template.docx) @contentType(application/vnd.openxmlformats-officedocument.wordprocessingml.document)' in bru
for item in collection["item"]:
    request = item["request"]
    assert request["url"].startswith("{{baseUrl}}/")
    assert request["method"] in ("GET", "POST")
    if request["url"].endswith("/documents"):
        assert request["body"]["mode"] == "formdata"
        assert [part["key"] for part in request["body"]["formdata"]] == ["request", "template"]
        assert not any(h["key"].lower() == "content-type" for h in request["header"])
        for part in request["body"]["formdata"]:
            assert (SITE / "downloads/postman" / part["src"]).is_file()
        assert request["body"]["formdata"][0]["contentType"] == "application/json"
expected = {"START-HERE.md": SITE / "downloads/START-HERE.md"}
for prefix, folder in (("Deploy/AMD64", "docker"), ("Deploy/ARM64", "docker-arm64"), ("Deploy/Helm", "helm"), ("Collections/Bruno", "bruno"), ("Collections/Postman", "postman")):
    parent = SITE / "downloads" / folder
    expected.update({prefix + "/" + p.relative_to(parent).as_posix(): p for p in parent.rglob("*") if p.is_file()})
for file in ["EXAMPLES.md", "scenarios.json", "nuaforma-example-template.docx", *[s["file"] for s in index]]:
    expected["Testkit/" + file] = SITE / "downloads" / file
with zipfile.ZipFile(SITE / "downloads/nuaforma-complete-kit.zip") as archive:
    assert set(archive.namelist()) == set(expected)
    for member, file in expected.items():
        assert archive.read(member) == file.read_bytes()
        assert ".." not in pathlib.PurePosixPath(member).parts and not member.startswith("/")
print("Client collection assets, multipart settings and complete-kit parity: PASS")
print(f"Static links, {len(index)} scenarios, Compose parity, archives and profiles: PASS")
