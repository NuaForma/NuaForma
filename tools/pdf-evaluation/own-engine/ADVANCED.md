# Advanced table fidelity trials

Date: 2026-10-24. These three controlled DOCX trials extend the [report proof](REPORT.md) in the existing own-engine worktree. They use separate real Word exports, not generated or reconstructed reference PDFs. Implementation and evidence are local and uncommitted; production conversion, profiles, dependencies, release workflows and provider registration are unchanged.

## Method and artifacts

`build_advanced_fixtures.py` creates three deterministic DOCX packages using only Python's standard library and the existing report/logo/styles. Each has an A4 section, logo/report header, page-number footer, a title/introduction and an ending paragraph. No hardcoded page breaks, reference coordinates, screenshots or row-to-page assignments participate in conversion. Test-only `OwnEngineDocxReader` imports source semantics into `nuaforma-pdf`; layout and PDF writing remain in that single shared artifact.

Word 16.0, build 16.0.20430 exported the identical source bytes read by the engine through `ExportAsFixedFormat`. Each reference has adjacent provenance JSON with source/reference SHA-256. The local caller-supplied Liberation Sans 2.1.5 regular/bold/italic files are identical for Word and engine; they are separate from portable test fixtures, and their hashes are recorded in every comparison. PDFBox 3.0.8 writes the candidate; Poppler 26.07.0 independently rasterizes both at 144 dpi. pdfplumber measures word rectangles and physical line baselines; pypdf independently extracts normalized content.

All generated evidence is below ignored `build/own-engine-advanced/<kind>/`: `source.docx`, `own-engine.pdf`, reference PDF/provenance and `comparison/`. Each comparison contains every page as a side-by-side image, an unaligned 50% overlay, amplified difference image, `comparison.json` and independent `source-audit.json`. No alignment/rescaling is used to improve scores. Pixel counts are diagnostics, not similarity percentages or an acceptance threshold. All nine page pairs were visually inspected, including page boundaries, nested borders, footer placement and the intentional clipped Word row.

| Kind | Current Word reference | Source SHA-256 | Word PDF SHA-256 |
|---|---|---|---|
| `merged` | `word-reference.pdf` | `4cf1159eac0d36b7821ac145313f5625cae86e84f80323550d0bdac0568d8833` | `a12a3ad3d6b983ac81a7379e06a1673b9954e6071cc55563117061076bba0abe` |
| `nested` | `word-reference-v2.pdf` | `555afc3879a8bc9d479b4e2eb6897309e6c3a489d51a926e893a3917740fd4fc` | `e08070e478a526fefc1d41fb169067dbb94caec3f48fd3d77248553f37ee7be1` |
| `split` | `word-reference-v3.pdf` | `b42d6c29954f08b699d8da1d7d9adc652a47ac32b916a6d3887c32ed9699dac8` | `03af252030dfc0987dac6689f54f53e8f2e9ef59f291728368643ef2199f7957` |

Older reference versions retained locally are obsolete and must not be compared with newer source bytes. Nested/split headers use the short label “Code” so the narrow ID column does not force an unrelated word-break feature. K01 has 28 paragraphs so it genuinely does not fit after S01's continuation but fits a fresh page.

## Merged cells: two pages

Two leading header rows repeat: the first spans two columns per heading, the second names four columns. Thirty-two data rows M01–M32 have category origins spanning eight rows each. Fase 3 crosses the page boundary. The model stores one origin with `columnSpan`/`rowSpan`; continuation slots carry no duplicate content. Span coverage, overlap, bounds and repeated-header boundaries are validated.

| Page | Word and engine content | Words / nonempty lines, each | Max baseline displacement | Max horizontal word-edge displacement | Pixels above 24/255 |
|---|---|---|---|---|---|
| 1 | Both header rows; M01–M17; Fase 1, 2 and 3 origins | 315 / 41 | 0.160 pt | 0.175 pt | 45,832 |
| 2 | Both repeated headers; M18–M32; Fase 3 continuation without repeated label; Fase 4 and ending | 259 / 35 | 0.140 pt | 0.175 pt | 38,495 |

Normalized text, physical text lines and geometric word order match on each page. Every source M marker and category label occurs once across the candidate. The vertical category fill continues on the second page without spurious internal horizontal borders or duplicate labels. There is no dropped/duplicated data row, clipping, overlap or additional page. Small stroke, glyph rounding and logo-resampling differences remain, as in the report proof; this is not pixel identity.

## Nested tables: two pages

Ten atomic parent rows N01–N10 each contain a heading and a two-column inner table with its own header, three data rows and smaller padding. The inner table stays inside the parent cell; its height affects the parent row. Word's mandatory empty paragraph after the inner table acts as the cell terminator in this controlled profile and does not add another exact text line. Explicit nested indentation is resolved separately from the outer-table text-origin convention.

| Page | Word and engine content | Words / nonempty lines, each | Max baseline displacement | Max horizontal word-edge displacement | Pixels above 24/255 |
|---|---|---|---|---|---|
| 1 | Parent header and N01–N06 with all six detail tables | 180 / 36 | 0.200 pt | 0.182 pt | 38,143 |
| 2 | Repeated parent header and N07–N10 with four detail tables, then ending | 112 / 24 | 0.150 pt | 0.182 pt | 19,415 |

Normalized text, physical lines and geometric word order match on each page. All 30 inner-row markers occur exactly once. Inner borders/fills, column widths, text padding, right-aligned status and parent heights remain aligned with Word. No inner table overlaps a following parent row. This qualifies one nested level in atomic parent rows, not arbitrary recursive nesting or nested-table fragmentation.

## Split rows: five pages, intentional difference on pages four and five

A01 has two paragraphs. S01 has 76 paragraphs and allows splitting. K01 has 28 paragraphs and `cantSplit`; it must move whole to a fresh page. K02 has 76 paragraphs and `cantSplit`, deliberately exceeding a full page. All four have unique source paragraph markers; no preassigned page break is present.

| Page | Word export | Own engine | Word / engine words; lines | Assessment |
|---|---|---|---|---|
| 1 | A01-001–002; S01-001–046 | Same | 479 / 479; 54 / 54 | Same text/word order; max baseline 0.180 pt, horizontal word edge 0.171 pt |
| 2 | S01-047–076; no K01 | Same | 281 / 281; 33 / 33 | Same text/word order; max baseline 0.140 pt, horizontal word edge 0.171 pt |
| 3 | K01-001–028 whole, starting fresh | Same | 265 / 265; 31 / 31 | Same text/word order; max baseline 0.140 pt, horizontal word edge 0.171 pt |
| 4 | K02 starts fresh but runs into footer/bottom edge; extraction includes K02-001–061 | K02-001–055 inside body area | 564 / 508; 64 / 58 | Deliberate content-preservation divergence; no whole-page fidelity claim |
| 5 | Ending paragraph only; missing K02 tail | Repeated table header, K02-056–076, then ending | 11 / 204; 3 / 25 | Candidate preserves remaining 21 paragraphs; ending consequently lower |

Word does **not** carry the oversized `cantSplit` row's tail to page five. K02-062–076 are absent even from extracted PDF content. K02-061 is present as PDF text outside the physical page; visual rendering clips it. Earlier bottom lines also collide with the footer or run into the bottom margin. Extraction counts therefore overstate Word's visible retained content. The independent audit records out-of-page glyphs in Word and zero in the candidate. The candidate retains all 182 source paragraph markers exactly once, without clipped text or footer overlap.

This observed behavior agrees with Microsoft's [Office implementation note for `cantSplit`](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/a55188f7-2443-4c17-ba5a-75999085bab4): Word cuts off an oversized kept row. The [Open XML description](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.cantsplit?view=openxml-3.0.1) instead describes starting fresh and allowing necessary continuation. The trial deliberately preserves content rather than emulating the clipping. `PdfTableRow.splitOversized` is explicit and defaults to false; the test-only DOCX importer enables it. This is a local experiment policy, not a silent relaxation of an existing production contract. A production reader needs an explicit assessment/diagnostic and agreed policy before exposing this behavior.

For the split comparison only, `--allow-content-differences` retains the unequal per-page result without asserting text equality. It still verifies source provenance and equal page counts and does not mark different pages as matched. The default comparison remains strict. There is no single displacement/similarity claim for pages four/five. Pixel diagnostics on pages 1–5 are 86,084 / 52,813 / 49,625 / 112,496 / 110,394; they do not turn the intentional discrepancy into a pass.

## Boundaries and verification

The neutral model accepts bounded cell blocks, column/row spans and one nesting level, with lazy aggregate paragraph/text accounting. Rows remain atomic by default. `allowSplit` enables line-boundary continuation with measured cell cursors; `splitOversized` separately permits an otherwise atomic oversized row to continue after moving fresh. A normal kept row never splits merely because the remaining page is short. Each fragment reserves repeated headers, cell padding/borders and body/footer limits. Page budgets and cancellation still apply, and unsupported/unsplittable pieces reject explicitly.

Combined allowed row splitting with vertical spans/nested tables is rejected in this trial profile. Oversized fragmentation in a vertically merged table is rejected as well. A long merged-cell paragraph crossing a page's row boundary is not a qualified continuation case and fails explicitly rather than clipping. Nested table pieces remain atomic. Vertical alignment, percentage/automatic widths, per-cell margin/border overrides, arbitrary nesting, first/even page masters, broad scripts, widow/orphan control, keep-with-next, general field evaluation, production DOCX/ODT import and archival/accessibility profiles remain future work. The engine is not yet qualified for arbitrary Word documents or hostile hosted input.

Regression tests reopen actual rendered PDFs and verify source markers exactly once, repeated headers, page counts/transitions, embedded fonts and preservation of the original report. Invalid span coverage/header crossings, orphan vertical continuation, excessive nesting, default oversized-row rejection and fragmentation page budgets have explicit cases. The original letter/report remain independent Word-reference regressions.

The final full build, all five proof renders, `verifyEmbeddedConsumers` and `verifyLibraryPublication` succeeded together in 13m 43s, using Java 17 libraries and Java 25 server tests. The JVM result totals 547 tests, zero failures/errors/skips. All five independent Java/Kotlin consumers passed, including actual ordinary-PDF rendering through the standalone `nuaforma-pdf` Java consumer. This does not qualify the production external converter; its existing independent consumer explicitly checks missing-runtime behavior without rendering a PDF. After the final proof render, all 45 independently rasterized advanced-trial images were byte-identical to the visually inspected run. Source/provenance hashes and marker audits were refreshed for the final PDF bytes. The original letter and all three original report pages still match their Word references in normalized text and retain the recorded geometry. Fonts in all six advanced candidate/reference PDFs are embedded Liberation Sans faces. No commit, push or remote publication was performed.

## Reproduce

Prepare the caller fonts as described in the [letter guide](README.md#run-the-own-engine-and-compare). Generate each fixture before exporting its reference:

```sh
python3 tools/pdf-evaluation/own-engine/build_advanced_fixtures.py build/own-engine-advanced/merged/source.docx --kind merged
python3 tools/pdf-evaluation/own-engine/build_advanced_fixtures.py build/own-engine-advanced/nested/source.docx --kind nested
python3 tools/pdf-evaluation/own-engine/build_advanced_fixtures.py build/own-engine-advanced/split/source.docx --kind split
```

On a Windows machine with Word, export each source using `export_word.ps1 -Source <source.docx> -Output <new-reference.pdf>`. The exporter refuses overwrites, disables macros, reads sources read-only and closes its private Word instance. Use the reference names above for this recorded run, or fresh names when deliberately regenerating. Never edit a reference PDF to hide a mismatch.

```sh
./gradlew :nuaforma-pdf:test
./gradlew :nuaforma-docx-pdf:test --tests 'dev.nuaforma.docx.pdf.ownengine.*'
./gradlew :nuaforma-docx-pdf:renderOwnEngineAdvanced
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-advanced/merged/word-reference.pdf build/own-engine-advanced/merged/own-engine.pdf build/own-engine-advanced/merged/comparison --source build/own-engine-advanced/merged/source.docx --font-dir build/own-engine-proof/fonts --word-bounds
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-advanced/nested/word-reference-v2.pdf build/own-engine-advanced/nested/own-engine.pdf build/own-engine-advanced/nested/comparison --source build/own-engine-advanced/nested/source.docx --font-dir build/own-engine-proof/fonts --word-bounds
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-advanced/split/word-reference-v3.pdf build/own-engine-advanced/split/own-engine.pdf build/own-engine-advanced/split/comparison --source build/own-engine-advanced/split/source.docx --font-dir build/own-engine-proof/fonts --word-bounds --allow-content-differences
```

Use `--poppler-dir` as needed. Run `audit_advanced.py <source.docx> <reference.pdf> <own-engine.pdf> <comparison/source-audit.json>` for each fixture. It checks unique markers read directly from the source XML against each PDF and rejects missing/duplicate candidate markers or candidate glyphs outside the physical page. It does not treat Word extraction beyond the page as visibly preserved content. Portable Gradle tests regenerate the same fixtures without requiring Word; they do not fabricate the Word reference.

Use `./gradlew build verifyEmbeddedConsumers verifyLibraryPublication` for the complete local checks. These local staging/consumer checks do not publish to a remote repository. Preserve needed ignored evidence before eventual post-merge worktree cleanup; do not commit build PDFs, Word references, fonts or previews merely to retain this run.
