"""Build portable HTTP-client collections from the authoritative website examples."""
import json
import pathlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "site/downloads"
DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def write_json(path, value):
    write(path, json.dumps(value, indent=2, ensure_ascii=False) + "\n")


def archive_files(target, files):
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, path in sorted(files.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, path.read_bytes())


def build(version="0.2.0-alpha.1"):
    scenarios = json.loads((OUT / "scenarios.json").read_text(encoding="utf-8"))
    requests = [
        ("Version", "GET", "/api/v1/version", None, "application/json", "Packaged version; not a readiness check."),
        ("Capabilities", "GET", "/api/v1/capabilities", None, "application/json", "Check available.writeFormats for PDF readiness, not only declared formats."),
        ("Assess final PDF request", "POST", "/api/v1/assess", scenarios[0]["file"], "application/json", "Preflight readiness/options only; this does not upload or validate the template."),
        ("OpenAPI JSON", "GET", "/openapi.json", None, "application/json", "Download the service OpenAPI JSON definition."),
        ("OpenAPI YAML", "GET", "/openapi.yaml", None, "application/yaml", "Download the equivalent OpenAPI YAML definition."),
    ]
    for scenario in scenarios:
        envelope = json.loads((OUT / scenario["file"]).read_text(encoding="utf-8"))
        media = DOCX if envelope["output"]["format"] == "docx" else "application/pdf"
        requests.append((scenario["title"], "POST", "/api/v1/documents", scenario["file"], media, scenario["description"] + " Expected: " + scenario["expected"]))

    bruno = OUT / "bruno"
    postman = OUT / "postman"
    write_json(bruno / "bruno.json", {"version": "1", "name": "NuaForma examples", "type": "collection", "ignore": ["assets"]})
    write(bruno / "collection.bru", 'vars:pre-request {\n  baseUrl: http://localhost:8080\n}\n')
    items = []
    for sequence, (name, method, route, file, media, description) in enumerate(requests, 1):
        envelope_text = (OUT / file).read_text(encoding="utf-8").strip() if file else None
        mode = "multipartForm" if route.endswith("/documents") else "json" if file else "none"
        bru = f"meta {{\n  name: {name}\n  type: http\n  seq: {sequence}\n}}\n\n{method.lower()} {{\n  url: {{{{baseUrl}}}}{route}\n  body: {mode}\n  auth: none\n}}\n"
        request = {"method": method, "header": [{"key": "Accept", "value": media}], "url": "{{baseUrl}}" + route, "description": description}
        if mode == "multipartForm":
            bru += f"\nbody:multipart-form {{\n  request: @file(assets/{file}) @contentType(application/json)\n  template: @file(assets/nuaforma-example-template.docx) @contentType({DOCX})\n}}\n"
            request["body"] = {"mode": "formdata", "formdata": [
                {"key": "request", "type": "file", "src": "assets/" + file, "contentType": "application/json"},
                {"key": "template", "type": "file", "src": "assets/nuaforma-example-template.docx", "contentType": DOCX},
            ]}
        elif mode == "json":
            bru += "\nbody:json {\n" + "\n".join("  " + line for line in envelope_text.splitlines()) + "\n}\n"
            request["header"].append({"key": "Content-Type", "value": "application/json"})
            request["body"] = {"mode": "raw", "raw": envelope_text, "options": {"raw": {"language": "json"}}}
        bru += f"\nheaders {{\n  Accept: {media}\n}}\n\nassert {{\n  res.status: eq 200\n}}\n\ndocs {{\n  {description}\n  Save a successful document response as PDF or DOCX. Errors are JSON diagnostics.\n}}\n"
        filename = f"{sequence:02d}-" + (pathlib.Path(file).stem if mode == "multipartForm" else route.rsplit("/", 1)[-1].replace(".", "-")) + ".bru"
        write(bruno / filename, bru)
        items.append({"name": f"{sequence:02d} - {name}", "request": request, "event": [{"listen": "test", "script": {"type": "text/javascript", "exec": [
            'pm.test("HTTP 200", function () { pm.response.to.have.status(200); });',
            'pm.test("Expected content type", function () { pm.expect(pm.response.headers.get("Content-Type")).to.include(' + json.dumps(media) + '); });',
        ]}}]})
    write_json(postman / "NuaForma.postman_collection.json", {
        "info": {"name": "NuaForma examples", "schema": SCHEMA, "description": f"Local evaluation collection. Set baseUrl, use the extracted postman folder as your working directory, then check capabilities before generating. Includes {len(scenarios)} complete scenarios and local assets. No credentials or automatic file-saving scripts."},
        "auth": {"type": "noauth"}, "variable": [{"key": "baseUrl", "value": "http://localhost:8080", "type": "string"}], "item": items,
    })
    assets = [OUT / "nuaforma-example-template.docx", *[OUT / s["file"] for s in scenarios]]
    for folder in (bruno, postman):
        (folder / "assets").mkdir(parents=True, exist_ok=True)
        for asset in assets:
            (folder / "assets" / asset.name).write_bytes(asset.read_bytes())
        write(folder / "README.md", client_readme(folder.name, version, len(scenarios)))
        archive_files(OUT / (folder.name + "-kit.zip"), {p.relative_to(folder).as_posix(): p for p in folder.rglob("*") if p.is_file()})


def client_readme(client, version="0.2.0-alpha.1", scenario_count=7):
    steps = (
        "1. Extract the entire ZIP into a local folder. In Bruno, choose **Open Collection** and select the folder containing `bruno.json` (not just an individual `.bru` file).\n"
        "2. Open the collection variables and set `baseUrl` if your service is not at `http://localhost:8080`. No environment selection is required.\n"
        "3. Files are relative to the collection root. Keep `assets/` beside `bruno.json`; no filesystem scripts or Node sandbox permissions are needed.\n"
        "4. Send **Version**, then **Capabilities**. Use the download/save response action to save generated PDF or DOCX bytes.\n"
        if client == "bruno" else
        "1. Extract the entire ZIP. In Postman desktop, choose **Import** and select `NuaForma.postman_collection.json`.\n"
        "2. In **Settings > General > Working directory**, choose the extracted folder containing this JSON and `assets/`. Leave **Read files outside working directory** off. File paths are relative to that working directory, not to the imported JSON.\n"
        "3. Set collection variable `baseUrl` if your service is not at `http://localhost:8080`. If a client version asks to reselect a file, select the matching file under `assets/` in its existing form-data row.\n"
        "4. Send **Version**, then **Capabilities**. For generated documents use **Send and Download**, or save the response to a file. Prefer the desktop client for local files; this kit does not require uploading files to a Postman team.\n"
    )
    cli = (
        "From the extracted collection root, with the optional Bruno CLI installed: `bru run --env-var baseUrl=http://localhost:8080`.\n"
        if client == "bruno" else
        "With optional Newman installed: `newman run NuaForma.postman_collection.json --working-dir . --env-var baseUrl=http://localhost:8080 --timeout-request 180000`.\n"
    )
    compatibility = (
        "Current repository source also documents newer APIs; this collection keeps its 11 alpha.3-compatible requests. Health/SBOM routes, extensible pipes and the separate Playground need a matching newer/source build, not an unchanged alpha.3 image."
        if version == "0.1.0-alpha.3" else
        "Use the matching service version for the image content pipe. Health/SBOM routes and the separate Playground are documented in the service guides; these collections focus on document generation."
    )
    return f"""# NuaForma — {client.title()} collection

For the NuaForma {version} service. Start your own installation first; this kit does not start a service or contact the website.

See the [documentation index](https://github.com/NuaForma/NuaForma/blob/main/docs/README.md) for template/API/PDF guides. {compatibility}

{steps}
The collection contains {5 + scenario_count} requests: version, capabilities, one JSON assessment, OpenAPI JSON/YAML, and all {scenario_count} document scenarios. An HTTP-success test does not establish renderer readiness or visual/PDF-A conformance. Check the PDF provider's `available.writeFormats`; declared formats alone are not sufficient. Assessment does not validate the Word template.

Multipart generation uses file parts named `request` (`application/json`) and `template` (the Word MIME type). Do not add a top-level Content-Type: the client generates the multipart boundary. All {scenario_count} JSON files and the same Word template are included under `assets/`; edit a JSON file to experiment. Tests assert successful responses but do not save documents automatically. Errors contain JSON diagnostics; do not rename an error response to PDF. Scenario 05 produces DOCX; the other document scenarios produce PDFs.

{cli}
CLI runs exercise every example and can take several minutes. They do not automatically retain generated documents. Keep request timeouts at least 180 seconds when testing PDF locally. No TLS verification is disabled. Use an authenticated TLS gateway for remote access and configure your own authorization when needed; the distributed service has no built-in authentication/TLS.

Only synthetic data is included. Review your client's workspace/cloud-sync settings before adding real data or credentials. Do not upload confidential templates or documents to shared workspaces unintentionally. NuaForma-authored examples are Apache-2.0; project branding grants no trademark rights. No client binaries are bundled.
"""


if __name__ == "__main__":
    build()
    print("Built Bruno and Postman collections, local assets and deterministic ZIP kits")
