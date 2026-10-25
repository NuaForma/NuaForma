# International page format proof

Date: 2026-10-25. Parent: [#57](https://github.com/NuaForma/NuaForma/issues/57). This is the standalone-size increment of roadmap part 6; mixed-size sections remain unimplemented.

## Coverage and model

Eight genuine Word-exported reports qualify A4, A3, US Letter and Legal, each in portrait and landscape. Each report includes the existing logo, fixed-width table with repeated headers, decimal amounts, flowing paragraphs, page bands and PAGE footer fields. Russian, Ukrainian and Greek paragraphs are included alongside Dutch text. Legal uses asymmetric 75 pt left / 45 pt right / 90 pt top / 70 pt bottom margins; the other cases retain the report margins.

The shared `PdfPage` model already stores physical point dimensions and margins without a regional default. No orientation enum, page-size normalization, new module, dependency, font asset or production provider is introduced. The test DOCX reader now accepts `w:pgSz/@orient`, validates its consistency with explicit width/height, and preserves those dimensions directly. It does not rotate the PDF canvas or scale content to A4. Absent orientation continues to preserve physical dimensions, including landscape dimensions. Unknown orientation values, explicit orientation/dimension conflicts and invalid dimensions reject.

Fixed table/header widths remain fixed; a larger page does not stretch them. Footer centering follows the actual content area, including asymmetric margins. Concurrent renders through one engine/font collection verify that page dimensions cannot leak between requests. Normal JVM tests additionally reopen real PDF bytes, check MediaBox/rotation, embedded fonts, per-page logos/fields, text bounds, all 28 row IDs and international text.

## Word comparison

All eight cases preserve normalized text per page and exact page counts against Word. The independent PDFium audit verifies the complete source body inventory, repeated table-header counts and successful source-script searches in both PDFs. Physical glyph bounds and line origins/baselines are checked separately. Images use the same independent Poppler profile as the existing corpus, without masks, alignment or rescaling.

Letter landscape has one deliberately recorded difference: on page 1, the candidate fits `meegenomen` at the end of the preceding line, while Word places it at the start of the following line. The line count, paragraph height, full page text and pagination remain identical. This falls within the maintainer's explicit allowance for small font-related line-wrap differences. The audit's `--allow-one-word-wrap` flag is used only for this case: it permits exactly one word to move between two adjacent lines on one page, while retaining all line positions and source/page inventories. Other line differences fail. The remaining seven cases have identical line breaks.

## Reproduction and limits

```sh
python3 tools/pdf-evaluation/own-engine/build_page_format_fixtures.py build/own-engine-page-formats
# Export each exact source through export_word.ps1, retaining its receipt.
./gradlew :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEnginePageFormats
# Repeat for each case; allow a one-word wrap only for letter-landscape.
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-page-formats/a4-portrait/word-reference.pdf build/own-engine-page-formats/a4-portrait/own-engine.pdf build/own-engine-page-formats/a4-portrait/comparison --source build/own-engine-page-formats/a4-portrait/source.docx --font-dir build/own-engine-proof/fonts --word-bounds
python3 tools/pdf-evaluation/own-engine/audit_page_formats.py build/own-engine-page-formats/a4-portrait/source.docx build/own-engine-page-formats/a4-portrait/word-reference.pdf build/own-engine-page-formats/a4-portrait/own-engine.pdf build/own-engine-page-formats/a4-portrait/audit.json
```

Word is an external QA oracle; normal JVM builds generate their own source fixtures without Word. This proof does not qualify mixed-size sections, first/even/odd page masters, every feature/script combination on every size, production DOCX/ODT import or any archival/accessibility profile. Complex/mixed RTL work remains open independently in part 5. One physical page policy still applies to each `PdfDocument`.

The full repository build and local publication checks passed with 634 JVM tests and zero failures/errors/skips. All five independent Java/Kotlin consumers passed against the unchanged five-library publication set. Playground, REST, the production converter, release workflows and the website have no behavior or dependency changes in this source/test-only increment.

Final source/content/font/geometry/barcode audits passed across all 22 Word-reference documents / 70 page pairs. All 350 reviewed comparison PNGs, including this increment's 130, retained their reviewed SHA-256 values after the full build rerendered every candidate.

## Measured pages and provenance

Word 16.0 build `16.0.20430`; independent Poppler 26.07.0 renders at 144 dpi. All 26 side-by-side page pairs were reviewed at full resolution, followed by contact sheets of all 130 Word/engine/overlay/difference/side-by-side PNGs. No clipping, missing content, displaced logo, broken table or page-band overlap was observed. The single adjacent-word wrap noted above is visible in the Letter landscape page-1 overlay.

| Case | Page | Lines | Max line-start delta (pt) | Max baseline delta (pt) | Changed pixels (%) | Note |
|---|---:|---:|---:|---:|---:|---|
| a4-portrait | 1 | 38 | 0.093 | 0.170 | 2.005 | Same line breaks |
| a4-portrait | 2 | 43 | 0.093 | 0.140 | 2.357 | Same line breaks |
| a4-portrait | 3 | 22 | 0.093 | 0.170 | 1.804 | Same line breaks |
| a4-landscape | 1 | 21 | 0.060 | 0.200 | 1.127 | Same line breaks |
| a4-landscape | 2 | 29 | 0.060 | 0.140 | 1.494 | Same line breaks |
| a4-landscape | 3 | 29 | 0.060 | 0.140 | 1.511 | Same line breaks |
| a4-landscape | 4 | 18 | 0.060 | 0.170 | 2.074 | Same line breaks |
| a3-portrait | 1 | 59 | 0.060 | 0.210 | 1.594 | Same line breaks |
| a3-portrait | 2 | 33 | 0.060 | 0.200 | 1.436 | Same line breaks |
| a3-landscape | 1 | 37 | 0.118 | 0.140 | 1.143 | Same line breaks |
| a3-landscape | 2 | 42 | 0.118 | 0.180 | 1.499 | Same line breaks |
| a3-landscape | 3 | 12 | 0.118 | 0.096 | 0.886 | Same line breaks |
| letter-portrait | 1 | 34 | 0.060 | 0.190 | 1.565 | Same line breaks |
| letter-portrait | 2 | 41 | 0.060 | 0.180 | 2.197 | Same line breaks |
| letter-portrait | 3 | 29 | 0.060 | 0.180 | 1.896 | Same line breaks |
| letter-landscape | 1 | 23 | 0.060 | 0.190 | 1.553 | One word wraps earlier |
| letter-landscape | 2 | 29 | 0.060 | 0.160 | 1.517 | Same line breaks |
| letter-landscape | 3 | 29 | 0.060 | 0.160 | 1.578 | Same line breaks |
| letter-landscape | 4 | 20 | 0.060 | 0.150 | 1.659 | Same line breaks |
| legal-portrait | 1 | 46 | 0.093 | 0.170 | 1.763 | Same line breaks |
| legal-portrait | 2 | 50 | 0.093 | 0.200 | 2.861 | Same line breaks |
| legal-portrait | 3 | 7 | 0.093 | 0.120 | 0.355 | Same line breaks |
| legal-landscape | 1 | 23 | 0.113 | 0.190 | 1.107 | Same line breaks |
| legal-landscape | 2 | 29 | 0.113 | 0.160 | 0.615 | Same line breaks |
| legal-landscape | 3 | 28 | 0.113 | 0.160 | 0.581 | Same line breaks |
| legal-landscape | 4 | 16 | 0.113 | 0.150 | 2.165 | Same line breaks |

Changed-pixel fractions are unmasked diagnostics, not similarity or acceptance scores. Line anchors are not bounds on every glyph advance.

| Case | Physical size (pt) | Pages | Source SHA-256 | Word PDF SHA-256 |
|---|---|---:|---|---|
| a4-portrait | 595.3 x 841.9 | 3 | `78eea5f42b66fff7e6182d06ea50a09c38f4f2121d59a93ff2f45cdd13dbb9bc` | `63b66b3e4dcb2d794f444dab611c908563d509eff6e138514a46bf3d14a85b38` |
| a4-landscape | 841.9 x 595.3 | 4 | `4661be2e097252d626e629bf8f48a8a26e7fffdc90c5bd3706f6946049b42e8e` | `2c15e4d1daebbff3111a0bb0d58e90309c888ca89b7de16589640f8cc3b72032` |
| a3-portrait | 841.9 x 1190.55 | 2 | `1c07761dee28f5994dfd720965a0d8e7919f82c7964900d60ea12391f738c4c5` | `322ebc4a8caa3f5da94215cec6ffcc90fe3394dcbdcb84d420ca214f09c82b4d` |
| a3-landscape | 1190.55 x 841.9 | 3 | `172568bfd238e22bdc21d596aaceb1156bff817c3d7b990f6b56314a0b7cb8aa` | `352d62d77c9cd2c6e8cc7f978db3dc7a1c32ad36f0757cb1abc2ff0123d2e380` |
| letter-portrait | 612.0 x 792.0 | 3 | `9e4f24dec207620ad70663ec49f10d2616d25cf46ddcbda82c9115245b991905` | `f1b06b15b1c0b8369654d8244e2c75a95b9f2b1cb219c78bf11a6f1cfbfb9867` |
| letter-landscape | 792.0 x 612.0 | 4 | `8c2394625458511b8d7ef09a36996e897f54d235e6cba62ec0afe65a13c95326` | `a36c09e066e36d9980d241f07c79b457a2e4405731cafc8ca0478eafa19a22dc` |
| legal-portrait | 612.0 x 1008.0 | 3 | `6ccb0afca236361329d0f05027cf0a6959097455ecf85aaefd84df213b9e5487` | `ed735f2e33e09e494892a9e9024abe4b00729807debbc3a590302ec1f7fb41e9` |
| legal-landscape | 1008.0 x 612.0 | 4 | `8a5ff49833bbd1897456cb9228c518999c6ecca6c8658530326990650d47d326` | `712126450aa461766f897008bfbfc2de32a5b8cba28a5f9f439b3614d7f57f71` |
