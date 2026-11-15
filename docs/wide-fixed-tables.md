# Fixed tables extending into the body margin

Unreleased source qualification preserves a top-level body table's explicit fixed grid when it extends beyond the text area but remains inside the physical page. Word permits this placement. Narrowing the grid to the text area would alter column widths, wrapping and table layout.

The DOCX adapter uses the existing `PdfTable.rightOverflow` setting for the exact additional space required by the fixed grid and resolved indentation. The shared engine still enforces the physical page edge, including border strokes. No public API, dependency, resource limit or rendering fallback changes. Existing percentage-table behavior retains its separate qualified allowance.

The additional allowance applies only to fixed tables in the main body. It does not grant more width to nested cells or page bands. A table/grid width mismatch, unsupported source formatting or actual physical overflow still rejects before PDF publication. Automatic width negotiation retains its existing policy.

## Regression controls

Sixteen synthetic DOCX/Word pairs cover A4/Letter, Word compatibility modes 12/15 and omitted, zero, positive and negative indentation. They retain independent Word-export receipts bound to the exact source bytes. The maintained inputs live under `nuaforma-docx-pdf/src/test/resources/wide-fixed-tables`.

`NativeWideFixedTableModelTest` checks the unchanged grid and precise allowance. `NativeWideFixedTableTest` covers Word geometry, standard PDF/PDF-A-1b/2b/3b, cleanup idempotence, original-byte retention, invalid grids, physical overflow and unchanged nested/page-band bounds. New native images are initial protected golden files; tests never regenerate them. The combined normal build passes 1833 JVM tests with no failures, errors or skips.

The Playground uses the same conversion and cleanup providers without a new option. This source refinement is not part of unchanged `0.5.0-alpha.3` artifacts. Corpus conversion counts are compatibility evidence, separate from fidelity comparisons against Word.

## Fixture authoring

Run `python3 tools/pdf-evaluation/own-engine/build_wide_fixed_table_fixtures.py --resources nuaforma-docx-pdf/src/test/resources --output <fresh-directory>`. The builder refuses existing outputs and writes no references or goldens. Its package payloads reproduce the retained sources; ZIP timestamps are canonical.

Export the synthetic sources with Microsoft Word, macros disabled, and record source/PDF hashes and the Word version/build. Compare actual native output with those exports before authoring initial 96-DPI images and their hash receipts. Existing golden replacement still requires explicit maintainer approval. Private samples and fonts remain local.

Local corpus qualification retains 13 complete PDFs and 237 explicit rejections across 250 unchanged sources. All thirteen prior PDFs and twenty-two pages remain pixel-identical. This fixed-grid refinement advances a table-width barrier; overlong words in narrow cells remain a separate rendering limitation.
