# Long words in fixed table cells

Unreleased source work adds emergency wrapping for a word wider than a fixed body-table cell. The declared column grid and table width remain unchanged. Ordinary word wrapping takes priority; a word that still cannot fit starts on the next line and breaks at supported grapheme boundaries. No hyphen is inserted.

The shared model exposes `PdfParagraph.breakLongWords` and the immutable `withLongWordBreaking(enabled)` method. Existing constructors default to `false`; all paragraph copy methods retain the setting. The DOCX adapter enables it for top-level fixed body-table cells. Automatic width negotiation, nested cells and page bands retain their existing policies. The setting is source-neutral and can also be used by a future ODT adapter.

Splitting retains each source run's font, size, color, decorations, navigation, semantics and UTF-16 offsets. Extended grapheme clusters remain intact, including clusters crossing run boundaries. Ordinary fitted text and explicit line breaks retain their existing behavior. Automatic wrapping does not modify the source template or insert new template instructions.

## Bounds and diagnostics

The initial oversized-word path supports unshaped text. Oversized words requiring active font shaping or bidi handling reject explicitly rather than approximating glyph placement. Existing support for shaped or bidi words that fit is retained. Inline objects are not split.

A cluster that cannot fit raises measured `CONTENT_OVERFLOW`, for example `Word cluster width exceeds line width; required=6.67 pt; available=1.5 pt`. The development diagnostic retains that closed engine explanation; production retains the existing disclosure policy. Physical page bounds, existing text/page/resource budgets, cancellation and independent PDF validation remain enforced.

Cluster measurement and final fragment assembly are linear in source text length. The bounded model's existing 100,000-character limit remains unchanged.

## Qualification

Twelve synthetic Word sources cover A4/Letter, compatibility modes 12/15 and regular/bold/italic faces. Each retains a genuine Word PDF export and a source-bound exporter receipt. The actual HTTP renderer comparison checks 1,296 visible glyphs against Word with the existing 0.21-point gate; maximum offsets are 0.2014 points horizontally and 0.2001 points vertically. Twelve independently exported explicit-break Word controls match Word's automatic breaks pixel for pixel at 144 DPI; the actual native controls also match at 144 DPI with no masks.

Actual HTTP qualification produced fifteen standard/archival PDFs and four precise numeric rejections. All twelve templates were cleaned twice without changing their bytes; downloaded templates generated identical Playground PDFs. Twelve new native golden pages are initial files. The normal combined build passes 1847 JVM tests with no failures, errors or skips. Maintained tests verify standard PDF and PDF/A-1b/2b/3b, unchanged fixed columns, preserved template bytes, immutable model copies, styled boundaries, source offsets, Unicode clusters, cancellation, linear worst-case measurement and measured rejection when one cluster cannot fit. Existing golden images must not be replaced to accommodate this work.

The Playground uses the same conversion provider without a separate switch. The work is not included in unchanged `0.5.0-alpha.3` artifacts. Local corpus conversion counts are compatibility evidence, separate from fidelity comparisons against Word.

## Fixture authoring

Run `python3 tools/pdf-evaluation/own-engine/build_long_word_fixtures.py --resources nuaforma-docx-pdf/src/test/resources --output <fresh-directory>`. The builder refuses existing outputs and does not generate Word references or golden images. Package payloads reproduce the retained synthetic sources; archive compression bytes can vary between Python/zlib versions.

Export the sources with Microsoft Word, with macros disabled, and bind the source/PDF hashes to the Word version/build. Compare the actual provider output against those references before authoring initial native images. Existing golden replacement continues to require explicit maintainer approval. Private corpus inputs and fonts remain local.

Local corpus qualification converts fourteen of 250 unchanged sources. All thirteen previous PDFs and twenty-two previous pages remain pixel-identical at 96 DPI with no masks; the additional document has two pages with embedded fonts and all glyphs inside the physical pages. Corpus compatibility and synthetic exact-source Word fidelity are separate checks.
