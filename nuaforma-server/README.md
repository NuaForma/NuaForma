# REST service

Development-stage Java 17 service using Ktor CIO and the same public engine/provider contracts as embedded generation. No second template evaluator, storage backend or implicit resource fetching is introduced. OpenAPI downloads belong to issue #13; final Docker packaging and mount support belong to #14/#35. No public service or image is deployed by the build.

## Run locally

```sh
./gradlew :nuaforma-server:installDist
./nuaforma-server/build/install/nuaforma-server/bin/nuaforma-server
```

The default listener is `127.0.0.1:8080`. Trusted JVM properties `nuaforma.server.host` and `nuaforma.server.port` override it; use the launcher's `JAVA_OPTS` or `:nuaforma-server:run` JVM configuration. PDF additionally needs `-Dnuaforma.pdf.launcher=/opt/nuaforma/bin/render-pdf` inside the [qualified runtime](../deployment/pdf-runtime/README.md); setting a property without that isolated runtime does not enable PDF. Missing PDF support leaves DOCX usable. Libraries do not start this server automatically.

The initial service has no authentication/TLS implementation. Do not expose it directly to an untrusted network; use an authenticated TLS gateway with connection/header limits, access controls and deployment quotas. No forwarded-header trust, CORS, request-body logging or telemetry is enabled by NuaForma. The application starts only on explicit invocation. Installed provider code is trusted executable application code, not protected by the document sandbox.

## Endpoints

| Method | Path | Response |
| --- | --- | --- |
| GET | `/api/v1/capabilities` | Registered providers, declared/usable formats, option metadata and safe readiness/discovery diagnostics |
| POST | `/api/v1/assess` | JSON envelope; selected provider or structured rejection, without document validation or font/resource reservation |
| POST | `/api/v1/documents` | Multipart generation; validated document attachment or structured JSON failure |

Both POST routes use the same JSON envelope:

```json
{
  "input": { "format": "docx" },
  "output": { "format": "docx", "options": { "fontMode": "preserve" } },
  "providerId": "nuaforma-docx",
  "filename": "agreement",
  "data": { "customer": { "name": "Ada" }, "reference": "NF001" },
  "options": {
    "locale": "nl-NL",
    "timeZone": "Europe/Amsterdam",
    "watermark": { "text": "CONCEPT — {customer.name}", "color": "#B0B0B0", "opacity": 0.25, "rotation": -45 },
    "extensions": {}
  }
}
```

`input` and `output` are required objects with extensible lowercase `format` identifiers and optional generic `options` objects. Providers validate these options, including versions/profiles and combinations; unknown explicit provider options fail rather than silently downgrade. `data` defaults to an empty object and supports arbitrary nested JSON objects, arrays, strings, booleans, exact integers/decimals and nulls. All supplied data is accessible to templates, but HTTP metadata and server configuration are not. Unknown envelope/general-option/watermark fields and duplicate JSON object keys are rejected. JSON is strict UTF-8 with depth/token/number limits; exceptions never reveal the submitted values.

`providerId` is optional, but DOCX needs a preference when both built-in providers are present. Multiple matches fail deterministically. `filename` is a transport-only ASCII basename (`[A-Za-z0-9][A-Za-z0-9_-]{0,63}`, default `document`); the actual output format supplies the extension. Uploaded filenames are ignored and never become paths. Locale defaults to root and time zone to UTC. General extensions remain provider-interpreted structured values, not HTTP settings. Request-selected resource budgets, filesystem paths, converter commands and provider installation are not supported.

Requested watermarks have the same defaults, validation and restricted language as embedded generation: resolve once against `data`, share generation budgets, do not reevaluate inserted values, and omit a blank resolved result. Existing template layout/watermarks remain under the documented provider policies. PDF conversion leaves DOCX output unchanged; the scoped text-box limitation in #32 still applies.

## Multipart and resources

Generation requires exactly one `request` part (`application/json`) and one binary `template` file part. A DOCX template uses its document media type or `application/octet-stream`. Optional binary file parts named `resource.<id>` supply fonts/resources; IDs match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. The suffix becomes the existing engine resource ID referenced by provider options, e.g. `resource.barcode` with a font declaration `{"family":"Libre Barcode 39","face":"regular","resource":"barcode"}`. No server path or URL resolution occurs. Parts may arrive in any order; duplicate/unknown names are rejected. Each request has private storage and resource factories; no global font installation occurs. Follow technical embedding permissions without proof-of-ownership/extra-consent requirements.

```sh
curl --fail-with-body http://127.0.0.1:8080/api/v1/documents \
  -F 'request=@request.json;type=application/json' \
  -F 'template=@template.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document' \
  --output agreement.docx
```

For PDF, request `output.format: "pdf"` with the [PDF provider's options](../nuaforma-docx-pdf/README.md); the initial supported profile is static standard PDF 1.7, not PDF/A, PDF/UA or forms. Supply font resources as additional file parts where required. Assessment validates requirements/readiness, not a template or font file, and is not a reservation.

## Limits and responses

Defaults: 4 concurrent HTTP operations (uploads, generation and downloads), 100 MiB total request body, 1 MiB JSON, 32 MiB template, 32 resource parts of at most 16 MiB each, 128 MiB output and a 3-minute complete handler deadline. The independent engine budgets still apply (including its 2-minute generation deadline, archive expansion, expressions and repetitions). `ServerConfiguration` permits trusted application configuration, never per-request increases. Ktor bounds the total body even without Content-Length; individual parts are independently counted. A JSON form field is buffered by the bounded multipart parser; using a JSON file part enables streaming/spooling before decoding. Use a hard temporary-filesystem quota in deployment; application byte limits are not a filesystem quota. This does not claim isolation of the in-process DOCX library against every parser denial-of-service.

Inputs and generated output are spooled per request; no document is published before successful engine completion. Downloads use a bounded file, not an unbounded in-memory output buffer. Private storage is deleted after download, failure or cancellation. Providers must honor the existing cancellation/limits contract. A client disconnect is not guaranteed to be observed immediately during CPU-bound generation; the server deadline still bounds abandoned work. A network failure after attachment headers are committed terminates the download, not a JSON response or rollback of delivered bytes. Forced process termination cannot guarantee cleanup; deployment must provide ephemeral storage.

Successful attachments use DOCX/PDF media types, `Content-Disposition`, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` and bounded `NuaForma-Warning-Codes`. Other extension formats use `application/octet-stream`. Response Content-Length describes the completed file. Error JSON contains a `diagnostics` array with `code`, `severity` and safe `message`; raw provider messages, stack traces, values and paths are not exposed. Generic safe diagnostic codes may contain information useful for client handling; capability option descriptions are trusted provider metadata.

| Status | Meaning |
| --- | --- |
| 200 | Assessment/capabilities success or generated document |
| 400 | Invalid JSON/envelope/multipart fields or filename |
| 408 | Request/generation cancellation or deadline |
| 413 | Upload/output/processing resource budget exceeded |
| 415 | Unsupported request/part media type or compressed request body |
| 422 | Unsupported requirements, ambiguous provider, invalid template/data/options |
| 503 | Unavailable provider/backend or HTTP capacity exhausted (`Retry-After: 1` for capacity) |
| 500 | Internal/provider/IO processing failure, without a partial success document |

## Verification

```sh
./gradlew :nuaforma-server:test
./gradlew build generatePomFileForMavenJavaPublication
```

REST tests exercise actual DOCX processing, embedded parity with only generated watermark UUIDs normalized, unknown/duplicate/deep JSON, media types/filenames/options, multipart limits/duplicates, output limits, concurrent data isolation, capacity, cancellation and cleanup. ArchUnit checks HTTP/coroutine/JSON adapter ownership with passing and deliberately violating fixtures.

`PdfRestQualification` is an explicit test-only real-socket entry point staged by `prepareEngineQualification`, not a production server API. In the qualified one-container runtime it submits a DOCX template plus private font resources over HTTP, generates two tenant-specific barcode PDFs and a five-page dynamic-watermark PDF, and checks private-file cleanup. Independent Poppler raster/font inspection and barcode decoding compare all nine pages with the embedded reference. No Office application, renderer changes, global font install or published container is required by this check. Keep its synthetic outputs and private QA fonts ignored; do not include them in distributions.
