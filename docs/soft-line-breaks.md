# Soft line breaks

Selected 0.5.0-alpha.4 adds `PdfLineBreak(style)` to the existing shared PDF artifact. A control starts another physical line inside the same paragraph: it does not restart numbering, introduce paragraph spacing or create another heading. Leading, consecutive and trailing controls retain their empty lines. DOCX maps ordinary `w:br` with omitted/default `textWrapping` and `none` policies, plus attribute-free `w:cr`. Column breaks, inline page breaks and floating-object clear policies remain explicit source rejections.

The source adapter validates original control attributes, namespaces and contents before Word binding, and the native reader checks them again. Invalid enum values cannot disappear into defaults during serialization. Qualified cleanup retains supported controls and original template bytes without evaluating expressions.

## Shared model

```kotlin
val style = PdfTextStyle("regular", 12f)
val paragraph = PdfParagraph(listOf(
    PdfTextRun("First line", style),
    PdfLineBreak(style),
    PdfTextRun("Second line", style)
))
```

Java callers use `new PdfLineBreak(style)` in the same `PdfParagraph` run list. `paragraph.withParagraphMarkStyle(style)` optionally provides the font metrics for an implicit empty final line after a trailing break. This immutable setting survives all existing copy/with methods; `null` uses the trailing control's style. Existing constructors and copy signatures are retained. Kotlin consumers with exhaustive `when` expressions over `PdfInline` must include the new variant.

Nonempty automatic lines use their visible content's font metrics. Empty forced lines use the break style; the final empty line uses `paragraphMarkStyle` when supplied. The existing `minimumLineStyle` and `minimumLineHeight` policies retain their separate minimum-box meaning. DOCX resolves its paragraph-mark formatting in the adapter; ODT can later map its own defaults into these format-neutral values. No document parser, package or dependency is added to the shared engine.

Controls participate in the existing text/run/page budgets and have an additional document-wide limit of 2,000. They are not newline glyphs and do not require encoding a control character in the supplied font. Tabs stop their alignment payload at a forced line end. Automatic/natural table measurement uses the widest explicit line. Text references containing controls remain explicitly unqualified rather than silently flattening the range.

## Qualification

Synthetic controls cover A4/Letter body text, exact/automatic spacing, mixed font sizes, empty lines, alignment, hanging indents, tabs, numbered lists, table cells, measured headers/footers and pagination. Representative requests cover standard PDF and PDF/A-1b/2b/3b. The maintained sources, genuine Word exports and native 96-DPI golden images live under `nuaforma-docx-pdf/src/test/resources/soft-line-breaks`.

Local real-HTTP qualification passed 31 conversions. The 25 non-RTL outputs matched Word text/pages and 164 geometry markers, with maximum differences of 0.145 point horizontally and 0.141 point vertically. Twenty malformed/unsupported HTTP requests rejected with precise source details and no PDF output. Core tests cover limits, copy/Java compatibility, mixed metrics, intrinsic widths and Hebrew source offsets.

Hebrew soft-break and explicit-paragraph controls independently produce pixel-identical pairs in Word and in NuaForma, retaining Word text and baseline positions. The existing absolute horizontal glyph-origin difference of up to 0.264 point is present in both controls; this feature does not qualify a stricter absolute Hebrew X match. Separate zero-tolerance native golden comparisons protect existing placement. Corpus compatibility counts are not a claim of Word fidelity for arbitrary private documents.

The combined normal build passes 1833 JVM tests with no failures, errors or skips. The Playground uses the same provider; no request control or JSON schema change is needed. This source refinement is not in unchanged `0.5.0-alpha.3` artifacts.

## Fixture authoring

`python3 tools/pdf-evaluation/create_soft_line_break_fixtures.py <fresh-output-directory>` builds the 22 synthetic sources and refuses an existing output directory. Package payloads reproduce the maintained Word-exported inputs; archive timestamps are canonical. The tool writes no references or golden images.

Export fresh controls with Microsoft Word and macros disabled; retain the exact source/PDF hashes and Word version/build. Review geometry and representative rendered pages before authoring initial 96-DPI native images and their hash receipts. Existing golden files require explicit maintainer approval to replace and are never refreshed by tests. Word is only a reference-authoring tool; it is not a product runtime dependency.
