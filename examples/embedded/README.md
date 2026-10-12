# Independent embedded consumers

These Java 17 and Kotlin examples consume the five library publications by Maven coordinates, not source-project dependencies or an included NuaForma build. They are verified development examples for issue #15, not published artifacts or a stable API promise. All sample inputs are synthetic; generated documents, dependency copies and local repositories remain ignored.

## Stage the libraries

From the repository root, with Java 17, Python 3 and the checked-in Gradle Wrapper:

```sh
./gradlew verifyLibraryPublication
```

The only publication destination is `build/library-repository`, a local file repository. All five libraries have their own POM, main JAR, source JAR and documentation JAR. The documentation JAR contains the module README and architecture guide; it is not generated Kotlin API documentation. Original dependency artifacts are neither shaded nor bundled in these library JARs. Consumers retain the licenses/notices of the dependencies they distribute separately.

## Verify Java/Maven and Kotlin/Gradle

With Maven 3.9.6 or later also available:

```sh
./gradlew verifyEmbeddedConsumers
```

This compiles/runs the independent Maven reactor under `maven/` and independent Gradle project under `gradle/`, using the staged repository. The wrapper supplies the pinned Gradle version to that separate build. Public dependencies and build tools are retrieved normally, but NuaForma's own JARs must match the staged artifact hashes; external runtime JAR names and hashes must match the reviewed Gradle graph. Maven's local cache for this check is isolated under `build/consumer-tests/m2`; runtime inventories/logs are retained under ignored output directories.

| Example | Direct NuaForma dependencies | Verification |
| --- | --- | --- |
| Maven provider | contracts only | A company-owned `company-note` provider with static metadata and SPI registration |
| Maven core | core, plus the example provider | Discovery and explicit registration both generate `Company: Ada`; no document/conversion/server implementation is installed |
| Maven DOCX | core, docx | Actual DOCX generation, formatted content and the restricted string language |
| Maven PDF | core, docx-pdf | Explicit trusted runtime configuration; missing conversion leaves PDF unavailable and DOCX selectable |
| Gradle Kotlin DOCX | core, docx | Actual generation through the same public contracts and runtime dependency isolation |

The examples' parent POM accepts `-Dnuaforma.repository=<local-file-repository-URL>` and `-Dnuaforma.version=<version>`. The Gradle example accepts `-PnuaformaRepository=<URL>` and `-PnuaformaVersion=<version>`. Until a release exists, the development version is `0.1.0-SNAPSHOT`; it is not a Maven Central release. There are no project substitutions, composite builds or dependencies on the service.

Runtime assertions reject server/test/veraPDF dependencies in the embedded host. The DOCX stack legitimately includes PDFBox IO/FontBox helpers, but not the optional full PDFBox parser, LibreOffice executable or isolated veraPDF validator. The PDF module's host dependencies differ from the converter's separate runtime; that runtime is not delivered through Maven.

## Configured PDF generation

`PdfExample` has two modes. With no arguments it verifies honest unavailability of PDF without the isolated runtime; it does not claim to render a PDF. With two arguments, supply the absolute path to the approved isolated launcher and a caller-owned output path:

```sh
java -cp 'examples/embedded/maven/pdf/target/classes:examples/embedded/maven/pdf/target/dependency/*' \
  dev.nuaforma.examples.PdfExample /absolute/path/to/isolated-launcher /absolute/path/to/output.pdf
```

Use your platform's classpath separator. This example explicitly requests PDF/A-3b and defines `Liberation Serif` as the template's ASCII/hAnsi document-default font, available in the qualified runtime. PDF font preservation requires explicit template defaults; NuaForma does not invent them from the host. Supply your own selected fonts through the documented resources policy when they are not bundled. The example writes output only after successful provider generation and profile validation; a bare `soffice` command is not a supported launcher. The existing [PDF module guide](../../nuaforma-docx-pdf/README.md) and [runtime guide](../../deployment/pdf-runtime/README.md) define readiness, isolation, fonts, limits and the accepted text-box limitation.

For a reproducible local check against an already qualified image, run `tools/release/verify_embedded_pdf.py --docker <docker-executable> --image <qualified-local-image> --output <fresh-output-directory>`. It uses the independently compiled Maven example and its resolved JARs inside a networkless, non-root, read-only, bounded container. Inspect/validate the resulting synthetic PDF separately; do not confuse the example check with the final release image qualification. No image, JAR or private font is uploaded by either helper.

## Third-party providers

The company provider demonstrates a separately compiled implementation with only `nuaforma-contracts`, a non-built-in format identifier and both registration mechanisms. Its deliberately small format accepts only the fixed `Company: {name}` UTF-8 template and a string `name`, with a 64 KiB template ceiling, a 65,536 UTF-16-unit name ceiling, no resources and caller output/expansion/time bounds. It is trusted application code, not an extension sandbox or a general-purpose document renderer. See the [contracts guide](../../nuaforma-contracts/README.md) for every provider obligation before implementing a real provider.
