# NuaForma example kit

Use these files with your own NuaForma 0.2.0-alpha.1 service. This website hosts files and instructions, not a document generator. All people, companies and example.invalid email addresses in the requests are illustrative.

Find the language, API, PDF and operating guides in the [documentation index](https://github.com/NuaForma/NuaForma/blob/main/docs/README.md). The matching 0.2.0-alpha.1 service supports image/content pipes, health/SBOM endpoints and a separate development Playground.

The same Word template works with all seven complete JSON request envelopes. Open it in Word to inspect paragraph-level conditional/repetition markers and dedicated merged table-marker rows. Do not place table-row markers in ordinary populated rows. The template's static images are opaque JPEGs so this kit also fits the restricted PDF/A-1b image profile.

## Run a request

The complete testset also includes `bruno/` and `postman/` collections, each with 12 preconfigured requests and its own `assets/` directory. In Bruno, open the folder containing `bruno.json`. In Postman desktop, import `NuaForma.postman_collection.json` and select that same `postman/` directory as the working directory; leave unrestricted file access off. See each collection's README. Both use collection variable `baseUrl` (default `http://localhost:8080`). All seven JSON scenarios are included as multipart file parts with explicit content types; no automatic file-saving or filesystem scripts are needed.

Start your service using the documented secure Docker settings. Check `GET http://127.0.0.1:8080/api/v1/capabilities` and confirm the PDF provider can write PDF. HTTP availability alone is not PDF readiness.

In Bruno or your preferred client, create `POST http://127.0.0.1:8080/api/v1/documents` with a multipart body:

- `request`: the selected JSON, content type `application/json`; a file part is recommended, or paste the JSON as a form field.
- `template`: `nuaforma-example-template.docx` as a file, content type `application/vnd.openxmlformats-officedocument.wordprocessingml.document`.

Let the client generate the multipart boundary. Do not set `Content-Type: application/json` on the entire request. Save a 200 response as PDF or DOCX according to `output.format`; failures return JSON diagnostics. No extra font upload is needed because the template uses the renderer-bundled Liberation Serif family.

```sh
curl --fail-with-body http://127.0.0.1:8080/api/v1/documents \
  -F 'request=@01-final-pdfa3b.json;type=application/json' \
  -F 'template=@nuaforma-example-template.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document' \
  --output example.pdf
```

## Scenarios

1. `01-final-pdfa3b.json`: final PDF/A-3b, no watermark. Repeated work rows, active/paused projects and nested conditional notes.
2. `02-draft-watermark.json`: PDF/A-2b with translucent dynamic `DRAFT NF-002`, urgent text, null/missing contact fields and extra nested notes.
3. `03-null-empty-standard.json`: ordinary PDF 1.7 with an empty row list, null address/message, missing nickname and excluded projects. Empty rows retain the static table header; missing/null/blank strings have distinct documented outcomes.
4. `04-opaque-pdfa1b.json`: restricted PDF/A-1b with full-opacity `DRAFT NF-004`. Do not lower opacity or insert transparent images when using this profile.
5. `05-editable-word.json`: editable DOCX with a draft watermark; explicitly selects `nuaforma-docx` because both installed providers can write Word.
6. `06-empty-projects-pdfa2b.json`: PDF/A-2b with an empty project list and a literal placeholder inside the message. Inserted values are not evaluated again.

7. `07-base64-image-pdfa3b.json`: a Base64 PNG in `data.image`, inserted with `{image | image(60, 25, true)}`. Maxima are millimetres; the 3:1 illustration fits at 60 by 20 mm. Other scenarios provide null and omit the illustration. No image file upload or URL is needed.

Every request provides its own nested `data`. Conditions require actual booleans or expressions that return booleans. List/object equality, arithmetic and arbitrary method calls are not supported; row totals and the overall total are caller-supplied numbers. `??` handles missing/null scalars; `trim(value ?? "") == ""` also handles blank strings.

The template demonstrates structural `if → each → if → each → if` nesting, inner/outer aliases, loop numbers, repeated table rows, inline `if`/nested `if`, all five built-in string functions, explicit null versus missing fallbacks, dates/numbers, headers/footers and static images.

## Endpoints

| Method | Route | Input and result |
| --- | --- | --- |
| GET | `/health/live` | Lightweight liveness, independent of PDF readiness |
| GET | `/health/ready` | Cached service readiness; not a promise that a particular PDF request can run |
| GET | `/api/v1/sbom` | Build-generated CycloneDX 1.7 inventory, excluding private operator/request resources |
| GET | `/api/v1/version` | Build/contract version and source commit; not a readiness check |
| GET | `/api/v1/capabilities` | Providers, available formats/options and bundled/mounted font and provider-owned pipe catalogs |
| POST | `/api/v1/assess` | JSON request envelope; preflight options/readiness, no template validation |
| POST | `/api/v1/documents` | Multipart JSON/template/resources; document attachment or diagnostics |
| GET | `/openapi.json` | Authoritative OpenAPI JSON download |
| GET | `/openapi.yaml` | Equivalent OpenAPI YAML download |

When supplying private fonts, add file parts named `resource.<id>` and matching `output.options.fonts` declarations; mounted resources use selectors from capabilities. Do not supply unused resource parts. Technical embedding permissions are enforced, while callers remain responsible for usage rights.

This alpha has no built-in authentication/TLS. Use loopback for local evaluation or an authenticated TLS gateway for remote access. See the repository module guides, release notes and accepted native-runtime security follow-up in issue #51. The known modern inline-text-box deviation in issue #32 remains scoped to that feature; this kit does not claim universal Word layout parity.

## Authoring and licenses

The seven-scenario template authoring procedure is `python3 tools/site/prepare_release_site.py --output build/example-kit-rebuild --version 0.2.0-alpha.1`, run from the signed `v0.2.0-alpha.1` source with Python, python-docx and Pillow and a fresh output directory. This stages example documents, not approved publication files; retain the release's reviewed deployment kits instead of publishing the entire staging directory. This release reuses the existing qualified image-pipe implementation. The new illustration scenario is structurally checked; no additional rendered example qualification is claimed. NuaForma-authored sample files and the original schematic illustration are Apache-2.0. The included organization logo is project branding; the project license does not grant trademark rights. No private test fonts are included.
