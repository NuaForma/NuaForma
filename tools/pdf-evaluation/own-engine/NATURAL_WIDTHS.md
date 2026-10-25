# Natural table column widths

Date: 2026-10-25. Parent: [#57](https://github.com/NuaForma/NuaForma/issues/57). Roadmap part 9, following [bordered row fragmentation](BORDER_FRAGMENTATION.md).

## Neutral policy and boundary

`PdfTable.widthPolicy` defaults to `PdfTableWidthPolicy.FIXED`, retaining existing constructor defaults and fixed-column behavior. The opt-in `FIT_CONTENTS` policy replaces preferred grid widths with the widest unwrapped paragraph in each column plus physical left/right cell padding. All rows contribute before pagination, including cells occurring on later pages. A new resolved table remains request-local; the source model is not mutated. Page-band copying retains the policy.

Sizing reuses the renderer's word/run/font measurement and accumulation order. Outward floating-point rounding ensures subtracting padding cannot narrow a measured line. Resulting widths are checked against physical layout limits and the containing frame. The renderer does not silently shrink text, clip content or introduce content-based wrapping to force an oversized natural table to fit.

This first policy supports direct text paragraphs without spans, nested tables, lists, indentation, controls, tabs, leading/trailing whitespace or RTL direction. Columns without measurable text reject explicitly. General AutoFit redistribution, preferred/percentage dimensions, fill-to-container behavior, spanning/nested natural widths and wrapping under width pressure remain planned. This is an additive draft-model policy, not a claim of complete Word AutoFit support. No new dependency, module, runtime baseline or production provider change.

## Test-only DOCX mapping

The adapter accepts an explicit `tblLayout` of `autofit` only with `tblW` and every `tcW` explicitly set to `auto`/`0`. A physical grid still supplies column count and preferred hints. Existing fixed tables retain their physical dimension checks. Other AutoFit preference combinations remain rejected. The source format's AutoFit algorithm is broader than this bounded mapping; see Microsoft's [WordprocessingML primer](https://download.microsoft.com/download/e/1/4/e14fb96f-83b8-4a2a-84db-7fa8acbe061a/Office%20Open%20XML%20Part%203%20-%20Primer.pdf).

## Proofs and reproduction

Generate sources with `python3 tools/pdf-evaluation/own-engine/build_natural_width_fixtures.py build/own-engine-natural-widths`. Export each source independently through Word and retain its provenance receipt. Run `./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEngineNaturalWidths`, then `compare.py` with source/font checks and `--word-bounds`, plus `audit_border_combinations.py` for each profile. Review every side-by-side, overlay and difference view before freezing hashes. Existing unmasked glyph, vector-area and quarter-point distance gates apply unchanged.

The A4 and Letter reports each contain forty body rows with repeated headers, solid cell borders, mixed regular/bold/italic runs, Greek/Cyrillic text, right-aligned currency amounts, a logo and PAGE footer. Their widest description occurs in the final row. Genuine Word exports each contain two pages.

Core tests reopen actual PDFs to check that oversized preferred hints can yield fitting natural tables, later wide cells move earlier column positions, fixed tables retain overflow behavior, page bands preserve the policy and shared-engine requests keep widths independent. Separate negative cases cover unsupported cell content and natural widths exceeding the physical/frame limits. No REST/Playground behavior, publication set, release workflow, website or PDF/A/UA claim changes.

## Qualification evidence

Both references are genuine Word exports from build `16.0.20527`. All four page pairs have identical normalized text, source inventories, glyph order and font faces/sizes/colors. All forty row codes occur once in each report; the table header repeats on the second page. Maximum glyph-start/baseline differences are 0.295/0.181 pt. Solid-border footprints pass the unchanged five-percent exact-area and quarter-point distance gates without masks or image alignment.

| Profile | Page | Visible glyphs | Maximum start/baseline delta (pt) | Maximum border-area delta | Samples beyond 0.25 pt |
|---|---:|---:|---|---:|---:|
| A4 | 1 | 973 | 0.281 / 0.160 | 4.115% | 0 |
| A4 | 2 | 414 | 0.218 / 0.140 | 4.113% | 0 |
| Letter | 1 | 919 | 0.294 / 0.180 | 3.973% | 0 |
| Letter | 2 | 468 | 0.218 / 0.130 | 4.107% | 0 |

All four original-size side-by-side views, overlays and difference views were reviewed. No clipping, overlap, lost text, changed row/page assignments or altered styles were found. Twenty constituent PNG hashes are retained for final regression verification.

| Profile | Source SHA-256 | Word PDF SHA-256 |
|---|---|---|
| A4 | e88ff455a9c76f0b3f5039731a435b458c0ae7e2fddc508ae01b4de9d4433063 | 3f0d701f118e93c68d6bf4f9e373014524bdb3f7dee8c495878a7be5375e56a3 |
| Letter | 3848d64e18c683000e98891e548ce3b0a460a11317655cc5f9b85479218de02b | 1b4b6571f3ec04e265d7c493bc137fa8047d110b0fa3fbedad61fb22e1300298 |

The full `build verifyEmbeddedConsumers verifyLibraryPublication` plus every render aggregate succeeded in 21 minutes 36 seconds. All 719 JVM tests passed without failures, errors or skips, together with all five independent Java/Kotlin consumers. Final source/content/font/geometry/barcode/border audits passed across 44 documents / 179 page pairs. All 895 reviewed PNGs remain unchanged, including the twenty new views. The new profiles preserve all 2,774 visible glyphs. The generated test-resource sources exactly match the source bytes bound to their Word receipts. Java 17 library and Java 25 server boundaries remain unchanged.
