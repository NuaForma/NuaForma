# Image compression-state metadata

Unreleased source qualification under [#262](https://github.com/NuaForma/NuaForma/issues/262).

## Source behavior

The native DOCX reader accepts the standard unqualified `a:blip/@cstate` values `none`, `email`, `screen`, `print` and `hqprint` on embedded pictures. The attribute describes the picture's stored compression state; it does not request another compression pass. See [Microsoft's DrawingML definition](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.blip).

The existing image bytes, physical extent, transform, crop and supported effects still determine the output. This qualification adds no image format, decoder, external retrieval, public API or dependency. Body, header/footer and supported note stories share the same rule, including pictures that pass the existing floating-image checks.

Original story XML is checked before DOCX binding can discard unknown attributes. Invalid compression values, namespaced lookalikes, unknown attributes and linked pictures remain explicit failures with the original story and image path. Theme definitions remain owned by the separate theme policy. All image-format, transparency, geometry, byte/pixel, font and cancellation limits remain in force.

## Reproduction and verification

`tools/pdf-evaluation/own-engine/build_image_compression_state_fixture.py --base <native-profile/word-letter.docx> --output <fresh-directory>` reproduces explicit-control and compression-state variants on A4 and US Letter. The opaque synthetic checker image appears five times in the body and once in each page band. Body image paragraphs are separate from their text labels.

Actual Microsoft Word exports of both variants are pixel-identical at 96 dpi on each paper. All seven image rectangles match the preceding qualified engine control: width, height and horizontal position are identical, with a maximum vertical difference of 0.021 points. Source, Word PDF, font and initial-golden checksums are retained beside the fixtures. No existing golden is replaced by these new fixtures.

`WordPictureMetadataTest` covers the enumerated states, original-source failures and story/theme scope. `NativeImageCompressionStateTest` exercises cleanup preservation, exact Word/control geometry, fonts, immutable golden pages and all four independently validated PDF profiles. Malformed original attributes in body, header and footer must publish no PDF and retain their exact source part/path.

Playground impact: existing generation and template cleanup use this same reader. Qualified compression metadata no longer needs removal; no new control or fallback is introduced. This source work does not publish a product release.
