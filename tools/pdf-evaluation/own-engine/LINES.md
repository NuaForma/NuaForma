# Paragraph borders and drawn lines — source qualification (#172)

This unreleased source feature adds passive vector strokes to the shared `nuaforma-pdf` model. The DOCX adapter resolves paragraph border inheritance and translates explicit line geometry; an ODT adapter can use the same model. No new module, renderer service, system font or dependency is introduced. Published alpha.5 is unchanged.

## Source profile

- Paragraph top/right/bottom/left/between borders: `single`, `double`, `dashed`, `dotted`, explicit RGB or automatic black, integral eighth-point widths within 0.1–4 points, spacing 0–31 points. Direct edge overrides and `nil`/`none` clear inherited edges. This includes Word's automatic paragraph rules.
- Equal consecutive border sets with equal left/right indents join into a frame. A `between` edge separates paragraphs. Border distance and stroke extent reserve their own space independently of ordinary collapsed paragraph spacing. Double borders reserve three stroke widths. Side borders follow text fragments; a continued paragraph does not gain artificial top/bottom edges at a page break.
- Explicit non-wrapping anchored VML `v:line` and DrawingML `prstGeom=line`, including horizontal, vertical, diagonal and reflected/rotated lines. Coordinates may follow paragraph/content axes or fixed physical page/margin axes. VML physical units are points, inches, centimetres and millimetres. Opaque RGB solid/dashed/dotted strokes and explicit DrawingML double strokes are qualified; line layers preserve behind/above text selection.
- Paragraph decorations participate in ordinary body, table-cell and page-band layout. Header/footer lines repeat with their bands. Existing table edges and tab leaders keep their existing representation and behavior.

Unsupported effects reject explicitly: theme/style-reference drawing strokes, transparency, shadows, artistic border styles, arrows, custom geometry, inline or wrapping line shapes, arbitrary VML CSS and borders beside floating images. This is line support, not general vector-shape or WordArt support. The diagnostic inspector remains available for rejected input; it does not turn unsupported shapes into qualified PDF output.

## Model and output safety

`PdfLineStroke`, `PdfParagraphEdge`, `PdfParagraphBorders` and `PdfLineDrawing` express points and RGB without DOCX/PDFBox types. `PdfParagraph` retains its original Java overloads and Kotlin default-constructor binary bridge; decorated constructors are additive. Collections are defensive immutable copies. Request-local layout retains decorations through normalization, fields, notes and page-band reconstruction. A paragraph accepts at most 32 drawn lines; PDF writing caps generated vector strokes at 20,000 and observes cancellation. Invalid/non-finite dimensions and drawing outside the physical canvas reject before any caller output is published.

Strokes are PDF artifacts, not invented logical text. They use opaque vector commands in standard PDF and PDF/A-1b/2b/3b, with existing font embedding and independent profile validation retained. Level A/UA support is not claimed.

## Reproduction and references

`build_line_fixture.py` deterministically builds `nuaforma-docx-pdf/src/test/resources/own-engine-lines/source.docx`. It includes four border styles, style inheritance/removal, joined frames with/without between rules, VML/DrawingML lines, rotation, a cell border, page-band borders/drawings and natural pagination. `NativeLinesTest` renders it through the production reader and registered provider. `PdfLinesTest`, `NativeDocxLinesTest` and the Java API test cover geometry, malformed/unsafe input, defensive copies, original binary construction and concurrent rendering.

Export the same source read-only with `export_word.ps1`, retaining its `.provenance.json`. The native integration test writes review PDFs/rasters under its `build/172-lines` directory. Word is a qualification tool only, never a test/runtime dependency. Existing golden files must not be regenerated for this feature.

## Initial Word comparison, 2026-11-01

Microsoft Word 16.0 build 16.0.20527 and the production native reader both generate four pages. `audit_lines.py` finds identical extracted words and all 51 control identifiers on identical pages:

| Page | Controls | Maximum horizontal displacement | Maximum vertical displacement |
|---|---:|---:|---:|
| 1 — rules, inherited/cleared edges, joined frames | 14 | 0.036 pt | 0.0974 pt |
| 2 — solid/dashed/dotted, vertical and double drawings | 8 | 0.036 pt | 0.1474 pt |
| 3 — reflected diagonal, page-fixed line, cell border, pagination | 20 | 0.036 pt | 0.0774 pt |
| 4 — continued body and joined rules | 9 | 0.036 pt | 0.0374 pt |

All four page pairs were visually inspected using the same PDFium rasterizer. There is no missing text, clipping or changed page break in the qualified input. Word quantizes page dimensions by 0.02 pt and paragraph stroke widths to a 0.12-pt grid; the engine retains the source point values. Drawing endpoint/dash measurements also distinguish paragraph dash patterns from VML/DrawingML shape patterns and confirm that a DrawingML compound width covers the whole double stroke, unlike a paragraph edge's per-stroke width. The footer drawing follows the paragraph's outer border origin, not its shifted text baseline.

Additional read-only Word probes verify a long bordered paragraph spanning two short pages: side borders continue, while only the first fragment receives a top edge and the final fragment a bottom edge. `PdfLinesTest` locks that pagination behavior. A cell-local drawn-line variant is independently built by `NativeLinesTest` for Word/export review.

The cell-local line has identical Word/native endpoints `(284.7, 298)`–`(324.7, 298)` pt. A second source variant adds 20 pt before `G12`: its connected side borders remain continuous and its horizontal between edge stays fixed, matching Word visually and geometrically. Connections are resolved after actual pagination, and a separate short-page test confirms that they do not bridge onto another page. The band variant also verifies connection after page-field reconstruction. VML `shortDash` is separately checked against Word's `3 × width, 1 × width` pattern.

The cell variant also includes a pale-yellow fill. Its paragraph border remains visible above the fill; a native raster assertion checks the actual border color, and the filled Word/native cell crops were visually compared. Paragraph borders belong to the content flow, while anchored drawings retain their explicit background/foreground layer.

The protected initial native raster references and source/Word hashes are documented under [own-engine-lines](../../../nuaforma-docx-pdf/src/test/resources/own-engine-lines/README.md). They assert exact native pixels at 96 DPI, separately from the approximate Word comparison. Tests never update references automatically. No pre-existing golden was changed.

Local verification: the complete `build coverageReport -Pcoverage=true --warning-mode=fail` and production coverage-inventory check pass, with 1,031 tests and no failures/errors. The aggregate report covers 10,045 of 10,518 executable JVM lines. Java API/default-constructor compatibility, concurrent shared-engine rendering, vector limits, physical stroke bounds and the real HTTP diagnostic/success paths are included. CI/native-platform/Sonar qualification belongs to the separately authorized PR delivery; these results are local source evidence, not a published release.
