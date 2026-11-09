# Minimum table row heights

Unreleased source qualification under [#264](https://github.com/NuaForma/NuaForma/issues/264).

## Shared model

`PdfTableRow.minimumHeight` is an optional physical row advance in points, including its cell frame. The default is null, retaining natural sizing. Values must be finite and within 0–14,400 points. Existing Java constructor overloads remain available. This format-neutral setting stays in `nuaforma-pdf`, ready for a later ODT adapter; no dependency is added.

The measured row is at least this tall and may grow for its contents, spans or nested tables. TOP/CENTER/BOTTOM alignment distributes the available space using the existing cell policy. Normalization and page-band field reconstruction preserve the floor. A height-controlled row moves intact when the remaining page is too short; impossible floors fail rather than silently losing blank height. Content that exceeds a smaller floor retains the existing qualified fragmentation rules. Repeated headers and existing non-splitting/alignment limits still apply.

## Word source mapping

The reader accepts original `w:trHeight` measurements with `w:hRule="atLeast"`, or with the rule omitted. The omitted-rule behavior is qualified against actual Word exports. Word's minimum is a content-area measurement: the adapter adds resolved cell top/bottom padding and the applicable top border advance to obtain the shared physical floor. A zero minimum has no effect. Explicit `auto` is qualified only with zero measurement.

Exact heights, malformed measurements, nonzero explicit `auto`, duplicate declarations, unknown attributes and namespace lookalikes remain explicit failures. Measurements are bounded before and after adding the frame. This increment supports direct row declarations; it does not qualify additional inherited table-style row-height rules or clipping.

## Regression coverage

`build_minimum_row_height_fixture.py` reproduces explicit-minimum and omitted-rule documents on A4 and US Letter. Each has three pages: short/large floors, natural content above the floor, vertical alignment, and a paginated table with repeated headers. Actual Word exports of both rule variants have identical pixels. Thirty-six text markers retain their page and horizontal position within 0.051 points; maximum vertical differences are 0.180 points on A4 and 0.201 on Letter. Assertions allow 0.21 points to account for coordinate rounding.

`NativeMinimumRowHeightTest` checks original-source diagnostics, atomic rejection, unchanged cleanup, Word text/geometry, embedded fonts and six initial golden pages. Standard PDF and PDF/A-1b/2b/3b are independently validated and match those pages exactly. The six new reference images have source/Word/golden checksums and review notes; they do not replace existing goldens. Shared Kotlin and Java tests cover the neutral API, copies, spans, nested tables, overflow and fragmentation.

Playground impact: generation and cleanup use the same reader, so qualified row floors no longer need removal. No additional UI, transport, fallback or release is introduced.
