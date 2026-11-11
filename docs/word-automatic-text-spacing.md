# Automatic East Asian text spacing

Unreleased source qualification under [#236](https://github.com/NuaForma/NuaForma/issues/236).

`autoSpaceDE` controls spacing at Latin/East Asian text boundaries; `autoSpaceDN` controls numeric/East Asian boundaries. Word defines the regions from Unicode characters and inherits the switches through paragraph styles. See Microsoft's [AutoSpaceDE](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.autospacede) and [AutoSpaceDN](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.autospacedn) references. These switches concern character spacing within a paragraph; the existing before/after paragraph-spacing and line-height policies retain their behavior.

## Qualified input

The native DOCX adapter accepts valid unique on/off switches under paragraph properties in defaults, styles and document stories. Explicitly disabled switches are inactive. Enabled switches are qualified when document stories and potential literal field values contain no East Asian text. Latin, Greek, Cyrillic and ordinary numbers remain unaffected. The source DOCX and supported cleanup output retain their original bytes; normalization affects only the private print model.

The conservative guard includes page bands, text boxes, footnotes/endnotes, supplementary ideographs, fullwidth/halfwidth forms, relevant punctuation and possible field text. Decoded DOCPROPERTY text and SET literals use the existing bounded field grammar and property decoder. Reading possible values does not consume the field-emission budget or evaluate template instructions a second time. Mixed documents can be rejected even where a particular unused style or individual paragraph would not require spacing.

Active mixed East Asian spacing remains unqualified. The adapter rejects it explicitly, along with duplicate, malformed, misplaced or foreign-namespace properties through the normal source-profile checks. The separate `autoSpaceLikeWord95` algorithm retains its existing qualification boundary. Missing fonts, glyph restrictions, embedding permissions, cancellation and resource limits remain enforced.

## References and verification

A4 and US Letter synthetic sources under `nuaforma-docx-pdf/src/test/resources/automatic-text-spacing` exercise default/style/direct switches, wrapping, signed indents, Greek and Cyrillic text. `tools/pdf-evaluation/own-engine/build_automatic_text_spacing_fixture.py --base <justification/source.docx> --output <fresh-directory>` rebuilds them deterministically and refuses replacement. Author independent Word PDFs with the existing `export_word_fonts.ps1` and the repository's Liberation Sans Regular fixture. Word is required only for reference authoring.

Standard PDF and PDF/A-1b/2b/3b must retain Word text/line geometry within the stated glyph bounds, match the initial reviewed engine goldens exactly, and match an otherwise identical source without these switches pixel-for-pixel. Provenance files bind source, Word PDF, font and golden hashes. Existing goldens must never be refreshed to hide a regression; replacement needs explicit approval and visual review.

The shared PDF model, public APIs, dependencies and runtime font bundle are unchanged. ODT remains independent of this DOCX-specific qualification. The Playground uses the existing provider/cleanup operation with no new control or deployment setting. Private corpus documents and installed test fonts remain local. Source support does not imply a published release.
