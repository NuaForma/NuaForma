# Consistent borders with advanced table layout

Date: 2026-10-25. Parent: [#57](https://github.com/NuaForma/NuaForma/issues/57). Follow-up to [solid cell borders](CELL_BORDERS.md). The bounded combinations below are fully verified; broader table behavior remains outside this evidence.

## Boundary

The shared renderer permits one consistent visible stroke style per table when combining custom borders with horizontal/vertical spans, one nested level and whole-row pagination. Nested tables retain their own independent style and table identity. Shared-edge priority remains a neutral model policy; conflicting source styles still reject in the test-only DOCX adapter. The [row-fragment follow-up](BORDER_FRAGMENTATION.md) qualifies explicitly splittable TOP-aligned custom-border rows. Oversized non-splitting custom rows and non-TOP cells crossing fragments remain unqualified.

Span heights reserve the origin row's effective top inset. The final closing reserve also considers vertical spans ending in the last row, even when their origin occurs earlier. It is calculated once during measurement. Page headers retain their borders when copied; complete rows retain their source grouping. No public model, dependency, module, runtime baseline or production provider changes.

## Proofs

`build_border_combination_fixtures.py` reuses the reproducible earlier report, advanced-table and paper-format builders and adds explicit half-point solid cell borders. Four positive profiles cover merged cells (two pages), nested tables (two), a repeated-header report (three) and US Letter portrait (three). The Letter report also contains Greek, Cyrillic and Ukrainian text. A fifth, five-page Word source retains the long-row research case and must still reject in the engine during this increment. Genuine Word exports and provenance receipts are retained per source; source bytes from the committed builder must equal the bytes bound by those receipts.

`audit_border_combinations.py` checks full source character inventories, all leading repeated header rows, exact glyph order, font faces/sizes/colors, page/logo geometry and border footprints. Header repetition counts come from the independent Word reference, so a final page containing only text after the table does not incorrectly require a table header. A page without colored border vectors is accepted only when both PDFs have none; other colored primitive types reject explicitly. It retains the existing five-percent exact vector-area and quarter-point distance gates without masks or image alignment. Unit tests reopen actual PDFs to check span closure, absence of rules through merged interiors, nested colors/alignment, repeated headers, geometry parity with uniform tables and concurrent shared-engine requests. Unsupported conflicting advanced styles and oversized non-splitting custom rows retain explicit failure checks.

## Reproduction

Generate sources with `python3 tools/pdf-evaluation/own-engine/build_border_combination_fixtures.py build/own-engine-border-combinations`. Independently export each source using the retained Word exporter. Run `./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEngineBorderCombinations`, then run `compare.py` and `audit_border_combinations.py` for the four positive profiles. Review all page views before freezing their hashes. The split profile is a negative qualification fixture rather than a render target. The merged proof uses `word-reference-v2.pdf`: its cell-property order was corrected to place borders after span/merge properties and before shading, consistent with the [Open XML SDK cell-property model](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablecellproperties). The original source/export remains preserved separately; references were regenerated through Word, never edited by hand.

No REST/Playground behavior, publication set, release workflow, website or PDF/A/UA claim changes.

## Local comparison evidence

| Profile | Page | Visible glyphs | Maximum start/baseline delta (pt) | Maximum border-area delta | Samples beyond 0.25 pt |
|---|---:|---:|---|---:|---:|
| merged | 1 | 1921 | 0.229 / 0.160 | 4.133% | 0 |
| merged | 2 | 1584 | 0.229 / 0.140 | 3.926% | 0 |
| nested | 1 | 1264 | 0.171 / 0.200 | 4.136% | 0 |
| nested | 2 | 790 | 0.171 / 0.150 | 3.991% | 0 |
| pages | 1 | 1848 | 0.299 / 0.170 | 4.131% | 0 |
| pages | 2 | 2050 | 0.294 / 0.200 | 4.134% | 0 |
| pages | 3 | 1125 | 0.270 / 0.170 | 0.000% | 0 |
| letter | 1 | 1495 | 0.294 / 0.190 | 4.127% | 0 |
| letter | 2 | 1851 | 0.294 / 0.180 | 4.130% | 0 |
| letter | 3 | 1820 | 0.294 / 0.180 | 4.123% | 0 |

All ten side-by-side page views (containing the original-size Word and engine renders), overlays and difference views were reviewed. No clipping, overlap, broken spans, duplicate labels, displaced logos or changed page assignments were found. The fifty constituent PNG hashes are retained for final regression verification. The A4 report's third page correctly contains no table/borders.

| Profile | Source SHA-256 | Word reference | Word PDF SHA-256 |
|---|---|---|---|
| merged | d45c05d7482151480542c71b869c9a48601ea32cbd5c98485933e171e80fec64 | word-reference-v2.pdf | 224b2e6773a78d055819601e54f647ddb955b88fb5597c6f844e98face3d5fa3 |
| nested | 0f129bb2506f8f12b8af268b24e9ddeef072324c5dc48a6d773d8ddc41646481 | word-reference.pdf | 22e614d62a56bb78e2a0789edcaaa0cf9f9897d36e9cfbf87bb252b6c2f5cd90 |
| pages | 7cb48242fb7ed1ad59797f407873b077f996a758ee5ae9be9c7a4236e08519b4 | word-reference.pdf | f346170838787f158be7b28264e76d8d9c69ddad5b611df274391c42044538b2 |
| letter | 4f808876fa781539e4f5b6a7369db76a7850351d9da54fa4fb7dc2a7e6df917d | word-reference.pdf | c30e04ce0f74853ee2cd19ebf0ae9f5c7578e10d77c480b51af2e2f130d8d3ce |

## Project checks

The full `build verifyEmbeddedConsumers verifyLibraryPublication` plus every existing and new render aggregate succeeded in 19 minutes 12 seconds. All 705 JVM tests passed without failures, errors or skips, together with all five independent Java/Kotlin consumers. Java 17 library and Java 25 server boundaries remain unchanged. Final source/content/font/geometry/barcode/border audits passed across 40 documents / 165 page pairs. All 825 reviewed comparison PNGs remain unchanged, including the fifty new views. The four new profiles have identical normalized text and preserve all 15,748 visible glyphs; their maximum glyph-start/baseline differences are 0.300/0.201 pt. No mask or image alignment exception was introduced.
