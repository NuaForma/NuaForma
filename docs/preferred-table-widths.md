# Preferred table widths

Source issue #259 extends the existing `nuaforma-pdf` model with `PdfTableWidthPolicy.AUTO_FIT`, optional `preferredWidth` on `PdfTable` and `PdfTableCell`, and immutable `PdfPreferredWidth.points(value)` / `.percent(value)`. No artifact or dependency is added. These are alpha source APIs; publication is separate.

## Shared model

Physical preferences use points, from zero through 14,400. Percentages use ordinary percentages, from zero through 100. Values must be finite. A missing preference leaves size negotiation to the content. Table percentages refer to the containing content width; cell percentages refer to the preferred/resolved table width. They are preferences, not clipping rectangles or a command to scale text.

`FIXED` retains the supplied grid and its existing validation. `FIT_CONTENTS` retains the existing unwrapped text-only profile. `AUTO_FIT` accepts nonnegative grid hints, measures intrinsic content with the same font metrics and word boundaries as paragraph layout, combines cell/span requirements, and negotiates preferred column widths inside the containing width. Text wraps normally. Minimum content that cannot fit fails explicitly; this bounded profile does not clip images or split unbreakable strings to imitate every Word fallback. Tabs and floating images in automatic-width cells remain unsupported. Existing cell, row, nesting, pagination, font and resource limits remain in force.

The width belongs to the current page, band or parent cell. Layout state remains request-local. Immutable document transformations and page-field resolution retain both table and cell preferences. The model contains no Word-specific objects and can also serve a future ODT reader.

## DOCX translation

Omitted `w:tblLayout` selects automatic layout. Qualified automatic tables preserve physical `dxa` and `pct` preferences from `tblW` and `tcW`; absent or zero-valued `auto`/`nil` dimensions do not impose a preference. DOCX percentages are integer fiftieths of a percent: `4000` becomes 80%, not 4,000%. Explicit fixed layouts retain the existing consistent physical-grid checks. The previously qualified all-automatic, unwrapped fixture remains on its existing `FIT_CONTENTS` path. Legacy Word alignment includes outside cell padding in the page-relative percentage reference; the adapter resolves that top-level preference into physical points for the current section before handing it to the neutral engine. Modern alignment retains the percentage. Specified/inherited complete cell margins remain required; no unqualified default-padding substitution is introduced.

Unknown units, malformed dimensions, unsupported structures and dimensions beyond the bounded profile fail with source diagnostics. Original width/layout attributes are checked before DOCX binding so serialization cannot silently discard an invalid attribute. This does not authorize external resource loading, font substitution or ignoring unsupported source features.

Microsoft documents table widths as preferences that can be overridden by shared-grid and content requirements: [table width](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablewidth), [cell width](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablecellwidth), [units](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.tablewidthunitvalues). The source qualification is bounded; it is not a claim of implementing every Word automatic-layout behavior.

## Qualification

`PdfAutoTableWidthsTest` exercises real wrapping, physical/percentage context, merged cells, immutable reconstruction, embedded fonts and explicit overflow. `JavaPdfEngineTest` exercises the Java factories and constructors. `NativePreferredTableModelTest` checks DOCX translation and invalid source values. `NativePreferredTableTest` exercises actual generation and independent validation for all four supported PDF profiles.

`tools/pdf-evaluation/own-engine/build_preferred_table_width_fixture.py` reproducibly creates A4/Letter probes for physical, percentage, implicit automatic and merged-cell tables. Microsoft Word references use the repository Liberation Sans faces and retain source/PDF/font hashes. Existing golden files must remain unchanged; initial references require rendering and review before adoption. The combined normal build passes 1,514 tests with no failures, errors or skips. All 26 synthetic markers per paper remain within 0.080 points horizontally and 0.170 points vertically of Word. The unchanged local 250-source corpus retains seven complete PDFs and 243 explicit rejections; all seven prior PDFs and eleven pages remain pixel-exact. Passing an earlier table barrier does not imply complete conversion or Word fidelity for a corpus document.

Playground impact: the existing generation and cleanup paths use this same source reader and shared renderer. No new switch, UI, production fallback or release is introduced.
