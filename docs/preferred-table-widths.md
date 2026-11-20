# Preferred table widths

Source issue #259 extends the existing `nuaforma-pdf` model with `PdfTableWidthPolicy.AUTO_FIT`, optional `preferredWidth` on `PdfTable` and `PdfTableCell`, and immutable `PdfPreferredWidth.points(value)` / `.percent(value)`. No artifact or dependency is added. These are alpha source APIs; publication is separate.

## Shared model

Physical preferences use points, from zero through 14,400. Percentages use ordinary percentages, from zero through 100. Values must be finite. A missing preference leaves size negotiation to the content. Table percentages refer to the containing content width; cell percentages refer to the preferred/resolved table width. They are preferences, not clipping rectangles or a command to scale text.

`FIXED` retains the supplied grid and its existing validation. `FIT_CONTENTS` retains the existing unwrapped text-only profile. `AUTO_FIT` accepts nonnegative grid hints, measures intrinsic content with the same font metrics and word boundaries as paragraph layout, combines cell/span requirements, and negotiates preferred column widths inside the containing width. Text wraps normally. Minimum content that cannot fit fails explicitly; this bounded profile does not clip images or split unbreakable strings to imitate every Word fallback. Automatic-width cells can measure qualified plain terminal default tabs. Interior or decorated tabs and floating images remain outside that profile. Existing cell, row, nesting, pagination, font and resource limits remain in force.

The width belongs to the current page, band or parent cell. Layout state remains request-local. Immutable document transformations and page-field resolution retain both table and cell preferences. The model contains no Word-specific objects and can also serve a future ODT reader.

## DOCX translation

Omitted `w:tblLayout` selects automatic layout. Qualified automatic tables preserve physical `dxa` and `pct` preferences from `tblW` and `tcW`; absent or zero-valued `auto`/`nil` dimensions do not impose a preference. DOCX percentages are integer fiftieths of a percent: `4000` becomes 80%, not 4,000%. Explicit fixed layouts retain the existing consistent physical-grid checks. The previously qualified all-automatic, unwrapped fixture remains on its existing `FIT_CONTENTS` path. Legacy Word alignment includes outside cell padding in the page-relative percentage reference; the adapter resolves that top-level preference into physical points for the current section before handing it to the neutral engine. Modern alignment retains the percentage. Specified/inherited complete cell margins remain required; no unqualified default-padding substitution is introduced.

Unknown units, malformed dimensions, unsupported structures and dimensions beyond the bounded profile fail with source diagnostics. Original width/layout attributes are checked before DOCX binding so serialization cannot silently discard an invalid attribute. This does not authorize external resource loading, font substitution or ignoring unsupported source features.

Microsoft documents table widths as preferences that can be overridden by shared-grid and content requirements: [table width](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablewidth), [cell width](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablecellwidth), [units](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablewidthunitvalues). The source qualification is bounded; it is not a claim of implementing every Word automatic-layout behavior.

## Qualification

`PdfAutoTableWidthsTest` exercises real wrapping, physical/percentage context, merged cells, immutable reconstruction, embedded fonts and explicit overflow. `JavaPdfEngineTest` exercises the Java factories and constructors. `NativePreferredTableModelTest` checks DOCX translation and invalid source values. `NativePreferredTableTest` exercises actual generation and independent validation for all four supported PDF profiles.

`tools/pdf-evaluation/own-engine/build_preferred_table_width_fixture.py` reproducibly creates A4/Letter probes for physical, percentage, implicit automatic and merged-cell tables. Microsoft Word references use the repository Liberation Sans faces and retain source/PDF/font hashes. Existing golden files must remain unchanged; initial references require rendering and review before adoption. The combined normal build passes 1,563 tests with no failures, errors or skips. All 26 synthetic markers per paper remain within 0.080 points horizontally and 0.170 points vertically of Word. The unchanged local 250-source corpus retains nine complete PDFs and 241 explicit rejections; all nine prior PDFs and thirteen pages remain pixel-exact. Passing an earlier table barrier does not imply complete conversion or Word fidelity for a corpus document.

Playground impact: the existing generation and cleanup paths use this same source reader and shared renderer. No new switch, UI, production fallback or release is introduced.

## Additional source spellings and omitted indents (#259, #267)

Qualified source now accepts bounded decimal `dxa` spellings, truncating positive fractional twips exactly as Word does. Literal percentages such as `33.3%` require `pct` units and an exact integer fiftieth after conversion. Original attributes are validated before canonicalization for integer-only DOCX binding; duplicates, wrong namespaces, unknown units and out-of-range values remain explicit failures. `auto`/`nil` still require zero. This does not change the neutral width API.

For top-level legacy Word tables, an omitted effective `tblInd` differs from explicit zero: the former places the table at the containing origin; explicit zero retains the existing cell-padding adjustment. Inherited declarations retain their meaning. Mode 15 and nested-table behavior are unchanged. Independent Word probes cover omitted/12/14/15 compatibility modes, omitted/explicit zero and A4/Letter, with complete glyph-position differences below .21 points.

`NativePreferredWidthSpellingTest` retains the existing width goldens for literal percentage, integer-decimal and fractional-twip sources. `NativeImplicitTableIndentTest` owns sixteen new synthetic sources and actual Word references. These are source qualification additions, not a published release or a claim that every automatic table layout is supported. The Playground uses the same reader; no new option or dependency is introduced.

## Physical AutoFit cell width protection

In a top-level automatic table, an enabled [Word cell no-wrap setting](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.nowrap) protects a positive physical preference on a single, unmerged cell. Other columns first shrink toward their intrinsic minimum. The table may grow beyond its overall preference where the containing width permits. If the protected preference still cannot fit, the containing width takes priority. Text continues to wrap at its normal opportunities. For fixed layouts the toggle has no effect. Automatic cells with percentage or unspecified widths have different no-wrap semantics and remain outside this profile.

The neutral model exposes this priority through immutable `PdfTableCell.withProtectedPreferredWidth(enabled)`. It applies during automatic grid sizing and does not change existing cell constructors. Playground generation and cleanup use the same reader.

## Terminal default tabs

Automatic tables can retain trailing default tabs on otherwise ordinary left-aligned text. The controls are excluded from intrinsic width measurement. Preserved spaces before a terminal tab remain in the line. The ordinary PDF layout verifies that the computed stop fits the resolved cell. The qualified case uses the preceding text's font and formatting, with no explicit stops or decoration. Interior, leader, other alignment and overflowing tab cases retain an explicit diagnostic.

## Table-level shading without cell spacing

[Word applies table-level shading to the gaps between table cells](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/ee110ab2-af6a-4ee8-923d-9a12a2fe65ab). With contiguous cells, a qualified direct table-level clear fill is preserved in the source and contributes no PDF paint. Explicit or styled cell fills still paint their own cells. Inter-cell spacing and unqualified patterns or shapes remain source-diagnosed.

## Whole-grid width protection

A cell occupying a single row and spanning the full grid of a qualified top-level AutoFit table may protect its combined physical width. Where the table has a physical preference, it is raised to at least the span's preference, subject to the available page width. Column negotiation still takes place separately. Partial spans and percentage-based tables with this whole-grid toggle remain explicit source rejections.
