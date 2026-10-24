# NuaForma documentation

Choose a guide for the task you want to complete. Module guides are the technical reference; this index links to them rather than maintaining duplicate API descriptions.

## Use NuaForma

- [Project overview](../README.md) and [website/downloads](https://nuaforma.dev).
- [Template language](../nuaforma-template/README.md): placeholders, null/missing values, conditions, nested repetitions, formatting and pipes.
- [Word templates](../nuaforma-docx/README.md): paragraph/table rules, text boxes, images, fonts and watermarks.
- [PDF output](../nuaforma-docx-pdf/README.md): standard/PDF-A profiles, font requirements, isolation and fidelity limits.
- [REST API](../nuaforma-server/README.md): multipart requests/resources, capabilities, assessment, errors and downloadable OpenAPI JSON/YAML.
- [Embedded Java/Kotlin examples](../examples/embedded/README.md): consume library artifacts without running a server.
- [Development Playground](../nuaforma-playground/README.md): prepare/export requests, select/upload fonts and generate documents through the same API; distributed as a separate development-only container.

## Deploy and operate

- [Source service container](../deployment/pdf-runtime/SERVICE.md): building, configuration, limits and shutdown. For published images, use the version-matched website kits instead of source-only build instructions.
- [ARM64 and Podman](../deployment/pdf-runtime/ARM64.md), [AppArmor](../deployment/pdf-runtime/APPARMOR.md) and [renderer isolation](../deployment/pdf-runtime/README.md).
- [Font and extension mounts](../deployment/pdf-runtime/MOUNTS.md).
- [Operational logging](logging.md): correlation, safe errors and sensitive development diagnostics.
- [Short processing trials](performance-snapshot.md): request counts, concurrency and isolated veraPDF A/B measurements; indicative results, not sustained capacity guarantees.
- [Health endpoints](health.md) and [source Helm chart](../deployment/helm/README.md).
- [Static CycloneDX inventory](sbom.md): component scope, exclusions and build-time validation.
- [Java runtime compatibility](java-runtime-compatibility.md): Java 17 libraries, Java 25 server/Playground and separate Java 17 validators.

## Develop, extend and contribute

- [Build and test](development.md).
- [Code quality and coverage](code-quality.md): SonarQube Cloud, JVM coverage and safe CI setup.
- [Branches, releases and the separate website](branch-workflow.md).
- [Own PDF engine design](architecture/conversion-engine.md): shared `nuaforma-pdf`, planned production DOCX/ODT adapters, milestones and dedicated integration/release track; source-only foundation and a test-only DOCX proof exist so far.
- [Own PDF engine foundation](../nuaforma-pdf/README.md): neutral model with a single-page default and opt-in exact-line pagination/tables/page bands; no replacement of the current PDF provider.
- [Own-engine DOCX fidelity proof](../tools/pdf-evaluation/own-engine/README.md): strict synthetic DOCX-to-neutral-model adapter, real Word-exported reference, reproducible overlay/difference and measured scope/limitations.
- [Own-engine report fidelity trial](../tools/pdf-evaluation/own-engine/REPORT.md): fixed tables, repeated column headers, header/footer bands and flowing pagination, with a Word comparison recorded per page.
- [Own-engine advanced table trials](../tools/pdf-evaluation/own-engine/ADVANCED.md): horizontal/vertical merges, nested tables and split rows, with actual Word references and per-page differences including Word's oversized-row clipping.
- [Own-engine frame trials](../tools/pdf-evaluation/own-engine/FRAMES.md): images in both page bands, text frames, wrapping, rotations and full-page barcode decoding, plus concurrent shared-engine verification.
- [Create/install extensions](extensions.md) and the [independent value-pipe example](../examples/value-pipes/README.md).
- [Contribution guidelines](../CONTRIBUTING.md).
- [Architecture decisions](../ARCHITECTURE.md), [provider contracts](../nuaforma-contracts/README.md) and [provider discovery/core](../nuaforma-core/README.md).
- [Dependency reviews](../DEPENDENCIES.md), [third-party notices](../THIRD_PARTY.md) and [license](../LICENSE).

## Source versus published versions

Release `0.2.0-alpha.2` adds the default-on operator PDF/A conformance policy and optional provider-owned `conformancePolicies` metadata under contract `0.4`; it retains earlier contract minima and Java 17 compatibility. Published `0.2.0-alpha.1` artifacts do not implement `NUAFORMA_PDFA_VALIDATION`. Only exact `false` delegates conformance validation downstream; PDFBox and isolation remain mandatory. The [PDF guide](../nuaforma-docx-pdf/README.md#operator-conformance-policy-unreleased-issue-93-source) documents consequences and configuration. Use the version-matched website kits and preserve the [short-trial limitations](performance-snapshot.md); those measurements are not new alpha.2 benchmarks.

Current evaluation release:

| Distribution | Available version and scope |
| --- | --- |
| Maven Central libraries | `0.2.0-alpha.2`, Java 17; [release](https://github.com/NuaForma/NuaForma/releases/tag/v0.2.0-alpha.2) |
| GHCR production image | `ghcr.io/nuaforma/nuaforma:0.2.0-alpha.2`, native Linux AMD64/ARM64, Java 25 service and Java 17 validators |
| GHCR development Playground | `ghcr.io/nuaforma/nuaforma-playground:0.2.0-alpha.2`, native Linux AMD64/ARM64; sensitive development logging enabled by default |
| Earlier alpha.1 baseline, retained in alpha.2 | Extensible value/content pipes and Base64 image insertion, correlated logging, pipe catalogs, health endpoints, CycloneDX SBOM and the separate Playground |

Version `0.2.0-alpha.2` uses runtime contract `0.4`, accepting compatible provider minima `0.1` through `0.4`. DOCX requires `0.3` and the PDF provider requires `0.4`; original pipe extensions may retain minimum `0.2`. Historical `0.2.0-alpha.1` uses contract `0.2` and the [candidate-specific distribution review](release-0.2.0-alpha.1-audit.md). Contract `0.2` accepts existing `0.1` providers; implementations using the new contracts require at least `0.2` and are rejected by older runtimes before initialization. Earlier `0.1` library/image tags remain immutable and do not gain these features. Native qualification covers the recorded runtime and fixtures, not arbitrary host platforms. Source builds do not publish artifacts automatically.

Published alphas are for evaluation and retain documented native-runtime security findings tracked in [issue #51](https://github.com/NuaForma/NuaForma/issues/51). Review their release notes and deployment boundaries; earlier risk acceptance is not approval of a different release candidate.

XLSX, ODT and ODS, external template retrieval, signable PDF fields and a LibreOffice-free renderer remain roadmap work. PDF/A level A and PDF/UA are not supported. Attachments and encryption are not implemented. See the [issues](https://github.com/NuaForma/NuaForma/issues) for current planning and the PDF guide for supported profiles and the scoped text-box limitation.

## Maintainer release guides

- [0.2.0-alpha.2 preparation](release-0.2.0-alpha.2.md): contract `0.4`, with the [candidate-specific distribution review](release-0.2.0-alpha.2-audit.md).
- [0.2.0-alpha.1 preparation record](release-0.2.0-alpha.1.md) and [distribution review](release-0.2.0-alpha.1-audit.md), including contract `0.2` and the scoped evaluation-alpha security disposition.
- [Maven Central signing, upload and publication](maven-central-releases.md).
- [Container qualification and publication](container-releases.md), including mandatory website/download synchronization.
- [Bundled runtime licensing/source provision](libreoffice-licensing.md).

Preparation, publication and website deployment are separate actions. These guides do not authorize a release or deploy the site.

## Historical research

- [PDF backend comparison](pdf-backend-evaluation.md).
- [PDF/A-3a feasibility](pdfa-3a-feasibility.md).
- [Endpoint/Playground delivery and impact review](endpoint-integration.md).

Research records describe the measured baseline, not current supported features or uncompleted release requirements. Follow the current module guides for actual behavior.
