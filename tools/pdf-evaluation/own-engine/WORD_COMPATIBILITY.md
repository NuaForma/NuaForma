# Word paragraph metadata and page-band logos

Issue #196 uses generated public documents rather than a private input document. The original input was unavailable for conversion; no claim about its exact rendering is made.

`build_word_compatibility_fixture.py` creates A4 and Letter documents with a large header EMF logo, a smaller footer EMF logo, real footnotes/endnotes, flowing text, signed indents and paragraph-mark run properties. Both logos are synthetic colored geometry. No private document, branding or text is embedded.

## Translation rules

- Paragraph-mark `pPr/rPr` supplies minimum natural line metrics and empty-paragraph formatting. It does not replace visible run formatting. Empty formatting-only runs do not require unused faces.
- Left/right indents may be negative within the physical page. First-line/hanging lengths remain nonnegative; hanging takes precedence. Off-page content rejects explicitly.
- DOCX page bands opt into measured content reservation. Body and footnotes share the remaining area. Footer offset is measured from the physical page bottom; a footer beyond the original bottom margin does not justify deleting it.
- Linked style metadata does not change the independent definitions selected by `pStyle` and `rStyle`. Named style-pane filters affect the editor UI only. Recognized attributes remain validated.

## Bounded EMF support

The DOCX adapter translates a qualified GDI EMF subset into request-local PNG pixels at 288 dpi for the declared size. The shared model and PDF writer remain source-neutral. This rasterizes image paths/text; image text is not searchable.

Records cover solid/null brushes, basic/extended pens, lines, polygons, Bezier paths, rectangles, ellipses, rounded rectangles, bounded transforms/clipping, saved contexts, RGB source-copy DIBs and explicitly positioned text. Text uses configured physical font bytes and existing permission checks. There is no system-font substitution, Office invocation or external resource loading.

Unknown records, EMF+ comments, GDI escapes, unsupported bitmaps and unqualified modes reject with an image-part diagnostic. Playground cleanup can remove that image and its reference while preserving surrounding band text and notes. Resource-limit/cancellation failures are not removable formatting.

Limits include 4 MiB input, 20,000 records, 256 object handles, 32 saved contexts, 32,000 points and 64 clip operations. Raster limits are 4096 pixels per edge and 4,194,304 cumulative pixels per translation, alongside existing request byte limits. Identical image/size combinations share request-local results.

Transparency is retained. Proven fully opaque rasters use RGB; their PDF/A-1b conversion is independently validated. PDF/A-1b continues to reject genuinely transparent images; no background is filled and no silent flattening is introduced. Standard PDF and PDF/A-2b/3b retain existing validation requirements. An additional real-provider test renders EMF text from configured physical font bytes, and seeded mutation tests inspect 1,200 damaged geometry/bitmap/font records for bounded, declared failures.

## Verification

Focused tests: `NativeWordCompatibilityTest`, `PdfWordGeometryTest`, `EmfPictureTest`, `WordLogoNotesTest`, `WordSettingsMetadataTest` and `WordAuthoringMetadataTest`. The combination test checks real conversion and independent PDF validation, both logos on every page, footnotes/final body content and unchanged cleanup bytes for supported source.

Export generated documents with `export_word_fonts.ps1` using repository Liberation Sans faces; use `compare.py` for per-page comparison and inspect rendered pages. Results stay under ignored build output until reviewed. Existing golden files must not be regenerated.

Real Word comparison on 2026-11-02 produced two pages for both A4 and Letter. Position-sorted words match on every page, both logos remain visible and footnotes stay above the footer. The standalone automatic image line uses the larger of the image and minimum font boxes; an unprinted paragraph mark does not add descent below a taller image. This preserves the Word paragraph/page boundaries in both cases.

| Case/page | Matching words | Maximum word-box top difference | Raw changed pixels at 144 dpi |
| --- | ---: | ---: | ---: |
| A4/1 | 369/369 | 0.148 pt | 3.169% |
| A4/2 | 96/96 | 0.649 pt | 1.166% |
| Letter/1 | 327/327 | 0.148 pt | 2.204% |
| Letter/2 | 138/138 | 0.639 pt | 1.174% |

These are unaligned measurements, not a pixel-identity claim or a relaxed assertion threshold. Small note/footnote baseline and separator-weight differences remain. Raw PDF content-stream text order differs for page bands, so text inventory and position-sorted word comparisons are recorded separately. No existing reference or tolerance was changed to hide differences. GDI left/right transform composition was independently checked with the Windows transform APIs; both noncommuting cases have pixel-position regression tests.

Playground impact: fewer unnecessary removals, focused image failures and original numeric values in cleanup explanations. No new production cleanup endpoint or permissive conversion mode is introduced.

References: [paragraph marks](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.paragraphmarkrunproperties?view=openxml-3.0.1), [linked styles](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.linkedstyle?view=openxml-3.0.1), [style-pane filters](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.wordprocessing.stylepaneformatfilter?view=openxml-3.0.1), [EMF](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-emf/).
