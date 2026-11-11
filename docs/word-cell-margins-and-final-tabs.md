# Cell margins, row fragments and final-line tabs

Source #268–#271 is qualified on the PDF engine development branch. These changes are not yet a published release. The shared engine remains one `nuaforma-pdf` artifact; no dependency is added.

## Physical cell margins

`PdfTableCell.margins` optionally overrides the table's `PdfMargins`, in points. A null value retains the existing table defaults. Existing Java constructors and Kotlin default-constructor descriptors remain available. Width negotiation, wrapping, nested tables, merged cells, vertical alignment, page-field resolution and row fragments retain the resolved physical margins.

The DOCX adapter accepts bounded physical `w:tcMar` edges with `w:type="dxa"`. Omitted edges inherit table/style defaults; explicit zero resets that edge. A qualified zero-valued `nil` edge instead restores the table default, bypassing a cell-style override. It must not be treated as a physical zero or simply deleted. Word uses the largest top and bottom margins within a row. The adapter resolves that Word behavior into physical per-cell margins before passing the document to the neutral engine. Explicit horizontal edges respect the visible border's half-width inset. A later ODT adapter can supply its own physical margins without inheriting Word rules.

Word truncates fractional twips before layout. Original declarations are validated and bounded before this canonicalization; malformed numbers, negative measurements, duplicate edges, foreign attributes and unsupported units reject with source context. The adapter does not modify the caller's input.

The synthetic controls cover direct and inherited margins, partial overrides, zero, wrapping, a horizontal span, minimum row height and a repeated header over a long splittable row on A4 and Letter. Actual Word exports place fourteen markers per ordinary/minimum fixture within 0.081 point horizontally and 0.131 point vertically. The split fixture retains both pages and all 144 markers within 0.081/0.201 point. Fractional-margin Word exports have exactly the same pixels as the integer controls; existing goldens are reused.

## First splittable row

An oversized first data row can begin after preceding text when a legal fragment fits. Previously the admission check could leave only the heading on the first page. Fragment boundaries, repeated headers, non-splitting rows and minimum-height guards still apply. The two-page Word controls exercise this through the real provider, alongside independent shared-engine tests.

## Final-line tabs

The shared engine accepts tabs on the final line of a left-to-right justified paragraph. Tab stops and leaders retain the left-aligned geometry; earlier ordinary wrapped lines still justify. A tabbed line that itself requires justification rejects explicitly. Centered and right-aligned tab paragraphs retain their existing restriction.

Word's decimal tab separator follows the exporting workstation's [regional number settings](https://support.microsoft.com/en-us/word/use-decimal-tabs-to-line-up-numbers-with-decimal-points). Qualification fixtures therefore declare a comma separator matching the reference exporter. All 48 content anchors for the four stop kinds and four leaders agree within 0.091 point horizontally and 0.131 point vertically on both papers. The number of decorative leader glyphs can differ because of fractional advances: text-inventory comparison normalizes repeated leader glyphs only, while geometry and every native golden pixel still assert. Decimal tabs with a numeric suffix expose a separate existing anchor discrepancy, tracked in #272; this work does not claim that discrepancy is fixed.

## Inactive run properties

Explicit `w:dstrike` values `0`, `false` and `off`, and a normal `w:w` scale of `100`, no longer reject otherwise supported visible runs. The on/off aliases are canonicalized before binding. A disabled double strike does not cancel an independently inherited single strike. Active double strike and non-default text scaling remain unsupported when used. Valid active properties in unused styles do not activate rendering effects.

The six inactive controls retain exact existing golden pixels on A4 and Letter. Active or malformed declarations must fail with the original part/path and publish no partial PDF.

## Verification boundaries

The normal combined build passes all 1,601 tests, with zero failures, errors or skipped tests. Feature tests include standard PDF, all three qualified PDF/A profiles, embedded fonts, extracted content, actual Word geometry and exact image-comparison checks against reviewed golden pages. Real HTTP qualification generates 34 PDFs containing 36 pages, all with embedded fonts and exact golden pixels at 96 dpi; nine focused negative requests return diagnostics without PDF bytes. The frozen runtime's 91 JARs match the final installed distribution. All 22 maintained fixture-builder sources reproduce their uncompressed ZIP entries. Twelve new initial golden pages were visually reviewed; no protected golden has been replaced for this group. Original Word export receipts retain their bytes in Git.

The final local corpus round retains nine successful documents out of 250: two of the original hundred and seven of the added 150. All source hashes remain unchanged. The thirteen previously generated pages retain exact pixels, text and page counts, with embedded fonts. No additional complete document converted in this group. Passing one feature's fixtures is not a claim of full support for arbitrary Word documents.

The production and Playground providers share these improvements. There is no new endpoint, control, permissive fallback, font substitution or runtime dependency. Original local sample documents and font files are not checked into the repository. No release or site publication is part of this work.
