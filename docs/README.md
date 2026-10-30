# NuaForma documentation

Choose a guide for the task you want to complete. Module guides are the technical reference; this index links to them rather than maintaining duplicate API descriptions.

## Use NuaForma

- [Project overview](../README.md) and [website/downloads](https://nuaforma.dev).
- [Template language](../nuaforma-template/README.md): placeholders, null/missing values, conditions, nested repetitions, formatting and pipes.
- [Word templates](../nuaforma-docx/README.md): paragraph/table rules, text boxes, images, fonts and watermarks.
- [Rich text](rich-text.md): explicit bounded RTF insertion, independent boundary trims, inline placement and font policies (source feature after alpha.4).
- [PDF output](../nuaforma-docx-pdf/README.md): standard/PDF-A profiles, font requirements, isolation and fidelity limits.
- [REST API](../nuaforma-server/README.md): multipart requests/resources, capabilities, assessment, errors and downloadable OpenAPI JSON/YAML.
- [Embedded Java/Kotlin examples](../examples/embedded/README.md): consume library artifacts without running a server.
- [Development Playground](../nuaforma-playground/README.md): prepare/export requests, select/upload fonts and generate documents through the same API; distributed as a separate development-only container.
- [Marked template diagnostic PDF](template-diagnostic-pdf.md): development-only parser error navigation, selected for alpha.4; not available in alpha.3.

## Deploy and operate

- [Source service container](../deployment/pdf-runtime/SERVICE.md): building, configuration, limits and shutdown. The service and separate Playground share the native provider and independently isolated validators.
- [ARM64 and Podman](../deployment/pdf-runtime/ARM64.md), [runtime default security policies](../deployment/pdf-runtime/APPARMOR.md) and [container/validator boundaries](../deployment/pdf-runtime/README.md).
- [Font and extension mounts](../deployment/pdf-runtime/MOUNTS.md).
- [Operational logging](logging.md): correlation, safe errors and sensitive development diagnostics.
- [Health endpoints](health.md) and [source Helm chart](../deployment/helm/README.md).
- [Static CycloneDX inventory](sbom.md): component scope, exclusions and build-time validation.
- [Java runtime compatibility](java-runtime-compatibility.md): Java 17 libraries, Java 25 server/Playground and separate Java 17 validators.

## Release

- [Single-final-approval release procedure](release-process.md): green CI, build once, verify identity/source coverage and publish under one final review.

## Develop, extend and contribute

- [Build and test](development.md).
- [Code quality and coverage](code-quality.md): SonarQube Cloud, JVM coverage and safe CI setup.
- [Branches, releases and the separate website](branch-workflow.md).
- [Own PDF engine design](architecture/conversion-engine.md): shared `nuaforma-pdf`, native DOCX adapter, shared layout/writing model, planned ODT adapter and remaining milestones.
- [Own PDF engine foundation](../nuaforma-pdf/README.md): the shared renderer used by the registered DOCX/PDF provider, with bounded pagination, tables, page bands, frames and image wrapping.
- [Own-engine DOCX fidelity proof](../tools/pdf-evaluation/own-engine/README.md): strict synthetic DOCX-to-neutral-model adapter, real Word-exported reference, reproducible overlay/difference and measured scope/limitations.
- [Own-engine report fidelity trial](../tools/pdf-evaluation/own-engine/REPORT.md): fixed tables, repeated column headers, header/footer bands and flowing pagination, with a Word comparison recorded per page.
- [Own-engine advanced table trials](../tools/pdf-evaluation/own-engine/ADVANCED.md): horizontal/vertical merges, nested tables and split rows, with actual Word references and per-page differences including Word's oversized-row clipping.
- [Own-engine frame trials](../tools/pdf-evaluation/own-engine/FRAMES.md): images in both page bands, text frames, wrapping, rotations and full-page barcode decoding, plus concurrent shared-engine verification.
- [Native requested-watermark qualification](../tools/pdf-evaluation/own-engine/WATERMARKS.md): original-scope restoration, profile restrictions, genuine Word references and protected visual regressions.
- [Own-engine roadmap](architecture/own-engine-roadmap.md): prioritized increments, delivery gates and current scope.
- [Own-engine paragraph pagination](../tools/pdf-evaluation/own-engine/PAGINATION.md): keeps, widow/orphan control and explicit page breaks compared with a real Word export.
- [Own-engine lists and numbering](../tools/pdf-evaluation/own-engine/LISTS.md): bullets, multiple levels, shared sequences/restarts, styled labels and chapter numbering compared per page with Word.
- [Create/install extensions](extensions.md) and the [independent value-pipe example](../examples/value-pipes/README.md).
- [Contribution guidelines](../CONTRIBUTING.md).
- [Architecture decisions](../ARCHITECTURE.md), [provider contracts](../nuaforma-contracts/README.md) and [provider discovery/core](../nuaforma-core/README.md).
- [Dependency reviews](../DEPENDENCIES.md), [third-party notices](../THIRD_PARTY.md) and [license](../LICENSE).

## Source versus published versions

This checkout uses the native DOCX reader and shared `nuaforma-pdf` renderer. Libraries target Java 17; server/Playground use Java 25, with separate Java 17 PDF validators. This guide targets the native engine selected for `0.3.0-alpha.4`. Consult the actual signed release and matching Maven/container artifacts for published availability; older LibreOffice-based images do not gain this implementation automatically. Source builds and checks do not themselves publish artifacts. Local evaluation can use staged libraries and the source container; publication retains its candidate-specific qualification and approval gates.

The published alpha.4 runtime contract is `0.6`, accepting compatible provider minima `0.1` through `0.6`. Current source advances to contract `0.7`, with source DOCX/PDF providers requiring 0.7 for rich-text content metadata; the diagnostic operation was introduced in 0.6. Published alpha.3 remains contract 0.5 with development syntax context and has no diagnostic-PDF endpoint. PDF generation defaults to PDF/A-3b. Only exact `NUAFORMA_PDFA_VALIDATION=false` delegates conformance checking downstream; structural/font checks and the container validation boundary remain mandatory. See the [current PDF guide](../nuaforma-docx-pdf/README.md).

XLSX, ODT and ODS, external template retrieval, signable PDF fields, PDF/A level A and PDF/UA remain planned. Attachments and encryption are not implemented. The [roadmap](architecture/own-engine-roadmap.md) distinguishes qualified combinations from further work.

## Maintainer release guides

- [Maven Central signing, upload and publication](maven-central-releases.md).
- [Container qualification and publication](container-releases.md), including mandatory website/download synchronization.
- [Bundled runtime licensing/source provision](runtime-distribution.md).

Preparation, publication and website deployment are separate actions. These guides do not authorize a release or deploy the site.

## Development Maven publications

See [Nexus publishing](nexus-publishing.md) for stable-versus-prerelease routing, automatic develop snapshots and manual dispatch, repository setup and anonymous Maven/Gradle consumption. [The release procedure](release-process.md) retains the single final approval for immutable product releases.
