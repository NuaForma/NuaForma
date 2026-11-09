# Character spacing (#266)

This unreleased source extension adds physical tracking to the existing shared PDF artifact. `PdfTextStyle.characterSpacing` defaults to zero; `withCharacterSpacing(points)` returns an immutable copy and all existing copy/decorations/font-feature helpers retain the setting. The approved API accepts finite values within ±14,400 points; actual font advances must remain nonnegative. Negative zero is normalized. Existing constructors and zero-style behavior remain compatible; no dependency is added.

## Translation and layout

The DOCX reader checks original `rPr/spacing` values and structure before binding, resolves direct/paragraph/character-style inheritance, and translates bounded signed integer twips to points. Explicit zero resets inherited tracking. Paragraph line spacing remains a different setting. Glyph positioning, following-run origins, wrapping, underlining and strikeout use the same tracking advance, including spacing after the last character as independently measured in Word.

Font shaping/cache keys include tracking and remain request-safe. Qualified simple glyph clusters can retain pair/OpenType kerning. Multi-character ligature/complex clusters with nonzero tracking and right-to-left tracked text remain explicit failures; explicit left-to-right text is supported. Existing zero-tracking bidi behavior is unchanged. This is not a claim of general Arabic or complex-script tracking support. No font substitution or external retrieval is introduced.

## Qualification

`PdfCharacterSpacingTest` checks exact advances, negative spacing, cache reset, decoration/copy preservation, invalid bounds, complex-cluster/RTL rejection, explicit LTR and concurrent requests. `JavaPdfEngineTest` exercises the public Java getter/copy method. Original XML guard tests accompany provider regressions in `NativeCharacterSpacingTest`.

The synthetic A4/Letter sources contain direct spacing, character/paragraph styles, zero overrides, Cyrillic/Greek text, bold, decorations and three wrapped paragraphs. Actual Microsoft Word exports retain source/PDF provenance. Sixty-four Word markers per paper, including fourteen following-run origins, differ by at most .151 points horizontally and .181 points vertically. New initial native goldens were visually reviewed; no existing golden is replaced. The provider test independently validates standard PDF and PDF/A-1b/2b/3b and requires exact golden pixels.

The existing Playground generation/cleanup paths inherit the same reader and renderer. There is no UI switch, new artifact or release/site change. Corpus success establishes conversion, not independent Word fidelity for private samples.
