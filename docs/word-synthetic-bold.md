# Word-compatible synthetic bold (source #234)

This source profile supports explicit bold Word text when the same supplied font family has no real bold face. Actual Word exports of Calibri Light and the portable Noto Serif Light fixture retain the normal font outlines and add a same-color stroke. The stroke width is the rendered font size divided by 35. The shared writer retains the font's nominal glyph advances and unchanged source bytes; Word can round advances differently. The 12-point reference differs by at most 0.66 points horizontally, within one pixel of the 96-DPI review, without a line/page break change.

Resolution prefers the exact bold or bold-italic face. Only when that face is absent may bold use the same family's regular face, or bold-italic use its actual italic face. A missing family, missing regular face or missing italic face still rejects. There is no cross-family substitution, synthetic italic, ambient font lookup or font-file rewriting. Existing glyph coverage, embedding permissions, resource limits and output validators remain mandatory. Real bold fonts never acquire an extra stroke.

The approved neutral API adds immutable `PdfTextStyle.syntheticBold` and `withSyntheticBold(enabled)`. Existing constructors, Kotlin default/copy descriptors, decomposition, default hashes and string representations remain compatible. Copy/decorations preserve the new setting. The shared renderer isolates text graphics state, applies matching fill/stroke color and retains shaping and later ordinary text. The model is format-independent and can be used by a future ODT adapter.

This is a PDF text-rendering qualification. DOCX automatic embedding still requires real declared faces; EMF raster text also retains its exact-face policy. Source font declarations are not fabricated. The Playground reuses the PDF provider with no new control. Availability still depends on explicitly supplied/configured fonts: a `Calibri Light` request needs actual Light bytes, not a renamed Calibri file.

## Local fonts and portable tests

Private Windows/Office fonts can be supplied as existing explicit request resources. This is useful when a deployment directory cannot admit two files sharing a typographic-family alias, such as Calibri and Calibri Light: the existing strict directory ambiguity policy remains unchanged. Request declarations select real matching metadata identities; no font alias is invented.

Portable references use only unmodified OFL-licensed Noto Serif Light/LightItalic 2.015 and the existing Liberation Sans faces. Their Word export records source, PDF and supplied-font hashes. Synthetic bold/bold-italic, exact real bold selection and colored underlined text followed by normal text are exercised across the four PDF profiles. The original Calibri Light sample and all private font files remain local. No new library dependency, production font package or published capability is implied.

## Reference authoring and golden policy

The source and Word PDF live under `nuaforma-docx-pdf/src/test/resources/synthetic-bold`. `build_synthetic_bold_fixture.py --base ... --output ...` builds a fresh source from `typography-preferences/trailing-1/source.docx` without overwriting files. The independent Word oracle uses `export_word_fonts.ps1` with the two approved Noto Serif Light font paths; it activates them temporarily and deactivates them in `finally`. Neither Word nor font installation is required by portable tests or production conversion.

`page-1.png` is the reviewed engine golden at 96 DPI; it is not a Word raster. `golden-provenance.json` binds it to source and Word hashes and records the observed positioning difference. Every output profile must match that image exactly, without masks. Tests write actual outputs only under `build/`. Never replace a golden merely to make a failing test pass; require a documented reason and explicit visual approval. Existing golden files remain unchanged.
