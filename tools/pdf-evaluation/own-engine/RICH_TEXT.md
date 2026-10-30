# Rich-text qualification (#118)

This report covers source changes after alpha.4. The executable contract and limits are owned by [the rich-text guide](../../../docs/rich-text.md). No RTF dependency or additional published artifact is added.

## Independent Word comparison

`NativeRichTextTest` builds a synthetic template and explicit RTF data, plus an independent expected DOCX whose WML formatting is written directly without calling the RTF reader. The generated and expected DOCX snapshots were exported read-only with the existing `export_word.ps1` helper. Their 96-DPI PDFium rasters are pixel-identical. The native PDF was visually reviewed beside that Word export; it retains the same text styles, paragraph structure, tab, RGB/size changes and Hebrew direction/alignment. Different font/rasterizer versions can affect text edges, so native regression uses the independently reviewed native raster rather than loosening a Word-to-native threshold.

The [initial golden provenance](../../../nuaforma-docx-pdf/src/test/resources/rich-text/README.md) records Word version/build and source/PDF/image hashes. CI needs neither Word nor installed host fonts. Existing golden images were not replaced. The new image assertion has zero pixel tolerance and no excluded regions.

## Executable coverage

- `RtfReaderTest`: nested/reset formatting, scoped Unicode fallback, surrogate pairs, code pages and UTF-8, font/colour tables, immutable fragment collections, malformed/active/unsupported input, resource/work ratios, deadlines and mid-parser cancellation.
- `RichTextTest` and `JavaDocxProviderTest`: actual DOCX structure, inline prefix/suffix joining, independent trims, intentional whitespace, empty/optional values, structural conditions/repetitions, literal braces beside tabs, table cells, legacy/modern text boxes, headers/footers, safe boundary handling, post-insertion font validation with one resource read, shared-provider isolation and cancellation without publication.
- `NativeRichTextTest`: actual registered-provider output for A4 and Letter in standard PDF and independently validated PDF/A-1b/2b/3b; embedded searchable fonts; reviewed native golden; empty documents; long pagination; optional headers/footers; data-position failures with no PDF publication.
- `PdfTextStyleTest` and `PdfOutputTest`: value/copy/constructor compatibility and single strike plus underline through all four output profiles.
- `ContentPipesTest`, `PipeCatalogTest`, `OpenApiTest` and `TemplateSocketTest`: strict legacy missing semantics, opt-in null handling, metadata/schema parity and actual HTTP conversion on the Java 25 service, including CP1251 RTF and an absent optional value.

## Published-binary compatibility probe

Separate Java/Kotlin consumers were compiled exclusively against the published alpha.4 PDF/contracts JARs and then executed unchanged against the new source libraries on Java 17. Constructor/default-constructor, six-property copy/default-copy and destructuring calls succeed; an old Kotlin copy also retains a newly enabled strikeout. Legacy normal/underlined style hash values match the published implementation. Reflection differences are explicitly documented: `PdfTextStyle` provides value methods directly and no longer has Kotlin's data-class marker.

The baseline JAR SHA-256 values, checked against the approved alpha.4 inventory, were:

| Artifact | SHA-256 |
| --- | --- |
| `nuaforma-pdf` alpha.4 | `135f4d5ac5caa23a24a7b4324e3f0d69e29be7ffcf80369c05f50835b820fcbe` |
| `nuaforma-contracts` alpha.4 | `1a3dc6bf7bcc4e84a81a40b9e755d1896d2bc04ebccb68357cf7ecfec735dc89` |

The complete local build passed 951 JVM tests without failures/errors/skips and the existing independent staged-library consumer checks. A final development-only source-paragraph provenance improvement for repeated rich data passed the complete DOCX test module and targeted native-PDF/HTTP/schema regressions separately; the exact delivery commit must also pass the normal Java 17/25, native AMD64/ARM64 and actual Sonar quality gate before merging.
