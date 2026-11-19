# Horizontal text scaling and explicit page breaks

Current source adds `PdfTextStyle.horizontalScale` / immutable `withHorizontalScale(factor)` and `PdfPageBreak(style)` to the existing shared PDF model. Existing style constructors and copy methods retain their signatures and the new setting. No package or dependency is added; a future ODT adapter can reuse these neutral controls.

## Horizontal scaling

The default factor is one; finite factors from 0.01 through 6 scale glyph outlines and natural advances horizontally. Extra character spacing and kerning adjustments remain point-based. Vertical size, baseline, underline thickness and line height stay unchanged. Decorations, links and following runs use the resulting physical advances. Shaping caches include the scale and belong to each render.

The DOCX adapter resolves `w:w` through ordinary run/paragraph-style inheritance and explicit resets to 100 percent. Original namespaces, duplicates, attributes and values are checked before binding. Only integer percentages from 1 through 600 are qualified; malformed, zero or out-of-range input fails at its original source location. Existing complex-script and font-feature limits remain applicable.

## Explicit page boundaries

`PdfPageBreak(style)` keeps a physical boundary inside one logical paragraph, preserving identity, indentation, text styles and source offsets. DOCX `w:br w:type="page"` maps to that control. Consecutive controls deliberately retain blank pages; an ending control does not invent another paragraph-mark line before the next paragraph. Page bands and page fields follow the resulting physical pages. Ordinary wrapping breaks keep their existing behavior.

Body paragraphs use the shared flow engine; owning footnotes remain on the page containing their call. Page-count, text, cancellation and control budgets remain active. Inline page breaks in table cells, frames, page bands, note definitions, linked/RTL source text or paragraphs participating in keep rules are explicitly outside this qualification. Boundaries are never silently dropped or converted to spaces. Wrapping around body frames remains separate. Single-page foundation rendering requires a paginated flow to use this control.

## Word controls and golden files

`python3 tools/pdf-evaluation/create_scaling_break_fixtures.py <fresh-directory>` builds inert A4/Letter sources and refuses an existing output directory. Genuine Word exports retain unchanged source hashes. Fourteen controls cover natural/compressed/expanded widths, tracking, kerning, inheritance/reset, wrapping, decorations, leading/trailing/consecutive page boundaries and shaded paragraphs split across pages. All 28 Word/native page pairs were visually reviewed before initial native references were authored.

The longest scaled wrapped line accumulates a measured horizontal marker difference of 0.312 point from Word; page-break controls stay within 0.061 point horizontally. New scaling controls specify a 0.35-point horizontal comparison bound; existing fixture bounds stay unchanged. Vertical differences remain below 0.143 point. Complete content, paper dimensions, embedded fonts and exact native reference pixels at 96 dpi are independently asserted. No masks are used and no established golden image was replaced. This permits small font-width differences and does not claim pixel identity between Word and the native engine.

The Playground shares the provider semantics and needs no separate control. Qualified rejection reasons remain visible in development diagnostics; production retains its redacted policy. These are source capabilities, not a claim about an already published release.
