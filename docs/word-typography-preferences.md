# Word typography preferences

Source work for [#232](https://github.com/NuaForma/NuaForma/issues/232), extending the existing DOCX reader and shared PDF engine. This is unreleased behavior. No package, dependency, fallback renderer or output profile is added.

## Qualified settings

- `w:compat/w:ulTrailSpace` explicitly controls decoration of spaces at a line's end. Word applies the same switch to underlining and strikeout, separately or together. Enabled spaces extend the requested lines without moving left, centered or right aligned text; decoration stops at the paragraph's right boundary. Wrapped separating spaces and space-only lines follow the same rule. Disabled spaces are not decorated. Absence retains original behavior. Bidi trailing-space decoration remains unqualified and fails explicitly when needed.
- `w:overflowPunct` cascades through paragraph defaults, styles and direct properties. Effective false keeps punctuation inside the text area. Effective true still fails because active punctuation overhang has not been qualified. An unused enabled style does not itself reject a document. Absence preserves existing layout.
- `w:suppressAutoHyphens` follows the paragraph cascade. Suppression is qualified; active automatic hyphenation is not. A document enabling `w:autoHyphenation` requires effective suppression on each rendered paragraph. Validated `w:doNotHyphenateCaps` does not enable hyphenation.
- A leaf `w:noBreakHyphen` inside an ordinary text run paints the hyphen-minus within the nonbreaking word. Word exports confirm that adjoining fragments move together when the word fits the next line. Discretionary hyphens and automatic hyphenation remain separate cases. [WordprocessingML nonbreaking hyphen](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.nobreakhyphen)
- Enabled `w:useFELayout` is qualified for the existing simple Latin, Greek and Cyrillic profile. Complex-script content remains guarded; this switch does not claim East Asian typography support.
- `w14:ligatures="standardContextual"` selects the existing standard OpenType shaping path with its contextual defaults. The independent Noto Serif reference retains searchable logical ligature text. This qualifies the source preference within existing font/script boundaries, not arbitrary scripts or every OpenType feature. Discretionary, historical and unknown values still fail.
- Style `w:autoRedefine` is an editor preference. It does not discard or recompute current paragraph/run formatting. Grammar `w:activeWritingStyle` preferences are bounded passive metadata, including legacy locale names such as `es-ES_tradnl`. No grammar engine or external resource is loaded.
- When the supported ordinary-text style hierarchy supplies no `w:sz`, the adapter starts from the 10-point nominal size observed in the A4 and Letter Word controls. A `w:szCs` by itself does not specify the ordinary-text size. Explicit and inherited `w:sz` values still take precedence. Font-family identity and complex-script selection use the existing checks. [WordprocessingML font size](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.fontsize)

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

## Display capitals

Word `w:caps` changes visible text after template substitution. The source template and input data retain their original casing. Direct formatting and the effective paragraph declaration can enable or disable it. An enabled character-style declaration toggles the paragraph setting and a false character-style declaration leaves the paragraph setting in place. The qualified Latin mapping uses simple uppercase glyph equivalents, including Turkish dotted I where the run language requests it and the unchanged single-character German sharp S observed in the reference exports. The PDF search/extraction text follows the printed case. Active case changes outside this Latin profile remain explicit failures.

## Zero-width text outlines

A qualified [Word text outline](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-docx/5b065f17-63c7-4d54-83e3-6f3c161f3afa) with explicit zero width does not paint. Word keeps the same searchable text and emits no outline path for the black, red and unfilled zero-width controls. The A4 reference retained identical pixels. The Letter reference placed some glyphs up to 0.27 point from the control. Qualified shapes use the default or round cap, a single centered stroke, and either an empty outline or a supported fill declaration with a solid dash and bevel. Visible outlines and unsupported structure still fail with the source location.

## Empty legacy page-band frames

An invisible legacy paragraph frame with the qualified centered, text-relative geometry occupies no flow space in the header or footer. Its original DOCX metadata is retained. Word references keep the same page pixels with an empty frame; an ordinary blank paragraph still takes up space. Frames with visible content, other geometry or page-break behavior remain qualified separately.

## Built-in page-number galleries

Word can store visible header or footer runs inside a [built-in document-part control](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.sdtcontentdocpartobject). The top and bottom page-number galleries retain their stored paragraphs and runs, including nested controls and existing page fields. The renderer reads the stored content from the document and recomputes the page number. Other galleries, bindings and unsupported metadata keep their source diagnostics.

## Formatting repeated complex page fields

In a header or footer, a supported complex page-count field normally uses the
formatting from its instruction run. With MERGEFORMAT it uses a uniform stored
result style when present. Word can omit the cache entirely and the field still
uses its code style and receives the calculated page number or page count.
Uncached static, linked and cross-paragraph field constructs remain qualified
separately.

## Paragraph spacing in line units

Word's [line-unit paragraph spacing](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/264a0139-8174-4574-b20d-ab778e770931)
uses `beforeLines` and `afterLines` in hundredths of a nominal line. In the
qualified A4 and Letter references the nominal unit is 12 points, independent of
the adjacent text size and its line-height multiplier. A nonzero line-unit
value takes precedence over the corresponding twip value, including a twip
value set later in the style hierarchy. Resulting gaps follow the same
automatic-spacing, contextual-spacing and paragraph-collapse rules as physical
gaps.

## Font family without an embedded theme

Word uses Times New Roman for qualified text whose style chain supplies no font
family when the package has no embedded document theme. Explicit font selectors
retain precedence. The runtime resolves the resulting family through the
configured font resources.

## Word no-proofing language

An explicit x-none value for Word's Latin proofing language clears an inherited
language for the affected text. The PDF style uses neutral case mapping and
omits language metadata for those runs.

## Vertical alignment within text lines

Top, center and bottom alignment can use the ordinary baseline for qualified
natural-height lines of uniform text. The Word control pages agreed visually
with the baseline sample for the uniform text. Mixed metrics, shifts and
explicitly fixed line boxes can change the vertical placement and retain a
source diagnostic.

## A line-spacing rule without a measurement

A paragraph can supply a line-spacing rule without a line measurement. Word
retains the inherited line box, while applying any explicit paragraph spacing
before or after the text.

## Discretionary hyphens

A Word soft-hyphen control in ordinary unshaped text gives the physical line
layout a possible break. It produces a visible hyphen only when selected to
end a line. Widely spaced text keeps the word joined. Automatic dictionary
hyphenation, tab-aligned text and automatic table widths retain a diagnostic
when they require unsupported combinations.

## Language tags with a region separator

Word accepts language-and-region values such as pt_BR and tr_TR. The PDF
model uses the corresponding language tags pt-BR and tr-TR, with the same
language-sensitive casing.

## Explicitly disabled small caps

An off setting for small caps on a text run or paragraph mark leaves the
printed glyphs and line metrics unchanged. Active small caps retain a source
diagnostic until their glyph sizing is qualified.

## Hanging list markers left of the text area

A left-aligned marker with a tab suffix can extend left of the text area when
it fits before the paragraph indent. The first line starts at the paragraph
indent. The marker must stay within the physical page.

## Visible web addresses

An HTTP or HTTPS address can wrap after an existing ASCII hyphen or fragment
separator in its path. Each resulting piece retains its text style and
hyperlink target. A word that fits remains intact. The PDF adapter retains
its overflow diagnostic when there is no qualified break, and keeps the
separate rules for tabs, lists, right-to-left text, and emergency breaks in
table cells.

## Compound paragraph borders

The thin-thick and thick-thin small-gap paragraph borders use two solid
strokes. Word's stored width controls one stroke. The second stroke and
the gap are each 0.75 points. The strokes run in page order from top
to bottom or from left to right, and the layout reserves the combined
extent. Compound
borders between paragraphs retain a source diagnostic.
