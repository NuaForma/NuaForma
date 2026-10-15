# NuaForma — Agent Instructions

NuaForma turns user-supplied templates and structured data into documents. The product is an embeddable Kotlin/JVM engine, with a thin REST service using that same engine. It must support hosted use and Docker-based on-premise deployment without requiring a proprietary service.

This file is the source of truth for shared project instructions. User-facing capabilities and installation instructions belong in README.md; architecture decisions and requirements live in [ARCHITECTURE.md](ARCHITECTURE.md). Reference those documents instead of duplicating them here. Keep planned capabilities distinct from implemented and verified functionality.

## Local instructions

Before working, read `AGENTS.local.md` next to this file if it exists. It supplements these shared rules with personal workflow agreements and workstation-specific settings and is intentionally ignored by Git. Its absence is normal in other checkouts and CI; do not create it automatically or treat its absence as a blocker.

Local instructions may specialize workstation and collaboration behavior, but must not weaken shared product, security or licensing requirements. If instructions conflict or the distinction is unclear, ask the user before acting. Do not copy local paths or personal agreements into tracked documentation, CI or build configuration.

## Scope and architecture

Implement only the requested milestone within the scope defined in [ARCHITECTURE.md](ARCHITECTURE.md). Keep future capabilities distinct from implemented functionality. Changes to architectural decisions require explicit approval; update ARCHITECTURE.md when an approved decision changes.

## First-time setup

- Read the repository status, this file, [ARCHITECTURE.md](ARCHITECTURE.md), README.md and applicable nested instructions before making changes. Follow the agreed architecture; do not duplicate its design rules here.
- Preserve existing local changes, including untracked and ignored files. Never reset, overwrite or clean unrelated work.
- Java 17 remains the minimum runtime/API/bytecode baseline for all five embeddable libraries. Java 25 is the approved target runtime for the standalone server and distributed container; see the JVM baseline decision in ARCHITECTURE.md. Ask before changing either boundary.
- Compile library Java and Kotlin code against Java 17 APIs/bytecode and retain Java-17-compatible dependencies. Use the explicit Java 25 toolchain for standalone server compilation/tests. Verify embedded use and independent consumers on Java 17, plus integration on the Java 25 server. The separate PDF validator JVMs remain pinned to Java 17; see docs/java-runtime-compatibility.md. Previously published images remain unchanged.
- Describe setup and build requirements independently of a particular operating system, shell, workstation path or virtualization environment. Machine-specific execution instructions belong exclusively in `AGENTS.local.md`.
- Use Gradle with Kotlin DSL (`build.gradle.kts` and `settings.gradle.kts`) as the production build system. Keep the build straightforward; do not introduce custom build logic without a concrete need.
- Check in the Gradle Wrapper with a pinned Gradle version when the build is created, and use it for builds and tests. Do not rely on a workstation-specific global Gradle installation.
- Preserve the Unix executable bit for `gradlew` when preparing a commit; its Git file mode must be `100755`. Do not stage it merely to change this mode without authorization to stage or commit.
- The Gradle build, six modules, initial public provider contracts, core provider discovery/selection, restricted template language, DOCX/PDF providers, bounded Ktor REST transport, equivalent OpenAPI JSON/YAML downloads and a Linux/amd64 service container exist. The optional PDF provider requires the qualified isolated LibreOffice/validator runtime; see its module guide for supported profiles and limits. The APIs are a development draft, not a stable release. Follow the service guide for isolation/configuration; public image distribution remains gated by the candidate-specific license/source audit.
- The DOCX module has a registered DOCX-to-DOCX generation provider with optional requested text watermarks and a separate unchanged round-trip facade, both with isolated docx4j state. See its module README for the supported structural template profile, formatting policy, lifecycle, limits and verified fidelity scope. The optional PDF module delegates DOCX unchanged and preserves requested watermarks in the qualified PDF profile. The server guide defines multipart transport, provider assessment/capabilities, resources, error policy and REST-to-engine parity.
- If CLAUDE.md or another agent entry-point file is introduced, make it a pointer to AGENTS.md rather than a second copy of the rules.

## Build and verification commands

Use the checked-in Gradle Wrapper from the repository root. The commands below use the Unix launcher; Windows environments can use the corresponding `gradlew.bat` launcher unless local instructions require a different environment.

- Full build: `./gradlew build`.
- All current tests: `./gradlew test`.
- One test: `./gradlew :nuaforma-core:test --tests dev.nuaforma.core.TestStackTest`.
- Scaffold dependency checks: `./gradlew verifyBuildStructure` (also included in `check` and `build`).
- Repository architecture rules and violating fixtures: `./gradlew :nuaforma-server:test --tests 'dev.nuaforma.architecture.*'` (also included in normal `test` and `build`).
- DOCX facade and round-trip integration tests: `./gradlew :nuaforma-docx:test`. The synthetic before/after reference files are generated under the module's ignored `build/docx-reference` directory; render them only after the build finishes.
- Format-independent language specification and executable examples: `nuaforma-template/README.md` and `./gradlew :nuaforma-template:test`. This module must not depend on core, server or a document library.
- Local publication metadata: `./gradlew generatePomFileForMavenJavaPublication`.
- Standalone local JAR/POM/source/documentation checks: `./gradlew verifyLibraryPublication` (included in `check` and `build`, requires Python 3). Independent Java/Maven and Kotlin/Gradle consumers: `./gradlew verifyEmbeddedConsumers` (also requires Maven 3.9.6 or later). Synthetic local OpenPGP QA requires GnuPG; see [the library release guide](docs/maven-central-releases.md). These commands do not upload artifacts or use the maintainer's private signing key.
- Word template-processing integration tests run with `./gradlew :nuaforma-docx:test`; real provider discovery and concurrent generation through core run with `./gradlew :nuaforma-server:test --tests dev.nuaforma.DocxEngineTest`.
- OpenAPI download, independent parsing, JSON/YAML parity and behavioral contract checks: `./gradlew :nuaforma-server:test --tests dev.nuaforma.server.OpenApiTest`. Export the same authoritative contract without starting the service with `./gradlew :nuaforma-server:generateOpenApi`; output belongs under the server's ignored `build/generated/openapi` directory, not in separately maintained source copies. Swagger Parser and its JSON/YAML parsing dependencies are test-only.
- REST integration tests: `./gradlew :nuaforma-server:test --tests dev.nuaforma.server.RestApiTest`. Run the explicit real-HTTP PDF qualification separately when transport, resources or PDF mapping change; no mocked conversion is a substitute. The server binds loopback by default; do not expose or deploy it merely to verify a local build.
- Shipped container qualification: follow [deployment/pdf-runtime/SERVICE.md](deployment/pdf-runtime/SERVICE.md), then `python3 tools/container/qualify_service.py --image nuaforma:local --output build/container-qualification`. Use a fresh output directory and the actual final image; include caller-font/barcode/watermark visual checks when relevant. Release preparation must retain only non-executable review metadata until distribution obligations are fulfilled; see [docs/container-releases.md](docs/container-releases.md).

The dependency versions live in `gradle/libs.versions.toml`; resolved module dependencies are locked in each subproject's `gradle.lockfile`. Update locks deliberately using `--write-locks` with the affected verification commands and review the resulting changes. Do not regenerate locks as a routine build step.

Gradle with Kotlin DSL is the selected build system. Install JDK 17 and JDK 25: library compilation/tests use 17, server compilation/tests use 25, and Gradle may run on either. `JDK17` and `JDK25` can supply installations not found automatically. Use the checked-in Gradle Wrapper appropriate to the supported environment; workstation-specific execution instructions remain in `AGENTS.local.md`.

- Run the affected tests while developing and the complete build before declaring an implementation complete.
- Run integration tests for changes to rendering, fonts, Docker, format conversion or the REST workflow; unit tests alone do not verify these boundaries.
- For documentation-only changes, inspect the diff and check referenced paths and commands. A build is not required unless documentation generation or executable examples are affected.
- Report commands actually run and their results. If a required check cannot run, state the limitation; never describe an unexecuted check as passing.

## Tests and reference documents

- Use JUnit Jupiter with the newest stable JUnit release compatible with Java 17 and the chosen test stack when establishing or updating the build. Pin the selected version for reproducible builds; do not use dynamic latest-version selectors or prereleases by default. The test framework choice is not fixed to JUnit 5.
- Use MockK for mocking Kotlin/JVM dependencies at facade and component boundaries. Do not introduce another mocking framework without approval. Verify interoperability with the selected JUnit, Kotlin and Java versions.
- Prefer real values and actual document processing for content, structure and fidelity tests. Mock external boundaries to isolate behavior; do not mock the processing under test or treat mocked renderer output as proof of visual fidelity.
- Use ArchUnit to enforce the architecture rules and coverage defined in [ARCHITECTURE.md](ARCHITECTURE.md#architecture-tests). JUnit, MockK and ArchUnit are test-scoped dependencies and must not appear in production artifacts.
- Additional test tools may be added when a concrete need arises and after approval. WireMock is a possible later choice for outgoing HTTP integration tests, not an initial mandatory dependency.
- Follow the repository's chosen assertion and parameterization style once established; do not add a second testing stack without approval.
- New behavior requires a minimal regression case with a synthetic template and data. Cover normal, boundary and invalid inputs, plus relevant nesting and formatting combinations.
- Verify both content and document structure. Include formatting, relationships, section geometry and unaffected package parts in fidelity tests.
- Render representative output and inspect pages for clipping, overlap, blank pages, broken tables, header/footer placement and font substitution. XML assertions alone cannot establish visual fidelity.
- Use independent checks where possible: decode rendered barcodes, validate PDF profiles and inspect signature fields rather than trusting the generator's own success result.
- Test concurrent generation, resource-limit enforcement, failure cleanup and prevention of cross-request data leakage.
- Test the Java-facing API as well as Kotlin use. Test REST-to-engine parity so transport wrappers cannot change semantics.
- For reference or golden outputs, investigate unexpected differences before regeneration. Use a deliberate regeneration procedure, review the full diff and keep regeneration mode disabled by default.
- Never hand-edit generated reference output to hide a failure. Source templates and fixture data may be deliberately edited; regenerate their expected output through the documented workflow.
- Binary fixture changes must have a reproducible builder or a documented authoring procedure and an explanation of the intended change. Do not commit build output, temporary renders or document previews unless deliberately maintained as fixtures.

## Dependencies and licensing

- Prefer actively maintained, genuinely open-source libraries with licenses compatible with distributing NuaForma under Apache-2.0 and embedding it in proprietary applications.
- Before adding or upgrading a dependency, check primary sources for its license, release activity, security posture, transitive dependencies and redistribution obligations. Do not equate a recent version number with healthy maintenance.
- Avoid unmaintained libraries or proprietary-only features for required functionality. If a candidate is in maintenance-only mode or has material licensing uncertainty, explain the trade-off and ask before adoption.
- Keep third-party notices and required attribution accurate. Separate dependency licenses, bundled assets and font licenses from the project's own license.
- Do not introduce copyleft, noncommercial, source-available or paid-only components without an explicit compatibility review and approval. Do not describe source-available software as open source.

## Markdown, comments and GitHub hygiene

- Soft-wrap prose: do not hard-wrap sentences mid-line. Do not rewrap unrelated existing text as a side effect of another edit. Use blank lines around headings and lists.
- Code identifiers, public API documentation and repository documentation should use English consistently. Match the user's language in conversation.
- Prefer clear function and variable names over comments. Necessary comments should be terse and explain a current constraint or non-obvious contract, not narrate attempts or history. Apply this to KDoc and tests too.
- Bug reports need the smallest reproducible template/data combination, the NuaForma version and relevant renderer/font details. Redact sensitive content before sharing.
- Use repository issue and PR templates when they exist. PR descriptions should explain behavior changes, compatibility impact and non-obvious trade-offs rather than narrating the session or listing files and test results already visible elsewhere.
- Keep unrelated changes separate. Do not stage, commit, push, create a PR or change remote settings unless the user requests that workflow.
- Every release, including an alpha or container-only release, requires a corresponding website update. Follow the website synchronization checklist in docs/container-releases.md; a release is not fully handed off until the matching site changes are deployed and its live downloads are verified. Do not infer publication/deployment authority from this requirement.
- Changes to `main` require a pull request, verified signed commits and the required `Build (Java 17)` GitHub Actions check. Keep the branch up to date with `main` and resolve review conversations before merging. Do not bypass these protections or rename the required check without updating and verifying the GitHub ruleset. CODEOWNERS assigns reviewers; approval is optional while there is one human maintainer.
- The GitHub organization and repository are NuaForma; the project domain is nuaforma.dev. Do not infer permission to publish, deploy or configure DNS from that ownership.

## Boundaries

- Always: preserve user work, follow ARCHITECTURE.md, verify document fidelity, enforce explicit failure policies and report validation honestly.
- Ask first: changes to public contracts or existing output behavior; new dependencies; JVM baseline changes; CI/workflow or deployment configuration changes; significant scope expansion; removal of existing files or assets.
- Never: execute template code; hide malformed input behind silent fallbacks; leak tenant data; commit secrets or private signing keys; claim unverified format/profile support; weaken tests or security limits merely to obtain a passing build.
