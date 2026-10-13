# NuaForma example kit

Use these files with your own NuaForma 0.1.0-alpha.1 service. This website hosts files and instructions, not a document generator. All people, companies and example.invalid email addresses in the requests are illustrative.

The same Word template works with all six complete JSON request envelopes. Open it in Word to inspect paragraph-level conditional/repetition markers and dedicated merged table-marker rows. Do not place table-row markers in ordinary populated rows. The final images are opaque JPEGs so this kit also fits the restricted PDF/A-1b image profile.

## Run a request

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

Every request provides its own nested `data`. Conditions require actual booleans or expressions that return booleans. List/object equality, arithmetic and arbitrary method calls are not supported; row totals and the overall total are caller-supplied numbers. `??` handles missing/null scalars; `trim(value ?? "") == ""` also handles blank strings.

The template demonstrates structural `if → each → if → each → if` nesting, inner/outer aliases, loop numbers, repeated table rows, inline `if`/nested `if`, all five built-in string functions, explicit null versus missing fallbacks, dates/numbers, headers/footers and static images.

## Endpoints

| Method | Route | Input and result |
| --- | --- | --- |
| GET | `/api/v1/version` | Build/contract version and source commit; not a readiness check |
| GET | `/api/v1/capabilities` | Providers, available formats/options and bundled/mounted font catalogs |
| POST | `/api/v1/assess` | JSON request envelope; preflight options/readiness, no template validation |
| POST | `/api/v1/documents` | Multipart JSON/template/resources; document attachment or diagnostics |
| GET | `/openapi.json` | Authoritative OpenAPI JSON download |
| GET | `/openapi.yaml` | Equivalent OpenAPI YAML download |

When supplying private fonts, add file parts named `resource.<id>` and matching `output.options.fonts` declarations; mounted resources use selectors from capabilities. Do not supply unused resource parts. Technical embedding permissions are enforced, while callers remain responsible for usage rights.

This alpha has no built-in authentication/TLS. Use loopback for local evaluation or an authenticated TLS gateway for remote access. See the repository module guides, release notes and accepted native-runtime security follow-up in issue #51. The known modern inline-text-box deviation in issue #32 remains scoped to that feature; this kit does not claim universal Word layout parity.

## Authoring and licenses

Rebuild with `python3 tools/site/build_example_kit.py` from the repository root, using Python, python-docx and Pillow. The generator is the template's authoring procedure; generated outputs must be checked with the actual NuaForma service before publication. NuaForma-authored sample files and the original schematic illustration are Apache-2.0. The included organization logo is project branding; the project license does not grant trademark rights. No private test fonts are included.
