# Page-band images and positioned text frames

Date: 2026-10-24. This controlled trial extends the [letter](README.md), [report](REPORT.md) and [advanced tables](ADVANCED.md). It adds images in both page bands, text frames and rotated text/images to the single shared `nuaforma-pdf` artifact. DOCX interpretation remains test-only. Production providers, server/Playground behavior, public conversion profiles, dependency versions and release workflows are unchanged. No commit or remote publication is part of this experiment.

## Reference method

`build_frame_fixtures.py` reproducibly creates three DOCX packages using the existing synthetic report styles, logo and Python's standard library. It generates a fixed PNG Code 39 test vector for `NF20260057`; it is not a production barcode encoder or a barcode-font qualification. There are no reference screenshots, reference-coordinate extraction, hardcoded page assignments or hidden external conversion calls in the engine.

Actual Microsoft Word 16.0 build 16.0.20430 exports each source independently through `export_word.ps1`. Every comparison verifies the source/reference SHA-256 against Word's adjacent provenance file. Both engines use the same local caller-supplied Liberation Sans regular/bold/italic font files as the preceding trials. The own-engine PDF contains embedded fonts and real text; no text is rasterized to approximate Word.

Ignored artifacts live under `build/own-engine-frames/<kind>/`: source DOCX, Word reference/provenance, engine PDF, `comparison/` page rasters/overlays/differences/JSON and `geometry/geometry.json`. All five page pairs are reviewed independently at 144 dpi with Poppler 26.07.0. Whole-page pixel differences are diagnostics, not similarity percentages. The rotated barcode check uses full pages at 300 dpi and the previously approved ZXing-C++ 3.1.1 QA decoder, outside the product dependency graph.

| Kind | Current Word reference | Source SHA-256 | Word PDF SHA-256 |
|---|---|---|---|
| `bands` | `word-reference-v3.pdf` | `feebac92a5d0c7e4302bb08748132e41beed249a43003bbf2f60eea6dc11d32f` | `dc314459ccc9d277f079be51deba7b7ada4fc9d5803bab146fc2fda64796e909` |
| `frames` | `word-reference-v4.pdf` | `4e8b2b12d781f1a9aa184487ccbecaba90ef8932bf6e82874f0a5e4e651b35b4` | `aefe806787c3b012120df1262bfd9caf683f7e0d4cfe82a236c8821d9534cb77` |
| `rotated` | `word-reference-v3.pdf` | `4b07e00c4a00a7e0b3bafaa4b1a16edfeddbf51b2d732a4ae5f86ed3f694febf` | `69be666b4d45e76b621cd40b1035e3e9cbf97f14a4354e29484dddaddf64e00e` |

Earlier references remain local historical artifacts and must not be used with newer source bytes. Source revisions deliberately gave empty anchor paragraphs sufficient line height, exercised real top/bottom wrapping with following body text and used modern DrawingML for rotation. References were re-exported through Word, never hand-edited to hide a renderer discrepancy.

## Results per page

### Images in both page bands

The three-page source has a logo in its header and a second independently related logo in its footer, alongside text and a live page number. Body markers B001–B082 each occur once. Both outputs have identical normalized text, geometric word order and page transitions.

| Page | Body content | Words / nonempty lines in each PDF | Max horizontal word-edge displacement | Max text-origin vertical displacement |
|---|---|---|---|---|
| 1 | Title and B001–B036 | 480 / 39 | 0.383 pt | 0.141 pt |
| 2 | B037–B074 | 501 / 40 | 0.383 pt | 0.141 pt |
| 3 | B075–B082 | 111 / 10 | 0.383 pt | 0.141 pt |

Both logos remain 18 × 18 pt at x = 56.7 pt. Header top is 31.2 pt; footer top is 789.1 pt in each output after accounting for Word's page-height rounding. Word resamples each logo to 49 × 49 pixels; the engine retains the 1254 × 1254 source. There are no missing/replaced images, footer collisions, shifted body breaks or lost page numbers. Pixel diagnostics above 24/255 are 146,700 / 153,318 / 28,540 on pages 1–3. Much of this is font rounding and raster/stroke differences over many repeated lines, not document restyling.

### Text frames and top/bottom wrapping

One page contains two fixed page-relative VML rectangles and an additional paragraph-relative frame. They exercise left/center/right paragraph alignment, top/middle/bottom vertical alignment, automatic text wrapping inside a fixed width, independent padding, background and border. All three reserve top/bottom exclusion areas. Body markers T01–T18 demonstrate that flow can use space above/between frames and resumes below the paragraph-relative frame without crossing it.

| Page | Word and engine result | Maximum horizontal word-edge displacement | Maximum vertical word-box displacement |
|---|---|---|---|
| 1 | Same 307 words and 31 physical lines; all three frames and all T01–T18 markers retained | 0.595 pt | 0.953 pt |

The two fixed frames are at y = 210 pt; the third follows its source paragraph. T01/T02 use the gap between the upper frames and the paragraph-relative frame; T03 resumes below the latter. The largest remaining vertical difference is at the wrap transition, below one point (approximately 0.34 mm). This is a measured difference, not an assertion of pixel-perfect positioning. No text, frame or following paragraph is clipped or overlapping. The amplified pixel diagnostic is 135,085.

Word paints floating-frame text after body text, while the candidate follows model block order. Consequently pypdf's content-stream order differs although the actual geometric word/character order is identical. `compare.py --allow-content-differences` records that mismatch; `inspect_frames.py` separately requires identical non-whitespace characters in geometric order and measures their origins. A mismatch is not silently relabeled as equal normalized stream text.

### Rotated text and barcodes in the margins

One page contains two PNG Code 39 barcodes inside native DrawingML text frames, rotated 90° and 270°. Additional real text is rotated 90°, 270° and 45°, including a diagonal frame with fill/border. The entire frame's contents rotate about its center using PDF transforms; barcode bars and text are not approximated through character stacking or pre-rendered page images.

| Page / content | Word and engine result |
|---|---|
| 1, ordinary body and bands | Identical non-whitespace character sequence in geometric order; max origin displacement x = 0.458 pt, vertical = 0.141 pt |
| 1, 90°/270° text | Same text and baseline directions; max origin differences x = 0.150 pt, vertical = 0.378 pt across the two orientations |
| 1, left barcode | Full-page decode returns `NF20260057`, orientation +90° in both PDFs |
| 1, right barcode | Full-page decode returns `NF20260057`, orientation −90° in both PDFs |
| 1, 45° text/frame | Visually aligned text and frame; candidate preserves the exact source phrase as searchable PDF text, while this Word export paints that phrase as graphics |

The diagonal source phrase is “Tekst onder vijfenveertig graden”. It is absent from both pypdf and pdfplumber text extraction of the Word export, despite being visible in the raster; the candidate retains all source characters under the correct −45° PDF text matrix. There is therefore no Word character-origin metric for this phrase. `--allow-graphic-reference-text` explicitly verifies that the extra candidate characters are exactly the source's one 45° frame, while retaining strict checks for all remaining angles. This exception is specific and visible in the JSON, not a generic permission to ignore missing text.

Both barcodes decode at 300 dpi from complete Word and candidate pages without cropping away frame/quiet-zone problems. The candidate has no glyph outside the physical page. The 144-dpi pixel diagnostic is 20,756. Differences in Word's graphics export/image resampling and font rounding remain; this is not raster identity or a general guarantee for every barcode type, font or size.

The initial legacy VML rotation probe revealed that Word can rotate a rectangle while retaining horizontal text or reflowing it into a narrow area. The importer now rejects unqualified VML rotation/text-flow combinations. The successful rotated proof uses explicit native DrawingML rectangle transforms instead. This distinction follows the source representation; the engine does not rewrite source documents into a different layout merely to get a closer score.

## Shared engine, concurrency and limits

`PdfTextBox` is a neutral immutable block with physical dimensions, a page or current-flow-cursor anchor, clockwise center rotation, padding, fill/border, paragraphs and vertical alignment. Inline PNG images use the existing paragraph/image model. `PdfBoxWrap.NONE` leaves body space unchanged. For unrotated `TOP_BOTTOM` blocks in `PdfFlow`, per-call exclusion intervals move intersecting line boxes below a frame while retaining usable space above it. Oversized content or a rotated border outside the physical page raises `CONTENT_OVERFLOW`; there is no clipping, shrinking or autofitting fallback. Paragraph anchoring means the current block flow cursor; generalized source-anchor relocation and overlap reconciliation across breaks are not implemented.

Internal layout groups hold transforms without exposing PDFBox types. The writer recursively paints bounded frame contents with saved/restored graphics state. Fonts, image objects, layout cursors, exclusion areas and PDF documents remain private to each render. No global document/font/image cache was added. Text/image/paragraph limits include frame content; each frame permits 1–128 paragraphs.

One regression uses **the same `PdfEngine` and shared immutable font snapshots for 12 requests on four threads**. Each request has its own rotated text marker, fill and uniquely colored PNG inside the frame. Reopened PDFs must contain only the expected marker and decode the correct per-request image pixels. Additional checks cover actual Java 17 frame construction/rendering, overflow, rotated page extents, invalid geometry/policies, wrapped-frame pagination/page budgets and rejected VML rotation/DrawingML flips/vertical text directions. This verifies concurrent correctness for these cases, not throughput, hard deadlines or hostile-input isolation. The test-only DOCX reader itself remains request-scoped; its parser context is not advertised as a shared concurrent facade.

The qualified source profile includes rectangular VML frames with explicit physical dimensions, unrotated top/bottom wrapping, and page-relative native DrawingML text frames with fixed rectangles and horizontal text before the frame transform. Arbitrary VML shapes, AlternateContent fallback selection, legacy rotation/text-flow combinations, square/tight side wrapping, general multi-page anchoring, frame splitting/autofitting, nested frames and table/page-band floating frames remain unimplemented. Combining wrapped frames with body tables or adding retroactive page exclusions fails explicitly. Regular inline images in page bands are qualified; first/even page masters remain future work. Production DOCX/ODT readers and PDF/A/UA/X qualification are unchanged future work.

## Reproduce and verify

Prepare caller fonts using the [letter guide](README.md#run-the-own-engine-and-compare). Generate sources before Word export:

```sh
python3 tools/pdf-evaluation/own-engine/build_frame_fixtures.py build/own-engine-frames/bands/source.docx --kind bands
python3 tools/pdf-evaluation/own-engine/build_frame_fixtures.py build/own-engine-frames/frames/source.docx --kind frames
python3 tools/pdf-evaluation/own-engine/build_frame_fixtures.py build/own-engine-frames/rotated/source.docx --kind rotated
```

Use `export_word.ps1 -Source <source.docx> -Output <new-reference.pdf>` on a machine with Word. It reads sources read-only, disables macros, refuses overwriting a reference and closes its private application. The reference names above identify this run; use fresh names for deliberate regeneration.

```sh
./gradlew :nuaforma-pdf:test
./gradlew :nuaforma-docx-pdf:test --tests 'dev.nuaforma.docx.pdf.ownengine.*'
./gradlew :nuaforma-docx-pdf:renderOwnEngineDrawingProofs
```

The aggregate render task runs `renderOwnEngineBands`, `renderOwnEngineFrames` and `renderOwnEngineRotated`. Portable tests regenerate sources using standard-library Python and do not need Word or fake its output.

For each kind, use `compare.py <reference.pdf> <own-engine.pdf> <comparison-directory> --source <source.docx> --font-dir build/own-engine-proof/fonts --word-bounds`, adding `--poppler-dir` when needed. The bands comparison is strict. Frames/rotated comparisons use `--allow-content-differences` for the documented content-stream order/Word graphics differences, plus the stricter rotation-aware inspection:

```sh
python3 tools/pdf-evaluation/own-engine/inspect_frames.py build/own-engine-frames/frames/word-reference-v4.pdf build/own-engine-frames/frames/own-engine.pdf build/own-engine-frames/frames/geometry build/own-engine-frames/frames/source.docx --poppler-dir <poppler-directory>
python3 tools/pdf-evaluation/own-engine/inspect_frames.py build/own-engine-frames/rotated/word-reference-v3.pdf build/own-engine-frames/rotated/own-engine.pdf build/own-engine-frames/rotated/geometry build/own-engine-frames/rotated/source.docx --poppler-dir <poppler-directory> --decoder-directory <approved-zxing-cpp-directory> --allow-graphic-reference-text
```

`inspect_frames.py` verifies reference provenance, page counts, geometric character content/order separately per rotation, physical bounds and optional full-page barcode values/orientations. Repeat for `bands` without the decoder/graphics exception. The scanner version is recorded. The candidate's 45° phrase must match the source exactly; no character-position bound is claimed against absent reference text.

Run `./gradlew build verifyEmbeddedConsumers verifyLibraryPublication` for the complete repository/local library checks, and rerender the earlier proofs for regression comparison. No new product or QA dependency was installed. The existing free decoder and runtime tools were reused. No changes are needed in Playground/server production wiring, existing release tags or published libraries. Preserve needed ignored evidence before eventual post-merge worktree cleanup.

The recorded final run combined the full build, all eight proof renders, local publication verification and five independent Java/Kotlin consumers: **successful in 13m 58s**, with **555 JVM tests and zero failures/errors/skips**. The library tests use Java 17 and server tests use Java 25. The standalone PDF-engine consumer actually rendered a PDF; the existing production-PDF consumer retained its separate missing-runtime/DOCX checks. All 90 final comparison PNGs across 18 page pairs were byte-identical to the visually reviewed versions, including the original letter/report/advanced-table regressions and these five new pages. Source/reference provenance, final candidate hashes and full-page 300-dpi barcode scans were refreshed for the final artifacts. All expected font resources in the six new reference/candidate PDFs are embedded Liberation Sans faces. Nothing was staged, committed, pushed or remotely published.

The source representations and rotation distinctions can be checked against Microsoft's [VML positioning/rotation description](https://learn.microsoft.com/en-us/windows/win32/vml/web-workshop---how-to-use-vml-on-web-pages----positioning-shapes), [VML wrapping values](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.vml.wordprocessing.wrapvalues?view=openxml-3.0.1) and [Word text-box rotation guidance](https://support.microsoft.com/en-us/word/rotate-text-in-word). Actual exported references and independent rasters determine the measured results here.
