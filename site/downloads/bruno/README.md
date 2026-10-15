# NuaForma — Bruno collection

For the NuaForma 0.1.0-alpha.3 service. Start your own installation first; this kit does not start a service or contact the website.

1. Extract the entire ZIP into a local folder. In Bruno, choose **Open Collection** and select the folder containing `bruno.json` (not just an individual `.bru` file).
2. Open the collection variables and set `baseUrl` if your service is not at `http://localhost:8080`. No environment selection is required.
3. Files are relative to the collection root. Keep `assets/` beside `bruno.json`; no filesystem scripts or Node sandbox permissions are needed.
4. Send **Version**, then **Capabilities**. Use the download/save response action to save generated PDF or DOCX bytes.

The collection contains 11 requests: version, capabilities, one JSON assessment, OpenAPI JSON/YAML, and all six document scenarios. An HTTP-success test does not establish renderer readiness or visual/PDF-A conformance. Check the PDF provider's `available.writeFormats`; declared formats alone are not sufficient. Assessment does not validate the Word template.

Multipart generation uses file parts named `request` (`application/json`) and `template` (the Word MIME type). Do not add a top-level Content-Type: the client generates the multipart boundary. All six JSON files and the same Word template are included under `assets/`; edit a JSON file to experiment. Tests assert successful responses but do not save documents automatically. Errors contain JSON diagnostics; do not rename an error response to PDF. PDF scenarios 01–04/06 produce PDFs; 05 produces DOCX.

From the extracted collection root, with the optional Bruno CLI installed: `bru run --env-var baseUrl=http://localhost:8080`.

CLI runs exercise every example and can take several minutes. They do not automatically retain generated documents. Keep request timeouts at least 180 seconds when testing PDF locally. No TLS verification is disabled. Use an authenticated TLS gateway for remote access and configure your own authorization when needed; the distributed service has no built-in authentication/TLS.

Only synthetic data is included. Review your client's workspace/cloud-sync settings before adding real data or credentials. Do not upload confidential templates or documents to shared workspaces unintentionally. NuaForma-authored examples are Apache-2.0; project branding grants no trademark rights. No client binaries are bundled.
