# REST service

Unreleased source adds safe correlated operational logging and explicit `NUAFORMA_MODE=development` diagnostics; see the [configuration, privacy and forwarding guide](../docs/logging.md). Every application-level response has a generated `X-Request-ID`; safe errors also return the matching `requestId`. Neither incoming headers nor request/template options enable development mode. Published alpha images are unchanged.

The unreleased source providers use the same [value pipes](../nuaforma-template/README.md#extensible-value-pipes-unreleased-source) in embedded and REST document/watermark generation. Operator-installed JARs use the existing [read-only extension mount and startup snapshot](../deployment/pdf-runtime/MOUNTS.md); request data cannot register code or configure class loaders. Provider construction captures the startup registry, so later request threads do not rediscover extensions. Source capabilities/OpenAPI include the provider-owned pipe catalogs described below (#60). This does not add value pipes to the published alpha image.

Development-stage Java 25 service using Ktor CIO and the same public Java-17-compatible engine/provider contracts as embedded generation. No second template evaluator, storage backend or implicit resource fetching is introduced. Equivalent OpenAPI JSON/YAML downloads, the [native AMD64/ARM64 on-premise container](../deployment/pdf-runtime/SERVICE.md), operator mounts and per-provider font catalogs are implemented. Install JDK 17 and JDK 25 for the source build; Gradle selects the appropriate module toolchains. Published containers through `0.1.0-alpha.3` remain on Java 17 until a separately approved release; see [runtime compatibility](../docs/java-runtime-compatibility.md). No public service or image is deployed by the build.

## Run locally

```sh
./gradlew :nuaforma-server:installDist
./nuaforma-server/build/install/nuaforma-server/bin/nuaforma-server
```

Launch the installed script with `JAVA_HOME` pointing to JDK 25, or Java 25 on `PATH` when `JAVA_HOME` is unset. Gradle's compile/test toolchain selection does not change your shell's runtime. `./gradlew :nuaforma-server:run` selects the Java 25 launcher explicitly. The container entry point likewise uses its own pinned Java 25 installation.

The default listener is `127.0.0.1:8080`. Trusted JVM properties `nuaforma.server.host` and `nuaforma.server.port` override it; use the launcher's `JAVA_OPTS` or `:nuaforma-server:run` JVM configuration. PDF additionally needs `-Dnuaforma.pdf.launcher=/opt/nuaforma/bin/render-pdf` inside the [qualified runtime](../deployment/pdf-runtime/README.md); setting a property without that isolated runtime does not enable PDF. Missing PDF support leaves DOCX usable. Libraries do not start this server automatically.

The real `Main` entry point also accepts the bounded operator properties documented in the [container configuration table](../deployment/pdf-runtime/SERVICE.md#configuration-and-private-storage). Invalid numeric values fail startup without echoing them. The writable absolute `java.io.tmpdir` is shared by HTTP and PDF private storage. The server installs a JVM shutdown hook for graceful handler/process cancellation; deployments still need child reaping, filesystem quotas and enough termination time. These are operator settings, not REST generation options.

The initial service has no authentication/TLS implementation. Do not expose it directly to an untrusted network; use an authenticated TLS gateway with connection/header limits, access controls and deployment quotas. No forwarded-header trust, CORS, request-body logging or telemetry is enabled by NuaForma. The application starts only on explicit invocation. Installed provider code is trusted executable application code, not protected by the document sandbox.

## Endpoints

This table describes current source. Health/SBOM/pipe catalogs are not retroactively added to existing alpha images; see [published versus source versions](../docs/README.md#source-versus-published-versions). The separate [Playground](../nuaforma-playground/README.md) is a development UI, not an OpenAPI operation.

Each provider in `/api/v1/capabilities` includes `fonts: {status, entries}`. Status is `AVAILABLE`, `UNSUPPORTED` (no optional facet) or `UNAVAILABLE` (enumeration failed/not ready). Entries contain `family`, `face`, `source` (`bundled`/`mounted`) and `resource` (opaque mounted selector or null). No uploaded/request-private/template fonts, paths or font bytes are exposed. Empty available catalogs and unsupported enumeration differ; names/styles do not establish embedding rights or glyph coverage. Both OpenAPI downloads describe these fields. The [mount guide](../deployment/pdf-runtime/MOUNTS.md) covers operator configuration and restart behavior.

| Method | Path | Response |
| --- | --- | --- |
| GET | `/health/live` | Lightweight application liveness, independent of optional converters and generation slots |
| GET | `/health/ready` | Cached lifecycle/provider readiness; DOCX can remain ready without PDF |
| GET | `/api/v1/sbom` | Static build-generated CycloneDX 1.7 JSON inventory |
| GET | `/api/v1/version` | Packaged application version, runtime contract version and source commit; no provider/readiness query |
| GET | `/api/v1/capabilities` | Registered providers, declared/usable formats, option metadata and safe readiness/discovery diagnostics |
| POST | `/api/v1/assess` | JSON envelope; selected provider or structured rejection, without document validation or font/resource reservation |
| POST | `/api/v1/documents` | Multipart generation; validated document attachment or structured JSON failure |
| GET | `/openapi.json` | OpenAPI 3.1.1 JSON attachment |
| GET | `/openapi.yaml` | Equivalent OpenAPI 3.1.1 YAML attachment |

## Build version

`GET /api/v1/version` returns `{"version":"0.1.0-SNAPSHOT","contractVersion":"0.1","commit":"<full-source-commit>"}` with JSON, `Cache-Control: no-store` and `nosniff`. Gradle records the application version and Git HEAD during the build and packages them in the server JAR; changing either invalidates generation. The contract version comes from the running contracts module. When Git identity is unavailable (for example, building an extracted source archive), `commit` is null. No timestamp, host paths, credentials or deployment environment is exposed. A commit identifies the checkout base, not proof that local sources were clean or a release was approved. OpenAPI `info.version` uses the same generated application version. This endpoint uses normal HTTP admission/deadline limits but never checks fonts, provider availability or conversion readiness.

## OpenAPI downloads and export

```sh
curl --fail http://127.0.0.1:8080/openapi.json --output nuaforma-openapi.json
curl --fail http://127.0.0.1:8080/openapi.yaml --output nuaforma-openapi.yaml
./gradlew :nuaforma-server:generateOpenApi
```

The Gradle task writes both documents under `nuaforma-server/build/generated/openapi`, without starting HTTP, discovering providers or rendering documents. A single server-owned `OpenApiContract` model defines both serializations; generated files are ignored, not separately authored copies. Both downloads use attachment filenames and `nosniff`, with `application/json` and `application/yaml` respectively. The source uses the common route constants and is served without reflecting request hosts, filesystem paths or deployment secrets. Specification downloads share the configured HTTP admission/deadline policy.

The contract defines the multipart template/JSON/resource envelope, extensible format requests, generic data/options, dynamic-watermark settings, capability metadata, assessment, output media types/headers and safe errors. It documents defaults, runtime validation beyond JSON Schema (e.g. valid locale/time zone and watermark UTF-16 budget), isolated font/PDF behavior and deployment boundaries. It does not hard-code an exhaustive provider-format/option enum: query capabilities and assess the whole request for current supported versions/profiles. The PDF provider offers static standard PDF and the three B-level PDF/A profiles; forms and level-A/accessibility profiles remain unsupported. See its guide for qualification and the restricted PDF/A-1b template scope.

Resource file parts use `resource.<id>` and the OpenAPI 3.1 multipart schema's `patternProperties`. Some client generators do not expose these dynamically named fields: append them explicitly in the multipart client. This is a tooling limitation, not permission to rename the transport or discard resources. OpenAPI JSON and YAML describe the same HTTP interface for hosted/on-premise use; they are not inputs required by embedded library users, who use the [Java/Kotlin engine API](../nuaforma-core/README.md) directly. No Swagger UI, CDN dependency, public hosting or remote schema fetching is introduced.

Swagger Parser 2.1.48 is test-only. Tests parse the two real HTTP downloads independently, compare their complete JSON trees, check local references, exercise documented endpoints and compare representative schema-valid/invalid envelopes with the actual decoder. The behavioral schema assertions cover the explicitly used keyword subset and fail on newly unsupported keywords; they are not advertised as a general-purpose JSON Schema validator. Tests include multipart resource-name validation and deliberate contract drift. Jackson parser transitives are test-aligned to patched 2.22.3; neither Swagger/Jackson/SnakeYAML nor their vendor models enter production artifacts. YAML emission uses a small JSON-compatible YAML 1.2 subset with every string/key quoted and no tags/anchors; parsing is performed only by independent test tooling.

## Pipe capabilities (unreleased source)

Each provider in `GET /api/v1/capabilities` now includes separate `valuePipes` and `contentPipes` catalogs, derived from its actual `TemplatePipeProvider`/`ContentPipeProvider` registration snapshots. Each catalog has `status` (`AVAILABLE`, `UNSUPPORTED` or `UNAVAILABLE`), sorted `entries` and safe `diagnostics`. An available empty registry differs from a provider without the optional facet. Invalid or colliding metadata fails closed for that provider's pipe catalogs without leaking exception text.

Value entries expose name, description, positional argument definitions/defaults/allowed values, null behavior, `chainable: true` and an `available` indication. They are format-independent transformations; the enclosing provider identifies the registry actually used. Do not merge registries from multiple providers or pick a pipe by classpath order. Fixed `number`/`date` language formatters are not custom extension registrations.

Content entries expose the same descriptive fields with `chainable: false`, insertion `contexts`, accepted source `contentTypes`, `declaredSupport` and `availableSupport`. Each support entry contains input/output format IDs and profiles. Empty profiles mean the profiles qualified by that generator, not every possible PDF variant. Runtime availability narrows combinations but is not a reservation or validation of template content/options. For example, the PDF provider can declare image insertion into DOCX/PDF while advertising only DOCX as currently usable when the converter is absent. Explicit PDF/A-1b transparency restrictions still apply. Always assess the complete requested options and choose the enclosing provider explicitly when matches are ambiguous.

The existing capability fields remain intact. Listing pipes does not scan plugins, invoke callbacks, decode images or generate documents. Embedded callers query the same optional contract facets; no transport types or new mandatory provider methods are introduced. These additions are source-only and not present in existing published alpha artifacts. The planned Playground uses these catalogs directly rather than maintaining a separate pipe inventory.

## Request envelope

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

## Generator content pipes (unreleased source)

The HTTP envelope does not register content pipes or carry executable code. An installed trusted provider may explicitly compose the new generator-owned content registry; its DOCX template uses the same `{value | registeredName(arguments)}` syntax, data scopes and safe failure rules as embedded generation. JSON values remain literal, including strings containing braces. Output profile selection remains in `output.options`, not in a separate content-pipe request object. Text watermarks reject content markers.

Source builds include the [bounded standard image pipe](../nuaforma-docx/README.md#image-content-pipe-unreleased-source); published alpha images do not. Use `{company.logo | image(40, 20)}` in ordinary template text and submit the same Base64 data in the existing JSON envelope, for example:

```json
{"input":{"format":"docx"},"output":{"format":"pdf","options":{"profile":"pdfa-3b"}},"data":{"company":{"logo":"<strict padded Base64 PNG or JPEG bytes>"}}}
```

The angle-bracket text is explanatory, not valid image data: replace it with your encoded image. Use `null` to omit an image, or `{company.logo ?? null | image(40, 20)}` to explicitly handle a missing path. There is no additional multipart image resource or image URL. Image data is subject to the same JSON/upload quotas as other data, plus the generator's stricter image budgets. Invalid image content returns a sanitized generation error without document bytes. For DOCX output choose `output.format = docx` and `providerId = nuaforma-docx`; for transparent PNG choose standard PDF or PDF/A-2b/3b, not restricted 1b.

The optional `ContentPipeProvider` contract supplies source `/api/v1/capabilities` content metadata through #60, with declared and currently usable combinations. See the [DOCX registration example](../nuaforma-docx/README.md#generator-content-pipes-unreleased-source), and preserve the existing extension trust, mount and gateway policies. REST callers cannot install implementations or choose arbitrary classes.

## Service health

`GET /api/v1/sbom` separately downloads the immutable build-generated CycloneDX 1.7 shipped-component inventory. It includes explicit standalone/container scope and source identity, excludes private/operator resources, and does not scan the host or start providers. See the [SBOM guide](../docs/sbom.md) for build validation, coverage, headers and trusted startup configuration.

Source builds expose lightweight `GET /health/live` and `GET /health/ready` outside generation admission slots. Readiness requires initialization and a recent background observation with at least one executable provider pair; missing PDF does not disable usable DOCX generation. Responses contain only `status: UP|DOWN`, use 200/503 and retain request correlation without routine access-log noise. See the [health/lifecycle and deployment guide](../docs/health.md) for snapshot freshness, shutdown, the bundled Docker probe and Helm/Kubernetes/OpenShift settings. Previously published alpha images do not gain these routes retroactively.

## Multipart and resources

Generation requires exactly one `request` part (`application/json`) and one binary `template` file part. A DOCX template uses its document media type or `application/octet-stream`. Optional binary file parts named `resource.<id>` supply fonts/resources; IDs match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`. The suffix becomes the existing engine resource ID referenced by provider options, e.g. `resource.barcode` with a font declaration `{"family":"Libre Barcode 39","face":"regular","resource":"barcode"}`. No server path or URL resolution occurs. Parts may arrive in any order; duplicate/unknown names are rejected. Each request has private storage and resource factories; no global font installation occurs. Follow technical embedding permissions without proof-of-ownership/extra-consent requirements.

```sh
curl --fail-with-body http://127.0.0.1:8080/api/v1/documents \
  -F 'request=@request.json;type=application/json' \
  -F 'template=@template.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document' \
  --output agreement.docx
```

For PDF, request `output.format: "pdf"` with the [PDF provider's options](../nuaforma-docx-pdf/README.md). Omitting `output.options.profile` selects `pdfa-3b`; explicit choices are `pdfa-1b`, `pdfa-2b`, `pdfa-3b` and `standard`. Omit `version` to derive it from the profile, or supply a matching value. PDF/A-1b rejects transparent watermarks/templates and unsupported drawing/media profiles. Supply font resources as additional file parts where required. Assessment validates requirements/readiness, not a template or font file, and is not a reservation. Failed conformance validation never returns a downgraded PDF.

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
./gradlew :nuaforma-server:test --tests dev.nuaforma.server.OpenApiTest
./gradlew :nuaforma-server:generateOpenApi
./gradlew build generatePomFileForMavenJavaPublication
```

REST tests exercise actual DOCX processing, embedded parity with only generated watermark UUIDs normalized, unknown/duplicate/deep JSON, media types/filenames/options, multipart limits/duplicates, output limits, concurrent data isolation, capacity, cancellation and cleanup. ArchUnit checks HTTP/coroutine/JSON adapter ownership with passing and deliberately violating fixtures.

`PdfRestQualification` is an explicit test-only real-socket entry point staged by `prepareEngineQualification`, not a production server API. In the qualified one-container runtime it submits a DOCX template plus private font resources over HTTP, generates two tenant-specific barcode PDFs and a five-page dynamic-watermark PDF, and checks private-file cleanup. Independent Poppler raster/font inspection and barcode decoding compare all nine pages with the embedded reference. No Office application, renderer changes, global font install or published container is required by this check. Keep its synthetic outputs and private QA fonts ignored; do not include them in distributions.
