# Paragraph justification and passive Word metadata

Source qualification under [#235](https://github.com/NuaForma/NuaForma/issues/235), not a claim about previously published releases.

## Justified paragraphs

`PdfAlignment.JUSTIFY` spreads the remaining width of an automatically wrapped line across its interior ordinary spaces. The final paragraph line, leading/trailing spaces and lines without an interior space are not stretched. Glyph widths, font selection and source text stay unchanged. First-line/hanging and right indents define the available line area. Pagination, tables and page bands use the same paragraph layout. Underline/strike decoration follows the expanded space width.

DOCX `w:jc w:val="both"` maps to this shared format-independent setting, including inherited styles. Existing left, center and right alignment remains unchanged. Distributed alignment, complex-script/bidirectional justification and tabbed justified paragraphs remain explicitly unqualified. Existing explicit line-break controls remain outside the native source profile; they are not converted to spaces. Word list-label alignment remains separate from paragraph alignment.

The synthetic reference under `nuaforma-docx-pdf/src/test/resources/justification` contains ordinary, first-line and hanging indentation plus a short final paragraph. Rebuild a fresh source with `tools/pdf-evaluation/own-engine/build_justification_fixture.py --output ...`. The independent Word export uses the existing `export_word_fonts.ps1` with the repository's Liberation Sans Regular test face; it records source, PDF and font hashes. Production and portable tests do not require Word or a font installation. Reviewed engine PNGs are immutable golden files: changing them requires explicit approval and an explained visual comparison.

## Nonprinting metadata

The DOCX adapter validates a direct, unique `w:numIdMacAtCleanup` as nonnegative 32-bit numbering-maintenance metadata. It does not prune list definitions, change list IDs or restart counters. Malformed, nested or misplaced values remain subject to strict source checks.

Valid, unique `embedTrueTypeFonts` and `saveSubsetFonts` on/off settings describe Word's font-saving behavior. They do not authorize ambient font lookup, choose PDF font-embedding policy or bypass missing-font/glyph/embedding checks. Actual embedded DOCX font resources keep their existing qualification rules.

A single internal OPC package preview may be named `docProps/thumbnail.jpeg`, `.jpg` or `.png`, with the matching declared JPEG/PNG content type and package thumbnail relationship. Its bytes remain bounded, opaque file-browser metadata; they are never decoded, rendered or evaluated as template instructions. External, missing, duplicate, orphan, empty or mismatched preview graphs reject explicitly. This does not add thumbnail support as a visible image format or permit other arbitrary package parts.

Normalization changes only the private print model. The source DOCX and supported cleanup output retain this metadata. The Playground uses the same provider; no new control, route or less strict generation mode is introduced. No dependency, module, runtime font bundle or release workflow changes.
