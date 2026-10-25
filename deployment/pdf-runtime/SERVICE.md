# On-premise service container

On the own-engine branch, the `service` target packages the Java 25 REST application, native PDF engine, Liberation fonts and separately pinned Java 17 PDF validators. LibreOffice is absent. Runtime generation needs no Office desktop, cloud account, worker container, Docker socket or dependency downloads. This candidate is under qualification; published releases remain unchanged. See the [native profile](../../nuaforma-docx-pdf/README.md) and [runtime compatibility](../../docs/java-runtime-compatibility.md).

The build/transport/operator instructions below remain applicable, with these native changes: `NUAFORMA_PDF_VALIDATOR_LAUNCHER` selects the trusted validator launcher, bundled fonts are captured from `/usr/share/fonts`, and occupied PDF slots reject immediately rather than queueing. Rendering executes inside the application JVM; only validation uses a private process. Existing published-backend performance/isolation results do not qualify this candidate. PDF watermarks, `paragraphDefaults`, mixed image/text paragraphs and unqualified source constructs explicitly reject; use the native profile as the source of truth over historical examples below. No PDF/A claim follows from disabling conformance validation.

## Build and start

Install JDK 17 and JDK 25, then use the checked-in wrapper and Docker with BuildKit/named build contexts. Libraries compile/test on 17, server on 25; set `JDK17`/`JDK25` when automatic toolchain discovery needs installation paths:

```sh
./gradlew --no-daemon build :nuaforma-server:installDist :nuaforma-docx-pdf:preparePdfValidation :nuaforma-docx-pdf:prepareVeraPdfValidation
python3 tools/container/prepare_verapdf_materials.py nuaforma-docx-pdf/build/verapdf-validation
python3 tools/container/prepare_application_materials.py
docker compose -f deployment/pdf-runtime/compose.yaml build
docker compose -f deployment/pdf-runtime/compose.yaml up -d
curl --fail http://127.0.0.1:8080/api/v1/capabilities
curl --fail http://127.0.0.1:8080/openapi.json --output openapi.json
docker compose -f deployment/pdf-runtime/compose.yaml down
```

An equivalent explicit build is:

```sh
docker build --platform linux/amd64 --file deployment/pdf-runtime/Dockerfile \
  --build-context pdfvalidation=nuaforma-docx-pdf/build/pdf-validation \
  --build-context verapdfvalidation=nuaforma-docx-pdf/build/verapdf-validation \
  --build-context application=nuaforma-server/build/install/nuaforma-server \
  --target service --tag nuaforma:local deployment/pdf-runtime
```

The application context contains Gradle's production distribution plus the original dependency source/notice materials prepared by the explicit helper; qualification classes, tests, private fonts, local agreements and the checkout are not copied. The Docker context is allowlisted. Original dependency JARs are unshaded, preserving embedded notices. Matching source JARs retain copyright/license headers omitted by some binary JARs; original docx4j notices and ANTLR 2 terms are supplemented separately. These materials are not on the runtime classpath. The final image records runtime SHA-256 inventories and includes NuaForma's LICENSE, THIRD_PARTY.md, DEPENDENCIES.md and [source-access instructions](SOURCES.md). The base image/runtime sources, licenses and publication gate in the [runtime guide](README.md#components-and-licenses) apply unchanged; that gate must be completed before redistributing any image, even one built locally.

Compose binds the host port to loopback, runs UID/GID 1000, includes Docker's child-reaping init, a read-only root, no capabilities, no-new-privileges and the reviewed default-deny seccomp profile. It limits CPU to 2, memory to 2 GiB, processes to 128 and `/tmp` to a 512 MiB tmpfs. A bridge network permits host access to the published port; it does not deny outer-JVM outbound traffic. The application performs no implicit network fetching, and each renderer/validator has its own network-denying sandbox. Add deployment-level egress controls when needed. An internal-only network behind a gateway container is an alternative, but Docker Desktop does not publish its ports to the host. Do not replace isolation with privileged mode, unconfined seccomp, host capabilities or mounted Docker sockets. Sandbox incompatibility leaves PDF unavailable, with no unsandboxed fallback. Current source passes native AMD64/ARM64 service checks with the reviewed AppArmor profile in GitHub Actions; the alpha.3 Apple Silicon/Podman results are recorded in issue #63. Other kernels/security policies/platforms, including a changed source runtime on that Mac setup, still require independent qualification. Do not disable host protections to make a deployment start.

The service has no built-in authentication or TLS. Keep it behind an authenticated TLS gateway for remote callers, with connection/header quotas. A successful HTTP response from capabilities proves HTTP availability, not PDF readiness: check the PDF provider's usable write formats and readiness diagnostics, or assess the intended request. DOCX remains usable when the converter is absent or isolation cannot start.

## Configuration and private storage

Source targets include the [health probes](../../docs/health.md) and [static shipped-component SBOM endpoint](../../docs/sbom.md). The maintained source Helm chart is in `deployment/helm/nuaforma`; website alpha kits retain their matching release behavior. The optional `playground` target and Compose override are documented in the [separate module guide](../../nuaforma-playground/README.md). Production `service` contains no Playground code/assets. Both applications retain the same renderer/validator isolation and deployment limits.

Operator environment variables, never request fields:

| Variable | Default | Purpose |
| --- | --- | --- |
| `NUAFORMA_HOST` | `0.0.0.0` | Container listener; host publication stays loopback in Compose |
| `NUAFORMA_MODE` | `production` when unset | From `0.2.0-alpha.1`, explicit `development` enables sensitive diagnostics/banner; empty/unknown values fail startup. See [logging](../../docs/logging.md); historical `0.1` images are unchanged |
| `NUAFORMA_PORT` | `8080` | Listener port, 0–65535; 0 selects an ephemeral port, unsuitable for the fixed container mapping |
| `NUAFORMA_HEAP_MB` | `512` | JVM maximum heap, 128–16384 MiB; still bounded by container memory |
| `NUAFORMA_TEMP_DIRECTORY` | `/tmp` | Absolute existing writable private-storage root |
| `NUAFORMA_PDF_LAUNCHER` | `/opt/nuaforma/bin/render-pdf` | Trusted isolated launcher; a missing path disables PDF only |
| `NUAFORMA_PDF_PARALLELISM` | `1` | Available from 0.2.0-alpha.2: 1–8 simultaneous PDF runtime processes per provider instance; invalid/empty values fail entry-point startup. Not a per-request option; earlier alpha.1 does not implement this setting |
| `NUAFORMA_PDFA_VALIDATION` | `true` | From 0.2.0-alpha.2: absent/exact `true` enables per-document veraPDF; only exact `false` disables it. Empty, whitespace, alternate case, numeric booleans and typos fail startup before traffic |
| `NUAFORMA_FONT_DIRECTORY` | `/opt/nuaforma/custom-fonts` | Absolute operator font mount target; startup snapshot, not a request option |
| `NUAFORMA_BUNDLED_FONT_DIRECTORIES` | Explicit shipped renderer font roots; see [mount guide](MOUNTS.md) | Path-separated immutable IMAGE roots for opt-in automatic DOCX embedding |
| `NUAFORMA_EXTENSION_DIRECTORY` | `/opt/nuaforma/extensions` | Absolute operator JAR mount target; startup snapshot of trusted executable code |

All launch arguments remain fixed; shell command strings from requests are never executed. Operator-controlled `JAVA_TOOL_OPTIONS` may set the JVM properties below, e.g. `-Dnuaforma.server.concurrency=2 -Dnuaforma.server.outputBytes=67108864`. This is trusted JVM configuration, not a restricted extension mechanism. Do not put secrets there; the JVM may report these options to stderr. Host/port/temp/launcher are supplied explicitly by the entry point; configure those through the listed environment variables.

| Property prefix `nuaforma.server.` | Default | Valid range |
| --- | --- | --- |
| `concurrency` | 4 | 1–64 HTTP operations |
| `requestBytes` | 104857600 | 1–1073741824 |
| `jsonBytes` | 1048576 | 1–16777216 |
| `resourceBytes` | 16777216 | 1–requestBytes |
| `resources` | 32 | 0–128 |
| `timeoutMillis` | 180000 | 1–3600000 |
| `templateBytes` | 33554432 | 1–1073741824 |
| `outputBytes` | 134217728 | 1–1073741824 |
| `expandedBytes` | 268435456 | 1–1073741824 |
| `archiveEntries` | 10000 | 1–100000 |
| `expressionDepth` | 64 | 1–256 |
| `repetitions` | 100000 | 1–1000000 |
| `generationMillis` | 120000 | 1–3600000 |

Invalid values fail startup, without repeating the supplied value. HTTP concurrency and the PDF provider's default single conversion slot are distinct limits; queued conversion still counts toward the deadline. Increased heap/limits need a correspondingly reviewed memory/tmpfs/process budget: maximum limits are not recommended deployment sizes. The JVM heap is not the total container memory; conversion and validation need headroom.

Source changes for [#91](https://github.com/NuaForma/NuaForma/issues/91) allow an operator to set, for example, `NUAFORMA_PDF_PARALLELISM=2` while keeping HTTP concurrency independently bounded. The same entry point/configuration applies to the separate Playground. Do not add this setting to JSON requests or treat the upper bound as a recommended worker count. Start at one, measure CPU/memory/PID/tmpfs usage and queue/response latency, and review aggregate budgets before increasing parallelism. No host limit, sandbox permission, output validation or PDF/A check is relaxed automatically.

Successful runtime probes are deduplicated for at most 30 seconds per provider instance and invalidated by launcher identity changes; cold/expired verification waits within the caller's deadline. Occupied process slots are not a missing backend. These improvements are available from 0.2.0-alpha.2; the [short-trial record](../../docs/performance-snapshot.md) retains its own private-source baseline, not a new release benchmark. Request-private font checks and PDFBox remain per generation; veraPDF additionally runs whenever the captured conformance policy is enabled. No Playground UI control is added because this is trusted startup configuration, not an end-user generation option.

All HTTP spools, conversion files, profiles and font caches are private per request beneath the configured temporary root. Keep this storage ephemeral and quota-limited. No document persistence volume is needed. If changing the root, create/mount an appropriately sized tmpfs accessible to UID/GID 1000 and keep `java.io.tmpdir` and HTTP/conversion storage aligned. Normal completion, error and cancellation clean request files; forced termination relies on tmpfs destruction, not a cleanup guarantee. Never mount a shared customer-document directory as writable temp storage.

## DOCX, PDF and fonts

Release 0.2.0-alpha.2 captures `NUAFORMA_PDFA_VALIDATION` once in the common server/Playground main before provider discovery. Only explicit exact `false` skips the per-document veraPDF stage and prints a conspicuous startup warning, including production. **PDF/A conformance is not checked by NuaForma** in this mode; the selected export profile still applies, and the operator owns downstream conformance validation. PDFBox structure/active-content/font checks, other font checks, profile/version/transparency restrictions, sandbox readiness, network denial, private files/JVMs, budgets, cancellation, cleanup and integrity checks remain mandatory. All runtime components/licenses/source/SBOM remain bundled. Enabled failures never fall back. Standard PDF/DOCX are unchanged; there is no JSON override. Capabilities expose the provider-owned effective policy independently of generation availability. Earlier alpha.1 images do not gain this setting.

Source Compose defaults `NUAFORMA_PDFA_VALIDATION` to `true`; its environment interpolation preserves an explicitly empty value so invalid configuration still fails. Operators deliberately choosing downstream validation can set exact `false`. The [source Helm chart](../helm/README.md) has the same default-on policy; neither example authorizes deployment or changes published download kits. The existing default-on release qualification remains mandatory. Native endpoint qualification additionally exercises both policies for production and Playground, real isolated PDFBox negatives, genuine downstream veraPDF profile/final-digest verification, fonts, concurrent requests and deadline cleanup.

Use the [multipart API](../../nuaforma-server/README.md#multipart-and-resources) with a `request.json` and `template.docx`:

```sh
curl --fail-with-body http://127.0.0.1:8080/api/v1/documents \
  -F 'request=@request.json;type=application/json' \
  -F 'template=@template.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document' \
  --output document.docx
```

For DOCX, request `{"input":{"format":"docx"},"output":{"format":"docx"},"providerId":"nuaforma-docx","data":{"name":"Ada"}}`. Both built-in providers write DOCX, so choose explicitly. For default PDF/A-3b, request `{"input":{"format":"docx"},"output":{"format":"pdf"},"data":{"name":"Ada"}}` and save the response as `document.pdf`. Set `output.options.profile` to `pdfa-1b`, `pdfa-2b`, `pdfa-3b` or `standard` to choose explicitly. Omit `version` to derive it from the profile; a mismatched explicit version fails. PDF/A-1b has a restricted transparency-free template profile; transparent watermarks need 2b/3b. By default, every PDF/A output undergoes independent explicit-profile validation before delivery, with no downgrade; the explicit source-only operator opt-out above delegates conformance downstream. Level-A profiles, PDF/UA, forms, attachments and signature fields remain unsupported. The accepted approximately 0.60 mm modern inline-text-box difference remains [issue #32](https://github.com/NuaForma/NuaForma/issues/32), not a general tolerance.

DOCX preserves template font choices without requiring server fonts. PDF needs fonts available in the private conversion environment; permitted bundled fonts retain their upstream notices. Supply custom fonts as `resource.<id>` multipart files or use operator startup snapshots from the [mount guide](MOUNTS.md). Select the [PDF font options](../../nuaforma-docx-pdf/README.md), including strict validation when needed. Only selected resources reach the renderer. Technical embedding permissions remain enforced without ownership declarations or global installation. Capabilities enumerate deployment fonts, not private uploads.

## Cancellation and shutdown

SIGTERM goes through Docker's init to the JVM. The server's shutdown hook stops admission, permits a short grace period and then cancels handlers; cancellation reaches the provider's bounded process handling. Compose allows 15 seconds before forced termination. SIGKILL cannot guarantee application cleanup; destroying the container's tmpfs removes remaining private files. A client disconnect alone may not immediately interrupt CPU-bound generation; the independent server/engine deadlines remain necessary. Preserve init/reaping and bounded process limits even for DOCX-only requests in this image.

## Qualification and release preparation

The source Java 25 migration adds `:nuaforma-server:prepareMountedProviderQualification` and `tools/container/qualify_java_runtime.py`. The latter checks the native platform, exact Java 25 server/Java 17 validator versions, live PDF readiness, an independently compiled Java 17 provider, two concurrent tenant references and a mounted open font's catalog/DOCX embedding/PDF-A conversion. Supply private generated extension/font directories through `--extensions` and `--fonts`; native CI uses only the already checked-in Libre Barcode 39 fixture. It adds no image-processing test dependency or production font. It complements, not replaces, the service, sandbox, independent layout/barcode and failure/cleanup qualification below. See [the split runtime decision](../../docs/java-runtime-compatibility.md).

`python3 tools/container/qualify_service.py --image nuaforma:local --output build/container-qualification` exercises the actual shipped entry point through published loopback HTTP, non-root/read-only execution, DOCX/PDF generation, per-request cleanup, SIGTERM during actual LibreOffice conversion, an absent converter and handler-deadline cancellation. It creates randomly named, labelled containers and removes only those containers. The output must be a fresh directory. Optional `--fixtures <docx-build-directory> --fonts <private-QA-font-directory>` adds two concurrent barcode tenants and a five-page dynamic-watermark fixture; these private fonts remain request inputs and must not be packaged or uploaded. Independently rasterize, inspect embedded fonts and decode barcodes as described in the [runtime guide](README.md#reproducible-qualification).

`python3 tools/container/collect_inventory.py --image nuaforma:local --destination build/container-inventory` exports image identity and exact OS/JAR inventories without starting a service. The manually invoked [release preparation workflow](../../.github/workflows/container-release.yml) runs the basic qualification on `main` and retains only review metadata, not executable images or private fonts. The [release procedure](../../docs/container-releases.md) records GHCR/versioning, source-access and approval gates. New binary publication requires the candidate-specific distribution audit and explicit authorization; published alpha images are not replaced by source builds.
