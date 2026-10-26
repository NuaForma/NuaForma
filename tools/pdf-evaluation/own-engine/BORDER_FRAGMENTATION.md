# Explicitly splittable rows with consistent cell borders

Date: 2026-10-25. Parent: [#57](https://github.com/NuaForma/NuaForma/issues/57). Follow-up to [advanced border combinations](BORDER_COMBINATIONS.md).

## Boundary

This increment targets explicitly splittable rows with one consistent visible border style, TOP cell alignment and optional horizontal column spans. Every physical fragment retains its background and closing/opening rules; short adjacent cells retain their text only on the first fragment. Leading table headers repeat independently. Complete non-splitting rows still move intact when they fit a fresh page.

Oversized non-splitting rows with custom borders, non-TOP cells across fragments, vertical spans crossing row-fragment boundaries and conflicting border styles remain unqualified and reject explicitly. The existing uniform-border `splitOversized` behavior remains unchanged: it preserves content instead of reproducing Word's known clipping of an oversized non-splitting row. No public model, dependency, runtime baseline or production provider changes.

## Proofs and reproduction

`python3 tools/pdf-evaluation/own-engine/build_border_fragmentation_fixtures.py build/own-engine-border-fragmentation` creates an A4 and a US Letter report, each with two explicitly splittable 100-line rows. One row spans the final two columns. Other rows must stay together. Both sources contain 212 unique line markers, a logo and page-number footer. Export each source independently through Word, retaining its provenance receipt, then run `./gradlew :nuaforma-pdf:test :nuaforma-docx-pdf:test :nuaforma-docx-pdf:renderOwnEngineBorderFragmentation`.

Run `compare.py` with source/font checks and `--word-bounds`, then `audit_border_combinations.py` for each profile. The latter verifies full source inventories, repeated headers from the independent Word output, glyph order/faces/sizes/colors, logo/page geometry and solid-border footprints. Existing five-percent exact vector-area and quarter-point distance gates apply unchanged, without masks or image alignment. Review every side-by-side, overlay and difference view before freezing hashes.

Core tests reopen actual PDFs to check every line exactly once, header repetition, three complete horizontal opening/closing rules on each physical page, glyph-position parity with uniform borders, explicit unsupported cases and concurrent use of a shared engine/font set. DOCX tests verify both paper sizes, horizontal spanning, embedded fonts and all markers/page numbers. No REST/Playground behavior, publication set, release workflow, website or PDF/A/UA claim changes.

## Qualification evidence

Both independently exported Word references use build `16.0.20527` and contain five pages. All ten page pairs retain identical normalized text, every source marker and glyph order/font/color. Maximum glyph-start/baseline differences are 0.205/0.181 pt; border-area differences stay below 4.145%, with zero samples beyond 0.25 pt. All ten original-size side-by-side views, overlays and difference views were inspected: no clipping, duplicated labels, lost text, broken merged interiors or changed page assignments were found. Fifty reviewed PNG hashes are retained locally.

| Profile | Page | Visible glyphs | Maximum start/baseline delta (pt) | Maximum border-area delta | Samples beyond 0.25 pt |
|---|---:|---:|---|---:|---:|
| A4 | 1 | 1649 | 0.204 / 0.180 | 4.139% | 0 |
| A4 | 2 | 1644 | 0.168 / 0.140 | 4.144% | 0 |
| A4 | 3 | 1660 | 0.168 / 0.140 | 4.139% | 0 |
| A4 | 4 | 1644 | 0.168 / 0.140 | 4.143% | 0 |
| A4 | 5 | 178 | 0.168 / 0.140 | 4.130% | 0 |
| Letter | 1 | 1533 | 0.180 / 0.180 | 4.137% | 0 |
| Letter | 2 | 1557 | 0.138 / 0.120 | 4.141% | 0 |
| Letter | 3 | 1515 | 0.138 / 0.120 | 4.136% | 0 |
| Letter | 4 | 1557 | 0.138 / 0.120 | 4.142% | 0 |
| Letter | 5 | 613 | 0.138 / 0.130 | 4.134% | 0 |

| Profile | Source SHA-256 | Word PDF SHA-256 |
|---|---|---|
| A4 | 0c55e96df133c4daef70566e01ede9bacfe34ef2ef3e6c45e1aebc53947f94b2 | cbea95f4befe132b6e219cfc1db5c941449d61518d5875070ebe9498cfc67d35 |
| Letter | 1a2c14f6dcf0377704937acb6f4102be7ca8de10060981f3d36701b4113809e9 | e51049f5602922fd3a5dea5884f8ed7586f9ab9de00db893713ff6f5cc1701b2 |

The full `build verifyEmbeddedConsumers verifyLibraryPublication` plus all render aggregates succeeded in 18 minutes 56 seconds. All 711 JVM tests passed without failures, errors or skips, together with all five independent Java/Kotlin consumers. Final source/content/font/geometry/barcode/border audits passed across 42 documents / 175 page pairs, and all 875 reviewed PNG hashes remain unchanged. The ten new pages preserve all 13,550 visible glyphs. Java 17 library and Java 25 server boundaries remain unchanged.

The final stronger actual-PDF rule assertions also passed a subsequent `build verifyLibraryPublication` in 1 minute 7 seconds; the renderer bytes used by the completed corpus audit were unchanged.
