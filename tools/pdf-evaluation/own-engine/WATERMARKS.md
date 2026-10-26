# Native requested-watermark qualification

Requested PDF watermarks were part of the original scope. The earlier alpha-complete statement was incorrect while the native provider rejected them. The maintainer required restoration before `0.3.0-alpha.1`, on a separate branch from and targeting develop. This increment restores the existing `TextWatermark` request API; it does not publish a release.

## Profile and implementation

Standard PDF, PDF/A-2b and PDF/A-3b accept text, RGB color, opacity and clockwise rotation. Every requested watermark rejects for PDF/A-1b before source access, including opaque or empty-resolving requests. DOCX watermark output remains unchanged.

The DOCX facade resolves the watermark once in the same template session as the document, retaining validation, shared budgets, locale/time-zone/value pipes and literal inserted data. A private conversion intermediate omits the generated header decoration and returns immutable resolved metadata. The native adapter passes it to the format-neutral `PdfWatermark`; the shared engine paints behind existing page content without affecting flow. Fonts retain explicit catalog selection and embedding checks; there is no OS-font fallback or Office renderer.

The shared render overload is additive: existing engine constructors/render methods and ordinary DOCX generation remain available. State is request-local. Tests exercise 12 concurrent users of one shared engine, independent graphics-state restoration, missing fonts, unsupported shaping, safe overlong rejection and cleanup. Latin, Greek, Cyrillic and bounded Hebrew/numbers retain the existing script restrictions. Rotated extraction tools may return physical glyph order; explicit `ActualText` preserves the original logical Unicode.

## Genuine Word comparison

`NativeWatermarkProof` produces five native PDF/A-3b candidates, plain controls and fresh DOCX files through the registered provider. Word exports those DOCX files using `ExportAsFixedFormat`; provenance records bind the exact input/output hashes. No old Word reference or golden is overwritten.

| Case | Pages | Purpose |
| --- | ---: | --- |
| A4 transparent | 1 | Dynamic conditional/reference text, opacity 0.25, rotation -45 degrees |
| Letter landscape opaque | 1 | Opacity 1, rotation 30 degrees, physical-page centering |
| Multipage | 4 | Same watermark on every page, including a short final page; preserved paragraph pagination |
| Cyrillic/Greek | 1 | Supplied-font glyph coverage and logical Unicode |
| Hebrew | 1 | Bounded RTL/numbers and rotation |

All eight page pairs retain all 6,111 non-whitespace body characters in order and the same font faces. Native body positions, sizes and colors are exactly identical with and without the watermark. Against Word, maximum body glyph-start/top differences are 0.297/0.128 pt. Word emits 11-point source text at 11.04 points; this existing exporter rounding is recorded and bounded to one OOXML twip (0.05 pt).

The requested decoration retains page centering, rotation, color and opacity. Word paints its WordArt as outlines, while the native engine embeds real text. Visible ink outlines differ: the measured maximum edge displacement is 6.13 pt (2.17 mm) on these large decorations. This is recorded rather than hidden with offsets or reference edits; there is no claim of pixel-identical WordArt glyph contours. The paired native/Word pages and all four new engine-only references were visually inspected; independent Poppler rendering confirms the layout. Native `ActualText` additionally retains the resolved watermark string on every page, including RTL.

## Reproduction

Build test classes and `:nuaforma-pdf:prepareOwnEngineValidation`, then run `:nuaforma-docx-pdf:renderNativeWatermarkProof` with five explicit arguments: the trusted `native-profile/word-letter.docx`, a fresh module build directory, the staged Liberation Sans font directory, the validator directory and Java 17 executable. The proof writes the original variant inputs, resolved DOCX files, marked PDFs and plain controls.

For each generated `<case>.docx`, invoke `export_word.ps1` with a fresh `<case>.word.pdf` destination. Then run `python tools/pdf-evaluation/own-engine/audit_watermarks.py --directory <proof-directory>` using Python with pdfplumber/pypdf. The audit verifies provenance, source watermark text, exact native body preservation, Word body/font/geometry bounds and each page's logical decoration text. Raster comparisons are diagnostic, not replacements for structural or independent PDF/A checks.

The runtime tests independently validate transparent and opaque output in all three supported profiles. Real HTTP/container qualification includes the same matrix and explicit PDF/A-1b rejection; both native architecture checks must pass before merge. The engine golden corpus gains four initial pages; all 26 prior PNGs and font/logo input hashes are unchanged. Existing golden comparison tolerances remain zero, without exclusions.
