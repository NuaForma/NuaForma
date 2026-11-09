# Font-defined private-use glyphs

Source issue #258 adds bounded BMP private-use glyph rendering to the shared PDF engine. These characters have meanings defined by the supplied font; the engine does not substitute a system font or guess an icon from its code. This is unreleased source behavior, with no new public API or runtime dependency.

## Bounds and logical text

The additional simple-glyph path is limited to Unicode category `Private_Use` in U+E000–U+F8FF. The selected font must encode every requested character, permit the chosen embedding mode and meet existing size/resource constraints. Missing glyphs still fail. Other unknown scripts, supplementary private-use characters, controls and unqualified shaping remain rejected.

Distinct character sequences may address the same physical glyph. For fonts used by private-use body text, page bands, frames, labels or watermarks, the writer uses its existing request-local character-alias mapping to preserve the original Unicode text. This applies to subset and full embedding, including an assigned alias and a private-use code appearing together in either order. The original font program, family/face and permission flags are unchanged.

## Independent appearance reference

The unmodified test-only Font Awesome Free TTF contains private-use and assigned Unicode aliases of the same physical glyphs. The tests verify those glyph IDs directly, then compare the new private-glyph PDF raster exactly with an assigned-alias control produced by the preceding engine. Ordinary words remain searchable and the actual private-use codes remain in extracted text.

The retained Microsoft Word references are exports of `control-source.docx`, which uses the assigned aliases and selects the expected physical font. Their glyph positions and widths are independently checked. In the local direct-private-use Word probe, the exporter selected a different font; that output is not used as a matching reference. The provenance records explicitly distinguish the private source from the Word control. No direct Word/private-source equivalence is claimed.

The A4/Letter controls retain four measured font glyphs, with maximum horizontal difference 0.178 points, vertical difference 0.050 points and width difference 0.040 points against Word. Initial control images were visually reviewed; older goldens were not replaced.

## Reproduction and verification

`tools/pdf-evaluation/own-engine/build_private_glyph_fixture.py` reproduces both source variants on A4 and Letter. The [test-font guide](../nuaforma-pdf/src/test/resources/fonts/README.md) retains the complete license, official source and checksums. These font bytes are not included in production font packages or library JARs.

`PdfPrivateGlyphTest` verifies physical aliases, exact rasters, logical text, full/subset embedding, concurrent subset isolation, watermark/body collisions and rejection boundaries. `NativePrivateGlyphTest` exercises real DOCX generation and independent validation for all four PDF profiles and both embedding modes, plus atomic missing-family failure. The full normal build and unchanged local corpus are qualified together with the table-width/default-style increments.

Playground impact: the existing generation path benefits when callers supply the actual required fonts. No additional font is installed in the production/Playground image, no fallback is enabled and no new setting or release is introduced.
