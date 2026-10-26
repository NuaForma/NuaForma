# Paragraph pagination fidelity trial

Issue [#57](https://github.com/NuaForma/NuaForma/issues/57), 2026-10-24. First increment in the [prioritized roadmap](../../../docs/architecture/own-engine-roadmap.md). The shared `nuaforma-pdf` engine now implements body-paragraph keeps, widow/orphan control and explicit page-break-before. The DOCX reader remains test-only. Production conversion, public provider contracts, current PDF/A capabilities and release wiring are unchanged.

## Source and independent reference

`build_pagination_fixture.py` creates one deterministic ten-page A4 DOCX, using the existing report's logo, header/footer and caller-supplied Liberation Sans font family. It contains five independent boundary cases. Source paragraph properties determine pagination; the engine does not receive expected page assignments. Fill paragraphs use 16-point exact lines. Narrow target paragraphs wrap four distinct control words onto four physical lines. Two heading styles form a `basedOn` chain, with keep properties inherited from the base style. Word's widow-control default is explicitly disabled in this fixture's document defaults and selectively enabled by the two widow/orphan cases.

The reference is a genuine Microsoft Word `ExportAsFixedFormat` PDF, version `16.0`, build `16.0.20430`. The private reference process uses the existing `export_word.ps1`, with macros disabled and read-only source. Word is a QA reference tool, not a product dependency. The retained reference is `build/own-engine-pagination/word-reference-v2.pdf`; the earlier eight-page reference predates the fifth case and must not be compared with the final source.

| Evidence | SHA-256 |
|---|---|
| Final source DOCX | `74d525588d71489678735905e175f4874241eda7f48ed53e576b6a73873ccfa0` |
| Word reference PDF | `186d800d20182255578c98d794a9107fd54f12e73157181713a7461a97342b7c` |
| Final engine PDF, this run | `75dab2e9add1a790db8f308eacc3c30e770b2445467ca46eacfed51539df7b06` |

Generated documents, full candidate hashes, Word provenance JSON, per-page geometry, raster pairs, overlays and differences remain under ignored `build/own-engine-pagination/`. The comparison verifies the source hash against the Word-export receipt before accepting results. Fonts are caller-supplied; the same source fonts are used for each renderer. No new font, product dependency or QA package is introduced.

## Results per page

All ten page pairs have identical normalized text and the same geometric word order. All 184 filler markers and all 20 target markers occur exactly once in the engine output. Both outputs retain the repeated logo, header and correct page numbers. There are no initial blank pages and no extracted glyphs outside the physical pages. Every page pair was visually reviewed with independent Poppler 26.07.0 rendering at 144 dpi, without aligning or rescaling the compared page content. Font resources use embedded Liberation Sans faces.

| Page | Boundary behavior shared with Word | Lines, Word / engine | Max absolute baseline difference, pt | Max word-start difference, pt | Changed pixels above 24/255 |
|---:|---|---:|---:|---:|---:|
| 1 | Two linked headings move with the following start | 41 / 41 | 0.20 | 0.132 | 2.445% |
| 2 | Both headings and G01–G04 together | 9 / 9 | 0.14 | 0.132 | 0.267% |
| 3 | Four-line kept paragraph moves whole | 41 / 41 | 0.20 | 0.144 | 2.448% |
| 4 | K01–K04 remain on one page | 7 / 7 | 0.14 | 0.132 | 0.227% |
| 5 | A single available line cannot orphan O01 | 42 / 42 | 0.20 | 0.162 | 2.507% |
| 6 | O01–O04 move together | 7 / 7 | 0.14 | 0.132 | 0.238% |
| 7 | W01–W02 stay; W03 moves to prevent a lone W04 | 42 / 42 | 0.20 | 0.234 | 2.424% |
| 8 | W03–W04 complete the paragraph | 5 / 5 | 0.14 | 0.132 | 0.157% |
| 9 | Heading stays with N01; following paragraph may split | 42 / 42 | 0.20 | 0.132 | 2.578% |
| 10 | N02–N04 continue without moving the heading | 6 / 6 | 0.14 | 0.179 | 0.191% |

Word-box top differences stay below 0.148 pt; word-end differences stay below 0.198 pt. The remaining raster differences are small font positioning/rendering differences, not missing text or changed pagination. Pixel percentages are threshold diagnostics over whole pages, **not** a document-similarity score or a general pixel-perfect guarantee. Long filler pages naturally contribute more differing text-edge pixels than mostly empty continuation pages.

## Implemented semantics and limits

- `PdfParagraphFormat.keepWithNext` looks ahead through a heading/paragraph chain. Intermediate paragraphs are kept whole in this qualified subset. The final following paragraph only needs its smallest legal start: one line normally, two with widow control (all three for a three-line paragraph), or all lines with `keepLines`. The fifth Word case specifically prevents accidentally keeping the entire final paragraph with its heading.
- `keepLines` moves the paragraph whole when it does not fit the remaining space. A kept paragraph larger than a fresh body area rejects with `CONTENT_OVERFLOW`; it is never clipped, shrunk or silently split.
- `widowControl` avoids a single first or final line across pages. For a three-line paragraph it may move the paragraph whole. An impossible combination, such as a three-line paragraph with only two lines of fresh-page capacity, rejects explicitly. The bounded prototype does not relax the policy to force success.
- `pageBreakBefore` wins over a preceding keep. At the initial page top it does not create a blank page. Repeated source breaks at new case titles produce pages 1/3/5/7/9 without hardcoded renderer page numbers. Page breaks in bands or frames reject explicitly. The single-page default still rejects a necessary additional page.
- The neutral model defaults flags to false. The test DOCX reader resolves document defaults, paragraph-style ancestry and direct properties, including explicit false overrides. It applies Word's implicit widow-control default of true when omitted. Invalid toggle values reject before rendering.
- A keep can target an image or the first atomic table data row with its repeated headers. Keeps targeting a splittable or vertically spanning first data row, or a frame, reject as unqualified. Paragraph pagination properties inside table cells reject; integration with cell/row fragmentation is a later qualification item. Keeps combined with active wrapped-frame exclusions also reject. Bands and frames already have bounded, non-paginating content areas; their keep flags do not cause a new page.
- A protected chain minimum must fit one fresh body area. Long intermediate paragraphs in keep chains are not yet allowed to span pages. Table/frame combinations, explicit inline page-break runs, automatic line spacing, section breaks, first/even/odd page masters and arbitrary real-world DOCX input remain outside this increment.

All measurement caches and keep-chain progress are local to one render call. Concurrent tests share one engine and immutable font inputs across eight requests on four threads, render distinct request-local models, verify distinct request text and check page allocation. This is correctness evidence, not a throughput estimate or proof of hosted memory isolation. The approved in-process execution direction is recorded in the [design](../../../docs/architecture/conversion-engine.md#9-execution-resources-and-capacity); aggregate production qualification remains future work.

## Verification and reproduction

Run from the repository root with the normal Java 17 library / Java 25 server toolchains:

```sh
python3 tools/pdf-evaluation/own-engine/build_pagination_fixture.py build/own-engine-pagination/source.docx
# Export this exact source through Microsoft Word using export_word.ps1.
# Prepare the same caller font directory used by the earlier proofs.
./gradlew :nuaforma-docx-pdf:renderOwnEnginePagination
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-pagination/word-reference-v2.pdf build/own-engine-pagination/own-engine.pdf build/own-engine-pagination/comparison --source build/own-engine-pagination/source.docx --font-dir build/own-engine-proof/fonts --word-bounds --poppler-dir <poppler-directory>
./gradlew build verifyEmbeddedConsumers verifyLibraryPublication
```

The Word exporter refuses to overwrite an existing reference. Use a new output name after source changes and update the comparison path. The comparison helper now normalizes Poppler's zero-padded page filenames for documents with ten or more pages; stale eight-page rasters cannot stand in for the newly rendered reference.

`PdfParagraphPaginationTest` covers keep chains, a partially continuing final paragraph, widow/orphan boundaries, the combined heading/three-line-widow case, explicit-break precedence, atomic table/image targets, oversized/impossible keeps, page limits, rejected frame/band combinations and concurrent rendering. `OwnEnginePaginationTest` verifies reader inheritance/defaults/false overrides, invalid toggles, rejected cell policies, all source markers and the ten expected page boundaries in actual PDF bytes.

The final delivery command completed successfully in **16m 20s**, including the full repository build, all nine proof renders, local publication verification and five independent Java/Kotlin consumers. The final reports contain **569 JVM tests, zero failures/errors/skips**. Libraries run on Java 17 and server tests on Java 25. The eleven paragraph-layout tests and three new DOCX reader/PDF tests are included in that total.

All 90 comparison PNGs from the earlier eight proofs (18 page pairs), plus all 50 PNGs from this ten-page proof, are byte-identical to their visually reviewed versions. Advanced-table content/geometry audits and frame/rotation audits also pass on the final output bytes, including full-page 300-dpi barcode decoding. Final source/reference/candidate receipts were refreshed. Both pagination PDFs have embedded Liberation Sans regular/bold fonts and no extracted glyphs outside the pages. PDF IDs/metadata may change on rerender; the recorded candidate hash identifies this run rather than promising binary identity on every run. This build duration is verification time, not a document-conversion throughput measurement.

The source-only neutral paragraph API gains the four keep/break flags; existing provider APIs and the Playground UI are unchanged. The reader is test-only and the production provider is unchanged. No current publisher, tag, image, release, site content or PDF/A claim changes. Work remains on the branch targeting `feature/own-pdf-engine`; the worktree is retained until authorized merge and evidence-preserving cleanup. The next proposed increment is **lists and numbering**.

Source semantics are checked against Microsoft's [keep-next documentation](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.keepnext?view=openxml-3.0.1) and [widow-control documentation](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.widowcontrol?view=openxml-3.0.1). The actual Word reference determines this trial's measured behavior.
