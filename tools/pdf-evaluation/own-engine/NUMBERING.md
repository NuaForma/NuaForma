# Sequence numbering, section totals and parity padding

Date: 2026-10-25. Parent: [#57](https://github.com/NuaForma/NuaForma/issues/57). This is the sequence-numbering follow-up to [global page fields](FIELDS.md), completing the bounded decimal profile in roadmap part 8. DOCX import remains test-only.

## Neutral model and source rules

`PdfPageSequence.numbering` is an immutable `PdfPageNumbering(start, alignParity)` policy. An absent start continues the preceding range's displayed numbering; an explicit start is bounded to 0â€“9999. Actual generated display numbers must also remain in that range. Existing constructors retain their defaults. `PdfSequencePageCount(style)` resolves to the number of content pages in the current sequence, independently of its starting number. `PdfPageCount` remains the actual physical document total.

Parity alignment is explicit and disabled by default. When enabled for an explicit restart after another sequence, a mismatch with the preceding range's next displayed number inserts one blank page. The blank uses the new sequence's page geometry, has no bands/text/images, consumes the global page budget and contributes to NUMPAGES. It does not increment the new range's PAGE values or contribute to either range's SECTIONPAGES. The first range receives no leading padding. First-page bands apply to the first content page; other band selection uses displayed numeric parity rather than the physical PDF page index. The writer still finishes all body pagination before resolving bands and returns no partial bytes on failure.

The DOCX adapter accepts only decimal `w:pgNumType` starts and plain PAGE/NUMPAGES/SECTIONPAGES fields in page bands. Missing starts continue; retained first/default/even stories inherit independently. For the tested Word profile, the adapter enables restart-parity padding only when document-level `evenAndOddHeaders` is enabled. With shared bands, the same restart has no padding. The source switch is resolved in the adapter, not inferred from DOCX types inside the shared engine.

Microsoft describes [section page-number starts](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.pagenumbertype), [first-page header selection by numbering parity](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/bce5f993-d683-4aa7-825e-bce25c64e8b8), and Word's [SECTIONPAGES meaning](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oe376/eec92f2a-3673-4e83-9b60-9579e4f3c021). Padding geometry, actual field values and shared-band differences below are established by genuine Word exports, not inferred from schema text alone.

PAGE reserves four UTF-16 units per control in the aggregate text budget; document/sequence totals reserve two under the unchanged 32-physical-page maximum. The existing field cache visual-format restrictions remain; language/direction metadata is preserved. Java/Kotlin tests reopen actual PDFs for restart/continuation, zero/four-digit starts, opt-in/opt-out padding, padding/page-number limits, single-page ranges, invalid field contexts/resources, aggregate budgets and concurrent shared-engine requests with distinct numbering.

## Four genuine Word profiles

All profiles retain the A4 portrait / Letter landscape / A3 portrait report, 28 table rows per range, source logo, Greek/Ukrainian text and source page margins. Header and footer fields carry stale `999` caches. Both display PAGE, SECTIONPAGES and NUMPAGES, including fields in header table cells. Word `ExportAsFixedFormat` generates each reference from the exact source; receipts record the source/reference hashes and observed Word build.

| Profile | Explicit starts A / B / C | Even/odd bands | Physical pages | Content pages by range | Padding |
|---|---|---|---:|---|---|
| restart | 25 / 1 / continue | Enabled | 10 | 3 / 4 / 2 | Page 4, Letter landscape |
| offset | 0 / 100 / continue | Enabled | 10 | 3 / 4 / 2 | Page 4, Letter landscape |
| continue | 2 / continue / continue | Enabled | 9 | 3 / 4 / 2 | None |
| shared | 25 / 1 / continue | Disabled | 9 | 3 / 4 / 2 | None |

The third range continues after the preceding restart rather than inheriting its starting value. Both absent `pgNumType` and an explicit decimal element without a start are exercised. Different-first-page switches demonstrate first-page precedence, while initial even/zero numbering and later ranges expose confusion between physical and displayed parity. The shared-band case retains unused variant references to verify that the document-level switch controls selection/padding.

`audit_page_masters.py` independently derives starts, stories, expected padding and totals from the source plus the actual physical PDF inventory. It checks the complete body inventory by range, exact header/footer values, genuinely blank padding, all source/page geometry, physical line text/wrapping, logo bounds and band font face/size against Word. Independent unmasked Poppler rendering uses the same caller fonts and 144-dpi profile as the existing corpus. No shifts, masks, rescaling or content-difference exemptions are introduced.

## Reproduction and limits

```sh
python3 tools/pdf-evaluation/own-engine/build_numbering_fixtures.py build/own-engine-numbering
# Export each source through export_word.ps1 without replacing existing references.
./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEngineNumbering
# Repeat for offset, continue and shared.
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-numbering/restart/word-reference.pdf build/own-engine-numbering/restart/own-engine.pdf build/own-engine-numbering/restart/comparison --source build/own-engine-numbering/restart/source.docx --font-dir build/own-engine-proof/fonts --word-bounds
python3 tools/pdf-evaluation/own-engine/audit_page_masters.py build/own-engine-numbering/restart/source.docx build/own-engine-numbering/restart/word-reference.pdf build/own-engine-numbering/restart/own-engine.pdf build/own-engine-numbering/restart/audit.json
```

Only next-page source sections are qualified here. Continuous/explicit odd/even/column section transitions, Roman/letter/chapter-prefixed page values, field switches/expressions, PDF viewer page labels, dynamic margins and general field relayout remain outside this profile. The bounded parity option is not general duplex layout. No module/dependency, production provider, REST/Playground, publication/release or PDF/A/UA change is included.

## Verification result

`build verifyEmbeddedConsumers verifyLibraryPublication` and all existing/new proof render tasks passed. The full repository reports 670 JVM tests with zero failures, errors or skips and five independent Java/Kotlin consumers. Final independent comparisons and source/content/font/geometry/barcode audits passed across all 30 documents / 148 page pairs. All 740 comparison PNGs, including the 190 reviewed for this increment, match their reviewed hashes. Existing intentional differences retain their earlier references and audit policies; this increment adds no exception or mask.

## Recorded page-level evidence

The 38 new page pairs were reviewed through contact sheets containing all five comparison views per page, with unscaled header-field and footer crops alongside full-page previews. Full text and line geometry are independently asserted rather than inferred from the small previews. The 190 underlying comparison PNG hashes are frozen after that review. Body layout, logos, bands and tables remain visually aligned; the unmasked differences show the existing small font/raster/position differences, not changed styling or missing content.

All four sources and their genuine Word references record Word build 16.0.20527. SHA-256 identities:

| Profile | Source DOCX | Word PDF |
|---|---|---|
| restart | `ac22b66b24aa0af4c63b614e3082b00caaddd06fcba5facb6ced77f167fc9707` | `86fb686ef63060f76ece3b8fc8301a420428df475654ee6d97f15f22ae780b5e` |
| offset | `a9337a7ef33a37e0724d88e724de2475fc69c69c7a69b3cadbeac7261c59163c` | `9a39dde616c1c1989df282a1d22ea16fd06e0960bce4fd97cb5ec1030a711543` |
| continue | `38e19baf16e5da86598bdad14e7bff892f24d6634dbbb17adfeccba97498a09d` | `24ef0fb80e4ca851c5f48c6102bcc0c89d5ea6a4fad20eb619054310ba7b6a8b` |
| shared | `7fa2c1249881cb9048f8d650e9e5b53570009e802d6b09f48536baed5f18accd` | `b79836b111282ce33620f095cc48d983521d6434aa779391bd6bc119b4b9f412` |

Per-page maxima below compare matched line starts and baselines in points. Text, page assignment and full line wrapping match on every page; both padding pages are truly empty. Font-size deltas for bands are at most 0.04 pt. Blank pages display no fields.

| Profile | Physical page | PAGE | SECTIONPAGES | Band | Max line-start delta (pt) | Max baseline delta (pt) |
|---|---:|---:|---:|---|---:|---:|
| restart | 1 | 25 | 3 | first | 0.040 | 0.140 |
| restart | 2 | 26 | 3 | even | 0.068 | 0.200 |
| restart | 3 | 27 | 3 | default | 0.036 | 0.130 |
| restart | 4 | — | — | blank | 0.000 | 0.000 |
| restart | 5 | 1 | 4 | default | 0.062 | 0.170 |
| restart | 6 | 2 | 4 | even | 0.088 | 0.160 |
| restart | 7 | 3 | 4 | default | 0.062 | 0.180 |
| restart | 8 | 4 | 4 | even | 0.088 | 0.130 |
| restart | 9 | 5 | 2 | default | 0.060 | 0.210 |
| restart | 10 | 6 | 2 | even | 0.078 | 0.240 |
| offset | 1 | 0 | 3 | even | 0.056 | 0.140 |
| offset | 2 | 1 | 3 | default | 0.040 | 0.200 |
| offset | 3 | 2 | 3 | even | 0.056 | 0.130 |
| offset | 4 | — | — | blank | 0.000 | 0.000 |
| offset | 5 | 100 | 4 | first | 0.102 | 0.170 |
| offset | 6 | 101 | 4 | default | 0.060 | 0.160 |
| offset | 7 | 102 | 4 | even | 0.060 | 0.180 |
| offset | 8 | 103 | 4 | default | 0.060 | 0.130 |
| offset | 9 | 104 | 2 | even | 0.060 | 0.210 |
| offset | 10 | 105 | 2 | default | 0.060 | 0.240 |
| continue | 1 | 2 | 3 | even | 0.060 | 0.140 |
| continue | 2 | 3 | 3 | default | 0.040 | 0.200 |
| continue | 3 | 4 | 3 | even | 0.060 | 0.130 |
| continue | 4 | 5 | 4 | first | 0.060 | 0.170 |
| continue | 5 | 6 | 4 | even | 0.070 | 0.160 |
| continue | 6 | 7 | 4 | default | 0.060 | 0.180 |
| continue | 7 | 8 | 4 | even | 0.070 | 0.130 |
| continue | 8 | 9 | 2 | default | 0.060 | 0.210 |
| continue | 9 | 10 | 2 | even | 0.084 | 0.240 |
| shared | 1 | 25 | 3 | default | 0.040 | 0.140 |
| shared | 2 | 26 | 3 | default | 0.040 | 0.200 |
| shared | 3 | 27 | 3 | default | 0.036 | 0.130 |
| shared | 4 | 1 | 4 | default | 0.060 | 0.170 |
| shared | 5 | 2 | 4 | default | 0.060 | 0.160 |
| shared | 6 | 3 | 4 | default | 0.060 | 0.180 |
| shared | 7 | 4 | 4 | default | 0.060 | 0.130 |
| shared | 8 | 5 | 2 | default | 0.060 | 0.210 |
| shared | 9 | 6 | 2 | default | 0.060 | 0.240 |
