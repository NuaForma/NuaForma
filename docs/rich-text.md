# Rich text in Word templates

Source feature for the next release after `0.3.0-alpha.4`, tracked in [#118](https://github.com/NuaForma/NuaForma/issues/118). Alpha.4 does not include this pipe. This is a bounded RTF text profile, not a general Office/HTML importer.

## Start with one marker

Put this in an ordinary Word paragraph:

```text
{description | richText("rtf")}
```

Supply an RTF **string** in the existing request data. For example, the JSON below contains the doubled backslashes required by JSON:

```json
{
  "description": "{\\rtf1\\ansi\\uc1 Plain {\\b bold}, {\\i italic}, {\\ul underlined}, {\\strike crossed}.\\par Second paragraph with Caf\\u233?.}"
}
```

The expression is evaluated before the content pipe. Conditions and repetitions use their existing scopes. The returned text becomes document elements and is never evaluated again as template instructions; escaped RTF text such as `\{customer.name\}` remains literal `{customer.name}`. Ordinary `{description}` placeholders continue to insert literal text and do not recognize RTF.

Use only this content pipe in its marker. Preceding/following pipe stages are rejected. A surrounding expression such as `{selectedDescription ?? fallback | richText("rtf")}` is allowed. An absent path, `null`, or a zero-length string inserts nothing. Invalid calculations, missing argument expressions and non-string values remain errors.

## Placement and empty paragraphs

The complete signature is positional:

```text
{description | richText("rtf", trimStart, trimEnd, inline)}
```

| Argument | Default | Meaning |
| --- | --- | --- |
| `format` | Required | Exactly `"rtf"`; no format detection. |
| `trimStart` | `true` | Remove empty paragraphs at the beginning of the fragment. |
| `trimEnd` | `true` | Independently remove empty paragraphs at the end. |
| `inline` | `true` | Join the first/last inserted paragraphs to the source paragraph's prefix/suffix. |

An empty paragraph contains **no text characters**. Spaces, tabs and nonbreaking spaces remain content: a space can have visible underline, background or font formatting inherited from the marker. Internal empty paragraphs are preserved. A terminal RTF `\par` closes the current paragraph; it does not invent another empty paragraph. Two successive terminators can therefore encode an intentional blank paragraph.

For example, `Before {description | richText("rtf")} after` with two RTF paragraphs becomes `Before First` and `Second after`. Literal text and spaces outside the marker retain their formatting. To require a standalone marker paragraph, use:

```text
{description | richText("rtf", true, true, false)}
```

That paragraph may contain surrounding ordinary spaces, which are preserved. Other text or markers outside the rich-text marker are rejected in this mode. A missing/empty fragment removes its marker and an otherwise empty ordinary paragraph. An empty body/table cell retains the structural paragraph needed for a valid document. A paragraph owning a section, frame, list, heading or page-start boundary is retained when empty; these boundaries must not disappear with optional data.

Supported placement is an ordinary text-only paragraph in the body, table cells, headers, footers and the already supported text boxes. Markers may span adjacent visible text runs. Hidden Word text keeps the [visible-source rules](template-diagnostic-pdf.md). Fields, hyperlinks, drawings, bookmarks/ranges and other mixed-content paragraphs reject rich insertion. Multi-paragraph insertion cannot split section/frame ownership, numbering, heading or page-start boundaries, including inherited ones. Source paragraph layout and style remain authoritative; use a body paragraph for multi-paragraph content. Comments, footnotes and endnotes reject rich markers.

## RTF text profile

The fragment must be a complete `\rtf1` root group. Character overrides apply only to inserted text; unspecified properties inherit the marker's formatting. Nested groups restore previous overrides. `\plain` returns to that marker base. Source paragraph spacing, indentation, direction and page geometry are retained. Empty-paragraph heights follow the template paragraph style; RTF character changes with no text do not replace paragraph-mark formatting.

| Supported construct | Scope |
| --- | --- |
| `\b`, `\i`, `\ul`, `\ulnone`, `\strike` | Bold, italic, single underline/strike; `0` disables a toggle and `1` enables it. |
| `\fsN` | Half-point character sizes, `2` through `400` (1–200 points). |
| `\fonttbl`, `\fN` | Up to 64 bounded family names; explicit font selection, no font programs or retrieval. |
| `\colortbl`, `\cfN` | Up to 256 automatic/RGB entries; components `0` through `255`. |
| `\par`, `\tab` | Real paragraph endings and tabs. `\pard` retains the template's paragraph layout. |
| `\\`, `\{`, `\}` | Literal backslash and braces. |
| `\~`, `\_` | Nonbreaking space/hyphen. |
| `\emdash`, `\endash`, `\bullet`, quote controls | Ordinary Unicode punctuation. |
| `\uN`, scoped `\ucN` | Signed UTF-16 units with 0–16 ANSI fallback characters; valid surrogate pairs are supported. |
| `\'hh` | Strictly decoded ANSI/UTF-8 hexadecimal bytes; no replacement of malformed data. |

Raw body text uses printable ASCII. Encode non-ASCII text using `\uN` or hexadecimal escapes. `\ansi` and `\ansicpgN` support Windows code pages 1250–1258 and UTF-8 (65001). Font charsets 0/1, 162, 163, 177, 178, 186, 204 and 238 use the documented corresponding code page. Raw CR/LF format the RTF source and do not create document breaks. A control word followed by letters needs a delimiter: write `\par Second`, not `\parSecond`.

Only a small, bounded set of nonvisual header metadata is accepted (`deff`, language/view identifiers, `nouicompat`, the ordinary font family/pitch declarations and a `generator` destination). A default-font declaration does not override the marker until an explicit `\fN` selects a declared family. Unknown controls/destinations fail, including unknown `\*` destinations. This avoids silently discarding requested formatting.

Images, tables/lists, imported document/section/paragraph layout, manual line breaks (`\line`), hidden RTF text, fields/links, scripts, DDE, OLE/objects, binary payloads, embedded fonts and external/file/network content are outside this profile. They are rejected before interpretation or retrieval. There is no Office process or network access in the reader.

## Fonts and PDF output

RTF family names do not grant access to installed host fonts. DOCX uses its existing `fontMode`/`fontSelection` policy. Declared validation/embedding rechecks names introduced by the fragment without reopening resources or embedding a supplied face twice. `fontSelection: "used"` collects actual faces after insertion. Explicitly requested fonts and embedding permissions must still be satisfied.

DOCX/PDF conversion uses the same filled DOCX and the existing native profile: standard PDF, PDF/A-1b, 2b and 3b, with mandatory independent validation for archival output. Fonts are embedded under that profile's existing rules. Single strikeout is drawn using the font's OpenType strikeout metrics. The native reader continues to reject unqualified scripts, missing glyphs/fonts, insufficient fixed line heights and other unsupported layout; accepting an RTF Unicode character does not add shaping support to the PDF engine. Explicitly qualified Hebrew RTL and Cyrillic text are covered alongside A4/Letter.

## Limits and diagnostics

Limits are request-private and cumulative across all parts, loops and markers; normal generation deadlines/cancellation also apply. There is no tenant-content cache.

| Budget | Limit |
| --- | --- |
| Encoded source | 1 MiB per fragment; 4 MiB per generation, additionally capped by `maxExpandedBytes`. UTF-8 accounting is conservative. |
| Group nesting | At most 64, or the lower configured expression-depth limit. |
| Control names / numeric digits | 32 ASCII letters / 9 digits. |
| Parser tokens | 250,000 per fragment; 1,000,000 per generation. |
| Decoded UTF-16 units | 100,000 per generation, including recognized metadata. |
| Formatted spans | 4,096 per fragment; 16,384 per generation. |
| Paragraphs | 512 per fragment; 2,048 per generation, charged before trimming. |
| Rich model / WML copy accounting | At most 32 MiB, lower `maxExpandedBytes`, and `1024 × (encoded source bytes + 1 KiB)`; copied property subtrees are capped at 16 KiB before cloning. |

Paragraph-boundary checks also cap the source style index at 10,000 entries and ancestry at 64. These are accounting/work bounds, not a promise about total JVM heap usage. The existing content-call and output/document budgets remain applicable. Limit/cancellation failures publish no output. Malformed RTF reports `INVALID_REQUEST` with `INVALID_RICH_TEXT`; unsupported RTF reports `UNSUPPORTED_RICH_TEXT`. Its `rtf:N` location is a zero-based UTF-16 position in the supplied RTF string. DOCX part/paragraph context identifies the owning marker in development mode. The message uses application-owned explanations; it never includes arbitrary parser exceptions or embedded data. `UNSUPPORTED_RICH_TEXT_PLACEMENT` identifies a source-layout restriction. The marked diagnostic-PDF operation diagnoses template-language syntax, not RTF request-data errors.

The Playground uses the same endpoints. Its provider catalog exposes the four arguments, defaults, media type, profile combinations and `missingAsNull` policy. Enter the RTF string in the normal JSON data editor; request export preserves those string bytes. No browser-side RTF interpretation or extra request object is required.

## Contracts and compatibility

Contract 0.7 adds `ContentPipeDefinition.missingAsNull` with a `false` default for existing constructors/providers. Only an opted-in content pipe normalizes a missing value to null. Ordinary placeholders, value pipes and argument expressions retain strict missing/null distinctions. Built-in `richText` opts in. `image` and `richText` are reserved in the DOCX generator; up to 126 custom content registrations fit alongside them within the existing total of 128.

The PDF model adds immutable single-strike styling. `PdfTextStyle` retains its original Java/Kotlin constructor, default-constructor, six-property `copy`/`copy$default` and destructuring descriptors. Copies retain the decoration; `withStrikethrough(true)` selects it. Its implementation now supplies value methods explicitly rather than using Kotlin's `data` marker, so reflection-based `isData` inspection changes. Tests cover existing descriptors and execute independently compiled legacy consumers before delivery.

The RTF parser, request budgets and structured result are private to the existing DOCX artifact. Core/template depend only on contracts and do not expose RTF/docx4j types. The shared PDF style remains format-neutral for a future ODT adapter. No new published artifact or parser dependency is introduced.

See the [qualification report](../tools/pdf-evaluation/own-engine/RICH_TEXT.md) for executable cases, independent Word comparison and published-binary compatibility evidence.
