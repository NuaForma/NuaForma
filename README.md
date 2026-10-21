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

NuaForma is an open-source document-generation engine. Combine a Word template, your own structured data and generation options to produce DOCX or PDF documents, without a proprietary platform or mandatory cloud service. Embed the Java-17-compatible libraries in your application, or run the REST service in your own infrastructure.

Visit [nuaforma.dev](https://nuaforma.dev) for deployment examples and downloadable test kits, or browse the [documentation](docs/README.md).

> **Evaluation alpha:** libraries and containers use `0.2.0-alpha.1`, with contract `0.2`. Production and separate development Playground images support native AMD64 and ARM64. APIs are not stable; see [version status](docs/README.md#source-versus-published-versions). There is no `latest` prerelease tag or hosted generation service.

## Why NuaForma?

- **Keep your Word layout:** replace placeholders and expand paragraphs/table rows while preserving supported formatting, headers, footers, images and text boxes.
- **Use your own data:** nested objects, lists and null values, with inline conditions, nested repetitions and explicit number/date formatting.
- **Choose the output:** DOCX, standard PDF or independently validated PDF/A-1b, PDF/A-2b and PDF/A-3b. PDF defaults to PDF/A-3b.
- **Control fonts and watermarks:** retain template fonts, supply fonts when needed and resolve dynamic text watermarks against the same data.
- **Run where you need it:** embedded Kotlin/Java, REST or an on-premise container, with no mandatory cloud account.
- **Respect security boundaries:** a restricted template language, bounded processing and isolated, network-denied PDF renderer/validator processes. Installed extensions remain trusted executable code, not sandboxed templates.

These are bounded supported profiles, not a promise to reproduce every Word feature. PDF/A-1b rejects transparency; the known text-box positioning limitation is documented in the [PDF guide](nuaforma-docx-pdf/README.md). Keep remotely accessible services behind an authenticated TLS gateway.

Unreleased issue #93 source adds a default-on [operator conformance policy](nuaforma-docx-pdf/README.md#operator-conformance-policy-unreleased-issue-93-source). Only exact `NUAFORMA_PDFA_VALIDATION=false` delegates PDF/A conformance checking downstream; PDFBox and isolation remain mandatory. This is not available in published alpha artifacts. See the [indicative short processing trials](docs/performance-snapshot.md) for the original measurements and their limitations.

## Get started

### Run the container

Download the complete [AMD64](https://nuaforma.dev/downloads/docker-kit.zip) or [ARM64](https://nuaforma.dev/downloads/docker-arm64-kit.zip) deployment kit. Extract it, keep its reviewed `seccomp.json` alongside `compose.yaml`, and follow the included instructions. No custom image build is required. See the [website](https://nuaforma.dev) and [Podman/Apple Silicon notes](deployment/pdf-runtime/ARM64.md#podman-runtime) when applicable; do not disable isolation to work around startup failures.

Use the site's Word/JSON test kit and Bruno/Postman collections to try generation against your own instance. The [REST guide](nuaforma-server/README.md) describes multipart uploads, options and responses.

For the separate [development Playground](nuaforma-playground/README.md), use its [AMD64](https://nuaforma.dev/downloads/docker-playground-kit.zip) or [ARM64](https://nuaforma.dev/downloads/docker-playground-arm64-kit.zip) kit. It enables development logging and may log document content and field values: use synthetic data and your own access controls. The production image does not include the UI.

### Embed the engine

Use `dev.nuaforma:nuaforma-core` plus `dev.nuaforma:nuaforma-docx` for Word generation; add `nuaforma-docx-pdf` and the qualified external runtime for PDF. Published coordinates use `0.2.0-alpha.1`. DOCX-only applications do not need Docker, LibreOffice or the server. Start with the [Java/Maven and Kotlin/Gradle examples](examples/embedded/README.md).

### Write a template

Keep the desired styling in Word and insert markers such as:

```text
Dear {trim(customer.name)},
Contact: {customer.email ?? "Not provided"}
You {#if active}have an active agreement{#else}do not have an active agreement{/if}.
{#each items as item}{item.name}: {item.amount | number("0.00")}{/each}
```

Supply the corresponding `data` object in the generation request. Conditions require actual booleans; null comparisons and explicit fallbacks are supported. Inserted values remain literal, not executable template code. See the [language specification](nuaforma-template/README.md) and [Word template rules](nuaforma-docx/README.md#word-template-rules).

## Documentation and development

- [Documentation index](docs/README.md): templates, API, deployment, fonts, security and known limitations.
- [Development guide](docs/development.md): build from source, tests and module overview.
- [Extension guide](docs/extensions.md): custom providers, value pipes, content pipes and installation.
- [Contributing](CONTRIBUTING.md): issues, changes, signed commits and pull requests.
- [Roadmap](https://github.com/NuaForma/NuaForma/issues?q=is%3Aissue%20is%3Aopen): planned formats and capabilities; open issues are not implemented support.

## License

NuaForma's code is [Apache-2.0](LICENSE): commercial/internal use, modification and proprietary integration are permitted under its terms. Dependencies, fonts and bundled runtime components retain their own licenses; the combined container is not wholly Apache-2.0. See [third-party notices](THIRD_PARTY.md) and the [runtime distribution checklist](docs/libreoffice-licensing.md).

Copyright 2026 NuaForma contributors.
