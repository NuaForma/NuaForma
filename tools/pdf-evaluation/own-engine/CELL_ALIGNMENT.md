# Vertical alignment in fixed table cells

Date: 2026-10-25. Parent: [#57](https://github.com/NuaForma/NuaForma/issues/57). This is the first bounded increment of roadmap part 9. Automatic widths and per-cell border policies remain planned. DOCX import remains test-only.

## Model and qualification boundary

`PdfTableCell.verticalAlignment` reuses the neutral `PdfVerticalAlignment` enum with TOP as the compatible default. CENTER and BOTTOM place the complete cell contents within the natural row height after measuring all cells. A vertical span uses the sum of its occupied row heights; a horizontal span retains the resolved policy. Paragraphs and atomic nested tables move together. Page-band field resolution preserves the cell property. Adapters resolve source semantics; no DOCX/ODT nodes enter shared layout, and no dependency or extra module is added.

The test reader accepts direct `w:tcPr/w:vAlign` values top/center/bottom and rejects other values or malformed controls. A missing value on a vertical continuation retains the origin's policy; an explicit conflicting continuation rejects. Microsoft documents [the table-cell property](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablecellproperties.tablecellverticalalignment) and its containing Open XML type. The observed placement is established independently by real Word exports below.

The qualified profile uses fixed physical columns, existing uniform borders and cell margins, and natural heights derived from cell contents. A non-TOP cell must fit wholly within one page slice. Center/bottom alignment in a fragmented row or a merged cell crossing pages rejects explicitly before any PDF bytes are published. Existing TOP fragmentation remains supported. Exact/minimum row heights, alignment inherited from table styles, distributed text, rotated cell text and general merged-cell pagination are not added here.

## Controlled Word trials

| Profile | Content | Genuine Word pages |
|---|---|---:|
| ordinary | Eighteen rows with top/center/bottom labels alongside four-line content, repeated headers, logo and footer | 2 |
| merged | Three-row vertical merges with center/bottom alignment and a centered horizontal span | 1 |
| nested | Five atomic nested tables, inner and outer cell alignment, independent inner margins | 1 |

All sources use existing caller-provided Liberation Sans fonts and deterministic synthetic data. Each reference comes from Word `ExportAsFixedFormat` with a receipt identifying exact source/PDF bytes and Word build. The reader does not use reference coordinates or forced page assignments.

`audit_cell_alignment.py` independently checks the source body-character inventory, allowing only the source repeated header on subsequent pages. It compares every visible glyph's order, position, baseline, face, size and color against Word, checks all physical page bounds and source dimensions, and verifies the header logo geometry. Strict unmasked Poppler comparisons accompany these assertions. JVM tests reopen actual PDFs for top/center/bottom positions, both span directions, nesting, page-band copying, unsupported fragmentation and concurrent shared-engine use.

## Reproduction

```sh
python3 tools/pdf-evaluation/own-engine/build_cell_alignment_fixtures.py build/own-engine-cell-alignment
# Export each source with export_word.ps1 without replacing existing references.
./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEngineCellAlignment
# Repeat for merged and nested.
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-cell-alignment/ordinary/word-reference.pdf build/own-engine-cell-alignment/ordinary/own-engine.pdf build/own-engine-cell-alignment/ordinary/comparison --source build/own-engine-cell-alignment/ordinary/source.docx --font-dir build/own-engine-proof/fonts --word-bounds
python3 tools/pdf-evaluation/own-engine/audit_cell_alignment.py build/own-engine-cell-alignment/ordinary/source.docx build/own-engine-cell-alignment/ordinary/word-reference.pdf build/own-engine-cell-alignment/ordinary/own-engine.pdf build/own-engine-cell-alignment/ordinary/audit.json
```

No production conversion provider, REST/Playground behavior, publication set, release configuration, website or PDF/A/UA qualification changes in this increment.

## Verification result

The full `build verifyEmbeddedConsumers verifyLibraryPublication` run and all proof render tasks passed. The repository reports 679 JVM tests with zero failures, errors or skips, plus five independent Java/Kotlin consumers. Final independent comparisons and source/content/font/geometry/barcode audits passed across 33 documents / 152 page pairs; all 760 reviewed comparison PNG hashes remain unchanged. The generated JVM fixture resources match the exact DOCX bytes exported by Word. Previous intentional differences retain their existing references and audit policies; no comparison exception is introduced here.

## Recorded page-level evidence

All four pages were visually reviewed at their original render size through side-by-side, overlay and difference views. These views include the original Word/engine images; the 20 underlying PNG hashes are frozen after review. Body text, cell alignment, tables, logos, margins and repeated headers remain visually aligned. Unmasked differences retain small glyph/raster differences and thin inner-table border edges; no mask, content exemption, image shift or rescaling is used.

All three genuine Word references record build 16.0.20527. SHA-256 identities:

| Profile | Source DOCX | Word PDF |
|---|---|---|
| ordinary | `26678a59e31ce12aedb60458bc00009b075cf28af2892f2aa23cd9d00fd0283e` | `696d46ec1eb4b57829206c07fc9d4a21c80f579430f63f19b5219cf513a336e3` |
| merged | `7dd1fd09edc57fee6640d17271b1efc9b81c9058cc7a8898fcc8b3c8e3a4c6a4` | `c9c78931e71009aa08aee5739830ad4fe09812e61b962ad0e47e836e974d7e7e` |
| nested | `ce656d3e1e195cfe1782778f86f40934531f9aeed4c1be35b1fcc137f751894a` | `229d4af6ef8643228829116c5f5720a85f5040e5ed4e0ffbf61d71a0d3fc9b92` |

Every visible glyph retains its reading order, face, size and color. Source inventory and page assignments match. Per-page maximum position differences in points:

| Profile | Page | Visible glyphs | Start delta (pt) | Baseline delta (pt) |
|---|---:|---:|---:|---:|
| ordinary | 1 | 2084 | 0.180 | 0.160 |
| ordinary | 2 | 1585 | 0.132 | 0.180 |
| merged | 1 | 1112 | 0.252 | 0.140 |
| nested | 1 | 700 | 0.180 | 0.140 |
