# Text highlighting and solid run shading

The native DOCX/PDF reader supports Word's sixteen `w:highlight` colors and solid text-run shading (`w:rPr/w:shd`, `w:val="clear"`). RGB fills, transparent automatic fills and the existing bounded theme fill/tint/shade resolver are supported. Paragraph and table shading patterns are not newly qualified by this work.

Highlight and shading inherit independently through the supported style hierarchy. Highlight takes visual precedence. `highlight="none"` reveals any inherited shading. A direct shading `nil` retains inherited run shading, as verified with Word; `clear` with automatic fill clears that inherited shading. White is opaque, including over a colored table cell.

Backgrounds cover the physical line box and each fragment's advance, including internal spaces, justified gaps and typed tabs. They follow wrapping, line breaks, page bands and table translation. Final trailing spaces follow the existing line-ending policy. Superscript and mixed font sizes share the physical line background. Backgrounds paint before the corresponding glyph group, remain PDF artifacts and preserve searchable text and embedded fonts.

Word's automatic foreground uses run shading when deciding between black and white; highlighting alone does not alter that decision. Explicit RGB or themed text colors remain explicit. Automatic selection is qualified against actual Word exports, including gray, component and mixed-RGB boundary controls; it is not a general accessibility contrast promise. The neutral PDF engine preserves its caller's explicit text color.

## Shared model

The shared additive API is `PdfTextStyle.background: PdfColor?` and `withBackground(color: PdfColor?)`. Null means no background. Existing constructors and copy signatures remain, and copies and decoration methods preserve the setting. This belongs to the existing shared `nuaforma-pdf` artifact for reuse by a future ODT adapter. No dependencies or packages were added.

## Qualification and boundaries

`python3 tools/pdf-evaluation/create_text_background_fixtures.py <fresh-directory>` creates inert A4/Letter source controls and refuses an existing directory. It neither generates reference PDFs nor replaces golden files. Actual Word exports preserve source hashes and record exporter provenance. Tests compare content, page dimensions, marker positions and foreground colors with Word, then compare native output exactly with independently reviewed initial 96-dpi golden images without masks. Highlight and shading controls also exercise PDF/A-1b, 2b and 3b, alongside standard PDF.

Controls cover all highlight colors, style inheritance and reset, highlight/shading precedence, theme tint/shade, opaque white on colored cells, mixed sizes, superscript, tabs, line breaks, wrapping, justification, Unicode and page bands. Invalid colors, duplicate declarations, foreign attributes and unsupported patterns reject without publishing a PDF. Original XML is validated before binding. Physical page and frame bounds remain enforced, with measured development diagnostics. Existing golden files remain unchanged.

Local source qualification on 2026-11-18 passed the complete build (1,880 JVM tests, no failures, errors or skips), 24 real Playground HTTP PDF requests and a source-located patterned-shading rejection. The 250-document development corpus improved from 14 to 17 complete conversions. All source hashes remained unchanged; the previous 14 successes retained all 24 pages with identical extracted text and pixels at 96 dpi without masks. The three new successes contain five pages, reviewed for visible clipping and readable content. These corpus checks are separate from the genuine Word comparisons in the ten maintained synthetic controls; the corpus itself and local fonts are not distributed as fixtures.

Run backgrounds on drawing objects are explicitly rejected. Patterned run shading and additional paragraph/table shading semantics remain outside this qualification. The Playground uses the same provider and needs no separate control. Previously published artifacts remain unchanged; this describes source work awaiting delivery.

Microsoft documents [highlight precedence](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.highlight) and [run shading](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.shading). Geometry, inheritance edge cases and automatic foreground choices were checked independently against Word exports.
