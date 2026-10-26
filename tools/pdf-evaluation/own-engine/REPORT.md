# Report fidelity proof with tables and page bands

Measured on 2026-10-24 against a real Microsoft Word 16.0 export, build `16.0.20430`. This extends the [letter proof](README.md) with a naturally paginated business report. It is still an experimental source/test route, not a production provider, general DOCX qualification or PDF/A implementation.

## Document and supported profile

The deterministic `build_report_fixture.py` builds one A4 section with a title, styled flowing paragraphs, a 28-row data table plus column header, a logo/report-name header and a footer containing a live `PAGE` field. There are no explicit page breaks, fixed row-to-page assignments, reference-PDF coordinates or embedded page screenshots. Word and the own engine independently paginate the same DOCX bytes.

This report records the original report milestone. The later [advanced table trials](ADVANCED.md) supersede its implementation-boundary statements for spans, one-level nesting and row splitting; its source and reference measurements remain the regression baseline.

The table has four fixed columns, physical cell margins, uniform solid borders, explicit cell fills and right-aligned amounts. Rows are atomic and the leading table header repeats after a page break. Header/footer content repeats on every page, with the number resolved for that page. A dedicated Footer style defines the PAGE field's actual type size in Word as well as in the importer. Widow/orphan control is explicitly disabled for the flowing proof paragraphs; the experiment does not yet implement Word's widow/orphan or keep-with-next policies.

The source SHA-256 is `6137fd255e747af47ae394a7653d11d110148a8f43b6eda39e2f939bad33dede`. The Word reference's SHA-256 is `29da33d3c52a701968efb2bdca89d25214d86dc2f5f999bcc936f2c180c3e29c`. Its recorded filename for this run is `word-reference-v3.pdf`; the adjacent provenance JSON records the source/export relationship. The report uses the same local caller-supplied Liberation Sans 2.1.5 font files as the letter comparison, with their actual hashes retained by the comparison tool. Those local files are separate from the existing portable test font fixtures and production artifacts.

## Results per page

Both outputs contain three pages, the same normalized text on each page, the same physical text-line contents and the same geometric word order. Across the report there are 818 extracted words. Source row IDs W01–W28 each occur once; repeated column labels are intentional.

| Page | Content and page transition | Words / nonempty text lines, each PDF | Maximum baseline displacement | Maximum horizontal word-edge displacement |
|---|---|---|---|---|
| 1 | Title, introduction, table header and W01–W12 | 300 / 40 | 0.170 pt | 0.274 pt |
| 2 | Repeated table header, W13–W28, findings and first two concluding paragraphs | 342 / 42 | 0.200 pt | 0.274 pt |
| 3 | Remaining conclusion, decision and final paragraph | 176 / 17 | 0.170 pt | 0.293 pt |

Horizontal word-edge displacement is the maximum of the start/end rectangle differences measured by pdfplumber. The baseline comparison uses actual text matrices, grouped into physical lines; whitespace-only image markers do not define the line's visible left edge. Maximum line-start displacement is 0.093 pt on all three pages. Maximum word-box top displacement is respectively 0.143, 0.148 and 0.123 pt. These are text geometry measures, not a bound on every rasterized contour or table pixel.

All three pages were inspected as rendered pages, side-by-side views and 50% overlays. Logo, report name, page numbers, column widths, wrapping, right alignment, borders, fills, bold/italic and body spacing remain recognizable and correctly positioned. No missing cells, duplicate data rows, overlapping content, clipped rows, blank extra pages or lost header/footer content were found. The table crosses the same page boundary as Word, and paragraph continuation starts with the same text on page three.

The outputs are close rather than pixel-identical. Word rounds body type/positions and paints many table borders as small filled rectangles; the engine uses source sizes, font advances and solid PDF strokes. Word also resamples the header logo; the engine preserves its source pixels and reuses one image object across pages. At 144 dpi, the amplified difference contains 44,369 / 52,179 / 28,659 pixels above the 24/255 threshold on pages 1/2/3. These whole-page counts are diagnostics, not similarity scores; white space and anti-aliasing make them unsuitable as a standalone acceptance gate.

All Word pages measure 595.32 × 841.92 pt; the engine uses the DOCX twips as 595.30 × 841.90 pt. The header logo is 18 × 18 pt at x = 56.7 pt and top = 31.2 pt in both outputs. Word resamples it to 49 × 49 pixels, while the engine retains the 1254 × 1254 source. The PDF y-coordinate difference of 0.02 pt is accounted for by page-height rounding, rather than a moved logo.

## Model and implementation boundaries

The shared artifact remains `nuaforma-pdf`. `PdfFlow` explicitly enables pagination, preserving the original single-page default/overflow contract. Neutral tables contain column widths, rows, cells, paragraphs, padding, fills, borders and a leading-header count. Page bands have reserved height and top/bottom offsets; `PdfPageNumber` is a semantic inline value resolved per page. Layout, row measurement, page breaks and PDF writing stay inside this artifact, without DOCX/ODT/parser types.

The test-only DOCX adapter remains in `nuaforma-docx-pdf`. It reads the added section relationships and parts, fixed tables, `cantSplit`, leading `tblHeader` rows, physical dimensions and default header/footer references. It rejects merged/nested cells, row splitting, unsupported border styles, percentage/automatic widths, per-cell margin/border overrides, alternate first/even bands, unsupported fields and multiple sections. A row taller than a fresh page, including its repeated headers, is rejected rather than split. This deliberately narrows the full Word behavior described by [`cantSplit`](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.cantsplit?view=openxml-3.0.1); the repeated-header interpretation follows [`tblHeader`](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tableheader?view=openxml-3.0.1).

Flow supports explicit exact line boxes and has a maximum of 32 pages. Repeated headers are reserved before placing the next atomic row. Table borders participate in vertical row measurement, and the final border is included before subsequent text. Header/footer bands must fit inside their reserved page margins. Page/paragraph limits, oversized rows and colliding bands fail explicitly. A bounded lazy traversal caps paragraph accounting before materializing arbitrarily repeated table graphs. Font/image decoding remains in-process; this is not hostile-input isolation or a hosted memory/load guarantee.

Merged cells, nested tables, splitting a row over pages, vertical cell alignment, keep-with-next, widow/orphan control, rich field evaluation, page-master variants, automatic line-spacing policies, broader scripts and archival/accessibility profiles remain outside this experiment. Production provider, REST/Playground behavior, existing default PDF profile, release workflows and website remain unchanged.

## Reproduce

Use the existing caller-font setup from the [letter proof](README.md#run-the-own-engine-and-compare). On a machine with Word, generate the report and a new reference filename:

```sh
python3 tools/pdf-evaluation/own-engine/build_report_fixture.py build/own-engine-report/source.docx
```

```powershell
./tools/pdf-evaluation/own-engine/export_word.ps1 -Source build/own-engine-report/source.docx -Output build/own-engine-report/word-reference.pdf
```

Run the engine on Java 17 and compare with the already available QA tooling:

```sh
./gradlew :nuaforma-docx-pdf:test --tests 'dev.nuaforma.docx.pdf.ownengine.*'
./gradlew :nuaforma-docx-pdf:renderOwnEngineReport
python3 tools/pdf-evaluation/own-engine/compare.py build/own-engine-report/word-reference.pdf build/own-engine-report/own-engine.pdf build/own-engine-report/comparison --source build/own-engine-report/source.docx --font-dir build/own-engine-proof/fonts --word-bounds
```

Use `--poppler-dir` when needed. Output includes a JSON record, side-by-side PNG, overlay and amplified difference for every page, without image alignment or content rescaling. Geometry always uses pdfplumber text matrices independently of pypdf content extraction, including across PDF graphics-state changes; `--word-bounds` additionally compares word rectangles. Tests regenerate the synthetic DOCX with Python's standard library but do not call Word or fabricate a Word reference. Preserve needed ignored QA evidence before eventual post-merge worktree cleanup.


## Word compatibility and table alignment

Release preparation exposed a source-reader alignment error in the website example: compatibility mode 15 was accepted but used the legacy table-text origin. Two genuine Word exports with identical document/styles/images and only the compatibility value changed place the first header text at 54.00 pt in mode 12 and 59.04 pt in mode 15. Pagination and vertical placement remain identical. Microsoft documents the [compatibility setting and default mode 12](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-docx/90138c4d-eb18-4edc-aa6c-dfb799cb1d0d); the geometry comparison is measured from these independent Word exports.

The DOCX adapter captures the setting before stripping authoring metadata. Legacy/default top-level tables retain the previous origin; mode 15 uses the border-anchored origin already used for nested tables. The shared renderer/model is unchanged. Ambiguous duplicate modes reject, and existing restrictions on other modes remain. Explicit columns are preserved; a modern indent that makes a table exceed the page rejects instead of silently narrowing it.

Six focused regressions exercise absent/12/15 settings, actual embedded-font report PDFs with repeated headers, unchanged nested relative placement, ambiguous/unqualified values and physical overflow. The positive report fixture supplies explicit spare page width for its modern indent; the unchanged full-width legacy input separately proves overflow rejection. Existing neutral-engine golden files are not replaced. Full source/native/Word qualification remains mandatory before merging a candidate.
