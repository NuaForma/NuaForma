# Global page-number and page-count field proof

Date: 2026-10-25. Parent: [#57](https://github.com/NuaForma/NuaForma/issues/57). This is the global decimal PAGE/NUMPAGES increment of roadmap part 8, over the [page-band variants](PAGE_MASTERS.md). The subsequent [sequence-numbering trial](NUMBERING.md) adds bounded decimal starts, continuation and section totals. General field expressions remain open.

## Resolution and bounded layout

The neutral model adds `PdfPageCount(style)` alongside `PdfPageNumber(style)` under `PdfPageField`. Body pagination completes for every physical range before selected first/even/odd bands are resolved. The total includes single-page ranges without flow. A second bounded pass measures actual field values and paints bands before existing body elements. Because margins reserve fixed areas, there is no circular body relayout or provisional total. An overflowing resolved band fails without publishing output bytes. Existing page/body positions and output ordering are preserved.

Fields are allowed in band paragraphs and table cells; body, body-table and frame uses reject explicitly. This increment reserved two text-budget units per field under the 32-page ceiling; the numbering follow-up reserves four for PAGE to accommodate starts through 9999, while totals retain two. Field-only documents cannot bypass the aggregate budget. State, fonts, totals and selection remain request-local. JVM checks reopen actual PDFs for mixed-size ranges, first/even/odd/table bands, single-page range contributions, two-digit totals, final-value overflow, invalid contexts/fonts, Java use and concurrent requests with different counts.

This trial uses plain `w:fldSimple` PAGE and NUMPAGES instructions in bands and ignores stale cached values. Cached visual result formatting must match the resolved paragraph style; language metadata and explicit simple-number direction remain preserved independently, including the existing Hebrew proof. The follow-up adds SECTIONPAGES. Instruction switches, INCLUDETEXT and body fields remain rejected. The shared model still accepts any resolved caller-supplied field style.

That formatting bound is deliberate: the first experiment had a 9-pt cached header-field result in an 11-pt paragraph. Word refreshed the field to approximately 11 pt during export, retaining adjacent 9-pt literals. The initial engine therefore differed by approximately 3.3 pt at the right-aligned line start. The retained first source/reference document that observation. The qualified source uses the paragraph's 11-pt header field style and 9-pt Footer style. A regression test rejects the inconsistent cache style rather than silently promising preservation. Reference PDF bytes are never patched.

## Genuine Word report

The report has 13 pages: A4 portrait pages 1–3, Letter landscape pages 4–7, then A3 portrait pages 8–13. It retains logos, inherited first/even/odd stories, Greek/Ukrainian text, report paragraphs and tables. The last table has 140 rows, crossing several pages. Header and footer both show `Pagina X van 13`; source caches deliberately contain `999`. The final total must replace cached values and stay correct on pages 9, 10 and 13.

Word 16.0 build 16.0.20527 exported the exact source through `ExportAsFixedFormat`. The initial failed-format experiment remains `source-v1.docx` / `word-reference.pdf`; the qualified reference is `word-reference-v2.pdf`, with its provenance receipt. Source, PDFs, rasters, font hashes and independent audit data remain ignored under `build/own-engine-fields/`.

`audit_page_masters.py` independently resolves source PAGE/NUMPAGES stories using the actual PDF page count. It checks complete source/body/story inventories, range/page assignment, physical line text/wrapping and logo geometry, plus band font face and size against Word. It does not trust the model's total or cached results. The unmasked Poppler comparison checks every normalized page text and raster without shifting, scaling or content exceptions.

## Reproduction and remaining scope

```sh
python3 tools/pdf-evaluation/own-engine/build_fields_fixture.py build/own-engine-fields/source.docx
# Export the exact source through export_word.ps1 without overwriting an existing reference.
./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEngineFields
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-fields/word-reference-v2.pdf build/own-engine-fields/own-engine.pdf build/own-engine-fields/comparison --source build/own-engine-fields/source.docx --font-dir build/own-engine-proof/fonts --word-bounds
python3 tools/pdf-evaluation/own-engine/audit_page_masters.py build/own-engine-fields/source.docx build/own-engine-fields/word-reference-v2.pdf build/own-engine-fields/own-engine.pdf build/own-engine-fields/audit.json
```

The prior builder exposes its entries without changing its default DOCX bytes. This remains bounded source qualification, with no new module/dependency, production DOCX/ODT provider, REST/Playground change, publication, release workflow or PDF/A/UA claim. Broad field formatting, cross-references and contents require separate source semantics and verification; the [numbering follow-up](NUMBERING.md) supplies the bounded decimal section-specific profile.

## Measured page evidence

Qualified source SHA-256: `b4dab45e5191cb2f6e76b1235c17d058cc1a62894338a94e7dc4165e29c2af82`. Word reference SHA-256: `5367414cd43626ff636e8bda08ebc11590d8e81c5e2d25d481bc568bb6edb405`. Independent Poppler renders use 144 dpi; changed-pixel fractions measure differences above 24/255 and are diagnostics, not document-similarity scores.

| Page | Range | Story | Changed pixels % | Max line start delta pt | Max baseline delta pt | Max band font size delta pt |
|---|---:|---|---:|---:|---:|---:|
| 1 | 1 | first | 2.078 | 0.042 | 0.140 | 0.040 |
| 2 | 1 | even | 2.550 | 0.040 | 0.200 | 0.040 |
| 3 | 1 | default | 1.618 | 0.039 | 0.130 | 0.040 |
| 4 | 2 | first | 1.375 | 0.060 | 0.170 | 0.040 |
| 5 | 2 | default | 1.510 | 0.060 | 0.160 | 0.040 |
| 6 | 2 | even | 1.591 | 0.073 | 0.180 | 0.040 |
| 7 | 2 | default | 1.509 | 0.060 | 0.130 | 0.040 |
| 8 | 3 | first | 1.665 | 0.060 | 0.210 | 0.040 |
| 9 | 3 | default | 1.715 | 0.060 | 0.210 | 0.040 |
| 10 | 3 | even | 1.722 | 0.081 | 0.210 | 0.040 |
| 11 | 3 | default | 1.706 | 0.060 | 0.210 | 0.040 |
| 12 | 3 | even | 2.393 | 0.081 | 0.230 | 0.040 |
| 13 | 3 | default | 0.226 | 0.060 | 0.120 | 0.040 |

All 13 side-by-side page pairs were visually reviewed at original resolution, followed by contact sheets covering all 65 Word/engine/overlay/difference/side-by-side PNGs. No clipped glyph, lost row, page-band overlap or unintended blank page was observed. Complete normalized text, physical line wrapping and page assignment match Word. Reviewed hashes are retained for the final regression check.

Final delivery verification passed the full repository build, library publication checks and five independent Java/Kotlin consumers: 659 JVM tests, zero failures/errors/skips. All final source/content/font/geometry/barcode audits passed across 26 documents / 110 page pairs / 550 unchanged reviewed PNGs, including the prior Hebrew and page-master profiles.
