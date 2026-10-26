# Independent embedded consumers

These Java 17 and Kotlin examples consume the six source library publications by Maven coordinates, without source-project dependencies or an included build. Inputs are synthetic; generated documents and local repositories remain ignored.

## Stage and verify the libraries

With JDK 17/25, Python 3, Maven 3.9.6 or later and the checked-in wrapper:

```sh
./gradlew verifyLibraryPublication
./gradlew verifyEmbeddedConsumers
```

The publication destination is the local `build/library-repository`. Each library has its own POM, main/source/documentation JARs and original license/notices. Verification compiles/runs the independent Maven reactor and Kotlin/Gradle project with an exclusive local repository and exact staged hashes. It does not upload or publish.

| Example | Direct NuaForma dependencies | Verification |
| --- | --- | --- |
| Maven provider | contracts | Company-owned provider, SPI and explicit registration |
| Maven core | core and the example provider | Discovery, selection and bounded generation |
| Maven DOCX | core, docx | Actual styled DOCX template processing |
| Maven PDF | core, docx-pdf | Native provider composition, validator readiness and DOCX availability |
| Maven neutral PDF | pdf | Direct shared-model rendering with caller-supplied font bytes |
| Kotlin/Gradle DOCX | core, docx | Independent Java-17-compatible use and runtime isolation |

Pass `-Dnuaforma.repository=<local-file-repository-URL>` and `-Dnuaforma.version=<candidate-version>` to Maven, or `-PnuaformaRepository=<URL>` and `-PnuaformaVersion=<candidate-version>` to the Gradle example. Use the version actually staged from this checkout. The native version is not yet published. Runtime checks keep server/test/veraPDF libraries out of embedded host dependencies; PDFBox writing is part of the native PDF host, while vendor conformance parsing stays in the separate validator.

## Configured PDF generation

`DocxPdfProvider(NativePdfConfiguration(...))` accepts immutable caller fonts, a genuine independent validator, bounded concurrency and a default-on conformance policy. Default provider discovery instead captures trusted JVM font/validator settings at construction. Libraries read no process environment. Disabling conformance checking retains mandatory structural validation and makes no verified archival-conformance claim.

`PdfExample` with no arguments checks honest PDF unavailability without validator configuration, while retaining DOCX output. With two arguments it reads a source DOCX and writes validated PDF/A-3b. For a compiled example, supply the resolved classpath and trusted settings:

```sh
java -Dnuaforma.pdf.validator.java=/absolute/java17/bin/java \
  -Dnuaforma.pdf.validator.directory=/absolute/pdfa-validation \
  -Dnuaforma.fonts.directory=/absolute/caller-fonts \
  -cp '<example classes and resolved runtime JARs>' \
  dev.nuaforma.examples.PdfExample /absolute/template.docx /absolute/output.pdf
```

The module's `prepareOwnEngineValidation` task stages the validator distribution locally. Use an appropriate reviewed launcher/isolation boundary for hosted processing; no Office installation, document-renderer executable or server is required for embedded conversion. The [PDF guide](../../nuaforma-docx-pdf/README.md) and [runtime guide](../../deployment/pdf-runtime/README.md) define the qualified profile, fonts, limits and validation boundary.

The `tools/release/verify_embedded_pdf.py` helper checks an independently compiled example inside an explicitly selected qualified native container, with runtime default policies and separate bounded Java validation. Supply `--source <synthetic.docx>`, `--docker docker`, `--image <candidate>` and a fresh `--output` directory after `verifyEmbeddedConsumers`. It configures the image's supplied fonts and validator paths, publishes no artifacts and supplements exact-candidate runtime qualification.

## Third-party providers

The company provider demonstrates a separately compiled implementation with only `nuaforma-contracts`, a non-built-in format identifier and both registration mechanisms. Its deliberately small format accepts only the fixed `Company: {name}` UTF-8 template and a string `name`, with a 64 KiB template ceiling, a 65,536 UTF-16-unit name ceiling, no resources and caller output/expansion/time bounds. It is trusted application code, not an extension sandbox or a general-purpose document renderer. See the [contracts guide](../../nuaforma-contracts/README.md) for every provider obligation before implementing a real provider.
