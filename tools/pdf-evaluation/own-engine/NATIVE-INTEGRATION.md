# Registered native DOCX/PDF integration trials

Roadmap part 14 promotes the bounded DOCX reader into the registered `DocxPdfProvider`, reuses `nuaforma-docx` for template processing and `nuaforma-pdf` for rendering, and removes the production LibreOffice backend. These are source-candidate trials, not a release or evidence of arbitrary Word compatibility. Final repository/runtime qualification is still in progress.

## Independent Word references

The 2026-10-25 local evidence bundle `57-native-integration-20261025` retains source/filled DOCX files, native PDFs, genuine Word exports with provenance hashes, and per-page comparison JSON/PNGs. Word exported the references through `ExportAsFixedFormat` (Word 16.0, build 20527). No existing Word reference or approved golden file was overwritten.

The A4 letter uses the sanitized, Word-saved fixture under `nuaforma-docx-pdf/src/test/resources/native-profile`. The report derives a template from the existing qualified report without changing that reference: single-cell structural marker rows repeat 80 data rows, with inline conditional descriptions, varying text lengths, quantities and amounts. The report reference is exported from the actual filled DOCX produced by the provider's DOCX route.

The test-only `NativeDocxProof` entry point invokes the registered native facade with explicit Liberation Sans faces and a real Java 17 `PdfProcessValidator`. Both trials request standard PDF and require independent structural/font/active-content validation before output. Archival-profile validation is covered separately by the native provider tests and the existing four-profile corpus.

## Per-page comparison

`compare.py` checks Word provenance, page counts, normalized page text, word sequences and word bounds, and renders both PDFs without content alignment/rescaling. All six side-by-side pages were visually inspected for branding, styles, table borders, wrapping, headers/footers and page transitions.

| Trial/page | Same page text and words | Max word start delta (pt) | Max word end delta (pt) | Max word top delta (pt) |
| --- | --- | ---: | ---: | ---: |
| Word-saved A4 letter, 1 | Yes | 0.1669 | 0.2160 | 0.0745 |
| Variable report, 1 | Yes | 0.2569 | 0.2520 | 0.1428 |
| Variable report, 2 | Yes | 0.2250 | 0.2520 | 0.1428 |
| Variable report, 3 | Yes | 0.2250 | 0.2520 | 0.1628 |
| Variable report, 4 | Yes | 0.2250 | 0.2520 | 0.1628 |
| Variable report, 5 | Yes | 0.2278 | 0.2923 | 0.1474 |

The report preserves row ranges R001–R012, R013–R033, R034–R053, R054–R073 and R074–R080 on the same five pages as Word. Conditional descriptions, amounts, colors and repeated page bands remain visible. These bounded trials show no content or page-assignment loss. Small text/raster differences remain; they do not establish universal pixel equality or justify replacing golden files.

The changed-pixel diagnostic above the existing 24/255 channel threshold ranges from 0.009334 to 0.031391 across the six pages. This is not a similarity score or an acceptance threshold: page white space dominates the statistic. Content, geometry and visual review remain necessary.

## Reproduction and remaining gates

Build test classes and `:nuaforma-pdf:prepareOwnEngineValidation`, then invoke `:nuaforma-docx-pdf:renderNativeDocxProof` with five explicit arguments: source DOCX, fresh output PDF, font directory, staged validator directory and Java 17 executable. A sixth argument of `80` derives the variable report from the existing report source. The entry point also retains the filled DOCX beside the candidate PDF. Use `export_word.ps1` with a fresh reference destination, followed by `compare.py --source <exact-Word-input.docx> --word-bounds`. Keep references and candidates distinct.

A cold cross-filesystem class-directory run of the letter hit the unchanged generation deadline. It succeeded after byte-identical runtime and validator JARs were staged on the local Linux filesystem; all 89 copied JAR hashes were checked. No product deadline, test expectation, golden image or validation policy was relaxed. This is workstation I/O evidence, not a conversion capacity benchmark.

The complete repository build passed 775 JVM tests with zero failures/errors/skips, plus all five independent Java/Kotlin consumer checks. Existing neutral-engine goldens remain unchanged. Current local Linux AMD64 container checks additionally pass for the production and Playground boundaries, shipped SBOMs, exact-profile validation with both operator policies, negative PDF safety cases, two concurrent tenants, deadline cleanup, PNG/JPEG pipes, image transparency restrictions, shutdown during genuine validation and missing-validator DOCX availability. Both synthetic namespace/device/security probe modes, the compiled syscall filter, native-platform check and retained-license inventory pass. Only Java runtime library lookup was added to the new validator launcher; isolation/limits were not relaxed.

Qualification now uses the parser's effective PDF version and genuine veraPDF profile/final-digest attestations rather than assuming that PDFBox's header line or uncompressed font dictionaries describe the complete file. A lower header with the requested catalog version is inspected by the actual parser. The former 11-point Liberation Serif/13.2-point exact-line fixture is preserved as an explicit PDF rejection and DOCX success; it is not silently enlarged. The positive native smoke fixture uses a qualified 16-point line.

Automatic deployment-font selection, DOCX embedding/deobfuscation and actual validated native PDF output passed for all four faces of Liberation Mono and Liberation Sans plus Libre Barcode 39. The extended host-mount trial then stopped at its unchanged `unreadable.ttf` negative: the local Windows directory share did not expose the requested Unix read restriction. That check is **not passed or skipped**; the strict Linux-host CI gate remains required. Native ARM64 qualification also remains open. No host security policy was disabled to obtain green results.

A separate small local load smoke completed twelve 24-paragraph documents, two requests at a time, with default PDF/A-3b validation enabled, in 35.868 seconds. All returned PDFs, no container OOM occurred and private files were cleaned. Before/after Docker memory readings were 228.6/260.7 MiB under the unchanged 2 GiB container limit; these are snapshots, **not peak measurements**. This is not a production throughput/capacity guarantee or a comparison with LibreOffice.

Requested native PDF watermarks, `paragraphDefaults`, mixed text/image paragraphs and source-embedded font packages remain explicitly unqualified; see the [current provider profile](../../../nuaforma-docx-pdf/README.md). Do not reinterpret legacy LibreOffice acceptance as native support for those inputs. Native ARM64, strict Linux host permissions and broader production capacity/fidelity remain delivery gates; no release, merge, commit or push is implied by these local results.
