# First even and odd page-band proof

Date: 2026-10-25. Parent: [#57](https://github.com/NuaForma/NuaForma/issues/57). This is the bounded first/even/odd increment of roadmap part 7, over the [neutral page sequences](SECTIONS.md).

## Model and source semantics

This trial uses continuous numbering without padding. The later [sequence-numbering trial](NUMBERING.md) qualifies displayed-number parity after explicit restarts, SECTIONPAGES and optional parity padding; [global field evidence](FIELDS.md) separately covers page X of Y.

`PdfFlow.firstPageBands` and `evenPageBands` contain resolved `PdfPageBands(header, footer)` pairs. An absent pair retains the shared `header`/`footer` behavior. An explicit empty pair suppresses both bands; either member can also be individually absent. The first-page pair applies to the first physical page of each sequence and takes precedence over an even-page pair. Subsequent selection uses the continuous global page number, so the second page of a sequence beginning on page 4 is odd page 5. The existing constructors and defaults remain source-compatible. No source-format types enter the shared model.

The DOCX fixture reader resolves `w:titlePg`, document-level `w:evenAndOddHeaders`, and distinct default/first/even references. Each story type inherits independently across source ranges. Missing first/even stories in the first range become explicit blank variants when enabled, rather than borrowing the default story. A false first-page switch resets per range; it does not inherit the preceding range's true flag. False document-level even/odd selection ignores retained even references. The adapter resolves inheritance before constructing neutral ranges.

The source rules follow Microsoft's [header-reference description](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.headerreference), [title-page description](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.titlepage), and Word-specific corrections for [default-story inheritance](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/5de15e59-3ec1-4f80-aa4d-00bdd447b86f) and [first-page parity/inheritance](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/bce5f993-d683-4aa7-825e-bce25c64e8b8). The genuine Word exports, not schema interpretation alone, verify the combinations below. Page-number restarts and parity-changing odd/even section breaks remain outside this profile.

All variant paragraphs/images join the existing aggregate document budgets, including variants supplied by callers that are not selected on a particular page. Layout/PDF/font/image state remains request-local. JVM checks reopen actual PDFs for first/even/odd choices, explicit blank/fallback behavior, images in first-page footers, global limits, late variant font failures, Java API use and concurrent requests.

## Two genuine Word profiles

Both profiles reuse the nine-page A4/Letter-landscape/A3 report. Header/footer labels distinguish FIRST, ODD and EVEN; selected headers retain the source logo. Each range retains all 28 table rows and the Ukrainian/Greek sample.

| Profile | Expected page-band selection on pages 1–9 |
|---|---|
| `variants` | FIRST, EVEN, ODD, FIRST, ODD, EVEN, ODD, FIRST, ODD |
| `blank` | blank first, blank even, ODD, FIRST, ODD, EVEN, ODD, EVEN, ODD |

The complete profile defines all three story types in range A and inherits them into B/C. The blank profile initially has only default stories, defines first/even stories in B, then explicitly disables the different-first-page flag in C. Page 8 therefore uses the even story. These cases expose local-first/global-parity mistakes and accidental fallback from an intentionally empty pair.

Both new references were exported by Word 16.0 build `16.0.20527`. Earlier corpus references retain their original Word build `16.0.20430` and hashes; they were not regenerated to adopt the newer Word build. The local Word installation changed between increments, and the new export receipts record the observed build. Caller fonts and the independent raster comparison profile remain unchanged.

`audit_page_masters.py` independently reads source references/settings and resolves the expected visible stories per range/page. PDFium checks the complete header/footer word/punctuation inventory, including actual PAGE field values and intentionally empty regions, plus complete body inventory per physical range. pdfplumber verifies full line content/wrapping, glyph/page bounds and selected-logo counts/physical geometry against Word. The existing unmasked Poppler comparison verifies normalized text and page counts; no content-difference flag, mask, alignment or rescaling is used for either new case.

## Reproduction and limits

```sh
python3 tools/pdf-evaluation/own-engine/build_page_master_fixtures.py build/own-engine-page-masters
# Export each exact source through export_word.ps1, retaining the receipt.
./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEnginePageMasters
# Repeat for blank.
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-page-masters/variants/word-reference.pdf build/own-engine-page-masters/variants/own-engine.pdf build/own-engine-page-masters/variants/comparison --source build/own-engine-page-masters/variants/source.docx --font-dir build/own-engine-proof/fonts --word-bounds
python3 tools/pdf-evaluation/own-engine/audit_page_masters.py build/own-engine-page-masters/variants/source.docx build/own-engine-page-masters/variants/word-reference.pdf build/own-engine-page-masters/variants/own-engine.pdf build/own-engine-page-masters/variants/audit.json
```

Word is an external QA oracle, not a normal build dependency. The existing sections builder now also exposes its entries to the new builder without changing its default DOCX bytes. Macro/external-relationship, path, package, type-uniqueness and budget checks remain in force. Newly accepted page-band parts are bounded local `header1.xml`–`header99.xml` / `footer1.xml`–`footer99.xml` names within the unchanged 64-entry/8-MiB fixture limits.

This does not qualify numbering restarts, page X of Y, continuous/odd/even/column transitions, dynamic margin growth, every frame/table/script combination, production DOCX/ODT import, PDF/A or PDF/UA. Page bands must fit their reserved margins. The module/dependency graph, five-library publication set, production provider, REST/Playground behavior, release workflows and website remain unchanged.

## Measured page evidence

Both nine-page proofs preserve every normalized page text, full physical line wrapping, source body range and PAGE field. Values below are diagnostic pixel-edge differences, not similarity percentages. No masks, shifts or scaling were applied.

| Profile | Source DOCX SHA-256 | Word PDF SHA-256 |
|---|---|---|
| variants | `17ed72054b4be85070a98d886934873383ccfc1e5343f1efa1b0c0aa79b9ba95` | `964e178f83899cc492dc676c8d8f831a003285c0d479eea844d4f8ccaf347cec` |
| blank | `c4cb821827b718d1f7cd6fe05a3bb77ad700fce2da6da47f596e450efbe6a7e1` | `ab54c051517a466709aa4fd5ab839edbf088f5d2c6fcc3e84d4e86f0db521576` |

| Profile | Page | Range | Story | Changed pixels % | Max line start delta pt | Max baseline delta pt |
|---|---:|---:|---|---:|---:|---:|
| variants | 1 | 1 | first | 2.050 | 0.069 | 0.140 |
| variants | 2 | 1 | even | 2.518 | 0.040 | 0.200 |
| variants | 3 | 1 | default | 1.586 | 0.066 | 0.130 |
| variants | 4 | 2 | first | 1.350 | 0.060 | 0.170 |
| variants | 5 | 2 | default | 1.480 | 0.060 | 0.160 |
| variants | 6 | 2 | even | 1.564 | 0.073 | 0.180 |
| variants | 7 | 2 | default | 1.479 | 0.060 | 0.130 |
| variants | 8 | 3 | first | 1.655 | 0.060 | 0.210 |
| variants | 9 | 3 | default | 1.357 | 0.060 | 0.240 |
| blank | 1 | 1 | first | 1.974 | 0.040 | 0.140 |
| blank | 2 | 1 | even | 2.458 | 0.040 | 0.200 |
| blank | 3 | 1 | default | 1.586 | 0.066 | 0.130 |
| blank | 4 | 2 | first | 1.350 | 0.060 | 0.170 |
| blank | 5 | 2 | default | 1.480 | 0.060 | 0.160 |
| blank | 6 | 2 | even | 1.564 | 0.073 | 0.180 |
| blank | 7 | 2 | default | 1.479 | 0.060 | 0.130 |
| blank | 8 | 3 | even | 1.656 | 0.063 | 0.210 |
| blank | 9 | 3 | default | 1.357 | 0.060 | 0.240 |

All 18 side-by-side page pairs were visually reviewed at original resolution, followed by contact sheets covering all 90 Word/engine/overlay/difference/side-by-side PNGs. No clipped text, missing logo, page-band overlap or unintended blank page was observed. The blank profile has no header/footer on pages 1–2 as required; page 8 uses the inherited even story after the explicit first-page reset. Reviewed image hashes and complete source/font/provenance/geometry records remain ignored under `build/own-engine-page-masters/`.

Final verification passed the full repository build, library publication checks and all five independent Java/Kotlin consumers: 650 JVM tests, zero failures/errors/skips. The freshly rendered corpus comprises 25 documents / 97 page pairs / 485 unchanged reviewed PNGs. All final source/content/font/geometry/barcode audits passed, including both new page-band profiles. The earlier report/section rejection tests now reject an invalid band type/unsupported columns respectively; their former rejection of newly supported first-page bands was superseded by the positive/invalid-input coverage above.
