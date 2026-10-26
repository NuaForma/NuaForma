<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="assets/branding/nuaforma-dark-transparent.png">
    <source media="(prefers-color-scheme: light)" srcset="assets/branding/nuaforma-light.png">
    <img src="assets/branding/nuaforma-light.png" alt="NuaForma — rounded teal and navy N logo" width="320">
  </picture>
</p>

# NuaForma

[![Maven Central](https://img.shields.io/maven-central/v/dev.nuaforma/nuaforma-core?strategy=highestVersion&label=Maven%20Central&color=008b90)](https://central.sonatype.com/artifact/dev.nuaforma/nuaforma-core)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=NuaForma_NuaForma&metric=alert_status&branch=develop)](https://sonarcloud.io/dashboard?id=NuaForma_NuaForma&branch=develop)

**Turn templates and structured data into documents.**

NuaForma is an open-source document-generation engine. Combine a Word template, structured data and generation options to produce DOCX or PDF documents. Embed the Java-17-compatible libraries in your application, or run the REST service in your own infrastructure.

> **Native-engine development source:** this branch converts DOCX with NuaForma's own shared PDF engine. LibreOffice, Microsoft Word and a cloud conversion service are not runtime requirements. This native version is not yet published; build this checkout and use its source guides. APIs remain an evaluation draft.

## What it supports

- Preserve supported styles, branding, tables, headers/footers, text boxes and page geometry while filling templates.
- Use nested objects and lists, conditions, repetitions, null fallbacks and explicit number/date formatting.
- Produce DOCX, standard PDF 1.7 or PDF/A-1b, 2b and 3b. PDF defaults to PDF/A-3b; independent conformance validation is enabled by default.
- Supply exact fonts with permission-checked subset/full embedding. DOCX supports requested text watermarks; native PDF watermarks remain outside the qualified profile and reject explicitly.
- Render PDF in the hosting JVM with request-local state and bounded admission. Independent PDFBox/veraPDF checks run in a separate bounded Java 17 process.
- Deploy the production service or the separate development Playground on native Linux AMD64/ARM64.

These are qualified, bounded profiles, not universal Word compatibility. Unsupported source constructs reject explicitly. PDF/A-1b rejects transparency. See the [PDF profile](nuaforma-docx-pdf/README.md) and [roadmap](docs/architecture/own-engine-roadmap.md) for support and remaining limits.

## Get started

### Run the source container

Follow the [service build and deployment guide](deployment/pdf-runtime/SERVICE.md) to prepare the application, validator runtime and container. The image includes the native engine, selected fonts and independent validators. It contains no LibreOffice and performs no dependency downloads during generation.

The [REST guide](nuaforma-server/README.md) describes uploads, options and responses. The [Playground](nuaforma-playground/README.md) is a separate development application: use synthetic data because development logging can include document contents and field values. Keep remotely accessible services behind an authenticated TLS gateway.

### Embed the engine

Use `dev.nuaforma:nuaforma-core` with `nuaforma-docx` for Word generation. Add `nuaforma-docx-pdf` for DOCX-to-PDF; it reuses `nuaforma-docx` and the shared `nuaforma-pdf` artifact. Configure caller fonts and the independent validator through [NativePdfConfiguration](nuaforma-docx-pdf/README.md#embedded-configuration). Embedded generation needs no server, Docker or Office installation.

The [Java/Maven and Kotlin/Gradle examples](examples/embedded/README.md) consume locally staged libraries independently of the source build. [nuaforma.dev](https://nuaforma.dev) is the project website; use this checkout's guides for the native source profile.

### Write a template

Keep the desired styling in Word and insert markers such as:

```text
Dear {trim(customer.name)},
Contact: {customer.email ?? "Not provided"}
You {#if active}have an active agreement{#else}do not have an active agreement{/if}.
{#each items as item}{item.name}: {item.amount | number("0.00")}{/each}
```

Supply the corresponding `data` object. Inserted values remain literal, not executable template code. See the [language specification](nuaforma-template/README.md) and [Word template rules](nuaforma-docx/README.md#word-template-rules).

## Documentation and development

- [Documentation index](docs/README.md) and [development guide](docs/development.md).
- [Shared PDF engine](nuaforma-pdf/README.md), [conversion design](docs/architecture/conversion-engine.md) and [Word comparisons](tools/pdf-evaluation/own-engine/README.md).
- [Engine golden files](nuaforma-pdf/src/test/resources/visual-goldens/README.md): reviewed references protected by automatic image-comparison tests.
- [Extension guide](docs/extensions.md), [contribution guide](CONTRIBUTING.md) and [open issues](https://github.com/NuaForma/NuaForma/issues?q=is%3Aissue%20is%3Aopen).

## License

NuaForma's code is [Apache-2.0](LICENSE). Dependencies, fonts and bundled runtime components retain their own licenses; the combined container is not wholly Apache-2.0. See [third-party notices](THIRD_PARTY.md) and the [runtime distribution checklist](docs/runtime-distribution.md).

Copyright 2026 NuaForma contributors.
