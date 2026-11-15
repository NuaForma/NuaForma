# Exact-leading text and physical page bounds

Source #295 separates virtual paragraph measurement from final physical-page ink checks. With exact line spacing, glyphs may legitimately extend above or below their logical line box; Word also permits adjacent lines to overlap. A virtual paragraph origin is not a physical page boundary.

The shared engine defers ink checks only while measuring text-only paragraphs with exact leading. It preserves logical line heights, pagination, cancellation and resource limits. After placement, marked text is checked against the final physical page using the printed glyph outlines, baseline shift, synthetic-bold strokes and decorations. Whole-font extrema are not the ink of every text fragment: an unrelated deep glyph must not reject an uppercase line that fits. Real top/bottom overflow rejects before publication and reports the actual required/available space in development diagnostics. Nonfinite ink bounds reject explicitly. Automatic line spacing, inline images/vectors, text-frame validation and the nonpaginated foundation checks retain their existing policies. No public API or dependency changes are required for this fix.

## Regression controls

Ten synthetic fixtures under `nuaforma-docx-pdf/src/test/resources/exact-leading-ink` cover A4 and Letter, 10/12/18-point Liberation Sans and exact line heights of 10/12/20 points. Letter also retains the base fixture's header/footer references. Genuine Word exports have receipts binding them to the exact checked-in DOCX bytes. The initial native PNGs are protected golden files, with image-comparison assertions at zero pixel tolerance; they are not regenerated during tests.

The focused tests verify Word text, page dimensions, font embedding and three geometry markers per fixture. Representative controls cover standard PDF and PDF/A-1b/2b/3b. Shared-model and real-provider negatives preserve physical edge rejection, numeric explanations and empty output on failure. The Playground uses the same provider and development diagnostics without a new control.

Local source qualification included 13 real HTTP conversions and 39 Word markers, differing by at most 0.10 point; representative overlapping lines were visually reviewed against Word. Eight real HTTP negative cases covered physical top/bottom edges on A4/Letter and standard/PDF-A-3b. Corpus compatibility remains distinct from Word fidelity for arbitrary documents; private samples and fonts are not fixtures or distribution assets. The combined normal build passes 1833 JVM tests with no failures, errors or skips.

## Authoring procedure

Generate fresh synthetic source archives with `python3 tools/pdf-evaluation/create_exact_leading_fixtures.py <new-output-directory>`. The builder refuses to replace source files and writes no Word references or golden PNGs. It derives the unchanged package resources and paper sizes from the maintained preferred-table controls and replaces only the body paragraphs. Archive timestamps are canonical; the existing Word-exported archives retain their original bytes and export receipts.

Export the new sources from Microsoft Word with macros disabled and record Word version/build plus source/PDF SHA-256 hashes. Compare native output with those independent exports before authoring initial 96-DPI engine PNGs and their hash receipts. Existing golden files require explicit maintainer approval to replace. No change here enables Word or LibreOffice as a runtime dependency.

The native container qualification includes a two-paragraph exact-leading positive that retains the original 264-twip spacing in DOCX and produces validated PDF/A-3b. A separate 20-twip line at the physical top edge must remain an explicit renderer rejection. This replaces the obsolete assumption that tight logical lines always overflow the physical page.
