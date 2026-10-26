# PDF engine golden images

These are reviewed **golden files**, generated directly from `PdfDocument` by `nuaforma-pdf`. No DOCX/ODT reader, Word, LibreOffice, REST service or source-format comparison participates. They preserve an accepted engine baseline, not independent proof of Word fidelity or support for every combination.

## Golden-file policy

Existing images must not be replaced, deleted or edited merely to make a test pass. Replacement requires a concrete reason (an approved intentional rendering change, a demonstrated baseline defect or an explicitly reviewed renderer/environment change), a documented visual comparison of old and proposed output, and explicit maintainer approval. Keep unaffected cases unchanged. Review fixture-source, font/logo, image and manifest changes together. Do not hand-edit PNGs. Normal builds/tests never regenerate or accept references automatically.

Initial creation and visual review were authorized by the maintainer. Subsequent regeneration creates **candidates**, never approved references. The SHA-256 inventory detects accidental image/input changes; updating the inventory is not approval of a changed image.

Initial review on 2026-10-25 covered all 17 pages for legibility, wrapping, alignment, clipping, overlap, tables, bands and page transitions. Two separate Java 17/PDFBox generations produced byte-identical PNGs and inventories. All PDFs were also rendered with independent Poppler and their page-layout overview inspected. The 150-DPI PDFBox PNGs are the references; Poppler renders and generated PDFs remain ignored review intermediates. Script coverage is a bounded rendering baseline, not a native-language editorial certification.

## Cases

| Fixture | Pages | Coverage |
| --- | ---: | --- |
| `a4-letter` | 1 | Logo, regular/bold/italic embedded fonts, accents, euro, wrapping, center/right alignment, hanging indent |
| `multipage-report` | 3 | Fixed table, repeated header, row transitions, images in header/footer, global page/total fields |
| `merged-nested-table` | 1 | Horizontal/vertical cell spans, one nested grid, fills and borders |
| `fragmented-table-row` | 2 | One long row fragmented across pages, repeated headers, final-line preservation |
| `lists-and-tabs` | 1 | Three list levels, restart, hanging continuation, styled markers, decimal tab and dot leader |
| `letter-rotated-frames` | 1 | US Letter portrait, positioned callout, fill/border, vertical centering, 90-degree searchable margin text |
| `letter-landscape-scripts` | 1 | US Letter landscape, Russian/Ukrainian/Greek, bounded unpointed Hebrew RTL with numbers |
| `mixed-paper-sections` | 3 | A3 portrait, US Legal portrait, A4 landscape, next-page section changes, continuing fields and edge markers |
| `paragraph-keeps` | 2 | Small-page boundary: heading moves with following kept paragraph and widow policy |
| `cell-alignment-borders` | 1 | Top/center/bottom cell alignment, competing edge widths/colors, fill and table indentation |
| `natural-line-spacing` | 1 | Supplied-font natural line boxes at single, 1.5 and double spacing |
| `anchored-image-wrap` | 1 | Paragraph-bound left/right images, two-sided rectangular wrapping around a central image, full-width resumption |
| `cropped-image-wrap` | 1 | Asymmetric source crop, explicit non-square output dimensions, rectangular wrapping on US Letter |

The two image-wrapping cases were added and visually reviewed on 2026-10-26 under the maintainer's authorization to implement and test roadmap part 10. All 17 existing reference PNGs retained their original hashes. These new references supplement the independent genuine Word comparisons; they do not replace them.

This first set is deliberately bounded. Existing semantic, negative, concurrent, Java-API and PDF/A conformance tests remain necessary. This set does not add Arabic shaping, general bidi, barcode decoding, PDF/A metadata validation or exhaustive combinatorial coverage. A visually identical PDF can still lose searchable text or conformance.

During initial fixture authoring, a title preceding the oversized split row caused the current conservative table-start policy to move the table to a fresh page, leaving a mostly empty title page. The isolated split-row fixture therefore starts with the table itself; the title-plus-oversized-row combination is not qualified by these goldens. No renderer behavior was changed or silently corrected through a baseline update.

## Reproduction and regression assertions

The fixture definitions are `src/test/kotlin/dev/nuaforma/pdf/PdfVisualFixtures.kt`. Fonts are the unchanged licensed Liberation Sans fixtures in `../fonts`; the logo is copied from the approved branding assets by `processTestResources`. `inputs.sha256` binds those inputs. Rendering uses PDFBox **3.0.8**, Java **17**, headless Java2D, `ImageType.RGB`, **150 DPI**, and subsampling disabled. The renderer's default rendering hints are retained. `pages.tsv` records the case, one-based page number, image dimensions and exact PNG SHA-256.

Run from the repository root, using the checked-in wrapper:

```sh
./gradlew :nuaforma-pdf:renderVisualFixtures
```

This creates PDFs, PNG candidates and inventories in the module's ignored `build/visual-candidate` directory and refuses an existing directory. To create a separate candidate set, use `-PvisualOutput=build/visual-review-2`. Paths are relative to the module. Output outside the real module build directory is rejected. Review every resulting page before manually installing any new golden; replacement of an existing golden additionally requires the approval above. Generated PDFs are review intermediates and are not golden resources.

`PdfVisualFixtureTest` checks inventory integrity, actual PDF content, exact page count/geometry and embedded fonts, then compares every rendered page using `dev.nuaforma:image-comparison-kotlin:5.0.0`. Its Java core arrives transitively. Pixel tolerance and allowed changed-pixel percentage are both **zero**, with grouping threshold 1 and no exclusions. The rendering configuration above is shared with candidate generation. A renderer/font/environment update is a reason to investigate a failed test, not to automatically loosen tolerance or replace a golden.

On mismatch, diagnostics under `nuaforma-pdf/build/visual-regression/<run>/<case>/page-<number>/` contain `expected.png`, the unmarked `actual.png`, `difference.png` and `comparison.txt` for each failing page. A new run-specific directory prevents stale diagnostics being mistaken for current results. The case also retains `actual.pdf`, including on structural assertion failures. The assertion message names the diagnostic directory. All failing pages within a case are checked before reporting their combined failure; no references are written. On a size mismatch the comparison report identifies both sizes; the library's result image is the actual image because a same-size difference overlay cannot be computed. Diagnostic-write errors are suppressed onto the original assertion, never substituted for it.

`PdfGoldenImagesTest` verifies that identical images pass without diagnostics, one changed pixel fails while preserving the original inputs and writing all diagnostics, and different dimensions fail. These negative controls establish the failure path; the eleven real engine fixtures establish regression coverage. This remains engine-only and does not replace the independent Word-reference trials, PDF/A validation, content or concurrency tests. Run the suite with `./gradlew :nuaforma-pdf:test`.

There is no production, Playground, publication or website behavior change.
