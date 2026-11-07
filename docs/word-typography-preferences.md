# Word typography preferences

Source work for [#232](https://github.com/NuaForma/NuaForma/issues/232), extending the existing DOCX reader and shared PDF engine. This is unreleased behavior. No package, dependency, fallback renderer or output profile is added.

## Qualified settings

- `w:compat/w:ulTrailSpace` explicitly controls decoration of spaces at a line's end. Word applies the same switch to underlining and strikeout, separately or together. Enabled spaces extend the requested lines without moving left, centered or right aligned text; decoration stops at the paragraph's right boundary. Wrapped separating spaces and space-only lines follow the same rule. Disabled spaces are not decorated. Absence retains original behavior. Bidi trailing-space decoration remains unqualified and fails explicitly when needed.
- `w:overflowPunct` cascades through paragraph defaults, styles and direct properties. Effective false keeps punctuation inside the text area. Effective true still fails because active punctuation overhang has not been qualified. An unused enabled style does not itself reject a document. Absence preserves existing layout.
- `w:suppressAutoHyphens` follows the paragraph cascade. Suppression is qualified; active automatic hyphenation is not. A document enabling `w:autoHyphenation` requires effective suppression on each rendered paragraph. Validated `w:doNotHyphenateCaps` does not enable hyphenation.
- Enabled `w:useFELayout` is qualified for the existing simple Latin, Greek and Cyrillic profile. Complex-script content remains guarded; this switch does not claim East Asian typography support.
- `w14:ligatures="standardContextual"` selects the existing standard OpenType shaping path with its contextual defaults. The independent Noto Serif reference retains searchable logical ligature text. This qualifies the source preference within existing font/script boundaries, not arbitrary scripts or every OpenType feature. Discretionary, historical and unknown values still fail.
- Style `w:autoRedefine` is an editor preference. It does not discard or recompute current paragraph/run formatting. Grammar `w:activeWritingStyle` preferences are bounded passive metadata, including legacy locale names such as `es-ES_tradnl`. No grammar engine or external resource is loaded.

New preferences validate attributes, namespaces, values and child content; malformed or duplicate singleton properties remain errors. Normalization affects a private interpretation of the package. Cleanup retains qualified source constructs rather than deleting them.

Unqualified active punctuation overflow and automatic hyphenation identify the effective source property. Playground cleanup can remove that preference while retaining the paragraph's text and unevaluated markers; it does not delete the paragraph merely to disable the preference.

## Shared PDF API

`PdfParagraph.underlineTrailingSpaces` is nullable and immutable. Use `withUnderlineTrailingSpaces(true)` to paint requested trailing underline/strikeout decorations, false to suppress them, and null to retain the original policy. The name follows Word's compatibility switch; actual Word probes establish its effect on both kinds of line. Java callers use `getUnderlineTrailingSpaces()` and the same method. Existing constructor and `copy` descriptors remain unchanged. Spacing, minimum-height, paragraph-mark, table, frame and page-band transformations retain the setting. ODT can use the same format-independent property later.

## Independent references and regression coverage

Nine synthetic fixtures under `nuaforma-docx-pdf/src/test/resources/typography-preferences` contain source DOCX, actual Microsoft Word PDF exports, source/reference SHA-256 provenance, and initially reviewed native 96-DPI PNG goldens:

| Case | Independent coverage |
| --- | --- |
| `trailing-0` | Disabled decoration; three alignments, all-space and wrapped lines on A4 |
| `trailing-1` | Enabled decoration; identical visible glyph positions, clipped right edge |
| `editor` | Editor/grammar/Far East preferences retaining inherited bold and direct italic |
| `ligatures` | Noto Serif none, standard and standard-contextual logical text |
| `hyphenation` | Suppressed global automatic hyphenation and punctuation containment |
| `bands-letter` | Letter page, table, header and footer trailing decoration |
| `inherited-underline` | Inherited bold/underline, direct disabling and centered trailing spaces |
| `strike-0` / `strike-1` | Disabled/enabled trailing strikeout, alone and combined with underline, including space-only lines |

`NativeTypographyPreferencesTest` checks cleanup identity, Word text and glyph positions, embedded fonts, validated standard/PDF/A-1b/2b/3b output and exact equality with the frozen native images. Native images are regression oracles after comparison with Word; they are not represented as pixel-identical Word exports. Existing goldens are unchanged. Do not replace references without maintainer approval.

`WordTypographyPreferencesTest` exercises inheritance, settings order, invalid metadata and unsupported active combinations. `PdfTrailingUnderlineTest` inspects actual PDF decoration bounds and visible glyph positions, including tables, frames and page bands; a Java consumer verifies the additive API. The Playground uses the same provider and cleanup behavior; no UI or transport change is needed.

The separate 250-document corpus remains a first-blocker compatibility probe, not proof that all documents render or match Word. Raw corpus contents stay local.

## Source semantics

Primary references: Microsoft's [trailing underlining](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.underlinetrailingspaces), [punctuation overflow](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.overflowpunctuation), [style editor updates](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.autoredefine), [grammar preferences](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.activewritingstyle) and [Far East layout](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.usefareastlayout). Existing JDK shaping delegates OpenType feature defaults through [OpenJDK's HarfBuzz bridge](https://github.com/openjdk/jdk17u/blob/master/src/java.desktop/share/native/libfontmanager/HBShaper.c).
