# On-premise service container

The `service` source target packages the Java 25 REST application, separately pinned Java 17 PDF validators and LibreOffice in one native Linux AMD64/ARM64 container. It needs no Office desktop, cloud account, worker container or Docker socket. Runtime generation does not fetch dependencies, templates or fonts; there is no application telemetry. Building requires access to the pinned artifacts. Published containers through `0.1.0-alpha.3` remain unchanged Java 17 releases; a local build does not publish a new image. See [runtime compatibility](../../docs/java-runtime-compatibility.md).

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

Compose binds the host port to loopback, runs UID/GID 1000, includes Docker's child-reaping init, a read-only root, no capabilities, no-new-privileges and the reviewed default-deny seccomp profile. It limits CPU to 2, memory to 2 GiB, processes to 128 and `/tmp` to a 512 MiB tmpfs. A bridge network permits host access to the published port; it does not deny outer-JVM outbound traffic. The application performs no implicit network fetching, and each renderer/validator has its own network-denying sandbox. Add deployment-level egress controls when needed. An internal-only network behind a gateway container is an alternative, but Docker Desktop does not publish its ports to the host. Do not replace isolation with privileged mode, unconfined seccomp, host capabilities or mounted Docker sockets. Sandbox incompatibility leaves PDF unavailable, with no unsandboxed fallback. Linux/amd64 on Docker Desktop is the qualified platform; other kernels/security policies/architectures require independent qualification. Do not disable host protections to make a deployment start.

The service has no built-in authentication or TLS. Keep it behind an authenticated TLS gateway for remote callers, with connection/header quotas. A successful HTTP response from capabilities proves HTTP availability, not PDF readiness: check the PDF provider's usable write formats and readiness diagnostics, or assess the intended request. DOCX remains usable when the converter is absent or isolation cannot start.

Source builds include the [static SBOM endpoint](../../docs/sbom.md), [health probes](../../docs/health.md) and maintained source chart under `deployment/helm/nuaforma`. Published alpha kits remain unchanged.

## Configuration and private storage

Operator environment variables, never request fields:

| Variable | Default | Purpose |
| --- | --- | --- |
| `NUAFORMA_HOST` | `0.0.0.0` | Container listener; host publication stays loopback in Compose |
| `NUAFORMA_MODE` | `production` when unset | Source-only explicit `development` enables sensitive diagnostics/banner; empty/unknown values fail startup. See [logging](../../docs/logging.md); published alpha images are unchanged |
| `NUAFORMA_PORT` | `8080` | Listener port, 0–65535; 0 selects an ephemeral port, unsuitable for the fixed container mapping |
| `NUAFORMA_HEAP_MB` | `512` | JVM maximum heap, 128–16384 MiB; still bounded by container memory |
| `NUAFORMA_TEMP_DIRECTORY` | `/tmp` | Absolute existing writable private-storage root |
| `NUAFORMA_PDF_LAUNCHER` | `/opt/nuaforma/bin/render-pdf` | Trusted isolated launcher; a missing path disables PDF only |
| `NUAFORMA_FONT_DIRECTORY` | `/opt/nuaforma/custom-fonts` | Absolute operator font mount target; startup snapshot, not a request option |
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

All HTTP spools, conversion files, profiles and font caches are private per request beneath the configured temporary root. Keep this storage ephemeral and quota-limited. No document persistence volume is needed. If changing the root, create/mount an appropriately sized tmpfs accessible to UID/GID 1000 and keep `java.io.tmpdir` and HTTP/conversion storage aligned. Normal completion, error and cancellation clean request files; forced termination relies on tmpfs destruction, not a cleanup guarantee. Never mount a shared customer-document directory as writable temp storage.

## DOCX, PDF and fonts

Use the [multipart API](../../nuaforma-server/README.md#multipart-and-resources) with a `request.json` and `template.docx`:

```sh
curl --fail-with-body http://127.0.0.1:8080/api/v1/documents \
  -F 'request=@request.json;type=application/json' \
  -F 'template=@template.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document' \
  --output document.docx
```

For DOCX, request `{"input":{"format":"docx"},"output":{"format":"docx"},"providerId":"nuaforma-docx","data":{"name":"Ada"}}`. Both built-in providers write DOCX, so choose explicitly. For default PDF/A-3b, request `{"input":{"format":"docx"},"output":{"format":"pdf"},"data":{"name":"Ada"}}` and save the response as `document.pdf`. Set `output.options.profile` to `pdfa-1b`, `pdfa-2b`, `pdfa-3b` or `standard` to choose explicitly. Omit `version` to derive it from the profile; a mismatched explicit version fails. PDF/A-1b has a restricted transparency-free template profile; transparent watermarks need 2b/3b. Every PDF/A output undergoes independent explicit-profile validation before delivery, with no downgrade. Level-A profiles, PDF/UA, forms, attachments and signature fields remain unsupported. The accepted approximately 0.60 mm modern inline-text-box difference remains [issue #32](https://github.com/NuaForma/NuaForma/issues/32), not a general tolerance.

DOCX preserves template font choices without requiring server fonts. PDF needs fonts available in the private conversion environment; permitted bundled fonts retain their upstream notices. Supply custom fonts as `resource.<id>` multipart files or use operator startup snapshots from the [mount guide](MOUNTS.md). Select the [PDF font options](../../nuaforma-docx-pdf/README.md), including strict validation when needed. Only selected resources reach the renderer. Technical embedding permissions remain enforced without ownership declarations or global installation. Capabilities enumerate deployment fonts, not private uploads.

## Cancellation and shutdown

SIGTERM goes through Docker's init to the JVM. The server's shutdown hook stops admission, permits a short grace period and then cancels handlers; cancellation reaches the provider's bounded process handling. Compose allows 15 seconds before forced termination. SIGKILL cannot guarantee application cleanup; destroying the container's tmpfs removes remaining private files. A client disconnect alone may not immediately interrupt CPU-bound generation; the independent server/engine deadlines remain necessary. Preserve init/reaping and bounded process limits even for DOCX-only requests in this image.

## Qualification and release preparation

The source Java 25 migration adds `:nuaforma-server:prepareMountedProviderQualification` and `tools/container/qualify_java_runtime.py`. The latter checks the native platform, exact Java 25 server/Java 17 validator versions, live PDF readiness, an independently compiled Java 17 provider, two concurrent tenant references and a mounted open font's catalog/DOCX embedding/PDF-A conversion. Supply private generated extension/font directories through `--extensions` and `--fonts`; native CI uses only the already checked-in Libre Barcode 39 fixture. It adds no image-processing test dependency or production font. It complements, not replaces, the service, sandbox, independent layout/barcode and failure/cleanup qualification below. See [the split runtime decision](../../docs/java-runtime-compatibility.md).

`python3 tools/container/qualify_service.py --image nuaforma:local --output build/container-qualification` exercises the actual shipped entry point through published loopback HTTP, non-root/read-only execution, DOCX/PDF generation, per-request cleanup, SIGTERM during actual LibreOffice conversion, an absent converter and handler-deadline cancellation. It creates randomly named, labelled containers and removes only those containers. The output must be a fresh directory. Optional `--fixtures <docx-build-directory> --fonts <private-QA-font-directory>` adds two concurrent barcode tenants and a five-page dynamic-watermark fixture; these private fonts remain request inputs and must not be packaged or uploaded. Independently rasterize, inspect embedded fonts and decode barcodes as described in the [runtime guide](README.md#reproducible-qualification).

`python3 tools/container/collect_inventory.py --image nuaforma:local --destination build/container-inventory` exports image identity and exact OS/JAR inventories without starting a service. The manually invoked [release preparation workflow](../../.github/workflows/container-release.yml) runs the basic qualification on `main` and retains only review metadata, not executable images or private fonts. The [release procedure](../../docs/container-releases.md) records GHCR/versioning, source-access and approval gates. Actual binary publication remains disabled until the candidate-specific distribution audit is complete.
