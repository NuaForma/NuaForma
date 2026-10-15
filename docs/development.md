# Build and develop NuaForma

This guide is for building NuaForma itself. Using a published container or embedding published libraries does not require a source build. For private extensions, start with the [extension guide](extensions.md); to contribute a change upstream, read [CONTRIBUTING.md](../CONTRIBUTING.md).

## Requirements

- JDK 17 for the five embedded libraries and JDK 25 for the server/Playground. Gradle can run on either; both compilation/test toolchains must be installed.
- Python 3 for publication checks, SBOM normalization and repository tools.
- The checked-in Gradle Wrapper; no global Gradle installation is required. Initial setup downloads pinned build tools/dependencies.
- Maven 3.9.6 or later for independent embedded-consumer verification.
- Node.js with its built-in test runner for the dependency-free Playground JavaScript checks; no npm installation is needed for those checks.
- Docker/BuildKit and a suitable native Linux engine for optional real-container qualification. GnuPG is needed only for the explicit synthetic local signing check.

Set `JDK17` and `JDK25` to installation directories if Gradle does not find both automatically. Commands below run from the repository root using the Unix wrapper; Windows users can use `gradlew.bat`. Workstation-specific agent settings are not project prerequisites.

Libraries retain Java 17 API/bytecode compatibility. The server and Playground use Java 25; isolated PDF validators retain their separately pinned Java 17 runtime. See [runtime compatibility](java-runtime-compatibility.md). Existing alpha images are not upgraded by building this branch.

## Build and test

```sh
./gradlew build
./gradlew test
./gradlew :nuaforma-template:test
./gradlew :nuaforma-server:test --tests 'dev.nuaforma.architecture.*'
node --test nuaforma-playground/src/test/javascript/builder.test.cjs
```

The full build includes module/dependency boundaries, ArchUnit architecture checks, Java/Kotlin tests, SBOM schema checks and local library-publication verification. JUnit Jupiter, MockK, ArchUnit and Swagger Parser are test-only. Changing only prose normally requires link/diff review rather than rendering every document; executable examples and generated documentation still need their applicable checks.

Tests using synthetic DOCX/PDF inputs are not proof of visual fidelity. Changes affecting document processing, fonts, conversion, resource handling or transport require the relevant real integration checks, representative rendered-page review and independent barcode/profile checks. See [container qualification](../deployment/pdf-runtime/SERVICE.md#qualification-and-release-preparation) and the [renderer guide](../deployment/pdf-runtime/README.md#reproducible-qualification). Use synthetic data and licensed test fonts, not customer documents or private credentials.

## Run the service and export its contract

```sh
./gradlew :nuaforma-server:run
```

Follow the [server guide](../nuaforma-server/README.md#run-locally) for configuration; this alone does not supply the isolated LibreOffice/PDF runtime. DOCX can remain usable when PDF is unavailable. Start the separate [Playground](../nuaforma-playground/README.md) only when you explicitly want development diagnostics and its sensitive-data warnings.

Export the same authoritative contract without starting HTTP:

```sh
./gradlew :nuaforma-server:generateOpenApi
```

The JSON/YAML files are written beneath `nuaforma-server/build/generated/openapi`. Do not maintain a second hand-authored contract. GUI routes/assets are intentionally outside OpenAPI. The [SBOM guide](sbom.md) describes static inventory generation and independent schema validation.

## Verify library packaging and independent consumers

```sh
./gradlew verifyLibraryPublication
./gradlew verifyEmbeddedConsumers
./gradlew generatePomFileForMavenJavaPublication
```

These checks stage POM/main/source/documentation JARs to the ignored `build/library-repository` and test independently compiled Java/Maven and Kotlin/Gradle consumers. They do not upload to Maven Central. Documentation JARs contain module guides and architecture material, not generated Kotlin API pages.

The unreleased `0.2.0-alpha.1` candidate uses contract `0.2`. Use the examples' exclusive local repository and exact staged artifact hashes when testing candidate APIs; changing metadata does not make the version available on Central. See [embedded examples](../examples/embedded/README.md) and the [value-pipe example](../examples/value-pipes/README.md).

## Modules

| Module | Responsibility |
| --- | --- |
| [nuaforma-contracts](../nuaforma-contracts/README.md) | Public provider/value/extension contracts and static compatibility metadata |
| [nuaforma-core](../nuaforma-core/README.md) | Provider discovery, selection and generation orchestration |
| [nuaforma-template](../nuaforma-template/README.md) | Restricted format-independent template language and pipe evaluation |
| [nuaforma-docx](../nuaforma-docx/README.md) | Native Word transformation, fonts, images and watermarks |
| [nuaforma-docx-pdf](../nuaforma-docx-pdf/README.md) | DOCX reuse plus isolated PDF conversion/validation |
| [nuaforma-server](../nuaforma-server/README.md) | REST transport and operational endpoints |
| [nuaforma-playground](../nuaforma-playground/README.md) | Separate development GUI over the public server composition facade |

The first five modules are independently publishable libraries. Server and Playground are separate Java 25 applications; the production server must not depend on the Playground. Keep external integrations behind component-specific `adapters` packages and use public contracts across module boundaries. [ARCHITECTURE.md](../ARCHITECTURE.md) is authoritative for the dependency and compatibility rules.

## Dependencies and releases

Pinned dependency versions live in `gradle/libs.versions.toml`; resolved graphs are locked in each module's `gradle.lockfile`. Change locks deliberately using `--write-locks` for the affected configurations, not as a routine build step. Review maintenance, licenses, security and Java-baseline compatibility before introducing or upgrading a dependency; record the review in [DEPENDENCIES.md](../DEPENDENCIES.md).

Building, staging libraries or creating a local image is not release authorization. The [Maven guide](maven-central-releases.md), [container guide](container-releases.md) and [source/license checklist](libreoffice-licensing.md) define the separately protected release gates. A matching website/download update is mandatory for each authorized release.
