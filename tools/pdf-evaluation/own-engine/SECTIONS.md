# Mixed page-sequence proof

Date: 2026-10-25. Parent: [#57](https://github.com/NuaForma/NuaForma/issues/57). This is the next-page range increment of roadmap part 6, alongside the [standalone paper matrix](PAGE_FORMATS.md).

## Shared model and budgets

`PdfPageSequence(page, blocks, flow)` is a resolved physical page policy over an immutable content range. `PdfDocument(pageSequences, maxPages)` starts each nonempty range on a fresh page. The model contains no DOCX section properties or ODT-specific types. Future adapters can resolve their source-specific section/master inheritance into the same ranges. Continuous, odd/even-page and column transitions are outside this increment.

Existing `PdfDocument(page, blocks, flow)` construction retains its original single-policy behavior, overloads and page limit. Its `page`, `blocks` and `flow` getters remain first-sequence views; callers handling multiple ranges must use `pageSequences`. The default global limit for the new list constructor is 32 pages, configurable down to the number of ranges. The document accepts 1–32 nonempty ranges, at most 1,000 total body blocks, 2,000 paragraphs, 100,000 text characters and 2,000 tab controls under the existing counting rules. Image/font/generated-leader limits remain document-wide. A range's own flow limit can further restrict it; a transition never resets global budgets.

The writer loads fonts/images into one request-local PDF document. It lays out each range using its own dimensions/margins and resolves PAGE fields using a global page offset. There is no PDF concatenation, external conversion process or shared mutable page/numbering state. The result becomes visible only after the entire document succeeds. Tests cover page counts and numbering beyond page 10, late-range missing fonts, immutable collection snapshots, aggregate limits, Java callers and concurrent mixed-size requests.

## DOCX translation and Word trial

The synthetic report has A4 portrait, Letter landscape and A3 portrait ranges with flowing text, all 28 fixed-table rows in each range, repeated table headers, Greek/Ukrainian text, a page-band logo and PAGE footer fields. Only the first range specifies header/footer references; later ranges inherit those references, then resolve each band's extent against the current margins. The text of each closing paragraph belongs to the range it closes.

The test reader resolves paragraph-level `w:pPr/w:sectPr` properties over the preceding content range and final body-level `w:sectPr` properties over the final range, following the [OOXML section-scope rule](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.sectionproperties). Only absent/default or explicit `nextPage` section types are accepted. Every range still needs explicit physical dimensions and margins. Unsupported section properties, empty final ranges, missing geometry, continuous/odd/even/column transitions and non-default bands reject. Paragraph contextual-spacing adjacency resets at a range boundary; source numbering remains resolved over the whole body.

Independent QA uses the real Word export and receipt, the existing unmasked Poppler comparisons, plus `audit_sections.py`. PDFium checks the complete body word/punctuation inventory within each physical range, allowing only exact extra copies of repeated table headers. It also checks actual dimensions, ordered range/page assignments and continuous PAGE fields. pdfplumber independently checks all line content/wrapping and origins/baselines, glyph bounds, and the single logo's physical geometry on every page.

## Reproduction and limits

```sh
python3 tools/pdf-evaluation/own-engine/build_sections_fixture.py build/own-engine-sections/source.docx
# Export these exact source bytes using export_word.ps1 and retain the receipt.
./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEngineSections
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-sections/word-reference-v2.pdf build/own-engine-sections/own-engine.pdf build/own-engine-sections/comparison --source build/own-engine-sections/source.docx --font-dir build/own-engine-proof/fonts --word-bounds
python3 tools/pdf-evaluation/own-engine/audit_sections.py build/own-engine-sections/source.docx build/own-engine-sections/word-reference-v2.pdf build/own-engine-sections/own-engine.pdf build/own-engine-sections/audit.json
```

Word is an external QA oracle, not a normal build dependency. The first archived source/reference used one language tag for the two-script sample; v2 assigns Ukrainian and Greek to separate runs. Only v2 matches the current source bytes.

This does not qualify first/even/odd masters, numbering restarts, continuous layout, columns, every table/frame/script combination across transitions, production DOCX/ODT import, PDF/A or PDF/UA. Source-specific inheritance remains the adapter's responsibility. No dependency, package, production provider, REST/Playground behavior, publication/release workflow or website change is introduced.

The full repository build and local publication checks passed with 641 JVM tests and zero failures/errors/skips, alongside all five independent Java/Kotlin consumers. The published-library set remains unchanged; the new API is in the experimental source-only shared artifact.

Final source/content/font/geometry/barcode audits passed across 23 Word-reference documents / 79 page pairs. All 395 comparison PNGs, including this increment's 45, retained their visually reviewed SHA-256 values after the final build rerendered every candidate. Existing proofs remain unchanged.

## Measured nine-page result

Word 16.0 build `16.0.20430`, with independent Poppler 26.07.0 renders at 144 dpi. All nine side-by-side page pairs were inspected at full resolution, followed by contact sheets of all 45 Word/engine/overlay/difference/side-by-side PNGs. The page bands, table headers and ending text remain intact through both format transitions; no clipped glyph, missing text, overlap or unintended blank page was observed. All line text/wrapping and normalized page text match Word.

| Page | Range and physical size (pt) | Lines | Max line-start delta (pt) | Max baseline delta (pt) | Changed pixels (%) |
|---:|---|---:|---:|---:|---:|
| 1 | 1: 595.3 x 841.9 | 38 | 0.093 | 0.140 | 2.048 |
| 2 | 1: 595.3 x 841.9 | 42 | 0.093 | 0.200 | 2.533 |
| 3 | 1: 595.3 x 841.9 | 20 | 0.093 | 0.130 | 1.595 |
| 4 | 2: 792.0 x 612.0 | 24 | 0.060 | 0.170 | 1.346 |
| 5 | 2: 792.0 x 612.0 | 29 | 0.060 | 0.160 | 1.480 |
| 6 | 2: 792.0 x 612.0 | 28 | 0.060 | 0.180 | 1.571 |
| 7 | 2: 792.0 x 612.0 | 18 | 0.060 | 0.130 | 1.479 |
| 8 | 3: 841.9 x 1190.55 | 60 | 0.060 | 0.210 | 1.649 |
| 9 | 3: 841.9 x 1190.55 | 30 | 0.060 | 0.240 | 1.349 |

Changed-pixel fractions are diagnostics, not similarity/acceptance scores. Line-anchor measurements do not bound every individual glyph advance. The reference/candidate logos have the same physical geometry within 0.1 pt on all nine pages.

Current DOCX SHA-256: `0c9bc85a7244ef3003e18deedb46770f10f1719976745e4353ec3852e8029a32`. Word reference `word-reference-v2.pdf` SHA-256: `16aafb43598bbc2d4858ec10a760c37c212494bc2b973cb72f7bc1cb25f5e799`. The source, reference, receipt, candidate and comparison evidence remain in the ignored `build/own-engine-sections` directory.
