# NuaForma — Postman collection

For the NuaForma 0.1.0-alpha.3 service. Start your own installation first; this kit does not start a service or contact the website.

1. Extract the entire ZIP. In Postman desktop, choose **Import** and select `NuaForma.postman_collection.json`.
2. In **Settings > General > Working directory**, choose the extracted folder containing this JSON and `assets/`. Leave **Read files outside working directory** off. File paths are relative to that working directory, not to the imported JSON.
3. Set collection variable `baseUrl` if your service is not at `http://localhost:8080`. If a client version asks to reselect a file, select the matching file under `assets/` in its existing form-data row.
4. Send **Version**, then **Capabilities**. For generated documents use **Send and Download**, or save the response to a file. Prefer the desktop client for local files; this kit does not require uploading files to a Postman team.

The collection contains 11 requests: version, capabilities, one JSON assessment, OpenAPI JSON/YAML, and all six document scenarios. An HTTP-success test does not establish renderer readiness or visual/PDF-A conformance. Check the PDF provider's `available.writeFormats`; declared formats alone are not sufficient. Assessment does not validate the Word template.

Multipart generation uses file parts named `request` (`application/json`) and `template` (the Word MIME type). Do not add a top-level Content-Type: the client generates the multipart boundary. All six JSON files and the same Word template are included under `assets/`; edit a JSON file to experiment. Tests assert successful responses but do not save documents automatically. Errors contain JSON diagnostics; do not rename an error response to PDF. PDF scenarios 01–04/06 produce PDFs; 05 produces DOCX.

With optional Newman installed: `newman run NuaForma.postman_collection.json --working-dir . --env-var baseUrl=http://localhost:8080 --timeout-request 180000`.

CLI runs exercise every example and can take several minutes. They do not automatically retain generated documents. Keep request timeouts at least 180 seconds when testing PDF locally. No TLS verification is disabled. Use an authenticated TLS gateway for remote access and configure your own authorization when needed; the distributed service has no built-in authentication/TLS.

Only synthetic data is included. Review your client's workspace/cloud-sync settings before adding real data or credentials. Do not upload confidential templates or documents to shared workspaces unintentionally. NuaForma-authored examples are Apache-2.0; project branding grants no trademark rights. No client binaries are bundled.
