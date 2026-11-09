# Qualified Word run theme colors

Unreleased source behavior for [#245](https://github.com/NuaForma/NuaForma/issues/245).

## Resolution and inheritance

The native reader resolves `w:color` theme references through the document's embedded DrawingML color palette. A resolved palette color takes precedence over the optional cached RGB `val`; a stale cache cannot change the output. Paragraph styles, character styles, table styles and direct run properties retain their normal cascade. A direct explicit RGB color replaces an inherited theme color.

Supported roles are `dark1`, `light1`, `dark2`, `light2`, `text1`, `background1`, `text2`, `background2`, `accent1`–`accent6`, `hyperlink` and `followedHyperlink`, under the existing qualified default color-scheme mapping. An included color scheme must contain exactly one of each of its twelve standard palette slots. Palette entries use explicit `srgbClr`. Standard `windowText`/black and `window`/white system entries are also qualified when their `lastClr` matches that standard value. Other system colors, unresolved references and palette color transforms reject explicitly.

Two-digit hexadecimal `themeTint` and `themeShade` values adjust HSL luminance. When both are present, tint takes precedence, following [Microsoft's Word-specific color behavior](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/8229a077-7fc8-4fba-96cc-c77b6a4fc768). Endpoints retain the original palette color or produce white/black as appropriate. Intermediate transformed colors can differ from the tested Word export by one 8-bit channel unit because of color quantization. Base palette colors and direct RGB overrides match exactly in the qualified references.

This feature resolves run-content colors into the existing neutral `PdfColor`. It does not qualify theme-based paragraph shading, borders, DrawingML fills/effects, arbitrary system palettes or custom color-scheme mappings. No public API, module, dependency or runtime-font change is introduced. Theme state is private to each document read.

## Source integrity and Playground

Malformed themed run-color attributes, nested content, duplicate colors and invalid original palette structure are checked on the original selected source before intermediate DOCX serialization can discard them. Unsupported content produces the existing focused development diagnostic, identifying the source part and construct. Missing palettes cannot silently fall back to a cached color. Cleanup retains already-qualified source bytes, and template processing keeps the normal visibility and expression rules. The Playground uses this same provider; no UI or deployment change is needed.

## References and regression protection

Two synthetic fixtures use different palettes on A4 and US Letter. Each has two pages: 33 base/transformed color cases, paragraph/character/direct overrides and a conditional table. Actual Microsoft Word exports and source/font hashes accompany both fixtures. The references contain 120 unique control markers across both documents.

The real provider is checked in standard PDF and PDF/A-1b, 2b and 3b, including independent archival validation, embedded fonts, page content/geometry, table fills, marker positions, sizes, faces and colors. Initial measurements are at most 0.071 pt horizontally, 0.131 pt vertically and one channel unit for intermediate theme transforms. Four initially reviewed engine images are immutable golden files; all 16 profile-page comparisons are exact at 96 dpi without masks. Existing goldens are not replaced.

`WordLayoutOracle` explicitly processes PDF color operators. A separate regression supplies two known PDF text colors and checks their recovered marker colors, so future comparisons cannot accidentally treat all text as default black.

Rebuild synthetic sources with `python tools/pdf-evaluation/own-engine/build_theme_color_fixture.py --base nuaforma-docx-pdf/src/test/resources --output build/theme-color-sources`. Export independently through the documented Word font workflow before authoring a new reference. Run `./gradlew :nuaforma-docx-pdf:test --tests 'dev.nuaforma.docx.pdf.NativeWordThemeColorTest'` and the full normal build. Tests never regenerate goldens.
