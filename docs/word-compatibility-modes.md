# Qualified Word compatibility modes

Unreleased source qualification under [#244](https://github.com/NuaForma/NuaForma/issues/244).

Word's compatibility setting selects a feature/layout policy. Value `14` identifies Word 2010 in Microsoft's [compatibility enumeration](https://github.com/MicrosoftDocs/VBA-Docs/blob/main/api/Word.WdCompatibilityMode.md); the [Open XML extension specification](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-docx/90138c4d-eb18-4edc-aa6c-dfb799cb1d0d) defines the setting. Accepting that value does not qualify every feature of that Word version.

## Layout policy

The bounded reader accepts explicit modes `12`, `14` and `15`. The existing absent/default setting retains its legacy behavior. Mode 14 uses the qualified legacy table-text anchor, Normal/table-style precedence, automatic-spacing behavior and mirrored-indent phase; mode 15 retains its modern policy. Existing explicit OpenType, table-style override and mirror switches still determine their effective settings. Mode 14 is not rewritten as mode 15.

The native reader captures effective settings before removing their private interpretation nodes. Original templates are not modified; supported cleanup retains their bytes. Ordinary DOCX-to-DOCX processing keeps its existing path. Unsupported active constructs, page/table overflow, font matching, glyph/embedding restrictions and the other source-profile limits remain explicit failures.

Compatibility containers and the remaining named settings require the correct namespaces, required name/URI/value attributes, leaf structure and qualified values. Original named-setting structure is checked before DOCX serialization can discard invalid attributes or duplicate containers; this read-only check preserves valid effective switches. The native reader then qualifies their policy values. Unknown attributes, nonblank structural text, duplicate containers/settings, unqualified modes and unknown policies reject. The real provider attributes these failures to `word/settings.xml` and publishes no partial PDF. Nothing is fetched from a setting URI.

## Word references and immutable goldens

Seven synthetic sources under `nuaforma-docx-pdf/src/test/resources/compatibility-mode-14` cover A4/US Letter spacing, fixed/conditional tables, Normal-style override, four-page mirrored indentation and explicit OpenType formatting. Independent Word exports have thirteen pages. Standard PDF and PDF/A-1b/2b/3b yield 28 documents and 52 rendered pages, checked for complete per-page text, marker positions, font size/face/color, paper geometry, embedding and table backgrounds.

Six mode-14 Word reference rasters are pixel-identical to their qualified mode-12 counterparts, and the engine output matches the existing six golden pages exactly. These goldens are reused with source/reference/hash provenance. The seven pages whose mode-14 layout differs from the former modern policy have separately reviewed initial goldens. Existing golden files are preserved; replacement requires a specific reason and approval. All profiles compare at 96 dpi without masks or pixel tolerance.

Rebuild source DOCX files with `tools/pdf-evaluation/own-engine/build_compatibility_mode_fixture.py --base <test-resources-directory> --output <fresh-directory>`. Author independent Word exports with the existing exporter and its explicitly supplied fixture fonts. All five fixture faces already belong to the test stack; no distributed runtime font is added.

The Playground inherits admission, rendering and source-preserving cleanup through its provider. No UI control, public API, dependency or package is added. Word-specific settings stay in the DOCX adapter; the shared PDF model remains suitable for other adapters. Source qualification does not publish a release, and private samples/local fonts remain outside Git and CI.
