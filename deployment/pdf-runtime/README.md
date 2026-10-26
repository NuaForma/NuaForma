# Native PDF service container

The source image packages NuaForma's Java 25 application and bounded Java 17 PDF validators. DOCX layout and PDF writing execute in the application JVM. LibreOffice and Bubblewrap are not installed. See the [native input profile](../../nuaforma-docx-pdf/README.md), [service guide](SERVICE.md) and [container policy](APPARMOR.md).

## Build and run

Use JDK 17 and JDK 25 with the repository wrapper, then Docker with BuildKit:

```sh
./gradlew build :nuaforma-server:installDist :nuaforma-docx-pdf:preparePdfValidation :nuaforma-docx-pdf:prepareVeraPdfValidation
python3 tools/container/prepare_verapdf_materials.py nuaforma-docx-pdf/build/verapdf-validation
python3 tools/container/prepare_application_materials.py
docker compose -f deployment/pdf-runtime/compose.yaml up --build
```

Compose uses the engine's default seccomp and AppArmor policies. There is no `seccomp.json` to install, no named AppArmor profile to load and no application requirement for nested user namespaces. Helm explicitly selects `RuntimeDefault`. Keep non-root UID/GID 1000, a read-only root, dropped capabilities, no-new-privileges, init/child reaping, 128 PIDs, 2 GiB memory, two CPUs and a 512 MiB writable `/tmp` tmpfs. These are the qualification settings; do not use privileged or unconfined execution.

The `qualification` target invokes the validator; `service` runs REST, and `playground` remains a separate development target. Named build contexts accept only staged validator distributions and Gradle's application `installDist`, never the checkout, private fonts, local instructions or credentials. Startup verifies application, JVM and validator SHA-256 inventories. Embedded consumers may configure `NativePdfConfiguration` and `PdfProcessValidator` directly.

## Validation boundary

Each validation gets a separate Java 17 process, private working directory, cleared inherited environment, fixed validator classpath, bounded output, deadlines and cancellation/reaping. The launcher preserves SerialGC, heap/direct/metaspace/class/code-cache/stack bounds, two active processors and Linux CPU/address-space/file-size limits. Mandatory structural/font/active-content checks and exact-profile/final-digest PDF/A checks remain unchanged.

Server and validators share the ordinary container filesystem, device and network boundary. There is no per-document filesystem or network namespace. Operator JARs and credentials are not added to the validator environment/classpath. The image label `dev.nuaforma.validation-boundary=container` identifies this contract; use matching source tooling for older images. Independent processes preserve validator state separation; the container is the OS trust boundary.

The default-on `NUAFORMA_PDFA_VALIDATION` policy accepts only exact `true` or `false`. Explicit `false` skips per-document veraPDF only and delegates conformance checking to the operator; it does not disable PDFBox, startup identity/readiness, process bounds or cleanup. Both validators and their materials remain shipped. See [configuration](SERVICE.md#configuration-and-private-storage).

## Components and corresponding source

NuaForma's code is Apache-2.0; the combined image retains the individual terms of Debian packages/fonts, both Temurin runtimes and application/validator JARs. veraPDF keeps its original MPL/GPL notices, matching sources and CC-BY-4.0 profile attribution. `NOTICE`, `SOURCES.md`, `/usr/share/doc`, `/usr/share/common-licenses`, Java `legal` directories and staged JAR materials remain extractable without starting the service.

`outer-packages.tsv` and the application/validator/JVM checksum inventories describe the actual runtime. The generated CycloneDX inventory contains no removed renderer, Bubblewrap or custom BPF component. Source preparation produces Debian, Java and JVM archives for the components actually shipped. Run the [exact-candidate distribution review](../../docs/container-releases.md) before any binary publication; a passing build is not publication approval. Existing released artifacts remain immutable.

## Qualification

```sh
python3 tools/container/qualify_service.py --image nuaforma:local --output build/native-service-qualification
python3 tools/container/collect_inventory.py --image nuaforma:local --destination build/native-runtime-inventory
```

Native AMD64/ARM64 checks use default runtime policies, verify an active seccomp filter/no-new-privileges/zero capabilities, confirm removed components are absent, and exercise real standard/PDF-A generation, both conformance policies, invalid PDFs, fonts, trusted extension mounts, concurrency, cancellation/deadline cleanup, shutdown and the shipped SBOM. The [Word proofs](../../tools/pdf-evaluation/own-engine/README.md) and [protected engine goldens](../../nuaforma-pdf/src/test/resources/visual-goldens/README.md) remain separate fidelity evidence. These source changes require qualification and a separately authorized release before being advertised as published behavior.
