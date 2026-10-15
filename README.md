<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/branding/nuaforma-dark-transparent.png">
    <source media="(prefers-color-scheme: light)" srcset="assets/branding/nuaforma-light.png">
    <img src="assets/branding/nuaforma-light.png" alt="NuaForma — rounded teal and navy N logo" width="320">
  </picture>
</p>

# NuaForma

[![Maven Central](https://img.shields.io/maven-central/v/dev.nuaforma/nuaforma-core?strategy=highestVersion&label=Maven%20Central&color=008b90)](https://central.sonatype.com/artifact/dev.nuaforma/nuaforma-core)

**Turn templates and structured data into documents.**

NuaForma is an open-source Kotlin/JVM document-generation engine for Java 17 applications, with a REST service and a self-hostable Docker container. Supply a Word template, your own structured data and generation options to produce DOCX or PDF documents without a proprietary platform or mandatory cloud service.

Visit [nuaforma.dev](https://nuaforma.dev) for deployment examples, endpoint documentation and a downloadable Word/JSON example kit you can run against your own installation.

> **Evaluation alpha:** libraries are available as `0.1.0-alpha.1` on Maven Central; the container is `ghcr.io/nuaforma/nuaforma:0.1.0-alpha.3`, with contract baseline `0.1`. APIs are not stable; review the [library release](https://github.com/NuaForma/NuaForma/releases/tag/v0.1.0-alpha.1) and [container release](https://github.com/NuaForma/NuaForma/releases/tag/v0.1.0-alpha.3), including accepted native-runtime security findings. There is no hosted document-generation service and no `latest` container tag.

## What works today

- **DOCX → DOCX:** replace placeholders while retaining native Word formatting, including markers split across text runs, headers, footers and supported VML/DrawingML text boxes.
- **Conditions and repetitions:** inline conditions within sentences, conditional paragraphs/sections, nested list expansion and repeated table rows.
- **Generic data:** nested JSON-compatible objects, lists and scalar values; no NuaForma-specific business schema.
- **Template functions:** null/missing fallbacks, comparisons, number/date formatting, `contains`, `startsWith`, `endsWith`, `trim` and `equalsIgnoreCase`.
- **Fonts and barcodes:** preserve template font choices, optionally validate/embed supplied fonts, and retain font-based barcode text and geometry in the qualified profiles.
- **Dynamic text watermarks:** resolve watermark placeholders and conditions against the same data, with configurable color, opacity and rotation.
- **DOCX → PDF:** standard PDF or independently validated PDF/A-1b, PDF/A-2b and PDF/A-3b, using the isolated LibreOffice runtime. DOCX output is not changed by PDF compatibility processing.
- **Embedded and REST use:** the same generation contracts from Kotlin/Java or multipart HTTP, with capabilities, preflight assessment and downloadable OpenAPI JSON/YAML.
- **On-premise container:** native Linux/amd64 and Linux/arm64 variants of one service image, with read-only mounts for operator fonts and trusted provider extensions; capabilities list deployment-available fonts.

These capabilities have bounded supported profiles, not a promise to preserve every Word feature or reproduce every renderer's pagination. See the module guides for exact rules.

## A template and a request

Author these markers in the Word template; the template owns the surrounding layout and styling:

```text
Dear {trim(customer.name)},
Your reference is {reference}.
You {#if active}have an active agreement{#else}do not have an active agreement{/if}.
Contact: {customer.email ?? "Not provided"}
```

For DOCX output, save the following as `request.json`:

```json
{
  "input": {"format": "docx"},
  "output": {"format": "docx"},
  "providerId": "nuaforma-docx",
  "data": {
    "customer": {"name": " Ada ", "email": null},
    "reference": "NF001",
    "active": true
  },
  "options": {"watermark": {"text": "CONCEPT — {reference}"}}
}
```

With the local service running and the template saved as `template.docx`:

```sh
curl --fail-with-body http://127.0.0.1:8080/api/v1/documents \
  -F 'request=@request.json;type=application/json' \
  -F 'template=@template.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document' \
  --output document.docx
```

Conditions require booleans, not implicit truthiness. Null checks such as `(customer.email ?? null) == null` produce a boolean and are supported. Inserted values remain literal, never executable template code. For complete syntax and examples, see the [template language](nuaforma-template/README.md) and [Word structural rules](nuaforma-docx/README.md#word-template-rules). For uploads, font resources, errors and limits, see the [REST guide](nuaforma-server/README.md).

## Choose how to use NuaForma

### Embed in a Java or Kotlin application

The repository contains five independently publishable libraries, an optional server and a separate development Playground application:

| Module | Responsibility |
| --- | --- |
| [nuaforma-contracts](nuaforma-contracts/README.md) | Public value types, provider interfaces and compatibility metadata |
| [nuaforma-core](nuaforma-core/README.md) | Provider discovery, selection and generation orchestration |
| [nuaforma-template](nuaforma-template/README.md) | Restricted, format-independent template language |
| [nuaforma-docx](nuaforma-docx/README.md) | DOCX generation, font handling and watermarks |
| [nuaforma-docx-pdf](nuaforma-docx-pdf/README.md) | DOCX output plus optional isolated PDF conversion/validation |
| [nuaforma-server](nuaforma-server/README.md) | Thin REST transport over the same engine |
| [nuaforma-playground](nuaforma-playground/README.md) | Separate development-only request builder, absent from the production server/image |

Use core plus DOCX for Word-only applications; add the DOCX/PDF module and its qualified external runtime for PDF. Core depends on contracts, not concrete providers. Both built-in providers can write DOCX, so select a provider explicitly when both are installed. Custom providers can introduce their own format identifiers through ServiceLoader or explicit registration.

DOCX-only applications need neither the server, Docker nor LibreOffice. PDF's isolated converter/validator runtime is not supplied by Maven dependencies alone. Libraries use Maven Central coordinates `dev.nuaforma:<module>:0.1.0-alpha.1`. The [standalone Java/Maven and Kotlin/Gradle examples](examples/embedded/README.md) also show local artifact staging and usage.

### Run the REST service or container

For a local JVM service, follow the [server launch guide](nuaforma-server/README.md#run-locally). For the combined service/LibreOffice container, follow the [Docker Compose build and start guide](deployment/pdf-runtime/SERVICE.md#build-and-start). The container includes the approved renderer isolation; no separate worker container or Microsoft Office installation is required.

The service exposes `/api/v1/documents`, `/api/v1/assess`, `/api/v1/capabilities`, `/api/v1/version`, `/openapi.json` and `/openapi.yaml`. The version endpoint returns packaged build version/source identity and the runtime contract version without checking converters; source archives report a null commit. The [mount guide](deployment/pdf-runtime/MOUNTS.md) describes custom fonts, trusted extensions and restart-based reload. The container is published as `ghcr.io/nuaforma/nuaforma:0.1.0-alpha.3`, with native Linux AMD64 and ARM64 variants and no `latest` alias. Download the complete [AMD64](https://nuaforma.dev/downloads/docker-kit.zip) or [ARM64](https://nuaforma.dev/downloads/docker-arm64-kit.zip) deployment kit, including the required seccomp profile.

### Source endpoints and Playground

Source builds additionally provide lightweight [liveness/readiness probes](docs/health.md), a static [CycloneDX SBOM download](docs/sbom.md) at `/api/v1/sbom` and a separately packaged [development Playground](nuaforma-playground/README.md). The Playground uses live capabilities/OpenAPI to prepare/export requests, select/upload fonts and generate documents through the same API. It requires explicit development mode, warns about sensitive logging and is not bundled with the production image. Published alpha artifacts and their deployment downloads remain unchanged; these source additions do not publish new binaries.

## PDF choices and important limits

For PDF, set `output.format` to `pdf` and omit the DOCX provider preference. Select `output.options.profile`:

| Profile | PDF version | Scope |
| --- | --- | --- |
| `pdfa-3b` | 1.7 | Default when no profile is specified |
| `pdfa-2b` | 1.7 | Archival B-level output |
| `pdfa-1b` | 1.4 | Restricted transparency-free template profile |
| `standard` | 1.7 | Ordinary static PDF, not PDF/A |

Omit `version` to derive it from the profile; an explicit version must match. Each PDF/A result must pass the selected veraPDF profile before delivery, without downgrade. PDF/A-1b rejects transparency and unsupported drawing/media features instead of flattening them and accepting altered appearance. PDF/A level A, PDF/UA, forms, digital-signature fields, attachments and encryption are not implemented.

PDF requires explicit ASCII/hAnsi document-default font names and available fonts. Fonts can come from the qualified bundled catalog, operator mounts, supplied request resources or template embedding. Technical embedding permissions are enforced; callers remain responsible for usage rights. Barcode encoding, quiet zones and scanability are separate from text replacement and must be checked for the intended template/font combination.

Container releases include native Linux/amd64 and Linux/arm64 variants; each host must still pass PDF isolation readiness. The source Java 25 service requires renewed native qualification, not reliance on a previous image's checks. A measured modern inline-text-box vertical difference of approximately 0.60 mm remains temporarily accepted in [issue #32](https://github.com/NuaForma/NuaForma/issues/32), with a result warning. It is not a general layout tolerance. See the [PDF options and fidelity profile](nuaforma-docx-pdf/README.md) before choosing templates or deployment platforms.

## Security and deployment boundaries

The source branch adds unreleased correlated operational logging and explicit `NUAFORMA_MODE=development` diagnostics. Production remains the default; API errors stay safe, while the warning makes sensitive field/pipe logging explicit. See the [logging guide](docs/logging.md) before enabling it. Published alpha images are unchanged.

The restricted language exposes only supplied `data`, not host objects, credentials or arbitrary methods. Providers enforce archive, expression and output limits. Conversion and PDF validation use bounded request-isolated child processes with network denial. These protections do not make in-process DOCX parsing a complete process/heap sandbox.

The service has no built-in authentication or TLS. Keep local access on loopback; use an authenticated TLS gateway and deployment quotas for remote access. Installed extensions are operator-trusted executable code with application-process permissions, not sandboxed templates. Preserve the isolation and storage settings in the deployment guides.

## Build and verify

Install JDK 17, JDK 25 and Python 3, then use the checked-in Gradle Wrapper. The five libraries compile and test on Java 17; the standalone server compiles and tests on Java 25. The wrapper can run on either JDK. If automatic toolchain discovery does not find both installations, set `JDK17` and `JDK25` to their installation directories. No global Gradle installation is required; the first build downloads pinned build tools and dependencies. On Windows the corresponding launcher is `gradlew.bat`.

This source branch requires Java 25 for the standalone server. Previously published `0.1.0-alpha.3` containers remain immutable and use Java 17; this change does not publish a new image or raise the Java 17 minimum of embedded libraries. The new service image deliberately retains separate Java 17 child runtimes for bounded PDF validation; see the [runtime compatibility decision](docs/java-runtime-compatibility.md).

```sh
./gradlew build
./gradlew test
./gradlew :nuaforma-server:generateOpenApi
```

The full build includes architecture, dependency-boundary and local library-publication checks. JUnit Jupiter, MockK and ArchUnit are test-only. The OpenAPI export writes JSON/YAML under `nuaforma-server/build/generated/openapi` without starting a server.

Standalone library consumers additionally require Maven 3.9.6 or later:

```sh
./gradlew verifyEmbeddedConsumers
```

Source/documentation JARs and POMs are staged only to an ignored local repository. Documentation JARs contain the module guides and architecture document, not generated Kotlin API pages. Synthetic signing QA optionally requires GnuPG. Actual PDF rendering, visual review and barcode scans are separate runtime qualification steps, not implied by a unit-test pass. See [AGENTS.md](AGENTS.md) for detailed verification commands and [DEPENDENCIES.md](DEPENDENCIES.md) for dated dependency reviews and compatibility exceptions.

## Roadmap and publication

The source branch adds unreleased, chainable JSON value pipes installed through ServiceLoader, a separate generator-owned content-pipe SPI and a bounded Base64 PNG/JPEG `image` pipe for DOCX and PDF. See the [value-pipe guide](nuaforma-template/README.md#extensible-value-pipes-unreleased-source), [independent extension example](examples/value-pipes/README.md), [image guide](nuaforma-docx/README.md#image-content-pipe-unreleased-source) and [content-pipe grammar](nuaforma-template/README.md#generator-content-pipes-unreleased-source). Source builds expose both provider-owned pipe catalogs through capabilities (#60). Published alpha libraries and containers remain unchanged; these source features are not in the deployment kits yet.

Signed libraries remain at `0.1.0-alpha.1` on Maven Central; the container release is `0.1.0-alpha.3` on GHCR without a `latest` tag. Matching native-component source/license materials are attached to the [container release](https://github.com/NuaForma/NuaForma/releases/tag/v0.1.0-alpha.3). Preparation workflows remain non-publishing; releases must pass their separate protected publication gates. Accepted alpha native-runtime security findings are tracked in [issue #51](https://github.com/NuaForma/NuaForma/issues/51).

- [Maven Central release preparation](docs/maven-central-releases.md)
- [GHCR container release gates](docs/container-releases.md)
- [Scoped roadmap issues](https://github.com/NuaForma/NuaForma/issues?q=is%3Aissue%20label%3Aroadmap) and [milestones](https://github.com/NuaForma/NuaForma/milestones)

XLSX, ODT and ODS providers, stored-template identifiers and database adapters are future extensions, not current capabilities. ODT is a text-document format; ODS is a spreadsheet format. No attachment milestone is planned. [nuaforma.dev](https://nuaforma.dev/) provides documentation and downloadable deployment/test kits through GitHub Pages; it is not a container registry or a hosted generation service.

## Contributing

Feedback, synthetic example templates, use cases and technical proposals are welcome. Do not submit personal data, credentials or confidential documents. Read [the architecture](ARCHITECTURE.md) and [contributor/agent instructions](AGENTS.md) before making changes.

Changes to `main` require a pull request, verified signed commits and the required `Build (Java 17)` check. [CODEOWNERS](.github/CODEOWNERS) assigns `@mrhoeve`; review approval is optional while there is one human maintainer. The build workflow uses pinned actions and read-only permissions and performs no deployment or publication. These repository protections are configured in GitHub, not established merely by documentation.

## License

NuaForma's own code is licensed under [Apache-2.0](LICENSE). Commercial/internal use, modification and proprietary integration are permitted subject to its terms; private modifications need not be published. The license does not grant trademark rights.

Dependencies and fonts retain their own licenses. See [THIRD_PARTY.md](THIRD_PARTY.md) and the [combined-runtime distribution checklist](docs/libreoffice-licensing.md). A container containing LibreOffice, Bubblewrap, veraPDF and other components is not wholly Apache-2.0.

Copyright 2026 NuaForma contributors.
