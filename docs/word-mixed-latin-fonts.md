# Mixed Latin font roles

Unreleased source qualification under [#248](https://github.com/NuaForma/NuaForma/issues/248).

The DOCX adapter resolves ASCII and high-ANSI font roles independently through document defaults, paragraph and character styles, direct formatting and Latin theme fonts. A direct role overrides its inherited role; a theme role takes priority over a stale direct-name cache in the same `rFonts` element. It does not implicitly replace the other inherited role.

Qualified default-hint Latin, Greek and Cyrillic text, together with supported ordinary punctuation, becomes adjacent existing PDF text runs with the selected family. Text, whitespace, natural line measurement, supplied-face selection, hyperlinks and bookmark destinations are preserved. Real or qualified synthetic bold selection still uses the selected family's existing policy. There is no ambient font lookup, cross-family substitution, new dependency or public model extension.

Distinct-role selection remains bounded. Combining clusters, supplementary characters and East Asian or complex-script selector combinations are not newly qualified. Mixed-role dynamic text references and generated contents styles remain explicit errors because their final text cannot be faithfully represented by one deferred font style. A list label must resolve to one font role; ordinary paragraph text can contain several. Existing same-family text retains its original layout path and script restrictions. Segmentation remains within the existing 1,000-run paragraph limit and cooperates with cancellation.

Original `rFonts` namespaces, leaf structure, known attributes, theme/hint values and uniqueness are checked before DOCX serialization can discard malformed properties. A schema-known role is not automatically a qualified rendering role. Errors retain the original part, construct and XML path; the Playground uses these same development diagnostics and qualification rules.

Microsoft documents the Unicode ranges and independent style precedence in [MS-OI29500, run-font selection](https://learn.microsoft.com/en-us/openspecs/office_standards/ms-oi29500/aef3c9a6-5d6c-434b-90b7-85e761fd8e62). The implementation deliberately qualifies a bounded subset rather than claiming a complete Word font-selection algorithm.

Two synthetic A4/Letter sources have four independently exported Word pages. They use existing licensed Liberation Sans Regular and Noto Serif Regular fixtures and cover direct/inherited/theme roles, stale caches, wrapped mixed-font words, tables, page bands, a static document property, bookmarks and an internal hyperlink. All 84 measured markers retain their page, physical face, point size and color; maximum position differences are 0.18 pt horizontally and 0.391 pt vertically. Text and page breaks match Word. The current actual cross-family Word references use regular faces; this does not claim separate visual qualification of every bold/italic family pair.

Four initially reviewed engine images are new immutable goldens. All sixteen profile-page comparisons are pixel-exact at 96 dpi without masks; no existing golden is replaced. The PDF profiles still use their independent output validator. Link rectangles resolve to the same first-page bookmark, with its model destination emitted once.

Rebuild sources using `python tools/pdf-evaluation/own-engine/build_mixed_latin_font_fixture.py --base nuaforma-docx-pdf/src/test/resources --output build/mixed-latin-font-sources`. Export with the exact fixture font files using the existing Word procedure. Run `./gradlew :nuaforma-docx-pdf:test --tests 'dev.nuaforma.docx.pdf.NativeMixedLatinFontTest'` and the normal full build. Reference generation remains disabled during tests.
